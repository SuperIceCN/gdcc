@tool
extends VBoxContainer

# Minimal control panel driving the compiled `GdccRpcClient` — interpreted only, never a gdcc
# compile target, so editor-only APIs (EditorInterface, OS) are allowed here.
#
# Every network wait is a signal `await` on the pending object returned by the client; the dock
# never blocks the editor main thread. While any request is in flight the dock temporarily
# disables `OS.low_processor_usage_mode`: the editor enables it by default, and without input
# or redraws the idle main loop would starve the client's frame pump and HTTPRequest (plan §7
# risk 7). The previous mode is restored once the last in-flight action finishes.

var _client: GdccRpcClient
var _editor_interface: EditorInterface

var _host_input: LineEdit
var _port_input: LineEdit
var _module_input: LineEdit
var _log_output: TextEdit
var _action_buttons: Array[Button] = []

var _current_task_id: int = -1
# Endpoint captured at compile start: task ids are scoped to one server instance, so the
# Cancel button must talk to the server that owns the task, not to whatever host/port the
# input fields happen to show now.
var _current_task_host: String = ""
var _current_task_port: int = 0
var _busy_count: int = 0
var _saved_low_processor_mode: bool = true


# Injected by plugin.gd before the dock enters the tree; `_ready` builds the UI afterwards.
func setup(client: GdccRpcClient, editor_interface: EditorInterface) -> void:
    _client = client
    _editor_interface = editor_interface


func _ready() -> void:
    var endpoint_row := HBoxContainer.new()
    endpoint_row.add_child(_make_label("Host"))
    _host_input = _make_line_edit("127.0.0.1", 110)
    endpoint_row.add_child(_host_input)
    endpoint_row.add_child(_make_label("Port"))
    _port_input = _make_line_edit("6099", 60)
    endpoint_row.add_child(_port_input)
    add_child(endpoint_row)

    var module_row := HBoxContainer.new()
    module_row.add_child(_make_label("Module"))
    _module_input = _make_line_edit("demo", 0)
    _module_input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
    module_row.add_child(_module_input)
    add_child(module_row)

    var session_row := HBoxContainer.new()
    _add_button(session_row, "Ping", _on_ping_pressed)
    _add_button(session_row, "Create", _on_create_module_pressed)
    _add_button(session_row, "Upload", _on_upload_script_pressed)
    add_child(session_row)

    var compile_row := HBoxContainer.new()
    _add_button(compile_row, "Analyze", _on_analyze_pressed)
    _add_button(compile_row, "Compile", _on_compile_pressed)
    # Cancel is not tracked: it must stay enabled while busy so an in-flight compile can
    # always be cancelled.
    _add_button(compile_row, "Cancel", _on_cancel_pressed, false)
    add_child(compile_row)

    _log_output = TextEdit.new()
    _log_output.editable = false
    _log_output.custom_minimum_size = Vector2(0.0, 200.0)
    _log_output.size_flags_vertical = Control.SIZE_EXPAND_FILL
    add_child(_log_output)


func _make_label(text: String) -> Label:
    var label := Label.new()
    label.text = text
    return label


func _make_line_edit(text: String, minimum_width: float) -> LineEdit:
    var line_edit := LineEdit.new()
    line_edit.text = text
    if minimum_width > 0.0:
        line_edit.custom_minimum_size.x = minimum_width
    return line_edit


func _add_button(parent: Control, text: String, handler: Callable, track_for_busy: bool = true) -> void:
    var button := Button.new()
    button.text = text
    button.pressed.connect(handler)
    parent.add_child(button)
    if track_for_busy:
        _action_buttons.append(button)


func _set_action_buttons_enabled(enabled: bool) -> void:
    for button in _action_buttons:
        button.disabled = not enabled


func _exit_tree() -> void:
    # GDScript has no try/finally: if the plugin is disabled mid-request, the pending
    # coroutines are dropped with the dock and `_end_busy` never runs — restore the global
    # low-processor flag here or the editor would stay in full-speed mode until restart.
    if _busy_count > 0:
        OS.low_processor_usage_mode = _saved_low_processor_mode
        _busy_count = 0


func _log(message: String) -> void:
    _log_output.text += message + "\n"
    # Moving the caret past the last line keeps the newest entry visible.
    _log_output.set_caret_line(_log_output.get_line_count() - 1)


func _log_error(action: String, rpc: Dictionary) -> void:
    var error: Dictionary = rpc["error"]
    _log(action + " failed [" + str(error["code"]) + "]: " + str(error["message"]))


# Busy bookkeeping for the editor-idle liveness mitigation; nesting-safe via a counter. Action
# buttons are disabled while busy so re-entrant Compile presses cannot race `_current_task_id`.
func _begin_busy() -> void:
    if _busy_count == 0:
        _saved_low_processor_mode = OS.low_processor_usage_mode
        OS.low_processor_usage_mode = false
        _set_action_buttons_enabled(false)
    _busy_count += 1


func _end_busy() -> void:
    _busy_count = maxi(_busy_count - 1, 0)
    if _busy_count == 0:
        OS.low_processor_usage_mode = _saved_low_processor_mode
        _set_action_buttons_enabled(true)


func _apply_endpoint() -> void:
    _client.host = _host_input.text.strip_edges()
    _client.port = int(_port_input.text)


# Returns the trimmed module id, or an empty string after logging why the action was skipped.
func _require_module_id(action: String) -> String:
    var module_id: String = _module_input.text.strip_edges()
    if module_id == "":
        _log(action + " skipped: module id is empty")
    return module_id


func _on_ping_pressed() -> void:
    _apply_endpoint()
    _begin_busy()
    var rpc: Dictionary = await _client.ping().completed
    _end_busy()
    if rpc["ok"]:
        _log("ping: " + str(rpc["result"]))
    else:
        _log_error("ping", rpc)


func _on_create_module_pressed() -> void:
    _apply_endpoint()
    var module_id: String = _require_module_id("create module")
    if module_id == "":
        return
    _begin_busy()
    var rpc: Dictionary = await _client.create_module(module_id, module_id).completed
    _end_busy()
    if rpc["ok"]:
        _log("module created: " + module_id)
    else:
        _log_error("create module", rpc)


func _on_upload_script_pressed() -> void:
    _apply_endpoint()
    var module_id: String = _require_module_id("upload")
    if module_id == "":
        return
    var script: Script = _editor_interface.get_script_editor().get_current_script()
    if script == null or script.resource_path == "":
        _log("upload skipped: no saved script is current in the script editor")
        return
    # The VFS path mirrors the res:// file name under /src; the display path keeps res://.
    var virtual_path: String = "/src/" + script.resource_path.get_file()
    _begin_busy()
    var rpc: Dictionary = await _client.put_file(
            module_id, virtual_path, script.source_code, script.resource_path).completed
    _end_busy()
    if rpc["ok"]:
        _log("uploaded " + script.resource_path + " -> " + virtual_path)
    else:
        _log_error("upload", rpc)


func _on_analyze_pressed() -> void:
    _apply_endpoint()
    var module_id: String = _require_module_id("analyze")
    if module_id == "":
        return
    _begin_busy()
    # Pass every argument explicitly: gdcc registers no ClassDB default values
    # (frontend_parameter_default §5.2), so interpreted callers cannot omit them.
    var rpc: Dictionary = await _client.analyze(module_id, false).completed
    _end_busy()
    if not rpc["ok"]:
        _log_error("analyze", rpc)
        return
    var result: Dictionary = rpc["result"]
    _log("analyze outcome: " + str(result["outcome"]))
    # Wire shape: AnalysisResult.diagnostics is a snapshot record wrapping the list.
    var diagnostics: Array = result["diagnostics"]["diagnostics"]
    for diagnostic in diagnostics:
        var line: String = "  " + str(diagnostic["severity"]) + " [" + str(diagnostic["category"]) + "] " \
                + str(diagnostic["message"]) + " (" + str(diagnostic["sourcePath"]) + ")"
        if diagnostic.get("range", null) != null:
            line += " @ " + str(diagnostic["range"])
        _log(line)
    if diagnostics.is_empty():
        _log("  no diagnostics")


func _on_compile_pressed() -> void:
    _apply_endpoint()
    var module_id: String = _require_module_id("compile")
    if module_id == "":
        return
    _begin_busy()
    var started: Dictionary = await _client.start_compile(module_id).completed
    if not started["ok"]:
        _end_busy()
        _log_error("compile start", started)
        return
    _current_task_id = int(started["result"]["taskId"])
    _current_task_host = _client.host
    _current_task_port = _client.port
    # Snapshot the identity locally: Cancel may clear the shared field while this coroutine is
    # suspended, so the whole poll loop, logs, and terminal handling use the local copy.
    var task_id: int = _current_task_id
    _log("compile started: task " + str(task_id))
    # Wall-clock deadline polling on the task snapshot — the only progress channel that does
    # not block behind the module gate while the compile runs (120s covers cold native builds).
    var deadline_msec: int = Time.get_ticks_msec() + 120000
    var last_progress_line: String = ""
    while Time.get_ticks_msec() < deadline_msec:
        var polled: Dictionary = await _client.get_compile_task(task_id).completed
        if not polled["ok"]:
            _end_busy()
            _log_error("compile poll", polled)
            return
        var task: Dictionary = polled["result"]
        var state: String = str(task["state"])
        var progress_line: String = state + " " + str(task["stage"]) + " " \
                + str(task["completedUnits"]) + "/" + str(task["totalUnits"])
        if progress_line != last_progress_line:
            last_progress_line = progress_line
            _log("task " + str(task_id) + ": " + progress_line)
        if state == "SUCCEEDED" or state == "FAILED" or state == "CANCELED":
            _log("task finished: createdAt=" + str(task["createdAt"])
                    + " completedAt=" + str(task["completedAt"]))
            if task["result"] != null:
                _log("compile outcome: " + str(task["result"]["outcome"]))
            if state == "SUCCEEDED":
                var last_result: Dictionary = await _client.get_last_compile_result(module_id).completed
                if last_result["ok"] and last_result["result"] != null:
                    _log("last result outcome: " + str(last_result["result"]["outcome"]))
            if _current_task_id == task_id:
                _current_task_id = -1
            _end_busy()
            return
        await get_tree().create_timer(0.25).timeout
    _end_busy()
    _log("compile poll timed out (task " + str(task_id) + " still running)")


func _on_cancel_pressed() -> void:
    if _current_task_id < 0:
        _log("cancel skipped: no compile task has been started from this dock")
        return
    # Snapshot the identity locally: the compile poll may clear the shared field while this
    # coroutine is suspended, so the request and the log line must use the local copy.
    var task_id: int = _current_task_id
    _client.host = _current_task_host
    _client.port = _current_task_port
    _begin_busy()
    var rpc: Dictionary = await _client.cancel_compile_task(task_id).completed
    _end_busy()
    if rpc["ok"]:
        var state: String = str(rpc["result"]["state"])
        _log("cancel requested: task " + str(task_id) + " state " + state)
        if (state == "SUCCEEDED" or state == "FAILED" or state == "CANCELED") \
                and _current_task_id == task_id:
            _current_task_id = -1
    else:
        _log_error("cancel", rpc)

@tool
extends EditorPlugin

# Editor plugin entry point — interpreted only, never a gdcc compile target.
#
# The `GdccRpcClient` class comes exclusively from the installed compiled GDExtension: the
# `.gd3` source is never loaded by the engine, so this plugin can only be enabled in a project
# where the compiled extension is installed (the bootstrap engine test produces exactly such a
# project copy). The plugin owns one client node (placed inside the editor SceneTree so its
# frame pump runs) and one dock.

const DockScript := preload("res://addons/gdcc/gdcc_dock.gd")

var _client: GdccRpcClient
var _dock: DockScript


func _enter_tree() -> void:
    _client = GdccRpcClient.new()
    add_child(_client)
    _dock = DockScript.new()
    _dock.setup(_client, get_editor_interface())
    add_control_to_bottom_panel(_dock, "GDCC")
    # Fire-and-forget: the coroutine awaits RPC responses off this synchronous stack.
    _dock.auto_setup_module()


func _exit_tree() -> void:
    remove_control_from_bottom_panel(_dock)
    _dock.free()
    _dock = null
    _client = null

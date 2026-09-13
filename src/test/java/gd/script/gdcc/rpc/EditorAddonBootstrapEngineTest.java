package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gd.script.gdcc.api.API;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end bootstrap proof: the GDScript client library is compiled natively by gdcc
/// itself, installed as a GDExtension into a copy of the real editor addon project, and then
/// driven by an interpreted `SceneTree` script inside a real Godot process against a real
/// JSON-RPC server — covering the full interop chain (compiled class connect → frame pump →
/// HTTPRequest → pending-object `completed` self-emit → interpreted `await`).
/// Gated on zig and `GODOT_BIN`; aborts cleanly when either is missing.
class EditorAddonBootstrapEngineTest {
    private static final Path ADDON_PROJECT_DIR = Path.of("src/editor_addon");
    private static final Path CLIENT_SOURCE_PATH = ADDON_PROJECT_DIR.resolve("addons/gdcc/gdcc_rpc_client.gd3");
    private static final Path CASE_ROOT = Path.of("tmp/test/editor_addon_bootstrap/default");
    private static final String RESULT_MARKER = "RPC_TEST_RESULT: ";
    /// Java-side timeout stays strictly above the driver's own 90s compile-poll deadline.
    private static final long PROCESS_TIMEOUT_MINUTES = 5;

    /// Driver steps in execution order; the summary must contain exactly these, all `ok`.
    private static final List<String> EXPECTED_STEPS = List.of(
            "config", "classdb_instantiate", "ping", "create_module", "put_file",
            "options", "analyze", "compile", "last_result", "unknown_method");

    /// Interpreted driver script (gdcc feature limits do not apply to it). Written into the
    /// project copy by the test. On the first failed step it records the failure and jumps to
    /// `_finish`, because every later step depends on the earlier ones (module must exist,
    /// server must answer); the Java side always gets a marker line to diagnose.
    private static final String DRIVER_SCRIPT = """
            extends SceneTree

            var _steps: Array = []
            var _config: Dictionary = {}

            func _initialize() -> void:
                _run()  # fire-and-forget coroutine; never do RPC inside _init

            # Bare-method calls go through this helper; the await is established in the same
            # synchronous segment as `call_rpc`, per the client's signal timing contract.
            func _rpc(client: Object, method: String, params: Dictionary = {}) -> Dictionary:
                var pending: Object = client.call_rpc(method, params)
                return await pending.completed

            func _step(step: String, ok: bool, detail: String = "") -> void:
                _steps.append({"step": step, "ok": ok, "detail": detail})

            func _finish() -> void:
                print("RPC_TEST_RESULT: " + JSON.stringify({"steps": _steps}))
                quit()

            func _run() -> void:
                var config_file := FileAccess.open("res://rpc_test_config.json", FileAccess.READ)
                if config_file == null:
                    _step("config", false, "cannot open rpc_test_config.json")
                    _finish()
                    return
                var parsed: Variant = JSON.parse_string(config_file.get_as_text())
                if typeof(parsed) != TYPE_DICTIONARY:
                    _step("config", false, "config is not a JSON object")
                    _finish()
                    return
                _config = parsed
                _step("config", true)

                # Explicit verification of the ClassDB fallback path: the primary path below
                # uses `GdccRpcClient.new()`, this proves the name is resolvable through
                # ClassDB as well.
                var via_classdb: Object = ClassDB.instantiate("GdccRpcClient")
                _step("classdb_instantiate", via_classdb != null)
                if via_classdb != null:
                    via_classdb.free()

                var client: GdccRpcClient = GdccRpcClient.new()
                client.host = "127.0.0.1"
                client.port = int(_config["port"])
                root.add_child(client)  # required: _ready creates the HTTPRequest child + connects

                # Ping doubles as the interop-chain smoke check: any broken link (frame pump
                # connect, request_completed handler, pending self-emit, interpreted await)
                # hangs or fails here first.
                var ping: Dictionary = await _rpc(client, "server.ping", {})
                _step("ping", ping["ok"] and str(ping.get("result", "")) == "pong", str(ping))
                if not ping["ok"] or str(ping.get("result", "")) != "pong":
                    _finish()
                    return

                var created: Dictionary = await client.create_module("demo", "Demo").completed
                _step("create_module", created["ok"], str(created))
                if not created["ok"]:
                    _finish()
                    return

                var script_source: String = "class_name RpcDriverDemo\\nextends RefCounted\\n\\nfunc value() -> int:\\n\\treturn 1\\n"
                var uploaded: Dictionary = await client.put_file(
                        "demo", "/src/main.gd", script_source, "res://main.gd").completed
                _step("put_file", uploaded["ok"], str(uploaded))
                if not uploaded["ok"]:
                    _finish()
                    return

                # options.set replaces the whole snapshot: edit the exact options.get shape,
                # never a hand-written partial object.
                var options_rpc: Dictionary = await _rpc(client, "options.get", {"moduleId": "demo"})
                if not options_rpc["ok"]:
                    _step("options", false, str(options_rpc))
                    _finish()
                    return
                var opts: Dictionary = options_rpc["result"]
                opts["projectPath"] = str(_config["project_path"])
                var options_set: Dictionary = await client.set_compile_options("demo", opts).completed
                _step("options", options_set["ok"], str(options_set))
                if not options_set["ok"]:
                    _finish()
                    return

                # Interpreted callers must pass every argument explicitly: gdcc registers no
                # ClassDB default values by design (frontend_parameter_default §5.2), so the
                # engine rejects omitted arguments across this boundary.
                var analyzed: Dictionary = await client.analyze("demo", false).completed
                var analyze_ok: bool = analyzed["ok"] \\
                        and str(analyzed.get("result", {}).get("outcome", "")) == "COMPLETED"
                _step("analyze", analyze_ok, str(analyzed))
                if not analyze_ok:
                    _finish()
                    return

                var started: Dictionary = await client.start_compile("demo").completed
                if not started["ok"]:
                    _step("compile", false, str(started))
                    _finish()
                    return
                var task_id: int = int(started["result"]["taskId"])
                # Wall-clock deadline (native builds are slow); polls only the task snapshot,
                # the one channel that never blocks behind the module gate.
                var deadline: int = Time.get_ticks_msec() + 90000
                var terminal_state: String = ""
                var task: Dictionary = {}
                while Time.get_ticks_msec() < deadline:
                    var polled: Dictionary = await client.get_compile_task(task_id).completed
                    if not polled["ok"]:
                        break
                    task = polled["result"]
                    terminal_state = str(task["state"])
                    if terminal_state == "SUCCEEDED" or terminal_state == "FAILED" \\
                            or terminal_state == "CANCELED":
                        break
                    await create_timer(0.25).timeout
                var compile_ok: bool = terminal_state == "SUCCEEDED" and task.get("result") != null \\
                        and str(task["result"]["outcome"]) == "SUCCESS"
                _step("compile", compile_ok, "state=" + terminal_state)
                if not compile_ok:
                    _finish()
                    return

                var last: Dictionary = await client.get_last_compile_result("demo").completed
                var last_ok: bool = last["ok"] and last.get("result") != null \\
                        and str(last["result"]["outcome"]) == "SUCCESS"
                _step("last_result", last_ok, str(last.get("result", {}).get("outcome", "")))
                if not last_ok:
                    _finish()
                    return

                # Negative check: unknown method must surface the JSON-RPC error, not a hang.
                var negative: Dictionary = await _rpc(client, "no.such.method", {})
                var negative_ok: bool = not negative["ok"] \\
                        and int(negative.get("error", {}).get("code", 0)) == -32601
                _step("unknown_method", negative_ok, str(negative))

                _finish()
            """;

    @Test
    void bootstrapClientExtensionInRealGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping editor addon bootstrap engine test");
            return;
        }
        var godotBinary = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        if (godotBinary == null) {
            Assumptions.abort("GODOT_BIN not found; skipping editor addon bootstrap engine test");
            return;
        }

        // 1) Compile the client library natively through the public API (in-process; the server
        //    the driver talks to is a separate API instance started below).
        var compileResult = EditorAddonProjectInstaller.compileClientLibrary(
                CASE_ROOT.resolve("client-build"), CLIENT_SOURCE_PATH, TargetPlatform.getNativePlatform());
        assertEquals(CompileResult.Outcome.SUCCESS, compileResult.outcome(),
                () -> "client library native build failed: " + compileResult.failureMessage()
                        + "\nbuild log:\n" + compileResult.buildLog());
        // Auxiliary artifacts (e.g. PDB files on Windows) may legally accompany the library;
        // only the loadable dynamic library count is contractual.
        var loadableLibraries = compileResult.artifacts().stream()
                .filter(artifact -> EditorAddonProjectInstaller.isDynamicLibrary(artifact.getFileName().toString()))
                .count();
        assertEquals(1, loadableLibraries,
                () -> "expected exactly one loadable dynamic library, got " + compileResult.artifacts());

        // 2) Copy the addon project (without .godot caches) and install the fresh extension.
        var projectDir = CASE_ROOT.resolve("project");
        EditorAddonProjectInstaller.copyProject(ADDON_PROJECT_DIR, projectDir);
        EditorAddonProjectInstaller.installExtension(
                projectDir, compileResult.artifacts(), EditorAddonProjectInstaller.EXTENSION_FILE_NAME,
                COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        // 3) Serve the API the driver will talk to; both the server and the API are closed
        //    before the test exits even on failure (the API owns the compile task cleaner and
        //    runners), so no port, sweeper, or runner thread ever leaks.
        try (var api = new API();
                var server = JsonRpcServer.start(
                        new JsonRpcDispatcher(api), "127.0.0.1", 0, JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var config = new JsonObject();
            config.addProperty("port", server.port());
            config.addProperty("project_path", CASE_ROOT.resolve("demo-build").toAbsolutePath().toString());
            Files.writeString(projectDir.resolve("rpc_test_config.json"), config.toString());
            Files.writeString(projectDir.resolve("rpc_driver.gd"), DRIVER_SCRIPT);

            var output = runGodotDriver(godotBinary, projectDir);
            var summary = extractSummary(output);
            assertDriverStepsOk(summary, output);
        }
    }

    /// Runs `godot --headless --path <copy> -s rpc_driver.gd` and returns the combined output.
    /// `-s` script mode never loads `main.tscn`/`root.gd`, so the unit-test runner's stop-signal
    /// supervision does not apply; completion here is the process exit triggered by the driver's
    /// own `quit()`, bounded by the process timeout (destroyed forcibly past it).
    private static String runGodotDriver(Path godotBinary, Path projectDir)
            throws IOException, InterruptedException {
        var command = List.of(
                godotBinary.toString(),
                "--headless",
                "--path", projectDir.toAbsolutePath().toString(),
                "-s", projectDir.resolve("rpc_driver.gd").toAbsolutePath().toString());
        var process = new ProcessBuilder(command).directory(projectDir.toFile()).start();
        var stdout = new StringBuffer();
        var stderr = new StringBuffer();
        var stdoutReader = startLineReader(process.getInputStream(), stdout);
        var stderrReader = startLineReader(process.getErrorStream(), stderr);
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                // Give the killed process a moment to die, then collect what the readers have —
                // a timeout diagnosis is useless without the fullest possible output.
                process.waitFor(5, TimeUnit.SECONDS);
                stdoutReader.join(5_000);
                stderrReader.join(5_000);
                throw new AssertionError("Godot driver timed out; output so far:\n"
                        + stdout + "\n--- stderr ---\n" + stderr);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        // The process has exited; join the readers briefly so failure messages see full output.
        stdoutReader.join(5_000);
        stderrReader.join(5_000);
        var output = stdout + "\n--- stderr ---\n" + stderr;
        // A clean driver run ends through `quit()` (exit code 0); a crash during GDExtension
        // unload after the marker line must fail the test, not pass as a false green.
        if (process.exitValue() != 0) {
            throw new AssertionError("Godot driver exited with code " + process.exitValue()
                    + "; output:\n" + output);
        }
        return output;
    }

    private static Thread startLineReader(InputStream stream, StringBuffer output) {
        return Thread.ofVirtual().name("gdcc-editor-addon-driver-", 0).start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException exception) {
                output.append("[stream read failed: ").append(exception.getMessage()).append("]\n");
            }
        });
    }

    /// Extracts the summary JSON from the driver's marker line. Godot's startup banner and
    /// engine noise share stdout, so matching is line-oriented (never whole-buffer) and the
    /// JSON is parsed from the marker offset to the end of that line.
    private static JsonObject extractSummary(String output) {
        for (var line : output.split("\n")) {
            var markerIndex = line.indexOf(RESULT_MARKER);
            if (markerIndex >= 0) {
                return JsonParser.parseString(line.substring(markerIndex + RESULT_MARKER.length()).trim())
                        .getAsJsonObject();
            }
        }
        throw new AssertionError(RESULT_MARKER.trim() + " marker not found in Godot output:\n" + output);
    }

    private static void assertDriverStepsOk(JsonObject summary, String output) {
        var steps = summary.getAsJsonArray("steps");
        var actualNames = new java.util.ArrayList<String>();
        for (var step : steps) {
            actualNames.add(step.getAsJsonObject().get("step").getAsString());
        }
        assertEquals(EXPECTED_STEPS, actualNames,
                () -> "driver stopped early or reordered steps; output:\n" + output);
        for (var step : steps) {
            var stepObject = step.getAsJsonObject();
            assertTrue(stepObject.get("ok").getAsBoolean(),
                    () -> "driver step failed: " + stepObject + "\nfull output:\n" + output);
        }
    }
}

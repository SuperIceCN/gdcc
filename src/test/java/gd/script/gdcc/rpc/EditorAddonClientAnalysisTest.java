package gd.script.gdcc.rpc;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalysisResult;
import gd.script.gdcc.api.AnalyzeOptions;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compile-readiness gate for the editor addon's GDScript JSON-RPC client library. The
/// analysis half proves gdcc can parse, analyze, and lower the real client source without
/// zig or Godot; the source-level half pins the interop contracts that lowering cannot see
/// (no coroutines, pending-object API surface, and an un-bypassed frame pump), because a file
/// with `await` would still lower cleanly yet break the cross-boundary coroutine contract.
class EditorAddonClientAnalysisTest {
    private static final Path CLIENT_SOURCE_PATH = Path.of("src/editor_addon/addons/gdcc/gdcc_rpc_client.gd3");
    private static final Path PLUGIN_MANIFEST_PATH = Path.of("src/editor_addon/addons/gdcc/plugin.cfg");
    private static final Path PLUGIN_SCRIPT_PATH = Path.of("src/editor_addon/addons/gdcc/plugin.gd");
    private static final Path DOCK_SCRIPT_PATH = Path.of("src/editor_addon/addons/gdcc/gdcc_dock.gd");
    private static final Pattern AWAIT_PATTERN = Pattern.compile("\\bawait\\b");

    @Test
    void clientLibraryAnalyzesAndLowersCleanly() throws IOException {
        var api = new API();
        api.createModule("editor-addon", "Editor Addon Client");
        // The API collects `.gd3` virtual paths directly as compile/analyze sources; no
        // extension mapping is needed.
        api.putFile("editor-addon", "/src/gdcc_rpc_client.gd3", clientSource());

        var result = api.analyze("editor-addon", new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome(), () -> diagnosticsText(result));
        assertFalse(result.hasErrors(), () -> diagnosticsText(result));
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, result.loweringStatus(), () -> diagnosticsText(result));
        assertEquals(List.of("/src/gdcc_rpc_client.gd3"), result.sourcePaths());
    }

    @Test
    void clientSourceHonorsPendingObjectAndFramePumpContracts() throws IOException {
        var source = clientSource();
        // Contract assertions run on the comment-stripped code: the file's own documentation
        // mentions `await`, `process_frame`, and the pump members, so matching the raw source
        // would stay green even after deleting the actual code (false green).
        var code = stripComments(source);

        // 1) No coroutine anywhere: `await` is the only construct that turns a function into a
        //    coroutine, and the cross-boundary coroutine limit forbids them all here.
        assertFalse(AWAIT_PATTERN.matcher(code).find(),
                "client library must not contain any coroutine (await found in code)");

        // 2) Pending-object API surface: inner RefCounted class with the
        //    self-emitted `completed` signal (pinned verbatim so the emit cannot drift into
        //    another function), the plain-method entry point, and the engine signal handler
        //    with its exactly-typed parameter list (exact-route requirement).
        assertTrue(code.contains("class PendingRequest extends RefCounted:"), "PendingRequest inner class missing");
        assertTrue(code.contains("signal completed(response: Dictionary)"), "completed signal missing");
        assertTrue(code.contains("func _finish(response: Dictionary) -> void:\n        completed.emit(response)"),
                "_finish must be the self-emission site of the completed signal");
        assertTrue(code.contains("func call_rpc(method: String, params: Dictionary = {}) -> PendingRequest:"),
                "call_rpc signature drifted from the pending-object contract");
        assertTrue(code.contains("func _on_request_completed("), "_on_request_completed missing");
        for (var parameter : List.of(
                "result: int", "response_code: int",
                "headers: PackedStringArray", "body: PackedByteArray")) {
            assertTrue(code.contains(parameter),
                    "_on_request_completed must declare typed parameter: " + parameter);
        }

        // 3) Frame-pump contract exists and is not bypassed: sending must be driven by the
        //    persistent `process_frame` connect (the client's liveness guarantee), and
        //    `call_rpc` itself must neither send nor complete — otherwise a synchronous failure
        //    would finish the pending object on the caller's own stack, before any `await`
        //    could connect, and the ping happy path would not expose it.
        for (var token : List.of("_on_process_frame", "_pump_next", "_pending_finish", "_inflight")) {
            assertTrue(code.contains(token), "frame-pump contract member missing: " + token);
        }
        assertTrue(code.contains("tree.process_frame.connect(_on_process_frame)"),
                "frame pump must be driven by a persistent process_frame connect");
        // A synchronous `request()` failure never emits `request_completed`; it must be parked
        // in `_pending_finish` so completion still happens off the caller's stack.
        var pumpBody = topLevelFunctionBody(code, "_pump_next");
        assertTrue(pumpBody.contains("_pending_finish.push_back("),
                "_pump_next must park synchronous request failures into _pending_finish");
        var callRpcBody = topLevelFunctionBody(code, "call_rpc");
        assertFalse(callRpcBody.contains("http.request"), "call_rpc must not send synchronously");
        assertFalse(callRpcBody.contains("_finish"), "call_rpc must not complete synchronously");
        assertFalse(callRpcBody.contains("_pump_next"), "call_rpc must not pump synchronously");
    }

    /// Parse-level gate for the interpreted plugin scripts: editor-only APIs (`EditorPlugin`,
    /// `EditorInterface`) are not gdcc compile targets, so the automated check stops at
    /// parsing — analysis would reject the editor API surface by design.
    @Test
    void pluginScriptsParseCleanly() throws IOException {
        var parserService = new GdScriptParserService();
        for (var scriptPath : List.of(PLUGIN_SCRIPT_PATH, DOCK_SCRIPT_PATH)) {
            var diagnosticManager = new DiagnosticManager();
            parserService.parseUnit(scriptPath, Files.readString(scriptPath), diagnosticManager);
            assertFalse(diagnosticManager.hasErrors(), () -> {
                var text = new StringBuilder("parse errors in ").append(scriptPath).append(":\n");
                for (FrontendDiagnostic diagnostic : diagnosticManager.snapshot().asList()) {
                    text.append(diagnostic.severity()).append(' ')
                            .append(diagnostic.category()).append(' ')
                            .append(diagnostic.message()).append('\n');
                }
                return text.toString();
            });
        }
    }

    /// File-level acceptance for the plugin manifest: all five regular fields must be
    /// present with non-empty values inside the `[plugin]` section (section-scoped, so a field
    /// stranded in a comment or another section cannot pass), otherwise the editor's plugin
    /// page may not list or enable the addon.
    @Test
    void pluginManifestDeclaresAllRequiredFields() throws IOException {
        var manifest = Files.readString(PLUGIN_MANIFEST_PATH);
        var section = pluginSection(manifest);
        for (var field : List.of("name", "description", "author", "version")) {
            assertTrue(Pattern.compile("(?m)^" + field + "=\"[^\"]+\"$").matcher(section).find(),
                    () -> "plugin.cfg [plugin] section missing a non-empty field: " + field);
        }
        assertTrue(Pattern.compile("(?m)^script=\"plugin\\.gd\"$").matcher(section).find(),
                "plugin.cfg [plugin] section must point script at plugin.gd");
    }

    /// Extracts the `[plugin]` section body (up to the next section header or EOF). The header
    /// must occupy its own line so commented-out or trailing text cannot fake the section.
    private static String pluginSection(String manifest) {
        var normalized = manifest.replace("\r\n", "\n");
        var headerMatcher = Pattern.compile("(?m)^\\[plugin]$").matcher(normalized);
        assertTrue(headerMatcher.find(), "plugin.cfg must declare a [plugin] section header line");
        var bodyStart = headerMatcher.end();
        var nextSection = normalized.indexOf("\n[", bodyStart);
        return nextSection < 0 ? normalized.substring(bodyStart) : normalized.substring(bodyStart, nextSection);
    }

    private static String clientSource() throws IOException {
        // Normalize line endings so verbatim multi-line assertions cannot false-fail on a
        // CRLF checkout (no .gitattributes pins the GDScript sources to LF).
        return Files.readString(CLIENT_SOURCE_PATH).replace("\r\n", "\n");
    }

    /// Removes `#` comments per line while respecting double-quoted string literals. This is
    /// sufficient for the client source, which uses neither triple-quoted strings nor `#`
    /// inside string literals.
    private static String stripComments(String source) {
        var codeOnly = new StringBuilder(source.length());
        for (var line : source.split("\n", -1)) {
            var inString = false;
            var end = line.length();
            for (var index = 0; index < line.length(); index++) {
                var character = line.charAt(index);
                if (character == '"') {
                    inString = !inString;
                } else if (character == '#' && !inString) {
                    end = index;
                    break;
                }
            }
            codeOnly.append(line, 0, end).append('\n');
        }
        return codeOnly.toString();
    }

    /// Extracts the body slice of one top-level function, from its `func` line up to (but not
    /// including) the next top-level `func`. Matching on the line-start `func ` prefix keeps
    /// indented inner-class methods (which could shadow the name) out of the search.
    private static String topLevelFunctionBody(String code, String functionName) {
        var signatureMarker = "\nfunc " + functionName + "(";
        var start = code.indexOf(signatureMarker);
        assertTrue(start >= 0, "top-level function not found in client source: " + functionName);
        var bodyStart = start + 1;
        var nextFunction = code.indexOf("\nfunc ", bodyStart + signatureMarker.length());
        return nextFunction < 0 ? code.substring(bodyStart) : code.substring(bodyStart, nextFunction);
    }

    private static String diagnosticsText(AnalysisResult result) {
        var text = new StringBuilder("Analysis diagnostics:\n");
        for (FrontendDiagnostic diagnostic : result.diagnostics().asList()) {
            text.append(diagnostic.severity())
                    .append(' ')
                    .append(diagnostic.category())
                    .append(' ')
                    .append(diagnostic.message())
                    .append('\n');
        }
        return text.toString();
    }
}

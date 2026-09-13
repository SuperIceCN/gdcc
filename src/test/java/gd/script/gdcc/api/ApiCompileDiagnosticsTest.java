package gd.script.gdcc.api;

import gd.script.gdcc.api.task.CompileTaskHooks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileDiagnosticsTest {
    @Test
    void compileReportsParseDiagnosticsAndSkipsBuild(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Broken Parse Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("parse-project")));
        api.putFile("demo", "/src/broken.gd", """
                class_name Broken
                extends Node
                
                func _ready(
                    pass
                """, "shown broken.gd");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var parseDiagnostic = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("parse.lowering"))
                .findFirst()
                .orElseThrow();

        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertFalse(result.success());
        assertEquals("Frontend diagnostics blocked compilation", result.failureMessage());
        assertTrue(result.diagnostics().hasErrors());
        assertEquals("shown broken.gd", parseDiagnostic.sourcePath());
        assertTrue(parseDiagnostic.message().contains("CST structural issue"));
        assertEquals(List.of("shown broken.gd"), result.sourcePaths());
        assertTrue(result.generatedFiles().isEmpty());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.outputLinks().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileReportsFrontendCompileBlockDiagnosticsAndSkipsBuild(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Compile Block Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("compile-check-project")));
        api.putFile("demo", "/src/blocked.gd", """
                class_name LoweringBlockedGetNode
                extends Node
                
                var camera = $Camera3D
                """);

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var compileDiagnostic = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .findFirst()
                .orElseThrow();

        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertEquals("Frontend diagnostics blocked compilation", result.failureMessage());
        assertTrue(result.diagnostics().hasErrors());
        assertTrue(compileDiagnostic.message().contains("Get-node expression"));
        assertEquals(List.of("/src/blocked.gd"), result.sourcePaths());
        assertTrue(result.generatedFiles().isEmpty());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.outputLinks().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileSucceedsForGetNodeShorthandInsideFunctionBody(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Get-Node Shorthand Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("get-node-shorthand-project")));
        api.putFile("demo", "/src/player.gd", """
                class_name ApiGetNodeShorthand
                extends Node
                
                func ready_camera() -> Node:
                    return $Camera3D
                """);

        // A get-node shorthand inside a supported executable body compiles end-to-end: no
        // frontend gate diagnostic, the native compiler is invoked, and the task reports success.
        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertTrue(result.success());
        assertFalse(result.diagnostics().hasErrors());
        assertTrue(result.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals("sema.compile_check")));
        assertEquals(List.of("/src/player.gd"), result.sourcePaths());
        assertEquals(1, compiler.invocationCount());
    }

    @Test
    void compileSucceedsForGetNodeShorthandInsideLambdaBody(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Get-Node Lambda Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("get-node-lambda-project")));
        api.putFile("demo", "/src/player.gd", """
                class_name ApiGetNodeLambda
                extends Node
                
                func ready_camera() -> Node:
                    var camera_provider := func() -> Node:
                        return $Camera3D
                    return camera_provider.call()
                """);

        // A get-node shorthand inside a lambda body compiles end-to-end: the leading self
        // capture supplies the receiver, so no frontend gate diagnostic fires and the native
        // compiler is invoked.
        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertTrue(result.success());
        assertFalse(result.diagnostics().hasErrors());
        assertTrue(result.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals("sema.compile_check")));
        assertEquals(List.of("/src/player.gd"), result.sourcePaths());
        assertEquals(1, compiler.invocationCount());
    }

    @Test
    void compileSucceedsForScriptStaticProperties(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Static Property Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("static-property-project")));
        api.putFile("demo", "/src/shared_state.gd", """
                class_name ApiStaticProperty
                extends RefCounted
                
                static var shared := 1
                
                static func read() -> int:
                    return shared
                """);

        // Static var declarations compile end-to-end: no frontend gate diagnostic, the native
        // compiler is invoked, and the task reports success.
        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertTrue(result.success());
        assertFalse(result.diagnostics().hasErrors());
        assertTrue(result.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals("sema.compile_check")));
        assertEquals(List.of("/src/shared_state.gd"), result.sourcePaths());
        assertEquals(1, compiler.invocationCount());
    }

    @Test
    void compilePreservesGodotStyleDisplayPathsAsStringsInDiagnosticsAndResultSources(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Godot Display Path Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("godot-display-path-project")));
        api.putFile("demo", "/src/player.gd", """
                class_name Player
                extends Node
                
                var camera = $Camera3D
                """, "res://actors/player.gd");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var compileDiagnostic = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .findFirst()
                .orElseThrow();

        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertEquals("res://actors/player.gd", compileDiagnostic.sourcePath());
        assertEquals(List.of("res://actors/player.gd"), result.sourcePaths());
        assertTrue(compileDiagnostic.message().contains("Get-node expression"));
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileReportsBrokenVirtualLinksDuringSourceCollection(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Broken Link Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("broken-link-project")));
        api.createLink("demo", "/src", VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome());
        assertEquals(
                "Virtual link '/src' in module 'demo' points to missing path '/missing'",
                result.failureMessage()
        );
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.outputLinks().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileRejectsModulesWithoutAnySourceFiles(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "No Source Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("no-source-project")));
        api.putFile("demo", "/notes/readme.txt", "hello");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome());
        assertEquals("Module 'demo' has no .gd/.gd3 source files to compile", result.failureMessage());
        assertTrue(result.sourcePaths().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.outputLinks().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileCollectsGd3SourceFiles(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Gd3 Compile Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("gd3-project")));
        api.putFile("demo", "/src/valid.gd3", validSource("CompileGd3Smoke"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertTrue(result.success());
        assertFalse(result.diagnostics().hasErrors());
        assertEquals(List.of("/src/valid.gd3"), result.sourcePaths());
        assertEquals(1, compiler.invocationCount());
    }

    @Test
    void compileReportsDuplicateDefaultClassNameForSameBasenameGdAndGd3(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Same Basename Compile Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("same-basename-project")));
        // Neither file declares `class_name`, so both derive the default top-level name 'A'.
        api.putFile("demo", "/src/a.gd", "extends Node\n");
        api.putFile("demo", "/src/a.gd3", "extends Node\n");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        // Collection itself must not fail: the duplicate derived name blocks the frontend instead.
        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertEquals(List.of("/src/a.gd", "/src/a.gd3"), result.sourcePaths());
        assertTrue(result.diagnostics().asList().stream()
                .anyMatch(diagnostic -> diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Duplicate top-level class source name 'A'")));
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileAcceptsSameBasenameGdAndGd3WithDistinctClassNames(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Distinct Names Compile Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("distinct-names-project")));
        api.putFile("demo", "/src/a.gd", validSource("DistinctGdA"));
        api.putFile("demo", "/src/a.gd3", validSource("DistinctGd3A"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertFalse(result.diagnostics().hasErrors());
        assertEquals(List.of("/src/a.gd", "/src/a.gd3"), result.sourcePaths());
        assertEquals(1, compiler.invocationCount());
    }

    @Test
    void compileRejectsProjectPathsThatCannotActAsBuildDirectories(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var occupiedPath = tempDir.resolve("occupied-project-path");
        Files.writeString(occupiedPath, "occupied");

        api.createModule("demo", "Occupied Project Path Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(occupiedPath));
        api.putFile("demo", "/src/valid.gd", validSource("OccupiedProjectPathSmoke"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var failureMessage = Objects.requireNonNull(result.failureMessage());

        assertEquals(CompileResult.Outcome.CONFIGURATION_FAILED, result.outcome());
        assertTrue(failureMessage.contains(occupiedPath.toString()));
        assertTrue(failureMessage.contains("cannot be used as a build directory"));
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.outputLinks().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileReportsNativeBuildFailureAfterWritingGeneratedFiles(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.failing("native build failed");
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("build-failure-project");

        api.createModule("demo", "Build Failure Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.putFile("demo", "/src/valid.gd", validSource("BuildFailureSmoke"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.BUILD_FAILED, result.outcome());
        assertEquals("Native build reported failure; see buildLog for details", result.failureMessage());
        assertEquals("native build failed", result.buildLog());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                List.of(
                        projectPath.resolve("entry.c"),
                        projectPath.resolve("engine_method_binds.h"),
                        projectPath.resolve("object_fat_ptr_types.h"),
                        projectPath.resolve("entry.h")
                ),
                result.generatedFiles()
        );
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.outputLinks().isEmpty());
        assertEquals(1, compiler.invocationCount());
    }

    @Test
    void unexpectedFailureDoesNotReportStaleGeneratedFilesFromProjectPath(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var projectPath = tempDir.resolve("stale-generated-project");
        Files.createDirectories(projectPath);
        Files.writeString(projectPath.resolve("entry.c"), "stale entry");
        Files.writeString(projectPath.resolve("engine_method_binds.h"), "stale binds");
        Files.writeString(projectPath.resolve("entry.h"), "stale header");
        var hookTriggered = new AtomicBoolean();
        var api = ApiCompileTestSupport.newApi(
                compiler,
                CompileTaskHooks.afterSnapshotUpdate(snapshot -> {
                    if (snapshot.stage() == CompileTaskSnapshot.Stage.COLLECTING_SOURCES
                            && Objects.equals(snapshot.stageMessage(), "Collected 1 source units")
                            && hookTriggered.compareAndSet(false, true)) {
                        throw new IllegalStateException("test hook failure after request freeze");
                    }
                })
        );

        api.createModule("demo", "Stale Generated Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.putFile("demo", "/src/valid.gd", validSource("StaleGeneratedSmoke"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.BUILD_FAILED, result.outcome());
        assertTrue(Objects.requireNonNull(result.failureMessage()).contains("test hook failure after request freeze"));
        assertTrue(result.buildLog().contains("java.lang.IllegalStateException: test hook failure after request freeze"));
        assertTrue(result.buildLog().contains("unexpectedFailureDoesNotReportStaleGeneratedFilesFromProjectPath"));
        assertTrue(result.generatedFiles().isEmpty());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.outputLinks().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func value() -> int:
                    return 1
                """.formatted(className);
    }
}

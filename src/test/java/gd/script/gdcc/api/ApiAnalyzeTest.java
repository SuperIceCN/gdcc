package gd.script.gdcc.api;

import gd.script.gdcc.exception.ApiModuleNotFoundException;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Focused tests for the synchronous analyze-only API surface (`API.analyze(...)`), which serves
/// editor-plugin warning/error flows without entering the C backend.
class ApiAnalyzeTest {
    @Test
    void analyzeRejectsUnknownModule() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        assertThrows(ApiModuleNotFoundException.class, () -> api.analyze("missing"));
    }

    @Test
    void analyzeRejectsModulesWithoutAnySourceFiles() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "No Source Demo");
        api.putFile("demo", "/notes/readme.txt", "hello");

        var result = api.analyze("demo");

        assertEquals(AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome());
        assertFalse(result.completed());
        assertEquals("Module 'demo' has no .gd/.gd3 source files to analyze", result.failureMessage());
        assertTrue(result.sourcePaths().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(AnalysisResult.LoweringStatus.NOT_REQUESTED, result.loweringStatus());
    }

    @Test
    void analyzeCollectsGd3SourceFiles() {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Gd3 Demo");
        api.putFile("demo", "/src/valid.gd3", validSource("AnalyzeGd3Smoke"));

        var result = api.analyze("demo");

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertNull(result.failureMessage());
        assertFalse(result.hasErrors());
        assertEquals(List.of("/src/valid.gd3"), result.sourcePaths());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void analyzeCollectsSameBasenameGdAndGd3AndReportsDuplicateClassName() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Same Basename Demo");
        // Neither file declares `class_name`, so both derive the default top-level name 'A'.
        api.putFile("demo", "/src/a.gd", "extends Node\n");
        api.putFile("demo", "/src/a.gd3", "extends Node\n");

        var result = api.analyze("demo");

        // Collection itself must not fail: the duplicate derived name is a frontend diagnostic.
        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertEquals(List.of("/src/a.gd", "/src/a.gd3"), result.sourcePaths());
        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().asList().stream()
                .anyMatch(diagnostic -> diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Duplicate top-level class source name 'A'")));
    }

    @Test
    void analyzeAcceptsSameBasenameGdAndGd3WithDistinctClassNames() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Distinct Names Demo");
        api.putFile("demo", "/src/a.gd", validSource("DistinctGdA"));
        api.putFile("demo", "/src/a.gd3", validSource("DistinctGd3A"));

        var result = api.analyze("demo");

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertFalse(result.hasErrors());
        assertEquals(List.of("/src/a.gd", "/src/a.gd3"), result.sourcePaths());
    }

    @Test
    void analyzeReportsBrokenVirtualLinksDuringSourceCollection() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Broken Link Demo");
        api.createLink("demo", "/src", VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing");

        var result = api.analyze("demo", new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome());
        assertEquals(
                "Virtual link '/src' in module 'demo' points to missing path '/missing'",
                result.failureMessage()
        );
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus());
    }

    @Test
    void analyzeSucceedsWithoutProjectPathAndSkipsNativeCompiler() {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Editor Demo");
        // Intentionally no setCompileOptions(...): analysis must not require a build directory.
        api.putFile("demo", "/src/valid.gd", validSource("AnalyzeSmoke"));

        var result = api.analyze("demo");

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertTrue(result.completed());
        assertNull(result.failureMessage());
        assertFalse(result.hasErrors());
        assertEquals(List.of("/src/valid.gd"), result.sourcePaths());
        assertEquals(AnalysisResult.LoweringStatus.NOT_REQUESTED, result.loweringStatus());
        assertEquals(0, compiler.invocationCount());
        assertNull(api.getLastCompileResult("demo"));
    }

    @Test
    void analyzeReportsWarningsWithoutFailing() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Warning Demo");
        api.putFile("demo", "/src/warn.gd", """
                class_name AnalyzeWarning
                extends Node
                
                func user():
                    var result = await plain()
                
                func plain() -> String:
                    return "x"
                """);

        var result = api.analyze("demo");
        var warning = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.redundant_await"))
                .findFirst()
                .orElseThrow();

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertFalse(result.hasErrors());
        assertEquals(FrontendDiagnosticSeverity.WARNING, warning.severity());
    }

    @Test
    void analyzeReportsSemanticErrorsAsCompletedDiagnostics() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Cycle Demo");
        api.putFile("demo", "/src/a.gd", """
                class_name AnalyzeCycleA
                extends AnalyzeCycleB
                """);
        api.putFile("demo", "/src/b.gd", """
                class_name AnalyzeCycleB
                extends AnalyzeCycleA
                """);

        var result = api.analyze("demo");

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertTrue(result.completed());
        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().asList().stream()
                .anyMatch(diagnostic -> diagnostic.category().equals("sema.inheritance_cycle")));
        assertEquals(List.of("/src/a.gd", "/src/b.gd"), result.sourcePaths());
    }

    @Test
    void analyzeReportsParseErrorsAsCompletedDiagnosticsWithDisplayPaths() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Broken Parse Demo");
        api.putFile("demo", "/src/broken.gd", """
                class_name AnalyzeBroken
                extends Node
                
                func _ready(
                    pass
                """, "res://shown/broken.gd");

        var result = api.analyze("demo");
        var parseDiagnostic = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("parse.lowering"))
                .findFirst()
                .orElseThrow();

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertTrue(result.hasErrors());
        assertEquals("res://shown/broken.gd", parseDiagnostic.sourcePath());
        assertEquals(List.of("res://shown/broken.gd"), result.sourcePaths());
    }

    @Test
    void analyzeWithoutLoweringSkipsCompileOnlyGate() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Compile Gate Demo");
        api.putFile("demo", "/src/blocked.gd", """
                class_name AnalyzeLoweringBlockedGetNode
                extends Node
                
                var camera = $Camera3D
                """);

        // The get-node property initializer is only rejected by the compile-only gate, so plain
        // analysis completes cleanly and never emits `sema.compile_check` diagnostics.
        var result = api.analyze("demo");

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertFalse(result.hasErrors());
        assertTrue(result.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals("sema.compile_check")));
        assertEquals(AnalysisResult.LoweringStatus.NOT_REQUESTED, result.loweringStatus());
    }

    @Test
    void analyzeWithLoweringVerifiesLowerableModuleWithoutNativeBuild() {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Lowering Demo");
        api.putFile("demo", "/src/valid.gd", validSource("AnalyzeLoweringSmoke"));

        var result = api.analyze("demo", new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertFalse(result.hasErrors());
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, result.loweringStatus());
        assertEquals(0, compiler.invocationCount());
        assertNull(api.getLastCompileResult("demo"));
        assertTrue(api.listDirectory("demo", "/").isEmpty()
                || api.listDirectory("demo", "/").stream()
                        .noneMatch(entry -> entry.name().equals("__build__")));
    }

    @Test
    void analyzeWithLoweringReportsUnlowerableModule() {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Lowering Blocked Demo");
        api.putFile("demo", "/src/blocked.gd", """
                class_name AnalyzeUnlowerableGetNode
                extends Node
                
                var camera = $Camera3D
                """);

        var result = api.analyze("demo", new AnalyzeOptions(true));
        var compileDiagnostic = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .findFirst()
                .orElseThrow();

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertTrue(result.hasErrors());
        assertTrue(compileDiagnostic.message().contains("Get-node expression"));
        assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void analyzeWithLoweringMarksParseErrorsAsLoweringFailure() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Parse Lowering Demo");
        api.putFile("demo", "/src/broken.gd", """
                class_name AnalyzeParseLowering
                extends Node
                
                func _ready(
                    pass
                """);

        // Parse errors end the pipeline before lowering can run, so the lowering answer is FAILED
        // while the analysis outcome stays COMPLETED with the parse diagnostics attached.
        var result = api.analyze("demo", new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().asList().stream()
                .anyMatch(diagnostic -> diagnostic.category().equals("parse.lowering")));
        assertEquals(AnalysisResult.LoweringStatus.FAILED, result.loweringStatus());
    }

    @Test
    void analyzeAfterSuccessfulCompileKeepsLastCompileResult(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Compile Then Analyze Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("compile-then-analyze-project")));
        api.putFile("demo", "/src/valid.gd", validSource("AnalyzeAfterCompileSmoke"));

        var compileResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        assertEquals(CompileResult.Outcome.SUCCESS, compileResult.outcome());

        var analysisResult = api.analyze("demo", new AnalyzeOptions(true));

        assertEquals(AnalysisResult.Outcome.COMPLETED, analysisResult.outcome());
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, analysisResult.loweringStatus());
        assertSame(compileResult, api.getLastCompileResult("demo"));
        assertEquals(1, compiler.invocationCount());
    }

    @Test
    void analyzeRunsConcurrentlyAcrossIndependentModules() throws InterruptedException {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("first", "First Concurrent Demo");
        api.putFile("first", "/src/first.gd", lambdaSource("AnalyzeConcurrentFirst"));
        api.createModule("second", "Second Concurrent Demo");
        api.putFile("second", "/src/second.gd", lambdaSource("AnalyzeConcurrentSecond"));

        // Module gates are independent, so both analyses really can overlap; each request must
        // still observe its own fresh semantic analyzer state.
        var startGate = new CountDownLatch(1);
        var firstResult = new AtomicReference<AnalysisResult>();
        var secondResult = new AtomicReference<AnalysisResult>();
        var firstFailure = new AtomicReference<Throwable>();
        var secondFailure = new AtomicReference<Throwable>();
        var firstThread = Thread.ofVirtual().start(() -> {
            await(startGate);
            try {
                firstResult.set(api.analyze("first", new AnalyzeOptions(true)));
            } catch (Throwable throwable) {
                firstFailure.set(throwable);
            }
        });
        var secondThread = Thread.ofVirtual().start(() -> {
            await(startGate);
            try {
                secondResult.set(api.analyze("second", new AnalyzeOptions(true)));
            } catch (Throwable throwable) {
                secondFailure.set(throwable);
            }
        });
        startGate.countDown();
        firstThread.join(TimeUnit.SECONDS.toMillis(30));
        secondThread.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertNull(firstFailure.get());
        assertNull(secondFailure.get());

        assertEquals(AnalysisResult.Outcome.COMPLETED, Objects.requireNonNull(firstResult.get()).outcome());
        assertFalse(firstResult.get().hasErrors());
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, firstResult.get().loweringStatus());
        assertEquals(List.of("/src/first.gd"), firstResult.get().sourcePaths());
        assertEquals(AnalysisResult.Outcome.COMPLETED, Objects.requireNonNull(secondResult.get()).outcome());
        assertFalse(secondResult.get().hasErrors());
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, secondResult.get().loweringStatus());
        assertEquals(List.of("/src/second.gd"), secondResult.get().sourcePaths());
    }

    @Test
    void analyzeSequentiallyReusesApiAcrossLambdaModules() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("first", "First Lambda Demo");
        api.putFile("first", "/src/first.gd", lambdaSource("AnalyzeLambdaFirst"));
        api.createModule("second", "Second Lambda Demo");
        api.putFile("second", "/src/second.gd", lambdaSource("AnalyzeLambdaSecond"));

        // FrontendSuiteResolver keeps per-run lambda state, so the second analysis on the same API
        // instance must observe a fresh resolver instead of stale scope/counter state from the first.
        var firstResult = api.analyze("first");
        var secondResult = api.analyze("second");

        assertEquals(AnalysisResult.Outcome.COMPLETED, firstResult.outcome());
        assertFalse(firstResult.hasErrors());
        assertEquals(AnalysisResult.Outcome.COMPLETED, secondResult.outcome());
        assertFalse(secondResult.hasErrors());
    }

    @Test
    void analyzeSequentiallyReusesApiAcrossLambdaModulesWithLowering() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("first", "First Lambda Lowering Demo");
        api.putFile("first", "/src/first.gd", lambdaSource("AnalyzeLambdaLoweringFirst"));
        api.createModule("second", "Second Lambda Lowering Demo");
        api.putFile("second", "/src/second.gd", lambdaSource("AnalyzeLambdaLoweringSecond"));

        // Same per-run state isolation requirement as the analyze-only path, but through the
        // lowering pipeline's own compile-ready analysis pass.
        var firstResult = api.analyze("first", new AnalyzeOptions(true));
        var secondResult = api.analyze("second", new AnalyzeOptions(true));

        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, firstResult.loweringStatus());
        assertEquals(AnalysisResult.LoweringStatus.SUCCEEDED, secondResult.loweringStatus());
        assertEquals(List.of("/src/first.gd"), firstResult.sourcePaths());
        assertEquals(List.of("/src/second.gd"), secondResult.sourcePaths());
    }

    @Test
    void analyzeWaitsForModuleGateBehindOtherOperations() throws InterruptedException {        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.createModule("demo", "Gate Demo");
        api.putFile("demo", "/src/valid.gd", validSource("AnalyzeGateSmoke"));

        var blocker = ApiCompileTestSupport.blockModuleOperation(api, "demo");
        assertTrue(blocker.awaitEntered());
        var resultRef = new AtomicReference<AnalysisResult>();
        var analyzeThread = Thread.ofVirtual()
                .name("gdcc-api-test-analyze-gate")
                .start(() -> resultRef.set(api.analyze("demo")));
        try {
            ApiCompileTestSupport.sleepForProgressPolling();
            assertNull(resultRef.get());
        } finally {
            blocker.close();
        }
        analyzeThread.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(analyzeThread.isAlive());

        var result = Objects.requireNonNull(resultRef.get());
        assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome());
        assertFalse(result.hasErrors());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test start gate", exception);
        }
    }

    private static String lambdaSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func make() -> Callable:
                    var add := func(a: int, b: int) -> int:
                        return a + b
                    return add
                """.formatted(className);
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

package gd.script.gdcc.api;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/// Executes one synchronous analyze-only request against a frozen module snapshot.
///
/// The runner reuses the compiler's parser, shared semantic pipeline, and (on request) the
/// lowering pipeline, but never enters the C backend: no C code is generated and no native build
/// starts. Parse and semantic problems surface as diagnostics instead of aborting the request,
/// matching editor-plugin flows that show warnings and errors without producing artifacts.
///
/// Semantic analyzers and lowering passes keep per-run state (for example lambda name counters and
/// scope reverse indexes), so every request constructs fresh pipeline instances and never shares a
/// `FrontendLoweringPassManager` with compile tasks, which also construct their own per task.
final class AnalysisRunner {
    private static final @NotNull DiagnosticSnapshot EMPTY_DIAGNOSTICS = new DiagnosticSnapshot(List.of());

    private final @NotNull GdScriptParserService parserService;

    AnalysisRunner(@NotNull GdScriptParserService parserService) {
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
    }

    @NotNull AnalysisResult analyze(
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(analyzeOptions, "analyzeOptions must not be null");
        var sourcePaths = request.sourceSnapshots().stream()
                .map(ModuleState.SourceSnapshot::displayPath)
                .toList();
        if (request.failure() != null) {
            // Frozen source collection only fails on broken or cyclic virtual links.
            return failureResult(
                    AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED,
                    request,
                    analyzeOptions,
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    request.failure().message()
            );
        }
        if (request.sourceSnapshots().isEmpty()) {
            return failureResult(
                    AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED,
                    request,
                    analyzeOptions,
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    "Module '" + request.moduleId() + "' has no .gd/.gd3 source files to analyze"
            );
        }

        var diagnostics = new DiagnosticManager();
        var units = new ArrayList<FrontendSourceUnit>(request.sourceSnapshots().size());
        for (var sourceSnapshot : request.sourceSnapshots()) {
            units.add(parserService.parseUnit(
                    sourceSnapshot.logicalPath(),
                    sourceSnapshot.source(),
                    diagnostics
            ));
        }
        var parseDiagnostics = remapDiagnosticSourcePaths(request, diagnostics.snapshot());
        if (parseDiagnostics.hasErrors()) {
            // Semantic phases require a well-formed AST, so parse errors end the pipeline with the
            // diagnostics collected so far instead of failing the whole request.
            return completedResult(
                    request,
                    analyzeOptions,
                    sourcePaths,
                    parseDiagnostics,
                    unverifiedLoweringStatus(analyzeOptions)
            );
        }

        final ClassRegistry classRegistry;
        try {
            classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(request.compileOptions().godotVersion()));
        } catch (IOException exception) {
            return failureResult(
                    AnalysisResult.Outcome.INTERNAL_FAILED,
                    request,
                    analyzeOptions,
                    sourcePaths,
                    parseDiagnostics,
                    "Godot extension metadata for "
                            + request.compileOptions().godotVersion()
                            + " could not be loaded: "
                            + exception.getMessage()
            );
        }
        var frontendModule = new FrontendModule(
                request.moduleName(),
                units,
                request.topLevelCanonicalNameMap()
        );
        if (!analyzeOptions.includeLowering()) {
            // The shared semantic entrypoint reports warnings and errors without the compile-only
            // gate, so editor callers never see `sema.compile_check` diagnostics from this path.
            new FrontendSemanticAnalyzer().analyze(frontendModule, classRegistry, diagnostics);
            return completedResult(
                    request,
                    analyzeOptions,
                    sourcePaths,
                    remapDiagnosticSourcePaths(request, diagnostics.snapshot()),
                    AnalysisResult.LoweringStatus.NOT_REQUESTED
            );
        }

        // Lowering reruns semantic analysis through the compile-ready gate and stops on the first
        // error, so a published LirModule plus clean diagnostics proves the module can lower. A
        // fresh pass manager keeps its per-run analyzer state isolated from concurrent compile tasks.
        var lowered = new FrontendLoweringPassManager().lower(frontendModule, classRegistry, diagnostics);
        var loweringDiagnostics = remapDiagnosticSourcePaths(request, diagnostics.snapshot());
        var loweringStatus = lowered != null && !loweringDiagnostics.hasErrors()
                ? AnalysisResult.LoweringStatus.SUCCEEDED
                : AnalysisResult.LoweringStatus.FAILED;
        return completedResult(request, analyzeOptions, sourcePaths, loweringDiagnostics, loweringStatus);
    }

    private static @NotNull AnalysisResult.LoweringStatus unverifiedLoweringStatus(@NotNull AnalyzeOptions analyzeOptions) {
        return analyzeOptions.includeLowering()
                ? AnalysisResult.LoweringStatus.FAILED
                : AnalysisResult.LoweringStatus.NOT_REQUESTED;
    }

    private static @NotNull AnalysisResult completedResult(
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions,
            @NotNull List<String> sourcePaths,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull AnalysisResult.LoweringStatus loweringStatus
    ) {
        return new AnalysisResult(
                AnalysisResult.Outcome.COMPLETED,
                analyzeOptions,
                request.compileOptions().godotVersion(),
                request.topLevelCanonicalNameMap(),
                sourcePaths,
                diagnostics,
                null,
                loweringStatus
        );
    }

    private static @NotNull AnalysisResult failureResult(
            @NotNull AnalysisResult.Outcome outcome,
            @NotNull ModuleState.CompileRequest request,
            @NotNull AnalyzeOptions analyzeOptions,
            @NotNull List<String> sourcePaths,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String failureMessage
    ) {
        return new AnalysisResult(
                outcome,
                analyzeOptions,
                request.compileOptions().godotVersion(),
                request.topLevelCanonicalNameMap(),
                sourcePaths,
                diagnostics,
                failureMessage,
                unverifiedLoweringStatus(analyzeOptions)
        );
    }

    private static @NotNull DiagnosticSnapshot remapDiagnosticSourcePaths(
            @NotNull ModuleState.CompileRequest request,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        var displayPathsByLogicalPath = request.sourceSnapshots().stream()
                .collect(Collectors.toMap(
                        sourceSnapshot -> DiagnosticSourcePathRemapper.logicalPathKey(sourceSnapshot.logicalPath()),
                        ModuleState.SourceSnapshot::displayPath,
                        (first, _) -> first
                ));
        return DiagnosticSourcePathRemapper.remap(displayPathsByLogicalPath, diagnostics);
    }
}

package gd.script.gdcc.api.task;

import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.api.CompileTaskSnapshot;
import gd.script.gdcc.api.DiagnosticSourcePathRemapper;
import gd.script.gdcc.api.VfsEntrySnapshot;
import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.CProjectInfo;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.exception.ApiEntryTypeMismatchException;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/// Executes one compile task after the API facade has accepted and registered it.
public final class CompileTaskRunner implements Runnable {
    private static final @NotNull DiagnosticSnapshot EMPTY_DIAGNOSTICS = new DiagnosticSnapshot(List.of());

    private final @NotNull Clock clock;
    private final @NotNull GdScriptParserService parserService;
    private final @NotNull CProjectBuilder projectBuilder;
    private final @NotNull CompileTaskState taskState;
    private final @NotNull Runnable executionStarter;
    private final @NotNull Supplier<Request> requestSupplier;
    private final @NotNull Consumer<CompileResult> completionHandler;

    public CompileTaskRunner(
            @NotNull Clock clock,
            @NotNull GdScriptParserService parserService,
            @NotNull CProjectBuilder projectBuilder,
            @NotNull CompileTaskState taskState,
            @NotNull Runnable executionStarter,
            @NotNull Supplier<Request> requestSupplier,
            @NotNull Consumer<CompileResult> completionHandler
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
        this.projectBuilder = Objects.requireNonNull(projectBuilder, "projectBuilder must not be null");
        this.taskState = Objects.requireNonNull(taskState, "taskState must not be null");
        this.executionStarter = Objects.requireNonNull(executionStarter, "executionStarter must not be null");
        this.requestSupplier = Objects.requireNonNull(requestSupplier, "requestSupplier must not be null");
        this.completionHandler = Objects.requireNonNull(completionHandler, "completionHandler must not be null");
    }

    @Override
    public void run() {
        Request request = null;
        CompileTaskState.bindCurrentThread(taskState);
        try {
            try {
                throwIfCancellationRequested();
                executionStarter.run();
                throwIfCancellationRequested();
                taskState.updateRunningStage(
                        CompileTaskSnapshot.Stage.FREEZING_INPUTS,
                        "Freezing compile inputs",
                        0,
                        0,
                        null
                );
                throwIfCancellationRequested();
                taskState.updateRunningStage(
                        CompileTaskSnapshot.Stage.COLLECTING_SOURCES,
                        "Collecting .gd/.gd3 sources from module VFS",
                        0,
                        0,
                        null
                );
                request = requestSupplier.get();
                throwIfCancellationRequested();
                taskState.updateRunningStage(
                        CompileTaskSnapshot.Stage.COLLECTING_SOURCES,
                        "Collected " + request.sourceSnapshots().size() + " source units",
                        0,
                        request.sourceSnapshots().size(),
                        null
                );
                finishCompileTask(compileFrozenRequest(request));
            } catch (Throwable throwable) {
                if (taskState.cancellationRequested() || throwable instanceof TaskCanceledException) {
                    finishCanceledTask(request);
                } else {
                    finishCompileTask(unexpectedTaskFailure(request, throwable));
                }
            }
        } finally {
            CompileTaskState.clearCurrentThread();
        }
    }

    private void finishCompileTask(@NotNull CompileResult result) {
        var completed = taskState.complete(
                clock.instant(),
                result,
                result.success() ? CompileTaskSnapshot.Stage.FINISHED : taskState.snapshot().stage()
        );
        if (completed) {
            completionHandler.accept(result);
        }
    }

    private void finishCanceledTask(@Nullable Request request) {
        var result = canceledResult(request);
        if (taskState.completeCanceled(clock.instant(), result)) {
            completionHandler.accept(result);
        }
    }

    /// Compilation runs against frozen source snapshots so later RPC writes cannot mutate the exact
    /// input set or output mount used by this pipeline.
    private @NotNull CompileResult compileFrozenRequest(@NotNull Request request) {
        throwIfCancellationRequested();
        var sourcePaths = displaySourcePaths(request);
        if (request.failure() != null) {
            return new CompileResult(
                    request.failure().outcome(),
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    request.failure().message(),
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
        if (request.sourceSnapshots().isEmpty()) {
            return new CompileResult(
                    CompileResult.Outcome.SOURCE_COLLECTION_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    "Module '" + request.moduleId() + "' has no .gd/.gd3 source files to compile",
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        var projectPath = request.compileOptions().projectPath();
        if (projectPath == null) {
            return new CompileResult(
                    CompileResult.Outcome.CONFIGURATION_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    "Compile options for module '" + request.moduleId() + "' must set projectPath before compile",
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        var diagnostics = new DiagnosticManager();
        throwIfCancellationRequested();
        taskState.updateRunningStage(
                CompileTaskSnapshot.Stage.PARSING,
                "Parsing source units",
                0,
                request.sourceSnapshots().size(),
                null
        );
        var units = new ArrayList<FrontendSourceUnit>(request.sourceSnapshots().size());
        for (var index = 0; index < request.sourceSnapshots().size(); index++) {
            var sourceSnapshot = request.sourceSnapshots().get(index);
            throwIfCancellationRequested();
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.PARSING,
                    "Parsing " + sourceSnapshot.displayPath(),
                    index,
                    request.sourceSnapshots().size(),
                    sourceSnapshot.displayPath()
            );
            units.add(parserService.parseUnit(
                    sourceSnapshot.logicalPath(),
                    sourceSnapshot.source(),
                    diagnostics
            ));
            throwIfCancellationRequested();
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.PARSING,
                    "Parsed " + sourceSnapshot.displayPath(),
                    index + 1,
                    request.sourceSnapshots().size(),
                    sourceSnapshot.displayPath()
            );
        }
        var parseAndFrontendDiagnostics = remapDiagnosticSourcePaths(request, diagnostics.snapshot());
        if (parseAndFrontendDiagnostics.hasErrors()) {
            return new CompileResult(
                    CompileResult.Outcome.FRONTEND_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    parseAndFrontendDiagnostics,
                    "Frontend diagnostics blocked compilation",
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        var projectPathError = ensureProjectPathUsable(projectPath);
        if (projectPathError != null) {
            return new CompileResult(
                    CompileResult.Outcome.CONFIGURATION_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    parseAndFrontendDiagnostics,
                    projectPathError,
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        try {
            throwIfCancellationRequested();
            var extensionApi = ExtensionApiLoader.loadVersion(request.compileOptions().godotVersion());
            var classRegistry = new ClassRegistry(extensionApi);
            var frontendModule = new FrontendModule(
                    request.moduleName(),
                    units,
                    request.topLevelCanonicalNameMap()
            );
            throwIfCancellationRequested();
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.LOWERING,
                    "Lowering frontend module",
                    request.sourceSnapshots().size(),
                    request.sourceSnapshots().size(),
                    null
            );
            // Each task lowers through a fresh pass manager because semantic analyzers and
            // lowering passes keep per-run state (lambda name counters, scope reverse indexes)
            // that must never leak into another module's compile.
            var lowered = new FrontendLoweringPassManager().lower(frontendModule, classRegistry, diagnostics);
            throwIfCancellationRequested();
            var frontendDiagnostics = remapDiagnosticSourcePaths(request, diagnostics.snapshot());
            if (lowered == null || frontendDiagnostics.hasErrors()) {
                return new CompileResult(
                        CompileResult.Outcome.FRONTEND_FAILED,
                        request.compileOptions(),
                        request.topLevelCanonicalNameMap(),
                        sourcePaths,
                        frontendDiagnostics,
                        "Frontend diagnostics blocked compilation",
                        "",
                        List.of(),
                        List.of(),
                        List.of()
                );
            }

            var projectInfo = new CProjectInfo(
                    request.moduleId(),
                    request.compileOptions().godotVersion(),
                    projectPath,
                    request.compileOptions().optimizationLevel(),
                    request.compileOptions().targetPlatform()
            );
            var codegen = new CCodegen();
            throwIfCancellationRequested();
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.CODEGEN_PREPARE,
                    "Preparing C code generation",
                    request.sourceSnapshots().size(),
                    request.sourceSnapshots().size(),
                    null
            );
            codegen.prepare(
                    new CodegenContext(projectInfo, classRegistry, request.compileOptions().strictMode()),
                    lowered
            );
            var outputMountError = validateAndPrepareOutputPublication(request, sourcePaths, frontendDiagnostics);
            if (outputMountError != null) {
                return outputMountError;
            }

            throwIfCancellationRequested();
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                    "Building native artifacts",
                    request.sourceSnapshots().size(),
                    request.sourceSnapshots().size(),
                    null
            );
            var buildResult = projectBuilder.buildProject(projectInfo, codegen);
            throwIfCancellationRequested();
            if (!buildResult.success()) {
                return new CompileResult(
                        CompileResult.Outcome.BUILD_FAILED,
                        request.compileOptions(),
                        request.topLevelCanonicalNameMap(),
                        sourcePaths,
                        frontendDiagnostics,
                        "Native build reported failure; see buildLog for details",
                        buildResult.buildLog(),
                        buildResult.generatedFiles(),
                        buildResult.artifacts(),
                        List.of()
                );
            }
            try {
                throwIfCancellationRequested();
                var outputLinks = request.outputMounter().apply(new BuildOutputs(
                        buildResult.generatedFiles(),
                        buildResult.artifacts()
                ));
                return new CompileResult(
                        CompileResult.Outcome.SUCCESS,
                        request.compileOptions(),
                        request.topLevelCanonicalNameMap(),
                        sourcePaths,
                        frontendDiagnostics,
                        null,
                        buildResult.buildLog(),
                        buildResult.generatedFiles(),
                        buildResult.artifacts(),
                        outputLinks
                );
            } catch (ApiEntryTypeMismatchException | IllegalArgumentException exception) {
                return outputMountConfigurationFailure(
                        request,
                        sourcePaths,
                        frontendDiagnostics,
                        exception,
                        buildResult.buildLog(),
                        buildResult.generatedFiles(),
                        buildResult.artifacts()
                );
            }
        } catch (IOException exception) {
            if (taskState.cancellationRequested()) {
                throw new TaskCanceledException();
            }
            var buildLog = stackTrace(exception);
            return new CompileResult(
                    CompileResult.Outcome.BUILD_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    remapDiagnosticSourcePaths(request, diagnostics.snapshot()),
                    "Build pipeline failed: " + exception.getMessage(),
                    buildLog,
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    private static @NotNull String stackTrace(@NotNull Throwable throwable) {
        var writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private void throwIfCancellationRequested() {
        if (taskState.cancellationRequested()) {
            throw new TaskCanceledException();
        }
    }

    /// Output publication is split into a non-mutating validation pass and a later cleanup pass so
    /// early failures keep the last successful output links intact until a new publish is imminent.
    private @Nullable CompileResult validateAndPrepareOutputPublication(
            @NotNull Request request,
            @NotNull List<String> sourcePaths,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        try {
            request.outputMountValidator().accept(request.compileOptions().outputMountRoot());
            request.outputPublicationPreparer().accept(request.compileOptions().outputMountRoot());
            return null;
        } catch (ApiEntryTypeMismatchException | IllegalArgumentException exception) {
            return outputMountConfigurationFailure(request, sourcePaths, diagnostics, exception, "", List.of(), List.of());
        }
    }

    private @Nullable String ensureProjectPathUsable(@NotNull Path projectPath) {
        try {
            Files.createDirectories(projectPath);
            return null;
        } catch (IOException exception) {
            return "Project path '" + projectPath + "' cannot be used as a build directory: " + exception.getMessage();
        }
    }

    private @NotNull List<String> displaySourcePaths(@NotNull Request request) {
        return request.sourceSnapshots().stream()
                .map(SourceSnapshot::displayPath)
                .toList();
    }

    /// Frontend internals still track host-usable logical paths, but API callers care about the
    /// original caller-facing labels such as `res://player.gd`. Results therefore remap matching
    /// frontend diagnostic source paths back to each frozen source snapshot's `displayPath`.
    private @NotNull DiagnosticSnapshot remapDiagnosticSourcePaths(
            @NotNull Request request,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
        var displayPathsByLogicalPath = request.sourceSnapshots().stream()
                .collect(Collectors.toMap(
                        sourceSnapshot -> DiagnosticSourcePathRemapper.logicalPathKey(sourceSnapshot.logicalPath()),
                        SourceSnapshot::displayPath,
                        (first, _) -> first
                ));
        return DiagnosticSourcePathRemapper.remap(displayPathsByLogicalPath, diagnostics);
    }

    private @NotNull CompileResult unexpectedTaskFailure(
            @Nullable Request request,
            @NotNull Throwable throwable
    ) {
        var failureMessage = "Compile task failed unexpectedly: " + describeThrowable(throwable);
        var buildLog = stackTrace(throwable);
        return new CompileResult(
                CompileResult.Outcome.BUILD_FAILED,
                request == null ? CompileOptions.defaults() : request.compileOptions(),
                request == null ? Map.of() : request.topLevelCanonicalNameMap(),
                request == null ? List.of() : displaySourcePaths(request),
                EMPTY_DIAGNOSTICS,
                failureMessage,
                buildLog,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull CompileResult canceledResult(@Nullable Request request) {
        return new CompileResult(
                CompileResult.Outcome.CANCELED,
                request == null ? CompileOptions.defaults() : request.compileOptions(),
                request == null ? Map.of() : request.topLevelCanonicalNameMap(),
                request == null ? List.of() : displaySourcePaths(request),
                EMPTY_DIAGNOSTICS,
                "Compile task was canceled",
                "",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull String describeThrowable(@NotNull Throwable throwable) {
        var message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getClass().getSimpleName() + ": " + message;
    }

    private @NotNull CompileResult outputMountConfigurationFailure(
            @NotNull Request request,
            @NotNull List<String> sourcePaths,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull Exception exception,
            @NotNull String buildLog,
            @NotNull List<Path> generatedFiles,
            @NotNull List<Path> artifacts
    ) {
        return new CompileResult(
                CompileResult.Outcome.CONFIGURATION_FAILED,
                request.compileOptions(),
                request.topLevelCanonicalNameMap(),
                sourcePaths,
                diagnostics,
                "Compile outputs for module '"
                        + request.moduleId()
                        + "' could not be mounted: "
                        + exception.getMessage(),
                buildLog,
                generatedFiles,
                artifacts,
                List.of()
        );
    }

    /// Frozen inputs and module-owned callbacks captured at compile-task start.
    public record Request(
            @NotNull String moduleId,
            @NotNull String moduleName,
            @NotNull CompileOptions compileOptions,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull List<SourceSnapshot> sourceSnapshots,
            @Nullable Failure failure,
            @NotNull Consumer<String> outputMountValidator,
            @NotNull Consumer<String> outputPublicationPreparer,
            @NotNull Function<BuildOutputs, List<VfsEntrySnapshot.LinkEntrySnapshot>> outputMounter
    ) {
        public Request {
            Objects.requireNonNull(moduleId, "moduleId must not be null");
            Objects.requireNonNull(moduleName, "moduleName must not be null");
            Objects.requireNonNull(compileOptions, "compileOptions must not be null");
            topLevelCanonicalNameMap = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                    topLevelCanonicalNameMap,
                    "topLevelCanonicalNameMap must not be null"
            )));
            sourceSnapshots = List.copyOf(Objects.requireNonNull(sourceSnapshots, "sourceSnapshots must not be null"));
            Objects.requireNonNull(outputMountValidator, "outputMountValidator must not be null");
            Objects.requireNonNull(outputPublicationPreparer, "outputPublicationPreparer must not be null");
            Objects.requireNonNull(outputMounter, "outputMounter must not be null");
        }
    }

    public record SourceSnapshot(
            @NotNull String displayPath,
            @NotNull Path logicalPath,
            @NotNull String source
    ) {
        public SourceSnapshot {
            Objects.requireNonNull(displayPath, "displayPath must not be null");
            Objects.requireNonNull(logicalPath, "logicalPath must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    public record Failure(
            @NotNull CompileResult.Outcome outcome,
            @NotNull String message
    ) {
        public Failure {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    public record BuildOutputs(
            @NotNull List<Path> generatedFiles,
            @NotNull List<Path> artifacts
    ) {
        public BuildOutputs {
            generatedFiles = List.copyOf(Objects.requireNonNull(generatedFiles, "generatedFiles must not be null"));
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
        }
    }

    private static final class TaskCanceledException extends RuntimeException {
    }
}

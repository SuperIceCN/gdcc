package gd.script.gdcc.api;

import gd.script.gdcc.api.cleaner.CompileTaskCleaner;
import gd.script.gdcc.api.task.CompileTaskHooks;
import gd.script.gdcc.api.task.CompileTaskRunner;
import gd.script.gdcc.api.task.CompileTaskState;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.exception.ApiCompileAlreadyRunningException;
import gd.script.gdcc.exception.ApiCompileTaskNotFoundException;
import gd.script.gdcc.exception.ApiModuleAlreadyExistsException;
import gd.script.gdcc.exception.ApiModuleBusyException;
import gd.script.gdcc.exception.ApiModuleNotFoundException;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/// In-memory module registry facade intended for RPC adapters.
///
/// The API package owns remote-facing lifecycle and state orchestration, while frontend/lowering/
/// backend remain the sole compilation fact sources. The facade provides virtual-path normalized
/// module VFS operations, compile configuration, task polling, and output publication without
/// coupling itself to any transport framework.
///
/// Lifecycle: `close()` shuts the facade down for SIGINT-style teardown — it stops the TTL
/// cleaner permanently, cancels every unfinished compile task (queued tasks complete as CANCELED
/// immediately, running tasks are interrupted and finish as CANCELED through the normal runner
/// completion path so module gates are released), and waits (bounded) for runner threads to die.
/// After close, state-mutating methods reject new work with `IllegalStateException` while
/// read-only queries keep serving the final snapshots.
public final class API implements AutoCloseable {
    private static final @NotNull Duration DEFAULT_COMPLETED_COMPILE_TASK_TTL = Duration.ofMinutes(30);
    private static final @NotNull Duration DEFAULT_COMPILE_TASK_SWEEP_INTERVAL = Duration.ofMinutes(1);
    /// Total budget for runner threads to finish after close requests their cancellation; runners
    /// respond to interruption promptly (native subprocesses are destroyed), so this only bounds
    /// pathological cases.
    private static final @NotNull Duration CLOSE_RUNNER_WAIT = Duration.ofSeconds(30);
    public static final int MAX_COMPILE_TASK_EVENT_PAGE_SIZE = 1_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(API.class);

    private final @NotNull Clock clock;
    private final @NotNull GdScriptParserService parserService;
    private final @NotNull CProjectBuilder projectBuilder;
    private final @NotNull AnalysisRunner analysisRunner;
    private final @NotNull CompileTaskHooks compileTaskHooks;
    private final @NotNull ConcurrentHashMap<String, ManagedModule> modules = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<Long, CompileTaskState> compileTasks = new ConcurrentHashMap<>();
    private final @NotNull CompileTaskCleaner compileTaskCleaner;
    private final @NotNull AtomicLong nextCompileTaskId = new AtomicLong(1);
    private final @NotNull AtomicBoolean closed = new AtomicBoolean();
    /// Serializes `compile(...)` admission against the close sweep so a task can never slip
    /// between "close scanned the task table" and "close requested cancellation".
    private final @NotNull Object lifecycleLock = new Object();

    public API() {
        this(
                Clock.systemUTC(),
                new GdScriptParserService(),
                new CProjectBuilder(),
                CompileTaskHooks.none(),
                DEFAULT_COMPLETED_COMPILE_TASK_TTL,
                DEFAULT_COMPILE_TASK_SWEEP_INTERVAL
        );
    }

    API(@NotNull Clock clock) {
        this(
                clock,
                new GdScriptParserService(),
                new CProjectBuilder(),
                CompileTaskHooks.none(),
                DEFAULT_COMPLETED_COMPILE_TASK_TTL,
                DEFAULT_COMPILE_TASK_SWEEP_INTERVAL
        );
    }

    API(
            @NotNull Clock clock,
            @NotNull GdScriptParserService parserService,
            @NotNull CProjectBuilder projectBuilder,
            @NotNull CompileTaskHooks compileTaskHooks,
            @NotNull Duration completedCompileTaskTtl,
            @NotNull Duration compileTaskSweepInterval
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
        this.projectBuilder = Objects.requireNonNull(projectBuilder, "projectBuilder must not be null");
        this.compileTaskHooks = Objects.requireNonNull(compileTaskHooks, "compileTaskHooks must not be null");
        analysisRunner = new AnalysisRunner(parserService);
        compileTaskCleaner = new CompileTaskCleaner(
                clock,
                compileTasks,
                completedCompileTaskTtl,
                compileTaskSweepInterval
        );
    }

    /// Records one event for the compile task currently executing on this thread. This is meant for
    /// frontend/backend code that runs inside the API-managed compile pipeline.
    public static boolean recordCurrentCompileTaskEvent(@NotNull String category, @NotNull String detail) {
        return CompileTaskState.recordCurrentThreadEvent(category, detail);
    }

    public @NotNull ModuleSnapshot createModule(@NotNull String moduleId, @NotNull String moduleName) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        var createdState = new ModuleState(
                normalizedModuleId,
                StringUtil.requireTrimmedNonBlank(moduleName, "moduleName"),
                clock
        );
        var existingState = modules.putIfAbsent(normalizedModuleId, new ManagedModule(createdState));
        if (existingState != null) {
            throw new ApiModuleAlreadyExistsException("Module '" + normalizedModuleId + "' already exists");
        }
        return createdState.snapshot();
    }

    public @NotNull ModuleSnapshot getModule(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, ModuleState::snapshot);
    }

    /// Stable ordering keeps RPC list responses predictable even though storage uses a concurrent map.
    public @NotNull List<ModuleSnapshot> listModules() {
        return modules.values().stream()
                .map(ManagedModule::snapshotIfPresent)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ModuleSnapshot::moduleId))
                .toList();
    }

    public @NotNull ModuleSnapshot deleteModule(@NotNull String moduleId) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        var managedModule = modules.get(normalizedModuleId);
        if (managedModule == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        var removedSnapshot = managedModule.reserveDelete(normalizedModuleId);
        try {
            if (!modules.remove(normalizedModuleId, managedModule)) {
                throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
            }
            managedModule.finishDelete();
            return removedSnapshot;
        } catch (RuntimeException exception) {
            managedModule.cancelDelete();
            throw exception;
        }
    }

    public @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot createDirectory(
            @NotNull String moduleId,
            @NotNull String path
    ) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.createDirectory(VirtualPath.parse(path))
        );
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull String content
    ) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.putFile(VirtualPath.parse(path), content)
        );
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull String content,
            @NotNull String displayPath
    ) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.putFile(VirtualPath.parse(path), content, displayPath)
        );
    }

    public @NotNull String readFile(@NotNull String moduleId, @NotNull String path) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.readFile(VirtualPath.parse(path))
        );
    }

    public @NotNull VfsEntrySnapshot.LinkEntrySnapshot createLink(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull VfsEntrySnapshot.LinkKind linkKind,
            @NotNull String target
    ) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.createLink(VirtualPath.parse(path), linkKind, target)
        );
    }

    public @NotNull VfsEntrySnapshot deletePath(@NotNull String moduleId, @NotNull String path, boolean recursive) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.deletePath(VirtualPath.parse(path), recursive)
        );
    }

    /// Directory entries are returned in stable lexical order so RPC consumers can diff results.
    public @NotNull List<VfsEntrySnapshot> listDirectory(@NotNull String moduleId, @NotNull String path) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.listDirectory(VirtualPath.parse(path))
        );
    }

    public @NotNull VfsEntrySnapshot readEntry(@NotNull String moduleId, @NotNull String path) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.readEntry(VirtualPath.parse(path))
        );
    }

    public @NotNull CompileOptions getCompileOptions(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, ModuleState::getCompileOptions);
    }

    public @NotNull CompileOptions setCompileOptions(
            @NotNull String moduleId,
            @NotNull CompileOptions compileOptions
    ) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.setCompileOptions(compileOptions)
        );
    }

    public @NotNull Map<String, String> getTopLevelCanonicalNameMap(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                ModuleState::getTopLevelCanonicalNameMap
        );
    }

    public @NotNull Map<String, String> setTopLevelCanonicalNameMap(
            @NotNull String moduleId,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.setTopLevelCanonicalNameMap(topLevelCanonicalNameMap)
        );
    }

    public @Nullable CompileResult getLastCompileResult(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, ModuleState::getLastCompileResult);
    }

    /// Runs one synchronous analyze-only pass over the module's current sources and returns the
    /// collected frontend diagnostics without producing artifacts. Editor-style callers use this to
    /// surface warnings and errors without configuring a build directory or polling an asynchronous
    /// compile task.
    public @NotNull AnalysisResult analyze(@NotNull String moduleId) {
        return analyze(moduleId, AnalyzeOptions.defaults());
    }

    /// When `AnalyzeOptions.includeLowering()` is set, the analysis pass continues into frontend
    /// lowering to verify whether the module can currently lower to LIR; the C backend still never
    /// runs. The call serializes through the module gate like any other operation, so it waits for
    /// a queued or active compile of the same module to finish first, and it never touches the
    /// module's last compile result.
    public @NotNull AnalysisResult analyze(@NotNull String moduleId, @NotNull AnalyzeOptions analyzeOptions) {
        checkOpen();
        var normalizedModuleId = normalizeModuleId(moduleId);
        var managedModule = requireManagedModule(normalizedModuleId);
        var options = Objects.requireNonNull(analyzeOptions, "analyzeOptions must not be null");
        return managedModule.runExclusive(normalizedModuleId, state ->
                analysisRunner.analyze(state.freezeCompileRequest(), options)
        );
    }

    /// Publishes one queued compile task immediately, then lets a fresh virtual thread wait for the
    /// module gate and execute the actual compile. The caller must poll `getCompileTask(...)` for
    /// queue progress, running stages, and final completion. Completed tasks stay queryable only
    /// until their retention TTL expires.
    public long compile(@NotNull String moduleId) {
        // Admission is serialized against the close sweep: either this task is fully registered
        // (and therefore visible to `close()` for cancellation) or close already won and the call
        // fails instead of leaking a runner.
        synchronized (lifecycleLock) {
            checkOpen();
            var normalizedModuleId = normalizeModuleId(moduleId);
            var managedModule = requireManagedModule(normalizedModuleId);
            var taskId = nextCompileTaskId.getAndIncrement();
            var taskState = new CompileTaskState(taskId, normalizedModuleId, clock.instant(), compileTaskHooks);
            compileTasks.put(taskId, taskState);
            try {
                managedModule.enqueueCompile(normalizedModuleId, taskId);
            } catch (RuntimeException exception) {
                compileTasks.remove(taskId);
                throw exception;
            }
            var ownerState = managedModule.state();
            try {
                compileTaskCleaner.ensureRunning();
                var thread = Thread.ofVirtual()
                        .name("gdcc-api-compile-" + taskId)
                        .unstarted(new CompileTaskRunner(
                                clock,
                                parserService,
                                projectBuilder,
                                taskState,
                                () -> managedModule.awaitCompileTurn(normalizedModuleId, taskId),
                                () -> freezeCompileTaskRequest(ownerState),
                                result -> {
                                    ownerState.setLastCompileResult(result);
                                    managedModule.finishCompile(taskId);
                                }
                        ));
                taskState.attachRunnerThread(thread);
                thread.start();
            } catch (RuntimeException exception) {
                compileTasks.remove(taskId);
                managedModule.finishCompile(taskId);
                throw exception;
            }
            return taskId;
        }
    }

    /// Returns the latest snapshot for one compile task started by `compile(...)`. Once the retention
    /// TTL expires, the task behaves as not found.
    public @NotNull CompileTaskSnapshot getCompileTask(long taskId) {
        return requireCompileTaskState(taskId).snapshot();
    }

    /// Requests cancellation for a retained queued or running compile task. Queued tasks release the
    /// module reservation immediately; running tasks are interrupted and complete as canceled once
    /// the compile runner reaches an interruptible point.
    public @NotNull CompileTaskSnapshot cancelCompileTask(long taskId) {
        checkOpen();
        var taskState = requireCompileTaskState(taskId);
        if (!taskState.requestCancellation()) {
            return taskState.snapshot();
        }

        var snapshot = taskState.snapshot();
        var managedModule = modules.get(snapshot.moduleId());
        if (managedModule != null && managedModule.cancelQueuedCompile(taskId)) {
            var result = canceledResult(snapshot);
            if (taskState.completeCanceled(clock.instant(), result)) {
                managedModule.state().setLastCompileResult(result);
            }
        }
        taskState.interruptRunner();
        return taskState.snapshot();
    }

    /// Returns the latest event for one retained compile task.
    public @Nullable CompileTaskEvent getLatestCompileTaskEvent(long taskId) {
        return requireCompileTaskState(taskId).latestEvent();
    }

    /// Events are returned in append order so clients can treat the list as a stable task log during
    /// the task retention window.
    public @NotNull List<CompileTaskEvent> listCompileTaskEvents(long taskId) {
        return requireCompileTaskState(taskId).events();
    }

    /// Returns an indexed, bounded event page starting at `startIndex`, inclusive. The index is kept
    /// outside `CompileTaskEvent` so the event payload stays transport-neutral.
    public @NotNull List<CompileTaskEvent.Indexed> listCompileTaskEvents(
            long taskId,
            long startIndex,
            int maxCount
    ) {
        return listCompileTaskEvents(taskId, null, startIndex, maxCount);
    }

    /// Returns an indexed, bounded event page for one category starting at `startIndex`, inclusive.
    public @NotNull List<CompileTaskEvent.Indexed> listCompileTaskEvents(
            long taskId,
            @Nullable String category,
            long startIndex,
            int maxCount
    ) {
        var normalizedCategory = category == null ? null : StringUtil.requireTrimmedNonBlank(category, "category");
        if (startIndex < 0) {
            throw new IllegalArgumentException("startIndex must not be negative");
        }
        if (maxCount <= 0 || maxCount > MAX_COMPILE_TASK_EVENT_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "maxCount must be between 1 and " + MAX_COMPILE_TASK_EVENT_PAGE_SIZE
            );
        }
        return requireCompileTaskState(taskId).events(
                normalizedCategory,
                startIndex,
                maxCount
        );
    }

    public void clearCompileTaskEvents(long taskId) {
        checkOpen();
        requireCompileTaskState(taskId).clearEvents();
    }

    /// Shuts the facade down (idempotent). Queued tasks are completed as CANCELED synchronously;
    /// running tasks are interrupted — their runners notice the cancellation, finish through the
    /// normal completion path (last-result writeback and module-gate release included) and die.
    /// Runner threads are joined with a bounded total budget; leftovers are logged, not hung on.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Stop the TTL cleaner first and permanently so it cannot resurrect mid-teardown.
        compileTaskCleaner.stop();
        synchronized (lifecycleLock) {
            for (var entry : compileTasks.entrySet()) {
                var taskState = entry.getValue();
                if (!taskState.requestCancellation()) {
                    continue;
                }
                var snapshot = taskState.snapshot();
                var managedModule = modules.get(snapshot.moduleId());
                if (managedModule != null && managedModule.cancelQueuedCompile(entry.getKey())) {
                    var result = canceledResult(snapshot);
                    if (taskState.completeCanceled(clock.instant(), result)) {
                        managedModule.state().setLastCompileResult(result);
                    }
                }
                taskState.interruptRunner();
            }
        }
        var deadlineNanos = System.nanoTime() + CLOSE_RUNNER_WAIT.toNanos();
        var unfinished = 0;
        for (var taskState : compileTasks.values()) {
            // Join every task's runner, not only still-running ones: a queued task canceled
            // synchronously above still owns a started runner thread parked on the module gate,
            // and it must be reaped before close returns. `Duration.ZERO` remainders are an
            // immediate liveness check, never an unbounded wait.
            var remaining = Duration.ofNanos(Math.max(0L, deadlineNanos - System.nanoTime()));
            try {
                if (!taskState.awaitRunner(remaining)) {
                    unfinished++;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                unfinished++;
                break;
            }
        }
        if (unfinished > 0) {
            LOGGER.warn("API closed with {} compile task(s) still running after cancellation", unfinished);
        }
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("API is closed");
        }
    }

    private @NotNull ManagedModule requireManagedModule(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var managedModule = modules.get(normalizedModuleId);
        if (managedModule == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        return managedModule;
    }

    private @NotNull String normalizeModuleId(@NotNull String moduleId) {
        return StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
    }

    private @NotNull CompileTaskState requireCompileTaskState(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        var taskState = compileTasks.get(taskId);
        if (taskState == null) {
            throw new ApiCompileTaskNotFoundException("Compile task '" + taskId + "' does not exist");
        }
        return taskState;
    }

    private @NotNull CompileTaskRunner.Request freezeCompileTaskRequest(@NotNull ModuleState ownerState) {
        var request = ownerState.freezeCompileRequest();
        return new CompileTaskRunner.Request(
                request.moduleId(),
                request.moduleName(),
                request.compileOptions(),
                request.topLevelCanonicalNameMap(),
                request.sourceSnapshots().stream()
                        .map(sourceSnapshot -> new CompileTaskRunner.SourceSnapshot(
                                sourceSnapshot.displayPath(),
                                sourceSnapshot.logicalPath(),
                                sourceSnapshot.source()
                        ))
                        .toList(),
                request.failure() == null
                        ? null
                        : new CompileTaskRunner.Failure(request.failure().outcome(), request.failure().message()),
                ownerState::validateOutputMountRoot,
                ownerState::prepareOutputPublication,
                outputs -> ownerState.mountCompileOutputs(
                        request.compileOptions().outputMountRoot(),
                        outputs.generatedFiles(),
                        outputs.artifacts()
                )
        );
    }

    private @NotNull CompileResult canceledResult(@NotNull CompileTaskSnapshot snapshot) {
        return new CompileResult(
                CompileResult.Outcome.CANCELED,
                CompileOptions.defaults(),
                Map.of(),
                List.of(),
                new DiagnosticSnapshot(List.of()),
                "Compile task " + snapshot.taskId() + " was canceled",
                "",
                List.of(),
                List.of(),
                List.of()
        );
    }

    /// First implementation favors correctness over read/write concurrency: all same-module API
    /// operations serialize through one gate, while different modules remain independent.
    private static final class ManagedModule {
        private final @NotNull ModuleState state;
        private boolean busy;
        private boolean deleted;
        private long queuedCompileTaskId;
        private long activeCompileTaskId;

        private ManagedModule(@NotNull ModuleState state) {
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        private @NotNull ModuleState state() {
            return state;
        }

        private synchronized @Nullable ModuleSnapshot snapshotIfPresent() {
            if (deleted) {
                return null;
            }
            // Registry listing is best-effort: once a module is deleted we skip it, otherwise we
            // expose the latest stable state snapshot without publishing a partially deleted entry.
            return state.snapshot();
        }

        /// Queue reservation happens on the caller thread so `compile(...)` can return a visible
        /// task ID immediately while still preventing later same-module operations from overtaking
        /// the pending compile before its inputs are frozen.
        private synchronized void enqueueCompile(@NotNull String moduleId, long taskId) {
            if (deleted) {
                throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
            }
            var existingTaskId = pendingCompileTaskId();
            if (existingTaskId != 0) {
                var existingStateLabel = queuedCompileTaskId != 0 ? "queued" : "active";
                throw new ApiCompileAlreadyRunningException(
                        "Module '" + moduleId + "' already has " + existingStateLabel + " compile task " + existingTaskId
                );
            }
            queuedCompileTaskId = taskId;
            notifyAll();
        }

        /// The background compile thread waits here until earlier same-module work drains, then it
        /// converts its published queued reservation into the active compile slot.
        private synchronized void awaitCompileTurn(@NotNull String moduleId, long taskId) {
            try {
                while (busy) {
                    awaitTurn();
                }
                if (deleted) {
                    throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
                }
                if (queuedCompileTaskId != taskId) {
                    throw new IllegalStateException("Compile task " + taskId + " lost its queued reservation");
                }
                busy = true;
                activeCompileTaskId = taskId;
                queuedCompileTaskId = 0;
            } catch (RuntimeException exception) {
                if (queuedCompileTaskId == taskId) {
                    queuedCompileTaskId = 0;
                    notifyAll();
                }
                throw exception;
            }
        }

        private <T> T runExclusive(@NotNull String moduleId, @NotNull Function<ModuleState, T> operation) {
            enterOperation(moduleId);
            try {
                return Objects.requireNonNull(operation, "operation must not be null").apply(state);
            } finally {
                leaveOperation();
            }
        }

        private synchronized @NotNull ModuleSnapshot reserveDelete(@NotNull String moduleId) {
            while (busy && activeCompileTaskId == 0 && queuedCompileTaskId == 0) {
                awaitTurn();
            }
            if (deleted) {
                throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
            }
            var compileTaskId = pendingCompileTaskId();
            if (compileTaskId != 0) {
                throw new ApiModuleBusyException(
                        "Module '" + moduleId + "' cannot be deleted while compile task "
                                + compileTaskId
                                + " is queued or active"
                );
            }
            busy = true;
            return state.snapshot();
        }

        private synchronized void finishDelete() {
            deleted = true;
            busy = false;
            notifyAll();
        }

        private synchronized void cancelDelete() {
            busy = false;
            notifyAll();
        }

        private synchronized void finishCompile(long taskId) {
            var changed = false;
            if (queuedCompileTaskId == taskId) {
                queuedCompileTaskId = 0;
                changed = true;
            }
            if (activeCompileTaskId == taskId) {
                activeCompileTaskId = 0;
                busy = false;
                changed = true;
            }
            if (changed) {
                notifyAll();
            }
        }

        private synchronized boolean cancelQueuedCompile(long taskId) {
            if (queuedCompileTaskId != taskId) {
                return false;
            }
            queuedCompileTaskId = 0;
            notifyAll();
            return true;
        }

        private synchronized void enterOperation(@NotNull String moduleId) {
            while (busy || queuedCompileTaskId != 0) {
                awaitTurn();
            }
            if (deleted) {
                throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
            }
            busy = true;
        }

        private synchronized void leaveOperation() {
            busy = false;
            notifyAll();
        }

        private long pendingCompileTaskId() {
            return queuedCompileTaskId != 0 ? queuedCompileTaskId : activeCompileTaskId;
        }

        private void awaitTurn() {
            try {
                wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for module operation", exception);
            }
        }
    }

}

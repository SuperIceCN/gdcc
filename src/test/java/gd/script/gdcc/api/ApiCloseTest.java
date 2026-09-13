package gd.script.gdcc.api;

import gd.script.gdcc.api.task.CompileTaskHooks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lifecycle tests for `API.close()`: admission rejection after close, read-only queries staying
/// available, cancellation of queued and running tasks, permanent cleaner shutdown, and bounded
/// runner reaping.
class ApiCloseTest {
    @Test
    void closeIsIdempotentAndSucceedsWithoutModules() {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());

        api.close();
        api.close();
    }

    @Test
    void closeRejectsNewWorkButKeepsReadOnlyQueries(@TempDir Path tempDir) {
        var api = ApiCompileTestSupport.newApi(ApiCompileTestSupport.RecordingCompiler.succeeding());
        api.createModule("demo", "Closed Demo");
        api.putFile("demo", "/src/main.gd", validSource("ClosedDemo"));

        api.close();

        // Mutating methods reject new work.
        assertThrows(IllegalStateException.class, () -> api.createModule("other", "Other"));
        assertThrows(IllegalStateException.class, () -> api.deleteModule("demo"));
        assertThrows(IllegalStateException.class, () -> api.createDirectory("demo", "/docs"));
        assertThrows(IllegalStateException.class, () -> api.putFile("demo", "/src/b.gd", "extends Node\n"));
        assertThrows(IllegalStateException.class, () ->
                api.putFile("demo", "/src/b.gd", "extends Node\n", "res://b.gd"));
        assertThrows(IllegalStateException.class, () ->
                api.createLink("demo", "/out", VfsEntrySnapshot.LinkKind.VIRTUAL, "/src"));
        assertThrows(IllegalStateException.class, () -> api.deletePath("demo", "/src/main.gd", false));
        assertThrows(IllegalStateException.class, () ->
                api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("closed-project"))));
        assertThrows(IllegalStateException.class, () ->
                api.setTopLevelCanonicalNameMap("demo", Map.of("A", "a.A")));
        assertThrows(IllegalStateException.class, () -> api.analyze("demo"));
        assertThrows(IllegalStateException.class, () -> api.analyze("demo", new AnalyzeOptions(true)));
        assertThrows(IllegalStateException.class, () -> api.compile("demo"));
        assertThrows(IllegalStateException.class, () -> api.cancelCompileTask(1));
        assertThrows(IllegalStateException.class, () -> api.clearCompileTaskEvents(1));

        // Read-only queries keep serving the final state.
        assertEquals("demo", api.getModule("demo").moduleId());
        assertEquals(1, api.listModules().size());
        assertTrue(api.readFile("demo", "/src/main.gd").contains("ClosedDemo"));
        assertEquals(1, api.listDirectory("demo", "/src").size());
        assertEquals("main.gd", api.readEntry("demo", "/src/main.gd").name());
        assertEquals("V451", api.getCompileOptions("demo").godotVersion().name());
        assertTrue(api.getTopLevelCanonicalNameMap("demo").isEmpty());
        assertNull(api.getLastCompileResult("demo"));
    }

    @Test
    void closeCancelsQueuedTaskAndCompletesItAsCanceled(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Queued Close Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("queued-close-project")));
        api.putFile("demo", "/src/demo.gd", validSource("QueuedCloseDemo"));

        long taskId;
        try (var blocker = ApiCompileTestSupport.blockModuleOperation(api, "demo")) {
            assertTrue(blocker.awaitEntered());
            taskId = api.compile("demo");
            assertEquals(CompileTaskSnapshot.State.QUEUED, api.getCompileTask(taskId).state());

            api.close();
        } finally {
            compiler.release();
        }

        // The queued reservation was canceled synchronously during close().
        var canceledTask = api.getCompileTask(taskId);
        assertEquals(CompileTaskSnapshot.State.CANCELED, canceledTask.state());
        assertEquals(CompileTaskSnapshot.Stage.QUEUED, canceledTask.stage());
        assertEquals(CompileResult.Outcome.CANCELED, Objects.requireNonNull(canceledTask.result()).outcome());
        assertEquals(canceledTask.result(), api.getLastCompileResult("demo"));
        assertEquals(0, compiler.invocationCount());
        // The queued runner thread (parked on the module gate) was reaped before close returned.
        assertRunnerExited(api, taskId);
    }

    @Test
    void closeInterruptsRunningTaskWhichCompletesAsCanceled(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Running Close Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("running-close-project")));
        api.putFile("demo", "/src/demo.gd", validSource("RunningCloseDemo"));

        try {
            var taskId = api.compile("demo");
            assertTrue(compiler.awaitEntered());
            ApiCompileTestSupport.awaitSnapshot(
                    api,
                    taskId,
                    snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                            && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                    "BUILDING_NATIVE"
            );

            // close() must interrupt the runner and wait for it; when it returns, the task has
            // completed as canceled through the normal runner completion path.
            api.close();

            var canceledTask = api.getCompileTask(taskId);
            assertEquals(CompileTaskSnapshot.State.CANCELED, canceledTask.state());
            assertEquals(CompileTaskSnapshot.Stage.BUILDING_NATIVE, canceledTask.stage());
            assertEquals(CompileResult.Outcome.CANCELED, Objects.requireNonNull(canceledTask.result()).outcome());
            assertEquals(canceledTask.result(), api.getLastCompileResult("demo"));
            assertRunnerExited(api, taskId);
        } finally {
            compiler.release();
        }
    }

    @Test
    void closeWaitsForRunnerCompletionBeforeReturning(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Join Close Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("join-close-project")));
        api.putFile("demo", "/src/demo.gd", validSource("JoinCloseDemo"));

        try {
            var taskId = api.compile("demo");
            assertTrue(compiler.awaitEntered());
            ApiCompileTestSupport.awaitSnapshot(
                    api,
                    taskId,
                    snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                            && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                    "BUILDING_NATIVE"
            );

            // With the runner blocked inside the native compiler, close() only returns after the
            // interrupted runner finished its cancellation path — the join is what the assertion
            // below actually pins.
            api.close();

            assertEquals(CompileTaskSnapshot.State.CANCELED, api.getCompileTask(taskId).state());
            assertRunnerExited(api, taskId);
        } finally {
            compiler.release();
        }
    }

    @Test
    void closeStopsCleanerPermanently(@TempDir Path tempDir) {
        var clock = new ApiCompileTestSupport.MutableClock(Instant.parse("2026-04-22T10:15:30Z"));
        var api = ApiCompileTestSupport.newApi(
                ApiCompileTestSupport.RecordingCompiler.succeeding(),
                CompileTaskHooks.none(),
                clock,
                Duration.ofSeconds(2),
                Duration.ofMillis(10)
        );

        api.createModule("demo", "Cleaner Close Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("cleaner-close-project")));
        api.putFile("demo", "/src/demo.gd", validSource("CleanerCloseDemo"));
        var taskId = api.compile("demo");
        ApiCompileTestSupport.awaitResult(api, taskId);

        // The compile started the cleaner; pin that precondition so the stop assertions below
        // cannot pass vacuously against a cleaner that never ran.
        var cleanerThread = awaitCleanerThread(api);
        assertTrue(cleanerThread.isAlive());

        api.close();

        // The sweep thread exits after stop(), later ensureRunning() must not resurrect it, and
        // completed tasks survive past their TTL because nothing sweeps anymore.
        awaitCleanerDeath(cleanerThread);
        ensureCleanerRunning(api);
        ApiCompileTestSupport.sleepForProgressPolling();
        assertFalse(cleanerThread.isAlive());
        clock.advance(Duration.ofMinutes(10));
        ApiCompileTestSupport.sleepForProgressPolling();
        ApiCompileTestSupport.sleepForProgressPolling();
        assertEquals(taskId, api.getCompileTask(taskId).taskId());
    }

    /// Pins runner reaping through the task table: close() must have joined this task's runner
    /// thread before returning (zero-timeout liveness check).
    private static void assertRunnerExited(API api, long taskId) {
        try {
            var taskState = taskStatesById(api).get(taskId);
            assertNotNull(taskState, "task state must exist for task " + taskId);
            assertTrue(taskState.awaitRunner(Duration.ZERO), "runner of task " + taskId + " must be dead");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while checking runner liveness", exception);
        }
    }

    private static Thread awaitCleanerThread(API api) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var thread = cleanerThreadOf(api);
            if (thread != null) {
                return thread;
            }
            ApiCompileTestSupport.sleepForProgressPolling();
        }
        throw new AssertionError("Cleaner thread never started");
    }

    private static void awaitCleanerDeath(Thread cleanerThread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cleanerThread.isAlive() && System.nanoTime() < deadline) {
            ApiCompileTestSupport.sleepForProgressPolling();
        }
        assertFalse(cleanerThread.isAlive(), "cleaner thread must exit after API.close()");
    }

    private static void ensureCleanerRunning(API api) {
        cleanerOf(api).ensureRunning();
    }

    private static gd.script.gdcc.api.cleaner.CompileTaskCleaner cleanerOf(API api) {
        try {
            var field = API.class.getDeclaredField("compileTaskCleaner");
            field.setAccessible(true);
            return (gd.script.gdcc.api.cleaner.CompileTaskCleaner) field.get(api);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to read API compileTaskCleaner", exception);
        }
    }

    private static Thread cleanerThreadOf(API api) {
        try {
            var field = gd.script.gdcc.api.cleaner.CompileTaskCleaner.class.getDeclaredField("cleanerThread");
            field.setAccessible(true);
            return (Thread) field.get(cleanerOf(api));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to read cleanerThread", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, gd.script.gdcc.api.task.CompileTaskState> taskStatesById(API api) {
        try {
            var field = API.class.getDeclaredField("compileTasks");
            field.setAccessible(true);
            return (Map<Long, gd.script.gdcc.api.task.CompileTaskState>) field.get(api);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to read API compileTasks", exception);
        }
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

package gd.script.gdcc.api.cleaner;

import gd.script.gdcc.api.task.CompileTaskState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/// Reclaims completed compile task snapshots after their retention window expires.
public final class CompileTaskCleaner {
    private final @NotNull Clock clock;
    private final @NotNull ConcurrentHashMap<Long, CompileTaskState> compileTasks;
    private final @NotNull Duration completedCompileTaskTtl;
    private final @NotNull Duration sweepInterval;
    private final @NotNull AtomicBoolean running = new AtomicBoolean();
    private final @NotNull AtomicBoolean stopped = new AtomicBoolean();
    // Null until the first sweep thread starts; stop() must never interrupt the calling thread.
    private volatile @Nullable Thread cleanerThread;

    public CompileTaskCleaner(
            @NotNull Clock clock,
            @NotNull ConcurrentHashMap<Long, CompileTaskState> compileTasks,
            @NotNull Duration completedCompileTaskTtl,
            @NotNull Duration sweepInterval
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.compileTasks = Objects.requireNonNull(compileTasks, "compileTasks must not be null");
        this.completedCompileTaskTtl = requirePositiveDuration(completedCompileTaskTtl, "completedCompileTaskTtl");
        this.sweepInterval = requirePositiveDuration(sweepInterval, "compileTaskSweepInterval");
    }

    /// Long-lived RPC services submit compile tasks continuously, so completed task snapshots and
    /// their event logs must age out instead of leaking memory and enlarging the polling surface
    /// forever.
    public void ensureRunning() {
        if (stopped.get()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread.ofVirtual()
                    .name("gdcc-api-compile-cleaner-" + System.identityHashCode(this))
                    .start(() -> {
                        cleanerThread = Thread.currentThread();
                        try {
                            run();
                        } finally {
                            running.set(false);
                            // Never restart after stop(): the API is closing and the cleaner must
                            // stay down instead of resurrecting on a non-empty task table.
                            if (!stopped.get() && !compileTasks.isEmpty()) {
                                ensureRunning();
                            }
                        }
                    });
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    /// Permanently stops the cleaner (idempotent): the sweep thread is interrupted so it exits at
    /// once, and later `ensureRunning()` calls become no-ops.
    public void stop() {
        stopped.set(true);
        var thread = cleanerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void run() {
        while (!stopped.get()) {
            collectExpiredTasks();
            if (compileTasks.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(sweepInterval);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void collectExpiredTasks() {
        var now = clock.instant();
        compileTasks.forEach((taskId, taskState) -> {
            if (taskState.expiredAt(now, completedCompileTaskTtl)) {
                compileTasks.remove(taskId, taskState);
            }
        });
    }

    private static @NotNull Duration requirePositiveDuration(@NotNull Duration duration, @NotNull String fieldName) {
        Objects.requireNonNull(duration, fieldName + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }
}

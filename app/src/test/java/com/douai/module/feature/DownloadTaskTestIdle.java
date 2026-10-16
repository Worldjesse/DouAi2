package com.douai.module.feature;

import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/** Drains real worker completion before Robolectric resets the next test's main looper. */
final class DownloadTaskTestIdle {
    private DownloadTaskTestIdle() { }

    static void beforeTest() { drain("before test; a previous test may have left notification state behind"); }

    static void afterTest() {
        for (DownloadTasks.Snapshot task : DownloadTasks.snapshots()) {
            if (task.cancellable) DownloadTasks.cancel(task.id);
        }
        drain("after test");
    }

    private static void drain(String stage) {
        ThreadPoolExecutor workers = read("WORKERS", ThreadPoolExecutor.class);
        ThreadPoolExecutor cancellations = read("CANCELLATIONS", ThreadPoolExecutor.class);
        ThreadPoolExecutor clock = read("CLOCK", ThreadPoolExecutor.class);
        AtomicBoolean notificationPending = read("NOTIFY_PENDING", AtomicBoolean.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            ShadowLooper.idleMainLooper();
            if (tasksTerminal() && idle(workers) && idle(cancellations) && idle(clock)) {
                // Task.released sets terminal before it posts its notification and toast. An idle
                // executor guarantees that release() has returned and those posts are now visible.
                ShadowLooper.idleMainLooper();
                assertFalse("DownloadTasks.NOTIFY_PENDING remained true " + stage
                        + "; do not reset it: wait for the originating test's workers and drain its looper",
                        notificationPending.get());
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (System.nanoTime() < deadline);
        fail("DownloadTasks did not fully finish " + stage + ": workers=" + state(workers)
                + ", cancellations=" + state(cancellations) + ", clock=" + state(clock));
    }

    private static boolean tasksTerminal() {
        return DownloadTasks.snapshots().stream().noneMatch(task -> "running".equals(task.status)
                || "queued".equals(task.status) || "cancelling".equals(task.status));
    }

    private static boolean idle(ThreadPoolExecutor pool) {
        return pool.getActiveCount() == 0 && pool.getQueue().isEmpty();
    }

    private static String state(ThreadPoolExecutor pool) {
        return pool.getActiveCount() + " active/" + pool.getQueue().size() + " queued";
    }

    private static <T> T read(String name, Class<T> type) {
        try {
            Field field = DownloadTasks.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(null));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("DownloadTasks test idle contract changed: " + name, failure);
        }
    }
}

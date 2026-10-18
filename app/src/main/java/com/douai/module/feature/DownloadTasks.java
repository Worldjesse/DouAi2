package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** In-process user download tasks; URLs and credentials never enter public snapshots. */
public final class DownloadTasks {
    interface Work { String run(DownloadTransfer transfer) throws Exception; }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
    private static final BoundedMediaQueue QUEUE = new BoundedMediaQueue(WORKERS);
    private static final ThreadPoolExecutor CANCELLATIONS = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
        Thread thread = new Thread(task, "DouAi-download-cancel"); thread.setDaemon(true); return thread;
    });
    private static final ScheduledThreadPoolExecutor CLOCK = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "DouAi-download-clock"); thread.setDaemon(true); return thread;
    });
    private static final Map<String, Task> TASKS = new LinkedHashMap<>();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean NOTIFY_PENDING = new AtomicBoolean();
    static Supplier<DownloadTransfer> transfers = DownloadTransfer::new;
    static { CLOCK.setRemoveOnCancelPolicy(true); }
    private DownloadTasks() { }

    public static final class Snapshot {
        public final String id, name, phase, status;
        public final long bytes, totalBytes;
        public final int completedFiles, totalFiles;
        public final boolean cancellable;
        public Snapshot(String id, String name, String phase, String status, long bytes, long totalBytes,
                        int completedFiles, int totalFiles, boolean cancellable) {
            this.id = id; this.name = name; this.phase = phase; this.status = status;
            this.bytes = bytes; this.totalBytes = totalBytes; this.completedFiles = completedFiles;
            this.totalFiles = totalFiles; this.cancellable = cancellable;
        }
    }

    public static List<Snapshot> snapshots() {
        List<Snapshot> result = new ArrayList<>();
        synchronized (TASKS) { for (Task task : TASKS.values()) result.add(task.snapshot()); }
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }
    public static AutoCloseable listen(Runnable listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }
    public static void cancel(String id) {
        Task task;
        synchronized (TASKS) { task = TASKS.get(id); }
        if (task != null) task.cancel();
    }
    static Task submit(Context source, String key, String name, Work work, Consumer<String> log) {
        Context application = source.getApplicationContext();
        Task task = new Task(application == null ? source : application, key, name, log,
                key.startsWith("third-party\n") ? "第三方下载失败，请重试" : "媒体保存失败，请重试");
        synchronized (TASKS) { TASKS.put(task.id, task); }
        BoundedMediaQueue.Handle handle = QUEUE.submit(key, () -> task.run(work), task::released);
        if (handle == null) {
            synchronized (TASKS) { TASKS.remove(task.id); }
            return null;
        }
        synchronized (task) { if (!task.terminal) task.handle = handle; }
        if (task.transfer.isCancelled()) handle.cancel();
        changed();
        return task;
    }
    private static void changed() {
        if (!NOTIFY_PENDING.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            NOTIFY_PENDING.set(false);
            for (Runnable listener : LISTENERS) try { listener.run(); } catch (RuntimeException ignored) { }
        });
    }
    private static void trim() {
        synchronized (TASKS) {
            int finished = 0;
            for (Task task : TASKS.values()) if (task.terminal) finished++;
            java.util.Iterator<Task> iterator = TASKS.values().iterator();
            while (finished > 50 && iterator.hasNext()) if (iterator.next().terminal) {
                iterator.remove(); finished--;
            }
        }
    }

    static final class Task {
        final String id = UUID.randomUUID().toString();
        final Context context;
        final Consumer<String> log;
        final String failureMessage;
        private final AnalyticsActionCapture analytics;
        final DownloadTransfer transfer = transfers.get();
        volatile BoundedMediaQueue.Handle handle;
        volatile String status = "queued";
        volatile String result = "failed";
        volatile String successMessage = "下载已完成";
        volatile boolean terminal;
        final AtomicBoolean disconnectQueued = new AtomicBoolean();
        Task(Context context, String key, String name, Consumer<String> log, String failureMessage) {
            this.context = context; this.log = log; this.failureMessage = failureMessage;
            // A parser finishing is not saved media. Completed saves count once per task, with no
            // work/visit attribution: a later foreground card must never inherit this completion.
            analytics = key.startsWith("third-party\nparse\n") ? null : AnalyticsActionCapture.capture(context, null);
            transfer.name = name; transfer.changed = DownloadTasks::changed;
        }
        Snapshot snapshot() {
            return new Snapshot(id, transfer.name, transfer.phase, status, transfer.bytes, transfer.totalBytes,
                    transfer.completedFiles, transfer.totalFiles, !terminal && !"cancelling".equals(status));
        }
        void run(Work work) {
            java.util.concurrent.ScheduledFuture<?> watchdog = null;
            try {
                transfer.start();
                synchronized (this) {
                    transfer.check();
                    status = "running";
                }
                transfer.phase("准备下载");
                watchdog = CLOCK.scheduleWithFixedDelay(this::watch, 1, 1, TimeUnit.SECONDS);
                successMessage = work.run(transfer);
                transfer.complete();
                result = "completed";
            } catch (Exception failure) {
                result = transfer.isTimedOut() ? "timed_out" : transfer.isCancelled()
                        || failure instanceof java.util.concurrent.CancellationException ? "cancelled" : "failed";
                if ("failed".equals(result) && log != null) try {
                    log.accept("Download failed: " + failure.getClass().getSimpleName());
                } catch (RuntimeException ignored) { }
            } finally {
                if (watchdog != null) watchdog.cancel(false);
                transfer.onStop(null);
            }
        }
        void watch() {
            try { transfer.check(); }
            catch (Exception stopped) {
                synchronized (this) { if (terminal) return; status = "cancelling"; }
                stopInBackground();
                changed();
            }
        }
        void cancel() {
            synchronized (this) {
                if (terminal || !transfer.requestCancel(false)) return;
                status = "cancelling";
            }
            changed();
            stopInBackground();
        }
        private void stopInBackground() {
            BoundedMediaQueue.Handle active = handle;
            if (active != null) active.cancel();
            if (!transfer.hasResources() || !disconnectQueued.compareAndSet(false, true)) return;
            try {
                CANCELLATIONS.execute(() -> {
                    try { transfer.disconnect(); }
                    finally { disconnectQueued.set(false); }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                // The running task's existing watchdog retries; never block the caller or add threads.
                disconnectQueued.set(false);
            }
        }
        void released() {
            synchronized (this) {
                if (terminal) return;
                status = transfer.isTimedOut() ? "timed_out" : transfer.isCancelled() ? "cancelled" : result;
                terminal = true;
                handle = null;
                transfer.phase = "completed".equals(status) ? "已完成" : "cancelled".equals(status) ? "已取消"
                        : "timed_out".equals(status) ? "超过 30 分钟，已停止" : "下载失败，请重试";
            }
            if ("completed".equals(status) && analytics != null) {
                analytics.manual("download_completed", "下载任务完成", "success");
            }
            trim(); changed();
            MAIN.post(() -> {
                try { Toast.makeText(context, "completed".equals(status) ? successMessage
                        : "failed".equals(status) ? failureMessage : transfer.phase, Toast.LENGTH_SHORT).show(); }
                catch (RuntimeException ignored) { }
            });
        }
    }
}

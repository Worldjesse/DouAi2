package com.douai.module.feature;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/** Deduplicates media requests while delegating capacity to a bounded executor. */
final class BoundedMediaQueue {
    private final Executor executor;
    private final Set<String> activeKeys = ConcurrentHashMap.newKeySet();

    BoundedMediaQueue(Executor executor) {
        if (executor == null) throw new IllegalArgumentException("Executor is required");
        this.executor = executor;
    }

    boolean submit(String key, Runnable task) {
        return submit(key, task, () -> { }) != null;
    }

    Handle submit(String key, Runnable task, Runnable released) {
        if (key == null || key.isEmpty() || task == null || !activeKeys.add(key)) return null;
        Handle handle = new Handle(key, task, released);
        try {
            executor.execute(handle);
            return handle;
        } catch (RejectedExecutionException rejected) {
            activeKeys.remove(key);
            return null;
        }
    }

    final class Handle implements Runnable {
        private final String key;
        private final FutureTask<Void> future;
        private final Runnable released;
        private final AtomicInteger state = new AtomicInteger(); // queued, running, released
        Handle(String key, Runnable task, Runnable released) {
            this.key = key; this.future = new FutureTask<>(task, null); this.released = released;
        }
        @Override public void run() {
            if (!state.compareAndSet(0, 1)) return;
            try { future.run(); }
            finally { state.set(2); release(); }
        }
        void cancel() {
            if (state.compareAndSet(0, 2)) {
                future.cancel(false);
                if (executor instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) executor).remove(this);
                release();
            } else if (state.get() == 1) {
                // Keep this key until the running body has finished cleaning up its files.
                future.cancel(true);
            }
        }
        private void release() {
            activeKeys.remove(key);
            released.run();
        }
    }
}

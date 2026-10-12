package com.douai.module.feature;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Deduplicates media requests while delegating capacity to a bounded executor. */
final class BoundedMediaQueue {
    private final Executor executor;
    private final Set<String> activeKeys = ConcurrentHashMap.newKeySet();

    BoundedMediaQueue(Executor executor) {
        if (executor == null) throw new IllegalArgumentException("Executor is required");
        this.executor = executor;
    }

    boolean submit(String key, Runnable task) {
        if (key == null || key.isEmpty() || task == null) return false;
        if (!activeKeys.add(key)) return false;
        try {
            executor.execute(() -> {
                try { task.run(); }
                finally { activeKeys.remove(key); }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            activeKeys.remove(key);
            return false;
        }
    }
}

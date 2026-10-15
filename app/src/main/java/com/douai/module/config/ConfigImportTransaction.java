package com.douai.module.config;

import android.content.SharedPreferences;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps live configuration readers on the last stable snapshot until import/rollback settles. */
public final class ConfigImportTransaction implements AutoCloseable {
    private static final Object LOCK = new Object();
    private static final Map<SharedPreferences, ConfigImportTransaction> ACTIVE = new IdentityHashMap<>();
    private static final Map<SharedPreferences, List<WeakReference<Registration>>> OBSERVERS = new IdentityHashMap<>();
    private final SharedPreferences preferences;
    private final Map<String, ?> stable;
    private final Map<Object, Runnable> deferred = new IdentityHashMap<>();
    private boolean closed;

    private ConfigImportTransaction(SharedPreferences preferences, Map<String, ?> stable) {
        this.preferences = preferences;
        this.stable = copy(stable);
    }

    /** The owner retains this handle; the registry must not keep module instances alive. */
    public static Registration observe(SharedPreferences preferences, Object owner, Runnable settledReload) {
        synchronized (LOCK) {
            List<WeakReference<Registration>> observers = OBSERVERS.computeIfAbsent(preferences, ignored -> new ArrayList<>());
            java.util.Iterator<WeakReference<Registration>> iterator = observers.iterator();
            while (iterator.hasNext()) {
                Registration previous = iterator.next().get();
                if (previous == null || previous.closed || previous.owner == owner) {
                    if (previous != null) previous.closed = true;
                    iterator.remove();
                }
            }
            Registration registration = new Registration(preferences, owner, settledReload);
            observers.add(new WeakReference<>(registration));
            return registration;
        }
    }

    public static final class Registration implements AutoCloseable {
        private final SharedPreferences preferences;
        private final Object owner;
        private final Runnable reload;
        private boolean closed;

        private Registration(SharedPreferences preferences, Object owner, Runnable reload) {
            this.preferences = preferences;
            this.owner = owner;
            this.reload = reload;
        }

        private void notifySettled() {
            synchronized (LOCK) { if (closed) return; }
            reload.run();
        }

        @Override public void close() {
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                List<WeakReference<Registration>> observers = OBSERVERS.get(preferences);
                if (observers != null) {
                    observers.removeIf(reference -> reference.get() == null || reference.get() == this);
                    if (observers.isEmpty()) OBSERVERS.remove(preferences);
                }
                ConfigImportTransaction transaction = ACTIVE.get(preferences);
                if (transaction != null) transaction.deferred.remove(owner);
            }
        }
    }

    public static ConfigImportTransaction begin(SharedPreferences preferences, Map<String, ?> expected) {
        synchronized (LOCK) {
            if (ACTIVE.containsKey(preferences)) throw new IllegalStateException("已有配置导入尚未完成");
            if (!preferences.getAll().equals(expected)) throw new IllegalStateException("设置已发生变化，请重新预览导入文件");
            ConfigImportTransaction transaction = new ConfigImportTransaction(preferences, expected);
            ACTIVE.put(preferences, transaction);
            return transaction;
        }
    }

    static Map<String, ?> capture(SharedPreferences preferences) {
        synchronized (LOCK) {
            ConfigImportTransaction transaction = ACTIVE.get(preferences);
            return transaction == null ? copy(preferences.getAll()) : transaction.stable;
        }
    }

    /** Capture and the decision to defer share begin's lock, so no imported memory can escape. */
    static Map<String, ?> captureForPublication(SharedPreferences preferences, Object owner, Runnable settledReload) {
        synchronized (LOCK) {
            ConfigImportTransaction transaction = ACTIVE.get(preferences);
            if (transaction == null) return copy(preferences.getAll());
            transaction.deferred.put(owner, settledReload);
            return null;
        }
    }

    public static boolean deferUntilSettled(SharedPreferences preferences, Object owner, Runnable settledReload) {
        synchronized (LOCK) {
            ConfigImportTransaction transaction = ACTIVE.get(preferences);
            if (transaction == null) return false;
            transaction.deferred.put(owner, settledReload);
            return true;
        }
    }

    @Override public void close() {
        List<Runnable> callbacks;
        synchronized (LOCK) {
            if (closed) return;
            closed = true;
            ACTIVE.remove(preferences);
            Map<Object, Runnable> notifications = new IdentityHashMap<>(deferred);
            List<WeakReference<Registration>> observers = OBSERVERS.get(preferences);
            if (observers != null) {
                java.util.Iterator<WeakReference<Registration>> iterator = observers.iterator();
                while (iterator.hasNext()) {
                    Registration registration = iterator.next().get();
                    if (registration == null || registration.closed) iterator.remove();
                    else notifications.put(registration.owner, registration::notifySettled);
                }
                if (observers.isEmpty()) OBSERVERS.remove(preferences);
            }
            callbacks = new ArrayList<>(notifications.values());
            deferred.clear();
        }
        // Callers schedule their reload; never execute application code while holding the capture lock.
        for (Runnable callback : callbacks) {
            try { callback.run(); } catch (RuntimeException ignored) { }
        }
    }

    private static Map<String, ?> copy(Map<String, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value instanceof Set<?>
                ? Collections.unmodifiableSet(new HashSet<>((Set<?>) value)) : value));
        return Collections.unmodifiableMap(result);
    }
}

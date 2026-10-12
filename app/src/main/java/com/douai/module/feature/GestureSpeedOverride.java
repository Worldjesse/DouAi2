package com.douai.module.feature;

import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** Keeps a gesture's speed choice attached to the real engine behind player facades. */
public final class GestureSpeedOverride implements AutoCloseable {
    @FunctionalInterface
    public interface ReflectiveCall {
        void run() throws ReflectiveOperationException;
    }

    private static final WeakHashMap<Object, GestureSpeedOverride> BINDINGS = new WeakHashMap<>();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private volatile float desired = 1f;
    private volatile Float deferredRestoration;
    private volatile boolean closed;

    public GestureSpeedOverride() { }

    public void apply(float speed, ReflectiveCall action) throws ReflectiveOperationException {
        if (closed) throw new IllegalStateException("Gesture speed session is closed");
        if (!Float.isFinite(speed) || speed <= 0f) throw new IllegalArgumentException("Invalid playback speed");
        Set<Object> previousBindings = new HashSet<>();
        synchronized (BINDINGS) {
            BINDINGS.forEach((engine, owner) -> { if (owner == this) previousBindings.add(engine); });
        }
        float previous = desired;
        desired = speed;
        try { runScoped(action, false); }
        catch (ReflectiveOperationException | RuntimeException | Error failure) {
            desired = previous;
            synchronized (BINDINGS) {
                BINDINGS.entrySet().removeIf(entry -> entry.getValue() == this
                        && !previousBindings.contains(entry.getKey()));
            }
            throw failure;
        }
    }

    public void restore(ReflectiveCall action) throws ReflectiveOperationException {
        runScoped(action, true);
    }

    public float restorationSpeed(float fallback) {
        Float original = deferredRestoration;
        return original == null ? fallback : original;
    }

    private void runScoped(ReflectiveCall action, boolean restoring) throws ReflectiveOperationException {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(this, restoring));
        try { action.run(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static boolean inScope() { return CURRENT.get() != null; }

    static boolean restoring() {
        Scope scope = CURRENT.get();
        return scope != null && scope.restoring;
    }

    static boolean bypassSpeedWrite(Object engine) {
        Scope scope = CURRENT.get();
        synchronized (BINDINGS) {
            if (scope != null) {
                if (!scope.restoring && !scope.owner.closed && engine != null) {
                    BINDINGS.put(engine, scope.owner);
                }
                return true;
            }
            GestureSpeedOverride owner = BINDINGS.get(engine);
            return owner != null && !owner.closed;
        }
    }

    static Float speedFor(Object engine) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.restoring) return null;
        synchronized (BINDINGS) {
            if (scope != null && !scope.owner.closed && engine != null) {
                BINDINGS.put(engine, scope.owner);
            }
            GestureSpeedOverride owner = BINDINGS.get(engine);
            return owner == null || owner.closed ? null : owner.desired;
        }
    }

    static boolean deferRestoration(Object engine, float original) {
        synchronized (BINDINGS) {
            GestureSpeedOverride owner = BINDINGS.get(engine);
            if (owner == null || owner.closed) return false;
            owner.deferredRestoration = original;
            return true;
        }
    }

    @Override public void close() {
        closed = true;
        synchronized (BINDINGS) { BINDINGS.entrySet().removeIf(entry -> entry.getValue() == this); }
    }

    private static final class Scope {
        final GestureSpeedOverride owner;
        final boolean restoring;
        Scope(GestureSpeedOverride owner, boolean restoring) {
            this.owner = owner;
            this.restoring = restoring;
        }
    }
}

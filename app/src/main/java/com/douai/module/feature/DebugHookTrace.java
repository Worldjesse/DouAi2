package com.douai.module.feature;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import com.douai.module.diagnostics.DebugCommentSnapshot;
import com.douai.module.diagnostics.DebugMessageSnapshot;

import java.io.FileNotFoundException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import io.github.libxposed.api.XposedInterface;

/** Observes registered hookers without replacing their chain or invoking the original method. */
public final class DebugHookTrace {
    private static final ThreadLocal<Boolean> OBSERVING = new ThreadLocal<>();
    private static final int MAX_ARGS = 6;
    private DebugHookTrace() { }

    public static XposedInterface wrap(XposedInterface base, BooleanSupplier enabled,
                                       Consumer<String> detailLog) {
        return wrap(base, enabled, detailLog, System::nanoTime);
    }

    static XposedInterface wrap(XposedInterface base, BooleanSupplier enabled,
                                Consumer<String> detailLog, LongSupplier nanoClock) {
        Objects.requireNonNull(base);
        Objects.requireNonNull(enabled);
        Objects.requireNonNull(detailLog);
        Objects.requireNonNull(nanoClock);
        if (base instanceof TracingFramework) return base;
        return new TracingFramework(base, enabled, detailLog, nanoClock);
    }

    public static long droppedCount(XposedInterface framework) {
        return framework instanceof TracingFramework ? ((TracingFramework) framework).limiter.totalDropped() : 0;
    }

    public static void reset(XposedInterface framework) {
        if (framework instanceof TracingFramework) {
            TracingFramework tracing = (TracingFramework) framework;
            tracing.limiter.reset();
            tracing.comments.reset();
            tracing.messages.reset();
        }
    }

    private static boolean enabled(BooleanSupplier enabled) {
        try { return enabled.getAsBoolean(); }
        catch (Throwable unavailable) { return false; }
    }

    static String label(String value) {
        if (value == null || value.isEmpty()) return "unnamed";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 120; i++) {
            char c = value.charAt(i);
            result.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == '-' || c == '$' || c == '<' || c == '>' ? c : '_');
        }
        return result.toString();
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        String type = value.getClass().getName();
        if (type.length() > 96) type = type.substring(0, 96);
        if (value instanceof Boolean) return type + "(" + (((Boolean) value) ? "true" : "false") + ")";
        try {
            if (value instanceof String) return type + "(len=" + ((String) value).length() + ")";
            if (value.getClass().isArray()) return type + "(size=" + Array.getLength(value) + ")";
            if (value instanceof Collection<?>) return type + "(size=" + ((Collection<?>) value).size() + ")";
            if (value instanceof Map<?, ?>) return type + "(size=" + ((Map<?, ?>) value).size() + ")";
        } catch (Throwable unavailable) { return type + "(size=unavailable)"; }
        // In particular, numbers, enums, DTOs and arbitrary CharSequence objects reveal no value.
        return type;
    }

    private static String arguments(XposedInterface.Chain chain) {
        try {
            List<Object> args = chain.getArgs();
            if (args == null) return "unavailable";
            int size = args.size();
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < Math.min(size, MAX_ARGS); i++) {
                if (i != 0) result.append(',');
                result.append(describe(args.get(i)));
            }
            if (size > MAX_ARGS) result.append(",more=").append(size - MAX_ARGS);
            return result.append(']').toString();
        } catch (Throwable unavailable) { return "unavailable"; }
    }

    private static final class TracingFramework implements XposedInterface {
        private final XposedInterface base;
        private final BooleanSupplier enabled;
        private final Consumer<String> log;
        private final LongSupplier clock;
        private final Limiter limiter = new Limiter();
        private final DebugCommentSnapshot comments;
        private final DebugMessageSnapshot messages;

        TracingFramework(XposedInterface base, BooleanSupplier enabled, Consumer<String> log, LongSupplier clock) {
            this.base = base; this.enabled = enabled; this.log = log; this.clock = clock;
            comments = new DebugCommentSnapshot(enabled, log, clock);
            messages = new DebugMessageSnapshot(enabled, log, clock);
        }

        private long now() {
            try { return clock.getAsLong(); }
            catch (Throwable unavailable) { return System.nanoTime(); }
        }

        private void emit(String message) {
            if (!enabled(enabled)) return;
            emitBasic(message);
        }

        private void emitBasic(String message) {
            Boolean previous = OBSERVING.get();
            OBSERVING.set(Boolean.TRUE);
            try { log.accept(message); }
            catch (Throwable unavailable) { /* Diagnostics cannot change a host invocation. */ }
            finally { if (previous == null) OBSERVING.remove(); else OBSERVING.set(previous); }
        }

        private String inputs(XposedInterface.Chain chain) {
            OBSERVING.set(Boolean.TRUE);
            try { return arguments(chain); }
            finally { OBSERVING.remove(); }
        }

        private void snapshot(String id, String phase, XposedInterface.Chain chain, Object result) {
            OBSERVING.set(Boolean.TRUE);
            try {
                Object owner = chain.getThisObject();
                List<?> arguments = chain.getArgs();
                comments.capture(id, phase, owner, arguments, result);
                messages.capture(id, phase, owner, arguments, result);
            }
            catch (Throwable unavailable) { /* Includes malformed framework metadata, never the hooker's failure. */ }
            finally { OBSERVING.remove(); }
        }

        private Hooker observe(String id, Hooker original) {
            return chain -> {
                // Disabled means no clock, argument inspection, JSON, limiter or chain access.
                if (!enabled(enabled)) {
                    try { return original.intercept(chain); }
                    catch (Throwable failure) {
                        if (!expectedPackageLookup(id, failure) && !Boolean.TRUE.equals(OBSERVING.get())) emitBasic("Hook call threw; hook=" + id
                                + "; exception=" + failure.getClass().getName());
                        throw failure;
                    }
                }
                if (Boolean.TRUE.equals(OBSERVING.get())) return original.intercept(chain);
                long started = now();
                long dropped = limiter.takePendingDrops(started / 1_000_000L);
                if (dropped != 0) emit("[DouAi.Trace] dropped=" + dropped + " total=" + limiter.totalDropped()
                        + " windowMs=" + Limiter.WINDOW_MS);
                boolean sampled = limiter.normal(id, started / 1_000_000L);
                String input = sampled ? inputs(chain) : "not_sampled";
                snapshot(id, "before", chain, null);
                long invokedAt = now();
                Object result;
                try {
                    result = original.intercept(chain);
                } catch (Throwable failure) {
                    long finishedAt = now();
                    if (expectedPackageLookup(id, failure)) {
                        if (sampled) emit("[DouAi.Trace] hook=" + id + " outcome=package_not_found"
                                + " elapsedUs=" + elapsed(invokedAt, finishedAt));
                        else limiter.dropped();
                    } else if (sampled || limiter.error(id, finishedAt / 1_000_000L)) {
                        emit("[DouAi.Trace] hook=" + id + " args=" + input + " throws="
                                + failure.getClass().getName() + " elapsedUs=" + elapsed(invokedAt, finishedAt));
                    } else limiter.dropped();
                    snapshot(id, "after", chain, null);
                    throw failure;
                }
                long finishedAt = now();
                if (sampled) {
                    String output;
                    OBSERVING.set(Boolean.TRUE);
                    try { output = describe(result); }
                    catch (Throwable unavailable) { output = "unavailable"; }
                    finally { OBSERVING.remove(); }
                    emit("[DouAi.Trace] hook=" + id + " args=" + input + " result=" + output
                            + " elapsedUs=" + elapsed(invokedAt, finishedAt));
                } else limiter.dropped();
                snapshot(id, "after", chain, result);
                return result;
            };
        }

        private long elapsed(long started, long finished) { return Math.max(0L, finished - started) / 1_000L; }

        private static boolean expectedPackageLookup(String id, Throwable failure) {
            if (!id.startsWith("DouAi.VersionInfo.")) return false;
            for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
                if ("android.content.pm.PackageManager$NameNotFoundException".equals(type.getName())) return true;
            }
            return false;
        }

        @Override public HookBuilder hook(Executable target) {
            return new Builder(base.hook(target), label(target.getDeclaringClass().getSimpleName() + "."
                    + (target instanceof Constructor<?> ? "<init>" : target.getName())));
        }
        @Override public HookBuilder hookClassInitializer(Class<?> type) {
            return new Builder(base.hookClassInitializer(type), label(type.getSimpleName() + ".<clinit>"));
        }

        private final class Builder implements HookBuilder {
            private HookBuilder delegate;
            private String id;
            Builder(HookBuilder delegate, String fallback) { this.delegate = delegate; id = fallback; }
            @Override public HookBuilder setPriority(int priority) { delegate = delegate.setPriority(priority); return this; }
            @Override public HookBuilder setExceptionMode(ExceptionMode mode) { delegate = delegate.setExceptionMode(mode); return this; }
            @Override public HookBuilder setId(String value) { delegate = delegate.setId(value); id = label(value); return this; }
            @Override public HookHandle intercept(Hooker hooker) {
                // Return the real handle, retaining the framework's unhook/getId/replaceHook contracts.
                return delegate.intercept(hooker == null ? null : observe(id, hooker));
            }
        }

        // Direct delegation deliberately introduces no Method.invoke/InvocationTargetException wrapper.
        @Override public int getApiVersion() { return base.getApiVersion(); }
        @Override public String getFrameworkName() { return base.getFrameworkName(); }
        @Override public String getFrameworkVersion() { return base.getFrameworkVersion(); }
        @Override public long getFrameworkVersionCode() { return base.getFrameworkVersionCode(); }
        @Override public long getFrameworkProperties() { return base.getFrameworkProperties(); }
        @Override public boolean deoptimize(Executable target) { return base.deoptimize(target); }
        @Override public Invoker<?, Method> getInvoker(Method target) { return base.getInvoker(target); }
        @Override public <T> CtorInvoker<T> getInvoker(Constructor<T> target) { return base.getInvoker(target); }
        @Override public void log(int priority, String tag, String message) { base.log(priority, tag, message); }
        @Override public void log(int priority, String tag, String message, Throwable failure) { base.log(priority, tag, message, failure); }
        @Override public ApplicationInfo getModuleApplicationInfo() { return base.getModuleApplicationInfo(); }
        @Override public SharedPreferences getRemotePreferences(String name) { return base.getRemotePreferences(name); }
        @Override public String[] listRemoteFiles() { return base.listRemoteFiles(); }
        @Override public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException { return base.openRemoteFile(name); }
    }

    private static final class Limiter {
        static final long WINDOW_MS = 5_000;
        private static final int MAX_KEYS = 512;
        private final Map<String, int[]> keys = new HashMap<>();
        private long start = Long.MIN_VALUE, windowDropped, pendingDropped, totalDropped;
        private int normal, errors;
        private void advance(long now) {
            if (start == Long.MIN_VALUE || now < start || now - start >= WINDOW_MS) {
                pendingDropped += windowDropped; windowDropped = 0;
                start = now; normal = 0; errors = 0; keys.clear();
            }
        }
        synchronized long takePendingDrops(long now) {
            advance(now); long result = pendingDropped; pendingDropped = 0; return result;
        }
        synchronized boolean normal(String id, long now) {
            advance(now); int[] counts = keys.get(id);
            if (normal >= 200 || counts != null && counts[0] >= 8 || counts == null && keys.size() >= MAX_KEYS) return false;
            if (counts == null) { counts = new int[2]; keys.put(id, counts); }
            normal++; counts[0]++; return true;
        }
        synchronized boolean error(String id, long now) {
            advance(now); int[] counts = keys.get(id);
            if (errors >= 20 || counts != null && counts[1] >= 2) return false;
            if (counts == null && keys.size() < MAX_KEYS) { counts = new int[2]; keys.put(id, counts); }
            errors++; if (counts != null) counts[1]++; return true;
        }
        synchronized void dropped() { windowDropped++; totalDropped++; }
        synchronized long totalDropped() { return totalDropped; }
        synchronized void reset() {
            start = Long.MIN_VALUE; windowDropped = 0; pendingDropped = 0; totalDropped = 0;
            normal = 0; errors = 0; keys.clear();
        }
    }
}

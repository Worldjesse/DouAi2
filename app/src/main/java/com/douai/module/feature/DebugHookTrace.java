package com.douai.module.feature;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.FeatureLogCatalog;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/** Observes registered hookers without replacing their chain or invoking the original method. */
public final class DebugHookTrace {
    private static final ThreadLocal<Boolean> OBSERVING = new ThreadLocal<>();
    private static final int MAX_ARGS = 6;
    private DebugHookTrace() { }

    /** Production path: safe detail fields go directly to the structured evidence sink. */
    public static XposedInterface wrap(XposedInterface base, BooleanSupplier enabled) {
        return wrap(base, enabled, ignored -> { }, System::nanoTime);
    }

    /** Optional safe-summary observer; it is never responsible for delivering structured evidence. */
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

        TracingFramework(XposedInterface base, BooleanSupplier enabled, Consumer<String> log, LongSupplier clock) {
            this.base = base; this.enabled = enabled; this.log = log; this.clock = clock;
        }

        private long now() {
            try { return clock.getAsLong(); }
            catch (Throwable unavailable) { return System.nanoTime(); }
        }

        private void emit(String message) {
            if (!enabled(enabled)) return;
            Boolean previous = OBSERVING.get();
            OBSERVING.set(Boolean.TRUE);
            try {
                DouAiLog.event("logging", "hook_detail_suppression", "detail_rate_limit", message);
            } catch (Throwable unavailable) { /* Diagnostics cannot change a host invocation. */ }
            finally { if (previous == null) OBSERVING.remove(); else OBSERVING.set(previous); }
            emitBasic(message);
        }

        private void detail(String id, String phase, String input, String output,
                            long durationUs, Throwable error, String summary) {
            Boolean previous = OBSERVING.get();
            OBSERVING.set(Boolean.TRUE);
            try { DouAiLog.hookDetail(id, FeatureLogCatalog.forHook(id), phase, input, output, durationUs, error); }
            catch (Throwable unavailable) { /* Diagnostics cannot change a host invocation. */ }
            finally { if (previous == null) OBSERVING.remove(); else OBSERVING.set(previous); }
            // Compatibility observers receive only the already-safe summary, never host objects.
            emitBasic(summary);
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

        private Hooker observe(String id, Hooker original) {
            Observation observation = new Observation(id);
            return chain -> {
                if (!Boolean.TRUE.equals(OBSERVING.get())) observation.entered();
                // Basic observations never inspect the host's arguments or read the clock.
                if (!enabled(enabled)) {
                    try {
                        Object result = original.intercept(chain);
                        observation.finished(false);
                        return result;
                    }
                    catch (Throwable failure) {
                        observation.finished(true);
                        if (!expectedPackageLookup(id, failure) && !Boolean.TRUE.equals(OBSERVING.get())) {
                            detail(id, "threw", "not_inspected", "unavailable", -1L, failure,
                                    "Hook call threw; hook=" + id + "; exception=" + failure.getClass().getName());
                        }
                        throw failure;
                    }
                }
                if (Boolean.TRUE.equals(OBSERVING.get())) return original.intercept(chain);
                long started = now();
                long decision = limiter.sample(id, started / 1_000_000L);
                long dropped = decision >>> 1;
                if (dropped != 0) emit("[DouAi.Trace] dropped=" + dropped + " total=" + limiter.totalDropped()
                        + " windowMs=" + Limiter.WINDOW_MS);
                boolean sampled = (decision & 1) != 0;
                String input = sampled ? inputs(chain) : "not_sampled";

                long invokedAt = sampled ? now() : 0;
                Object result;
                try {
                    result = original.intercept(chain);
                } catch (Throwable failure) {
                    observation.finished(true);
                    long finishedAt = now();
                    if (expectedPackageLookup(id, failure)) {
                        if (sampled) detail(id, "package_not_found", input, "unavailable",
                                elapsed(invokedAt, finishedAt), null,
                                "[DouAi.Trace] hook=" + id + " outcome=package_not_found"
                                        + " elapsedUs=" + elapsed(invokedAt, finishedAt));
                        else limiter.dropped();
                    } else if (sampled || limiter.error(id, finishedAt / 1_000_000L)) {
                        detail(id, "threw", input, "unavailable", sampled ? elapsed(invokedAt, finishedAt) : -1, failure,
                                "[DouAi.Trace] hook=" + id + " args=" + input + " throws="
                                        + failure.getClass().getName() + " elapsedUs=" + (sampled ? elapsed(invokedAt, finishedAt) : -1));
                    } else limiter.dropped();

                    throw failure;
                }
                long finishedAt = sampled ? now() : 0;
                observation.finished(false);
                if (sampled) {
                    String output;
                    OBSERVING.set(Boolean.TRUE);
                    try { output = describe(result); }
                    catch (Throwable unavailable) { output = "unavailable"; }
                    finally { OBSERVING.remove(); }
                    if (enabled(enabled)) detail(id, "returned", input, output, elapsed(invokedAt, finishedAt), null,
                            "[DouAi.Trace] hook=" + id + " args=" + input + " result=" + output
                                    + " elapsedUs=" + elapsed(invokedAt, finishedAt));
                } else limiter.dropped();

                return result;
            };
        }

        /** A return is only transport observation. It never closes a business operation as success. */
        private final class Observation {
            final String id, feature;
            final AtomicLong entered = new AtomicLong(), returned = new AtomicLong(), threw = new AtomicLong(), completed = new AtomicLong();
            final AtomicReference<Boolean> previousFailure = new AtomicReference<>();
            Observation(String id) { this.id = id; feature = FeatureLogCatalog.forHook(id); }
            void entered() {
                if (entered.incrementAndGet() == 1) report("first_trigger", "entered=1;business_result=unknown");
            }
            void finished(boolean failure) {
                if (Boolean.TRUE.equals(OBSERVING.get())) return;
                if (failure) threw.incrementAndGet(); else returned.incrementAndGet();
                long count = completed.incrementAndGet();
                Boolean previous = previousFailure.getAndSet(failure);
                if (previous == null || previous.booleanValue() != failure || count >= 64 && (count & (count - 1)) == 0) {
                    report(failure ? "threw" : "returned", "entered=" + entered.get() + ";returned=" + returned.get()
                            + ";threw=" + threw.get() + ";aggregated=" + (count > 1)
                            + ";business_result=unknown");
                }
            }
            void report(String phase, String evidence) {
                Boolean prior = OBSERVING.get();
                OBSERVING.set(Boolean.TRUE);
                try { DouAiLog.hook(id, feature, phase, evidence); }
                catch (Throwable ignored) { }
                finally { if (prior == null) OBSERVING.remove(); else OBSERVING.set(prior); }
            }
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
                try {
                    HookHandle handle = delegate.intercept(hooker == null ? null : observe(id, hooker));
                    DouAiLog.hook(id, FeatureLogCatalog.forHook(id), handle == null ? "installation_empty" : "installed",
                            "business_result=not_implied");
                    return handle;
                } catch (Throwable failure) {
                    DouAiLog.failure(FeatureLogCatalog.forHook(id), "hook_installation", failure);
                    throw failure;
                }
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
        synchronized long sample(String id, long now) {
            advance(now);
            long result = pendingDropped << 1; pendingDropped = 0;
            int[] counts = keys.get(id);
            if (normal >= 200 || counts != null && counts[0] >= 8 || counts == null && keys.size() >= MAX_KEYS) return result;
            if (counts == null) { counts = new int[2]; keys.put(id, counts); }
            normal++; counts[0]++; return result | 1;
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

package com.douai.module.feature;

import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public final class DebugHookTraceTest {
    @Test public void fluentImmutableDelegateStaysWrappedAndReturnsTheOriginalHandle() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, () -> true, logs::add, () -> 0);
        XposedInterface.HookBuilder builder = wrapped.hook(target());
        assertSame(builder, builder.setPriority(42));
        assertSame(builder, builder.setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH));
        assertSame(builder, builder.setId("DouAi.Trace.Example"));
        Object value = new Object();
        assertSame(f.handle, builder.intercept(chain -> value));
        assertSame(value, f.hooker.intercept(f.chain()));
        assertEquals(Arrays.asList("priority:42", "exception:PASSTHROUGH", "id:DouAi.Trace.Example"), f.setters);
        assertEquals(3, f.interceptBuilderDepth);
        assertTrue(logs.get(0).contains("hook=DouAi.Trace.Example"));
        f.handle.unhook();
        assertEquals(1, f.unhooked);
        assertSame(wrapped, DebugHookTrace.wrap(wrapped, () -> true, logs::add));
        assertEquals("fixture", wrapped.getFrameworkName());
        assertEquals(102, wrapped.getApiVersion());
    }

    @Test public void disabledExecutionTouchesNeitherChainMetadataNorClockAndCanBeEnabledLater() throws Throwable {
        Fixture f = new Fixture(); AtomicBoolean enabled = new AtomicBoolean(); AtomicInteger clock = new AtomicInteger();
        List<String> logs = new ArrayList<>(); Object value = new Object();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, enabled::get, logs::add,
                () -> { clock.incrementAndGet(); return 0; });
        wrapped.hook(target()).setId("toggle").intercept(chain -> value);
        XposedInterface.Chain unreadable = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (p, method, args) -> { throw new AssertionError("metadata read"); });
        assertSame(value, f.hooker.intercept(unreadable));
        assertEquals(0, clock.get()); assertTrue(logs.isEmpty());
        enabled.set(true);
        assertSame(value, f.hooker.intercept(f.chain()));
        assertEquals(1, logs.size());
    }

    @Test public void originalChainAndReturnObjectArePassedThroughWithoutAnExtraProceed() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>();
        XposedInterface.Chain originalChain = f.chain();
        DebugHookTrace.wrap(f.framework, () -> true, logs::add, () -> 0).hook(target()).intercept(chain -> {
            assertSame(originalChain, chain);
            return chain.proceed();
        });
        assertSame(f.result, f.hooker.intercept(originalChain));
        assertEquals(1, f.proceeded);
        assertEquals(1, logs.size());
    }

    @Test public void disabledFailureRetainsTheOriginalThrowableAndOnlyEmitsASimpleClassSummary() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>(); AtomicInteger calls = new AtomicInteger();
        Error original = new AssertionError("PRIVATE FAILURE CONTENT");
        DebugHookTrace.wrap(f.framework, () -> false, logs::add,
                () -> { throw new AssertionError("disabled clock read"); }).hook(target()).setId("basic").intercept(chain -> {
                    calls.incrementAndGet(); throw original;
                });
        XposedInterface.Chain unreadable = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (p, method, args) -> { throw new AssertionError("metadata read"); });
        try { f.hooker.intercept(unreadable); fail(); } catch (Throwable actual) { assertSame(original, actual); }
        assertEquals(1, calls.get());
        assertEquals(List.of("Hook call threw; hook=basic; exception=java.lang.AssertionError"), logs);
    }

    @Test public void absentPackagesStayNativeLookupResultsAndDoNotFloodBasicErrors() throws Throwable {
        for (boolean detail : new boolean[]{false, true}) {
            Fixture f = new Fixture(); List<String> logs = new ArrayList<>(); AtomicInteger calls = new AtomicInteger();
            Throwable original = new android.content.pm.PackageManager.NameNotFoundException("private-package");
            DebugHookTrace.wrap(f.framework, () -> detail, logs::add, () -> 0).hook(target())
                    .setId("DouAi.VersionInfo.int").intercept(chain -> { calls.incrementAndGet(); throw original; });
            try { f.hooker.intercept(f.chain()); fail(); } catch (Throwable actual) { assertSame(original, actual); }
            assertEquals(1, calls.get());
            if (detail) {
                assertTrue(logs.stream().anyMatch(line -> line.contains("outcome=package_not_found")));
                assertFalse(String.join("\n", logs).contains("throws="));
            } else assertTrue(logs.isEmpty());
            assertFalse(String.join("\n", logs).contains("private-package"));
        }
    }

    @Test public void intentionalSuppressionNeverInvokesTheOriginalAndFailuresRetainIdentity() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, () -> true, logs::add, () -> 0);
        wrapped.hook(target()).intercept(chain -> false);
        assertEquals(Boolean.FALSE, f.hooker.intercept(f.chain()));
        assertEquals(0, f.proceeded);
        Exception originalFailure = new Exception("PRIVATE ERROR CONTENT");
        wrapped.hook(target()).setId("throws").intercept(chain -> { throw originalFailure; });
        try { f.hooker.intercept(f.chain()); fail(); }
        catch (Throwable actual) { assertSame(originalFailure, actual); }
        assertTrue(logs.get(1).contains("throws=java.lang.Exception"));
        assertFalse(String.join("\n", logs).contains("PRIVATE ERROR CONTENT"));
    }

    @Test public void summariesExposeOnlyTypesBooleanAndContainerLengthsWithoutCallingToStringOrIterators() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>();
        f.arguments = Arrays.asList("PRIVATE CONTENT", 987654321L, true, new Poison(), new SizedPoison(), new byte[7]);
        f.result = Boolean.FALSE;
        DebugHookTrace.wrap(f.framework, () -> true, logs::add, () -> 0).hook(target()).setId("summary").intercept(XposedInterface.Chain::proceed);
        assertEquals(false, f.hooker.intercept(f.chain()));
        String line = logs.get(0);
        assertTrue(line.contains("java.lang.String(len=15)"));
        assertTrue(line.contains("java.lang.Long"));
        assertTrue(line.contains("java.lang.Boolean(true)"));
        assertTrue(line.contains("(size=2)")); assertTrue(line.contains("[B(size=7)"));
        assertTrue(line.contains("result=java.lang.Boolean(false)"));
        assertFalse(line.contains("PRIVATE CONTENT")); assertFalse(line.contains("987654321"));
    }

    @Test public void brokenDiagnosticsAndLogSinksCannotReplaceTheHookResultOrException() throws Throwable {
        Fixture f = new Fixture(); Object value = new Object();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, () -> true,
                line -> { throw new AssertionError("logging unavailable"); }, () -> { throw new AssertionError("clock unavailable"); });
        wrapped.hook(target()).intercept(chain -> value);
        assertSame(value, f.hooker.intercept(f.chain()));
        Error original = new AssertionError("original");
        wrapped.hook(target()).intercept(chain -> { throw original; });
        try { f.hooker.intercept(f.chain()); fail(); } catch (Throwable actual) { assertSame(original, actual); }
    }

    @Test public void hotHooksAreSampledAndDropTotalsAreReportedOnTheNextWindow() throws Throwable {
        Fixture f = new Fixture(); AtomicLong nanos = new AtomicLong(); List<String> logs = new ArrayList<>();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, () -> true, logs::add, nanos::get);
        wrapped.hook(target()).setId("hot").intercept(XposedInterface.Chain::proceed);
        for (int i = 0; i < 100; i++) f.hooker.intercept(f.chain());
        assertEquals(100, f.proceeded); assertEquals(8, logs.size());
        assertEquals(92, DebugHookTrace.droppedCount(wrapped));
        Exception expected = new Exception("private");
        wrapped.hook(target()).setId("hot").intercept(chain -> { throw expected; });
        for (int i = 0; i < 50; i++) {
            try { f.hooker.intercept(f.chain()); fail(); } catch (Throwable actual) { assertSame(expected, actual); }
        }
        assertEquals("Two bounded error records can pass an exhausted hot-hook quota", 10, logs.size());
        assertEquals(140, DebugHookTrace.droppedCount(wrapped));
        nanos.set(5_000_000_000L);
        wrapped.hook(target()).setId("hot").intercept(XposedInterface.Chain::proceed);
        f.hooker.intercept(f.chain());
        assertEquals(12, logs.size()); assertTrue(logs.get(10).contains("dropped=140 total=140"));
    }

    @Test public void globalBudgetAlsoBoundsManyDifferentHookIds() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, () -> true, logs::add, () -> 0);
        for (int i = 0; i < 210; i++) {
            wrapped.hook(target()).setId("id." + i).intercept(XposedInterface.Chain::proceed);
            f.hooker.intercept(f.chain());
        }
        assertEquals(210, f.proceeded); assertEquals(200, logs.size());
        assertEquals(10, DebugHookTrace.droppedCount(wrapped));
    }

    @Test public void resettingTheWrapperRestoresItsQuotaAndDropStatistics() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, () -> true, logs::add, () -> 0);
        wrapped.hook(target()).setId("reset").intercept(XposedInterface.Chain::proceed);
        for (int i = 0; i < 12; i++) f.hooker.intercept(f.chain());
        assertEquals(8, logs.size()); assertEquals(4, DebugHookTrace.droppedCount(wrapped));
        DebugHookTrace.reset(wrapped);
        assertEquals(0, DebugHookTrace.droppedCount(wrapped));
        f.hooker.intercept(f.chain());
        assertEquals(9, logs.size()); assertEquals(13, f.proceeded);
    }

    @Test public void timingExcludesArgumentAndSnapshotInspection() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>(); AtomicLong nanos = new AtomicLong();
        f.onGetArgs = () -> nanos.addAndGet(1_000_000L);
        DebugHookTrace.wrap(f.framework, () -> true, logs::add, nanos::get).hook(target()).intercept(chain -> {
            nanos.addAndGet(750_000L); return true;
        });
        assertEquals(true, f.hooker.intercept(f.chain()));
        assertTrue(logs.get(0).contains("elapsedUs=750"));
    }

    @Test public void constructorAndClassInitializerHooksUseTheSameWrapper() throws Throwable {
        Fixture f = new Fixture(); List<String> logs = new ArrayList<>();
        XposedInterface wrapped = DebugHookTrace.wrap(f.framework, () -> true, logs::add, () -> 0);
        wrapped.hook(Target.class.getConstructor()).intercept(chain -> null);
        f.hooker.intercept(f.chain());
        wrapped.hookClassInitializer(Target.class).intercept(chain -> null);
        f.hooker.intercept(f.chain());
        assertTrue(logs.get(0).contains("hook=Target.<init>"));
        assertTrue(logs.get(1).contains("hook=Target.<clinit>"));
    }

    public static final class Target { public Target() { } public void action() { } }
    private static Method target() throws Exception { return Target.class.getMethod("action"); }
    private static final class Poison { @Override public String toString() { throw new AssertionError("DTO toString"); } }
    private static final class SizedPoison extends AbstractCollection<Object> {
        @Override public int size() { return 2; }
        @Override public Iterator<Object> iterator() { throw new AssertionError("container iteration"); }
        @Override public String toString() { throw new AssertionError("container toString"); }
    }

    private static final class Fixture {
        XposedInterface.Hooker hooker;
        List<Object> arguments = List.of(); Object result = new Object(); Runnable onGetArgs = () -> { };
        int proceeded, unhooked, interceptBuilderDepth;
        final List<String> setters = new ArrayList<>();
        final XposedInterface.HookHandle handle = (XposedInterface.HookHandle) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class}, (p, method, args) -> {
                    if (method.getName().equals("unhook")) unhooked++;
                    return null;
                });
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "hook": case "hookClassInitializer": return builder(0);
                        case "getFrameworkName": return "fixture";
                        case "getApiVersion": return 102;
                        default: return null;
                    }
                });
        XposedInterface.HookBuilder builder(int depth) {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class}, (p, method, args) -> {
                        switch (method.getName()) {
                            case "setPriority": setters.add("priority:" + args[0]); return builder(depth + 1);
                            case "setExceptionMode": setters.add("exception:" + ((Enum<?>) args[0]).name()); return builder(depth + 1);
                            case "setId": setters.add("id:" + (String) args[0]); return builder(depth + 1);
                            case "intercept": hooker = (XposedInterface.Hooker) args[0]; interceptBuilderDepth = depth; return handle;
                            default: return null;
                        }
                    });
        }
        XposedInterface.Chain chain() {
            return (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (p, method, args) -> {
                        switch (method.getName()) {
                            case "getArgs": onGetArgs.run(); return arguments;
                            case "getThisObject": return null;
                            case "proceed": proceeded++; return result;
                            default: return null;
                        }
                    });
        }
    }
}

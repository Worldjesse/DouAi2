package com.douai.module.feature;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.douai.module.config.FeatureSettings;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Uses Douyin's native, permission-aware avatar follow click for the currently selected card. */
public final class AutoFollowHook implements AutoCloseable {
    private static final int FOLLOW_BUTTON_ID = 2131379757;

    private final Field quickRootField;
    private final Method awemeGetter;
    private final Set<String> attemptedIds = ConcurrentHashMap.newKeySet();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final AtomicLong settingsGeneration = new AtomicLong();
    private final Object settingsLock = new Object();
    private final Consumer<String> log;
    private volatile FeatureSettings settings;
    private volatile boolean closed;

    private AutoFollowHook(Field quickRootField, Method awemeGetter, FeatureSettings settings,
                          Consumer<String> log) {
        this.quickRootField = quickRootField;
        this.awemeGetter = awemeGetter;
        this.settings = settings;
        this.log = log;
    }

    public static AutoFollowHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, FeatureSettings settings,
                                         Consumer<String> log) throws Exception {
        Method selected = resolveMethod(report, "feed_holder_selected", loader);
        HostCandidate rootAnchor = uniqueCandidate(report, "feed_holder_quick_root");
        if (!rootAnchor.getClassDescriptor().equals(descriptor(selected.getDeclaringClass()))) {
            throw new IllegalStateException("Feed holder root anchor does not match selection callback");
        }
        Field root = selected.getDeclaringClass().getField("z");
        if (root.getType() != View.class || selected.getParameterCount() != 1
                || selected.getParameterTypes()[0] != int.class || selected.getReturnType() != void.class) {
            throw new IllegalStateException("Feed holder selection contract changed");
        }
        Method aweme = selected.getDeclaringClass().getMethod("getAweme");
        if (!"com.ss.android.ugc.aweme.feed.model.Aweme".equals(aweme.getReturnType().getName())) {
            throw new IllegalStateException("Feed holder Aweme accessor changed");
        }
        root.setAccessible(true);
        aweme.setAccessible(true);

        AutoFollowHook hook = new AutoFollowHook(root, aweme, settings,
                log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(selected)
                    .setId("DouAi.Automation.AutoFollow")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::afterSelected);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(FeatureSettings settings) {
        FeatureSettings next = settings == null ? FeatureSettings.defaults() : settings;
        synchronized (settingsLock) {
            if (closed) return;
            FeatureSettings previous = this.settings;
            if (previous == null || previous.automationEnabled != next.automationEnabled
                    || previous.autoFollowEnabled != next.autoFollowEnabled) {
                settingsGeneration.incrementAndGet();
            }
            this.settings = next;
            if (!next.automationEnabled || !next.autoFollowEnabled) attemptedIds.clear();
        }
    }

    private Object afterSelected(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        long generation;
        synchronized (settingsLock) {
            FeatureSettings current = settings;
            if (closed || current == null || !current.automationEnabled || !current.autoFollowEnabled) {
                return result;
            }
            generation = settingsGeneration.get();
        }

        Object holder = chain.getThisObject();
        String reservedId = null;
        try {
            Object aweme = awemeGetter.invoke(holder);
            if (aweme == null || Boolean.TRUE.equals(invokeBoolean(aweme, "getIsAdAweme"))) return result;
            Object author = fieldValue(aweme, "author");
            Object status = author == null ? null : invokeNoArg(author, "getFollowStatus");
            if (!(status instanceof Number) || ((Number) status).intValue() != 0) return result;
            String awemeId = readString(aweme, "getAid", "aid");
            if (awemeId == null || !isLoggedIn() || !enabled(generation)
                    || !attemptedIds.add(awemeId)) return result;
            reservedId = awemeId;

            Object root = quickRootField.get(holder);
            if (!(root instanceof View)) return result;
            View rootView = (View) root;
            View button = HostResourceCatalog.findVerifiedView(rootView, FOLLOW_BUTTON_ID);
            if (button == null || button.getVisibility() != View.VISIBLE || !button.isEnabled()) {
                return result;
            }
            Activity activity = activityFrom(button.getContext());
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return result;
            }
            boolean queued = button.post(() -> {
                synchronized (settingsLock) {
                    if (!enabledLocked(generation)) {
                        attemptedIds.remove(awemeId);
                        return;
                    }
                    try {
                        Object currentAweme = awemeGetter.invoke(holder);
                        Object currentRoot = quickRootField.get(holder);
                        Object currentAuthor = fieldValue(currentAweme, "author");
                        Object currentStatus = currentAuthor == null
                                ? null : invokeNoArg(currentAuthor, "getFollowStatus");
                        if (!awemeId.equals(readString(currentAweme, "getAid", "aid"))
                                || currentRoot != rootView
                                || HostResourceCatalog.findVerifiedView(rootView, FOLLOW_BUTTON_ID) != button
                                || !(currentStatus instanceof Number)
                                || ((Number) currentStatus).intValue() != 0
                                || button.getVisibility() != View.VISIBLE
                                || !button.isEnabled()
                                || !button.isAttachedToWindow()
                                || !button.performClick()) {
                            attemptedIds.remove(awemeId);
                        }
                    } catch (Throwable failure) {
                        attemptedIds.remove(awemeId);
                        safeLog("Native follow click failed: " + failure.getMessage());
                    }
                }
            });
            if (queued) reservedId = null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Auto-follow skipped: " + failure.getMessage());
        } finally {
            if (reservedId != null) attemptedIds.remove(reservedId);
        }
        return result;
    }

    private boolean enabled(long generation) {
        synchronized (settingsLock) {
            return enabledLocked(generation);
        }
    }

    private boolean enabledLocked(long generation) {
        FeatureSettings current = settings;
        return !closed && settingsGeneration.get() == generation
                && current != null && current.automationEnabled && current.autoFollowEnabled;
    }

    private static Activity activityFrom(android.content.Context context) {
        android.content.Context current = context;
        while (current instanceof android.content.ContextWrapper && !(current instanceof Activity)) {
            current = ((android.content.ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private boolean isLoggedIn() {
        try {
            Class<?> serviceClass = Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService",
                    false, quickRootField.getDeclaringClass().getClassLoader());
            Object userService = serviceClass.getMethod("userService").invoke(null);
            return Boolean.TRUE.equals(userService.getClass().getMethod("isLogin").invoke(userService));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Boolean invokeBoolean(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Search the host model hierarchy.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object fieldValue(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Search the host model hierarchy.
            }
        }
        return null;
    }

    private static String readString(Object target, String getterName, String fieldName) {
        Object value = invokeNoArg(target, getterName);
        if (!(value instanceof String)) {
            try {
                value = fieldValue(target, fieldName);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        Class<?> owner = Class.forName(className(candidate.getClassDescriptor()), false, loader);
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(candidate.getMethodName())
                    && methodDescriptor(method).equals(candidate.getMethodDescriptor())) return method;
        }
        throw new NoSuchMethodException("Scanned method is unavailable: " + ruleId);
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static String className(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String methodDescriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    @Override
    public void close() {
        synchronized (settingsLock) {
            closed = true;
            settingsGeneration.incrementAndGet();
        }
        unhookAll(null);
        attemptedIds.clear();
    }

    private void unhookAll(Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable unhookFailure) {
                if (failure == null) failure = unhookFailure;
                else failure.addSuppressed(unhookFailure);
            }
        }
        handles.clear();
        if (originalFailure == null && failure != null) safeLog("Auto-follow unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not change the native follow action.
        }
    }
}

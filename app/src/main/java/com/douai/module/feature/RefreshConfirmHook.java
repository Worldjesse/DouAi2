package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Confirms repeated home-tab taps before forwarding the native refresh request. */
public final class RefreshConfirmHook implements AutoCloseable {
    private static final String HOME = "homepage_home";
    private static final String HOME_FEED = "homepage_hot";
    private static final ThreadLocal<Boolean> CONFIRMED_REENTRY = new ThreadLocal<>();

    private final Method selectTabMethod;
    private final ClassLoader hostLoader;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final AtomicBoolean confirmationPending = new AtomicBoolean();
    private final Consumer<String> log;
    private volatile boolean enabled;

    private RefreshConfirmHook(Method selectTabMethod, ClassLoader hostLoader,
                               ExtendedSettings settings, Consumer<String> log) {
        this.selectTabMethod = selectTabMethod;
        this.hostLoader = hostLoader;
        this.enabled = settings != null && settings.confirmRefresh;
        this.log = log;
    }

    public static RefreshConfirmHook install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, ExtendedSettings settings,
                                             Consumer<String> log) throws Exception {
        Method target = resolveMethod(report, "home_tab_select_or_refresh", loader);
        if (target.getParameterCount() != 2
                || target.getParameterTypes()[0] != Boolean.class
                || target.getParameterTypes()[1] != String.class
                || target.getReturnType() != void.class) {
            throw new IllegalStateException("Home tab selection signature changed");
        }
        target.setAccessible(true);
        RefreshConfirmHook hook = new RefreshConfirmHook(target, loader, settings,
                log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(target)
                    .setId("DouAi.HomeTab.RefreshConfirm")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        enabled = settings != null && settings.confirmRefresh;
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(CONFIRMED_REENTRY.get())) {
            CONFIRMED_REENTRY.remove();
            return chain.proceed();
        }
        if (!enabled) return chain.proceed();

        Object[] arguments = chain.getArgs().toArray();
        if (arguments.length != 2 || !(arguments[1] instanceof String)) return chain.proceed();
        String requestedTab = (String) arguments[1];
        if ((!HOME.equals(requestedTab) && !HOME_FEED.equals(requestedTab))
                || !isCurrentHomeTab(requestedTab)) return chain.proceed();

        Activity activity = currentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return chain.proceed();
        if (!confirmationPending.compareAndSet(false, true)) return null;
        activity.runOnUiThread(() -> showConfirmation(activity, chain.getThisObject(), arguments));
        return null;
    }

    private void showConfirmation(Activity activity, Object receiver, Object[] arguments) {
        if (!enabled || activity.isFinishing() || activity.isDestroyed()) {
            confirmationPending.set(false);
            return;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("刷新首页？")
                    .setMessage("再次刷新首页推荐内容？")
                    .setNegativeButton("取消", (dialog, which) -> {
                        confirmationPending.set(false);
                        dialog.dismiss();
                    })
                    .setPositiveButton("刷新", (dialog, which) -> {
                        confirmationPending.set(false);
                        invokeConfirmed(receiver, arguments);
                    })
                    .setOnCancelListener(dialog -> confirmationPending.set(false))
                    .show();
        } catch (Throwable failure) {
            confirmationPending.set(false);
            safeLog("Could not show refresh confirmation: " + failure.getMessage());
        }
    }

    private void invokeConfirmed(Object receiver, Object[] arguments) {
        CONFIRMED_REENTRY.set(true);
        try {
            selectTabMethod.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            safeLog("Confirmed refresh failed: " + (cause == null ? failure.getMessage() : cause.getMessage()));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Confirmed refresh failed: " + failure.getMessage());
        } finally {
            CONFIRMED_REENTRY.remove();
        }
    }

    private boolean isCurrentHomeTab(String requestedTab) {
        try {
            Class<?> homePage = Class.forName("com.ss.android.ugc.aweme.ability.EzHomePage", false, hostLoader);
            Object bottomAbility = homePage.getMethod("getBottomTabAbility").invoke(null);
            if (sameCurrentTab(bottomAbility, requestedTab)) return true;
            if (sameCurrentTab(bottomAbility, HOME)) {
                Object topAbility = homePage.getMethod("getTopTabAbility").invoke(null);
                return sameCurrentTab(topAbility, requestedTab);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not inspect current home tab: " + failure.getMessage());
        }
        return false;
    }

    private static boolean sameCurrentTab(Object ability, String tabId) throws ReflectiveOperationException {
        if (ability == null) return false;
        Method current = ability.getClass().getMethod("currentShowTab");
        Object value = current.invoke(ability);
        return tabId.equals(value);
    }

    private Activity currentActivity() {
        try {
            Class<?> monitor = Class.forName("com.bytedance.ies.ugc.appcontext.AppMonitor", false, hostLoader);
            Field instanceField = monitor.getField("INSTANCE");
            Object instance = instanceField.get(null);
            Object activity = monitor.getMethod("getCurrentActivity").invoke(instance);
            return activity instanceof Activity ? (Activity) activity : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not read current Activity: " + failure.getMessage());
            return null;
        }
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        HostCandidate candidate = result.getCandidates().get(0);
        String className = candidate.getClassDescriptor().substring(1,
                candidate.getClassDescriptor().length() - 1).replace('/', '.');
        Class<?> owner = Class.forName(className, false, loader);
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(candidate.getMethodName())
                    && methodDescriptor(method).equals(candidate.getMethodDescriptor())) return method;
        }
        throw new NoSuchMethodException("Scanned host method is unavailable: " + ruleId);
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
        unhookAll(null);
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
        if (originalFailure == null && failure != null) safeLog("Refresh confirm unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not alter the host tab transition.
        }
    }
}

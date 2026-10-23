package com.douai.module.feature;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Changes only Douyin's reported PackageInfo version, and only while explicitly enabled. */
public final class VersionInfoHook implements AutoCloseable {
    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";

    private final List<XposedInterface.HookHandle> handles;
    private final Consumer<String> log;
    private volatile ExtendedSettings settings;

    private VersionInfoHook(List<XposedInterface.HookHandle> handles,
                            ExtendedSettings settings, Consumer<String> log) {
        this.handles = new ArrayList<>(handles);
        this.settings = settings;
        this.log = log;
    }

    public static VersionInfoHook install(XposedInterface framework, ClassLoader loader,
                                          ExtendedSettings settings, Consumer<String> log)
            throws Exception {
        if (framework == null || loader == null) throw new IllegalArgumentException();
        Class<?> packageManager = Class.forName("android.app.ApplicationPackageManager", false, loader);
        ArrayList<Method> targets = new ArrayList<>();
        for (Method method : packageManager.getDeclaredMethods()) {
            if (!"getPackageInfo".equals(method.getName())
                    || method.getReturnType() != PackageInfo.class
                    || method.getParameterCount() != 2
                    || method.getParameterTypes()[0] != String.class
                    || !isFlagsParameter(method.getParameterTypes()[1])) continue;
            targets.add(method);
        }
        if (targets.isEmpty()) throw new NoSuchMethodException("PackageManager.getPackageInfo overloads not found");

        VersionInfoHook hook = new VersionInfoHook(Collections.emptyList(), settings,
                log == null ? ignored -> { } : log);
        try {
            for (Method method : targets) {
                hook.handles.add(framework.hook(method)
                        .setId("DouAi.VersionInfo." + method.getParameterTypes()[1].getSimpleName())
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(hook::intercept));
            }
            if (hook.handles.isEmpty()) throw new IllegalStateException("No version hooks registered");
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        this.settings = settings;
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        ExtendedSettings current = settings;
        if (!current.spoofVersion || !TARGET_PACKAGE.equals(chain.getArg(0))
                || !(result instanceof PackageInfo)) {
            DouAiLog.event("version", "decision", !current.spoofVersion ? "disabled" : "package_not_applicable", "original_package_info_retained=true");
            return result;
        }
        if (current.fakeVersionName.isEmpty() && current.fakeVersionCode.isEmpty()) {
            DouAiLog.event("version", "decision", "empty_version_override", "original_package_info_retained=true");
            return result;
        }
        DouAiLog.Operation operation = DouAiLog.begin("version", "package_info_read");

        PackageInfo copy;
        Parcel parcel = Parcel.obtain();
        try {
            ((PackageInfo) result).writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            copy = PackageInfo.CREATOR.createFromParcel(parcel);
        } catch (RuntimeException failure) {
            operation.error("copy_package_info", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "package_info_copy_failed", "original_package_info_retained=true");
            safeLog("Could not copy PackageInfo: " + failure.getMessage());
            return result;
        } finally {
            parcel.recycle();
        }
        if (!current.fakeVersionName.isEmpty()) copy.versionName = current.fakeVersionName;
        if (!current.fakeVersionCode.isEmpty()) {
            try {
                long versionCode = Long.parseLong(current.fakeVersionCode);
                copy.versionCode = (int) versionCode;
                if (android.os.Build.VERSION.SDK_INT >= 28) copy.setLongVersionCode(versionCode);
            } catch (NumberFormatException ignored) {
                operation.stage("invalid_code_retained", "configured_code_numeric=false");
                safeLog("Version code setting is not numeric; existing value kept");
            }
        }
        boolean nameChanged = !java.util.Objects.equals(copy.versionName, ((PackageInfo) result).versionName);
        boolean codeChanged = copy.versionCode != ((PackageInfo) result).versionCode;
        operation.finish(nameChanged || codeChanged ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.SKIPPED,
                nameChanged || codeChanged ? "local_package_info_returned" : "no_valid_override_change",
                "version_name_changed=" + nameChanged + ";version_code_changed=" + codeChanged
                        + ";result_scope=returned_package_info;installed_package_unchanged=true");
        return copy;
    }

    private static boolean isFlagsParameter(Class<?> type) {
        return type == int.class || "android.content.pm.PackageManager$PackageInfoFlags".equals(type.getName());
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
        if (originalFailure == null && failure != null) safeLog("Version info unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not affect PackageManager behavior.
        }
    }
}

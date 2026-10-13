package com.douai.module.feature;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Changes only the host's duration-based default, leaving its progress engine and gestures intact. */
final class ShortVideoProgressHook implements AutoCloseable {
    private final BehaviorHookGroup legacy;
    private final BehaviorHookGroup pro;
    private volatile ComponentHideSettings settings;

    private ShortVideoProgressHook(ExtendedSettings settings, Consumer<String> log) {
        this.settings = settings == null ? null : settings.components;
        legacy = new BehaviorHookGroup("short_progress_legacy", log);
        pro = new BehaviorHookGroup("short_progress_pro", log);
    }

    static ShortVideoProgressHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, ExtendedSettings settings, Consumer<String> log) {
        ShortVideoProgressHook hook = new ShortVideoProgressHook(settings, log);
        hook.install(framework, report, loader, "component_short_progress_legacy", hook.legacy, 2);
        hook.install(framework, report, loader, "component_short_progress_pro", hook.pro, 1);
        return hook;
    }

    private void install(XposedInterface framework, HostScanReport report, ClassLoader loader,
                         String rule, BehaviorHookGroup group, int parameters) {
        try {
            Method target = HostTargets.method(report, rule, loader);
            if (target.getReturnType() != boolean.class || target.getParameterCount() != parameters) {
                throw new IllegalStateException("Short progress predicate contract changed");
            }
            group.add(framework, target, chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result)) return result;
                Object duration = parameters == 2 ? chain.getArg(1) : null;
                return overrideDecision(true, chain.getArg(0), duration);
            });
        } catch (Exception failure) { group.fail(failure); }
    }

    void setSettings(ExtendedSettings value) { settings = value == null ? null : value.components; }

    boolean overrideDecision(boolean hidden, Object work, Object suppliedDuration) {
        ComponentHideSettings current = settings;
        if (!hidden || current == null || !current.configured || !current.enabled
                || current.alpha(Component.PROGRESS) <= 0 || work == null) return hidden;
        if (Boolean.TRUE.equals(HostTargets.invoke(work, "getAd"))
                || Boolean.TRUE.equals(HostTargets.invoke(work, "getIsAdAweme"))) return hidden;
        Object type = HostTargets.invoke(work, "getAwemeType");
        if (!(type instanceof Number) || ((Number) type).intValue() != 0) return hidden;
        Object video = HostTargets.invoke(work, "getVideo");
        if (video == null) return hidden;
        Object duration = suppliedDuration;
        if (!(duration instanceof Number)) duration = HostTargets.invoke(video, "getDuration");
        if (!(duration instanceof Number)) duration = HostTargets.read(video, "x");
        if (!(duration instanceof Number)) duration = HostTargets.read(work, "duration");
        if (!(duration instanceof Number)) return hidden;
        long millis = ((Number) duration).longValue();
        return millis <= 0 || millis > 30_000;
    }

    boolean isLegacyInstalled() { return legacy.isActive(); }
    boolean isProInstalled() { return pro.isActive(); }

    @Override public void close() {
        settings = null;
        legacy.close();
        pro.close();
    }
}

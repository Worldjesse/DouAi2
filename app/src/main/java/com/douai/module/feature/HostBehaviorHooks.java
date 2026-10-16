package com.douai.module.feature;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Host 40.6 UI gates and owned view visibility, independent of the host's account state. */
public final class HostBehaviorHooks implements AutoCloseable {
    private final Map<String, BehaviorHookGroup> groups = new LinkedHashMap<>();
    private final Map<View, HiddenView> hidden = new WeakHashMap<>();
    private final Map<View, String> observed = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private volatile boolean closed;

    private HostBehaviorHooks(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static HostBehaviorHooks install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, Context context,
                                             LocalOptions options, Consumer<String> log) {
        HostBehaviorHooks hooks = new HostBehaviorHooks(options, log);
        hooks.suppress(framework, report, loader, "remove_splash_ads", false,
                "adv_ui_splash_gate");
        hooks.suppress(framework, report, loader, "remove_teen_dialog", false,
                "adv_ui_teen_guide");
        hooks.installUpdates(framework, report, loader);
        hooks.suppress(framework, report, loader, "disable_double_tap_like", null,
                "adv_ui_double_tap", "adv_ui_big_digg");
        hooks.suppress(framework, report, loader, "prevent_skip_hidden", null,
                "adv_ui_delete_aweme", "adv_ui_delete_aweme_full_feed");
        hooks.suppress(framework, report, loader, "prevent_insert_video", null,
                "adv_ui_insert_single", "adv_ui_insert_single_fallback",
                "adv_ui_insert_list", "adv_ui_insert_list_fallback");
        hooks.suppress(framework, report, loader, "block_long_press_speed", null,
                "adv_ui_long_press_speed");
        hooks.installBlackEdges(framework, report, loader);
        hooks.installPublishButton(framework, report, loader);
        return hooks;
    }

    public boolean supports(String key) {
        BehaviorHookGroup group = groups.get(key);
        return group != null && group.isActive();
    }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        this.options = options;
        onMain(() -> {
            if (closed) return;
            for (Map.Entry<View, String> entry : new ArrayList<>(observed.entrySet())) {
                updateView(entry.getKey(), entry.getValue());
            }
        });
    }

    private boolean enabled(String key) {
        LocalOptions current = options;
        return !closed && current != null && current.enabled(key) && supports(key);
    }

    private void suppress(XposedInterface framework, HostScanReport report, ClassLoader loader,
                          String key, Object skippedResult, String... rules) {
        BehaviorHookGroup group = newGroup(key);
        try {
            ArrayList<Method> targets = new ArrayList<>();
            for (String rule : rules) targets.add(HostTargets.method(report, rule, loader));
            for (Method target : targets) {
                boolean returnsBoolean = target.getReturnType() == boolean.class;
                if ((returnsBoolean && !(skippedResult instanceof Boolean))
                        || (!returnsBoolean && target.getReturnType() != void.class)) {
                    throw new IllegalStateException("Suppression contract changed: " + target);
                }
                group.add(framework, target, chain -> enabled(key) ? skippedResult : chain.proceed());
            }
            reportInstalled(key, targets.size());
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installUpdates(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "remove_update_dialog";
        BehaviorHookGroup group = newGroup(key);
        try {
            Class<?> notification = HostTargets.type(report, "adv_ui_update_dialog", loader);
            Class<?> bottom = HostTargets.type(report, "adv_ui_update_bottom", loader);
            if (!Dialog.class.isAssignableFrom(notification)) {
                throw new IllegalStateException("Update notification is no longer a Dialog");
            }
            group.add(framework, Dialog.class.getDeclaredMethod("show"), chain -> {
                if (enabled(key) && notification.isInstance(chain.getThisObject())) return null;
                return chain.proceed();
            });
            Class<?> fragment = Class.forName("androidx.fragment.app.DialogFragment", false, loader);
            Class<?> manager = Class.forName("androidx.fragment.app.FragmentManager", false, loader);
            Class<?> transaction = Class.forName("androidx.fragment.app.FragmentTransaction", false, loader);
            if (!fragment.isAssignableFrom(bottom)) {
                throw new IllegalStateException("Update bottom sheet is no longer a DialogFragment");
            }
            for (String name : new String[] { "show", "showNow" }) {
                group.add(framework, fragment.getDeclaredMethod(name, manager, String.class), chain -> {
                    if (enabled(key) && bottom.isInstance(chain.getThisObject())) return null;
                    return chain.proceed();
                });
            }
            group.add(framework, fragment.getDeclaredMethod("show", transaction, String.class), chain -> {
                if (enabled(key) && bottom.isInstance(chain.getThisObject())) return -1;
                return chain.proceed();
            });
            reportInstalled(key, 4);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installBlackEdges(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "hide_video_black_edges";
        BehaviorHookGroup group = newGroup(key);
        try {
            Method method = HostTargets.method(report, "adv_ui_black_spaces", loader);
            Method created = method.getDeclaringClass().getDeclaredMethod("onViewCreated", View.class, Bundle.class);
            Field top = HostTargets.field(method.getDeclaringClass(), "i");
            Field bottom = HostTargets.field(method.getDeclaringClass(), "j");
            if (!View.class.isAssignableFrom(top.getType())
                    || !View.class.isAssignableFrom(bottom.getType())) {
                throw new IllegalStateException("Feed black-space field types changed");
            }
            // A feed-mode switch can rebuild the panel's views before the next space update.
            // Bind after creation as well, when the host has assigned the replacement i/j fields.
            for (Method target : new Method[]{method, created}) {
                group.add(framework, target, chain -> {
                    Object result = chain.proceed();
                    if (closed || !group.isActive()) return result;
                    try {
                        View topSpace = (View) top.get(chain.getThisObject());
                        View bottomSpace = (View) bottom.get(chain.getThisObject());
                        remember(topSpace, key);
                        remember(bottomSpace, key);
                        if (target == created) reportBlackSpacesCreated(key, topSpace, bottomSpace);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        rollbackViews(key);
                        group.fail(failure);
                    }
                    return result;
                });
            }
            reportInstalled(key, 2);
        } catch (Throwable failure) {
            group.fail(failure);
            rollbackViews(key);
        }
    }

    private void reportBlackSpacesCreated(String key, View top, View bottom) {
        try {
            log.accept("Black-space panel created: enabled=" + enabled(key)
                    + " top=" + (top == null ? "null" : top.getVisibility())
                    + " bottom=" + (bottom == null ? "null" : bottom.getVisibility()));
        } catch (Throwable ignored) { }
    }

    private void installPublishButton(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "hide_camera_plus";
        BehaviorHookGroup group = newGroup(key);
        try {
            Method attach = HostTargets.method(report, "adv_ui_publish_attach", loader);
            if (!View.class.isAssignableFrom(attach.getDeclaringClass())) {
                throw new IllegalStateException("Publish button is no longer a View");
            }
            group.add(framework, attach, chain -> {
                Object result = chain.proceed();
                if (!closed && group.isActive()) {
                    try { remember((View) chain.getThisObject(), key); }
                    catch (RuntimeException failure) {
                        rollbackViews(key);
                        group.fail(failure);
                    }
                }
                return result;
            });
            reportInstalled(key, 1);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private BehaviorHookGroup newGroup(String key) {
        BehaviorHookGroup group = new BehaviorHookGroup(key, log);
        groups.put(key, group);
        return group;
    }

    private void reportInstalled(String key, int count) {
        try { log.accept("Advanced feature registered: " + key + " targets=" + count); }
        catch (Throwable ignored) { }
    }

    private void remember(View view, String key) {
        if (view == null) return;
        onMain(() -> {
            if (closed) return;
            observed.put(view, key);
            updateView(view, key);
        });
    }

    private void updateView(View view, String key) {
        if (view == null) return;
        HiddenView state = hidden.get(view);
        if (!enabled(key)) {
            if (state != null) {
                hidden.remove(view);
                state.restore();
            }
            return;
        }
        if (state == null) {
            state = new HiddenView(view, key, "hide_camera_plus".equals(key) ? View.INVISIBLE : View.GONE);
            hidden.put(view, state);
        }
        state.apply();
    }

    private void rollbackViews(String key) {
        onMain(() -> {
            for (Map.Entry<View, String> entry : new ArrayList<>(observed.entrySet())) {
                if (!key.equals(entry.getValue())) continue;
                HiddenView state = hidden.remove(entry.getKey());
                if (state != null) state.restore();
                observed.remove(entry.getKey());
            }
        });
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (BehaviorHookGroup group : groups.values()) group.close();
        onMain(() -> {
            for (HiddenView state : new ArrayList<>(hidden.values())) state.restore();
            hidden.clear();
            observed.clear();
        });
    }

    private final class HiddenView implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> view;
        private final String key;
        private final int applied;
        private int original;
        private ViewTreeObserver observer;

        HiddenView(View view, String key, int applied) {
            this.view = new WeakReference<>(view);
            this.key = key;
            this.applied = applied;
            original = view.getVisibility();
            observer = view.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnPreDrawListener(this);
        }

        void apply() {
            View target = view.get();
            if (target == null) return;
            ViewTreeObserver next = target.getViewTreeObserver();
            if (next != observer && next.isAlive()) {
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                observer = next;
                observer.addOnPreDrawListener(this);
            }
            if (target.getVisibility() != applied) {
                original = target.getVisibility();
                target.setVisibility(applied);
            }
        }

        @Override public boolean onPreDraw() {
            try {
                if (!enabled(key)) {
                    View target = view.get();
                    if (target != null) hidden.remove(target);
                    restore();
                } else apply();
            } catch (RuntimeException failure) {
                BehaviorHookGroup group = groups.get(key);
                if (group != null) group.fail(failure);
                rollbackViews(key);
            }
            return true;
        }

        void restore() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            View target = view.get();
            if (target != null && target.getVisibility() == applied) target.setVisibility(original);
        }
    }
}

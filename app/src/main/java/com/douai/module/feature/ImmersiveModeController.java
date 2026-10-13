package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;

import com.douai.module.config.ExtendedSettings;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reversible status-bar hiding for the target app's resumed Activities. */
public final class ImmersiveModeController implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private static final int HIDE_STATUS_FLAGS = 1284;
    private static final int HIDE_NAVIGATION_FLAGS = 2 | 512 | 4096;
    private static final int FULLSCREEN_FLAG = 1024;

    private final Application application;
    private final Consumer<String> log;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Activity, WindowState> windows = new WeakHashMap<>();
    private volatile boolean hideStatusBar;
    private volatile boolean hideNavigationBar;
    private volatile boolean closed;

    private ImmersiveModeController(Application application, ExtendedSettings settings,
                                    Consumer<String> log) {
        this.application = application;
        this.hideStatusBar = settings != null && settings.removeBlackEdges;
        this.hideNavigationBar = settings != null && settings.hideNavigationBar;
        this.log = log;
    }

    public static ImmersiveModeController install(Context context, ExtendedSettings settings,
                                                  Consumer<String> log) {
        return install(context, settings, log, () -> context instanceof Activity ? (Activity) context : null);
    }

    public static ImmersiveModeController install(Context context, ExtendedSettings settings,
                                                  Consumer<String> log, Supplier<Activity> resumedActivity) {
        if (context == null) throw new IllegalArgumentException("Target context is required");
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) throw new IllegalStateException("Target Application unavailable");
        Application application = (Application) appContext;
        ImmersiveModeController controller = new ImmersiveModeController(application, settings,
                log == null ? ignored -> { } : log);
        application.registerActivityLifecycleCallbacks(controller);
        controller.runOnMain(() -> {
            if (!controller.closed && resumedActivity != null) {
                controller.onActivityResumed(resumedActivity.get());
            }
        });
        return controller;
    }

    public void setSettings(ExtendedSettings settings) {
        if (closed) return;
        hideStatusBar = settings != null && settings.removeBlackEdges;
        hideNavigationBar = settings != null && settings.hideNavigationBar;
        runOnMain(this::refreshWindows);
    }

    private void refreshWindows() {
        if (closed) return;
        synchronized (windows) {
            for (Map.Entry<Activity, WindowState> entry : windows.entrySet()) {
                Activity activity = entry.getKey();
                if (activity == null) continue;
                if (hideStatusBar || hideNavigationBar) apply(activity, entry.getValue());
                else restore(activity, entry.getValue());
            }
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (closed || activity == null || !application.getPackageName().equals(activity.getPackageName())) return;
        runOnMain(() -> attachResumedWindow(activity));
    }

    private void attachResumedWindow(Activity activity) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        synchronized (windows) {
            WindowState state = windows.get(activity);
            if (state == null) {
                state = capture(activity);
                if (state == null) return;
                windows.put(activity, state);
            }
            attachFocusObserver(activity, state);
            if (hideStatusBar || hideNavigationBar) apply(activity, state);
        }
    }

    private void attachFocusObserver(Activity activity, WindowState state) {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (!observer.isAlive() || state.focusObserver.get() == observer) return;
        removeFocusObserver(state);
        WeakReference<Activity> owner = new WeakReference<>(activity);
        state.focusListener = focused -> {
            Activity resumed = owner.get();
            if (!focused || closed || resumed == null || resumed.isFinishing() || resumed.isDestroyed()
                    || state.focusObserver.get() == null || (!hideStatusBar && !hideNavigationBar)) return;
            // The host can clear its startup flags after onResume, before the window gains focus.
            reapplyRequestedBars(resumed);
        };
        state.focusObserver = new WeakReference<>(observer);
        observer.addOnWindowFocusChangeListener(state.focusListener);
    }

    private static void removeFocusObserver(WindowState state) {
        ViewTreeObserver observer = state.focusObserver.get();
        if (observer != null && observer.isAlive() && state.focusListener != null) {
            observer.removeOnWindowFocusChangeListener(state.focusListener);
        }
        state.focusObserver.clear();
        state.focusListener = null;
    }

    private void reapplyRequestedBars(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null || window.getDecorView() == null) return;
            View decor = window.getDecorView();
            int requested = (hideStatusBar ? HIDE_STATUS_FLAGS : 0)
                    | (hideNavigationBar ? HIDE_NAVIGATION_FLAGS : 0);
            int visibility = decor.getSystemUiVisibility();
            if ((visibility & requested) != requested) decor.setSystemUiVisibility(visibility | requested);
            if (hideStatusBar && (window.getAttributes().flags & FULLSCREEN_FLAG) == 0) {
                window.addFlags(FULLSCREEN_FLAG);
            }
        } catch (RuntimeException failure) {
            safeLog("Could not apply system bar visibility: " + failure.getMessage());
        }
    }

    private static WindowState capture(Activity activity) {
        Window window = activity.getWindow();
        if (window == null || window.getDecorView() == null) return null;
        return new WindowState(window.getDecorView().getSystemUiVisibility(),
                window.getAttributes().flags);
    }

    private void apply(Activity activity, WindowState state) {
        try {
            Window window = activity.getWindow();
            if (window == null || window.getDecorView() == null) return;
            int visibility = state.systemUiVisibility;
            if (hideStatusBar) visibility |= HIDE_STATUS_FLAGS;
            if (hideNavigationBar) visibility |= HIDE_NAVIGATION_FLAGS;
            int windowFlags = state.windowFlags;
            if (hideStatusBar) windowFlags |= FULLSCREEN_FLAG;
            window.getDecorView().setSystemUiVisibility(visibility);
            window.setFlags(windowFlags, FULLSCREEN_FLAG);
        } catch (RuntimeException failure) {
            safeLog("Could not apply system bar visibility: " + failure.getMessage());
        }
    }

    private void restore(Activity activity, WindowState state) {
        try {
            Window window = activity.getWindow();
            if (window == null || window.getDecorView() == null) return;
            window.getDecorView().setSystemUiVisibility(state.systemUiVisibility);
            window.setFlags(state.windowFlags, FULLSCREEN_FLAG);
            if ((state.windowFlags & FULLSCREEN_FLAG) == 0) window.clearFlags(FULLSCREEN_FLAG);
        } catch (RuntimeException failure) {
            safeLog("Could not restore system bar visibility: " + failure.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        hideStatusBar = false;
        hideNavigationBar = false;
        application.unregisterActivityLifecycleCallbacks(this);
        runOnMain(() -> {
            synchronized (windows) {
                for (Map.Entry<Activity, WindowState> entry : windows.entrySet()) {
                    removeFocusObserver(entry.getValue());
                    if (entry.getKey() != null) restore(entry.getKey(), entry.getValue());
                }
                windows.clear();
            }
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not affect Activity rendering.
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) {
        synchronized (windows) {
            WindowState state = windows.get(activity);
            if (state != null) removeFocusObserver(state);
        }
    }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        synchronized (windows) {
            WindowState state = windows.remove(activity);
            if (state != null) removeFocusObserver(state);
        }
    }

    private static final class WindowState {
        final int systemUiVisibility;
        final int windowFlags;
        WeakReference<ViewTreeObserver> focusObserver = new WeakReference<>(null);
        ViewTreeObserver.OnWindowFocusChangeListener focusListener;

        WindowState(int systemUiVisibility, int windowFlags) {
            this.systemUiVisibility = systemUiVisibility;
            this.windowFlags = windowFlags;
        }
    }
}

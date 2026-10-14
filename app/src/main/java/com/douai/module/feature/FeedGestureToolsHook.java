package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsRepository;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** Feed-only tools tied to the selected, scan-proven video holder and its native gestures. */
public final class FeedGestureToolsHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    public static final String[] ACTION_IDS = { "none", "menu", "copy_text", "save_media",
            "open_comments", "toggle_components", "speed", "view_finder" };
    public static final String[] ACTION_LABELS = { "无", "快捷菜单", "复制文案", "保存媒体",
            "打开评论", "隐藏/显示组件", "倍速", "视图查找" };
    public static final String[] REGION_IDS = { "top_left", "top_center", "top_right",
            "middle_left", "middle_center", "middle_right", "bottom_left", "bottom_center", "bottom_right" };
    public static final String[] REGION_LABELS = { "左上", "上中", "右上", "左中", "中央", "右中",
            "左下", "下中", "右下" };
    private static final String[] LEGACY_COLUMNS = { "left", "center", "right" };
    private static final long FINDER_GESTURE_TIMEOUT_MILLIS = 3000L;
    private final Map<String, BehaviorHookGroup> groups = new LinkedHashMap<>();
    private final Map<Method, Boolean> pauseGuardMethods = new WeakHashMap<>();
    private final Map<View, Boolean> pendingReleaseDiscard = new WeakHashMap<>();
    private final Application application;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private XposedInterface framework;
    private final java.util.concurrent.atomic.AtomicBoolean holderRecoveryQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Method awemeAccessor;
    private final Field rootField;
    private final Field itemParams;
    private final Field feedContext;
    private final Class<?> touchType;
    private Method playerAccessor;
    private Method speedHolderAccessor;
    private Method getSpeed;
    private Method setSpeed;
    private Method isPlaying;
    private Method managerResume;
    private Method managerPlay;
    private Method managerPause;
    private Method showPlayControl;
    private Method finderTouchDispatch;
    private Field finderLongPressRunnable;
    private Field finderLongPressInterval;
    private Field finderRawX;
    private Field finderRawY;
    private Field finderDiscardCurrentEvent;
    private final ThreadLocal<Boolean> replayingFinderTouch = new ThreadLocal<>();
    private volatile LocalOptions options;
    private volatile boolean closed;
    private WeakReference<Object> selectedHolder = new WeakReference<>(null);
    private WeakReference<Object> pausedHolder = new WeakReference<>(null);
    private WeakReference<View> selectedRoot = new WeakReference<>(null);
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private WeakReference<Activity> pausedActivity = new WeakReference<>(null);
    private WeakReference<Object> ownedSpeedPlayer = new WeakReference<>(null);
    private float originalSpeed;
    private float appliedSpeed;
    private boolean manualSpeedEnabled;
    private GestureSpeedOverride speedOverride;
    private WeakReference<Object> manualSpeedHolder = new WeakReference<>(null);
    private String manualSpeedAid;
    private boolean speedReadbackConfirmed;
    private long speedRevision;
    private Runnable speedReadback;
    private PopupWindow toolbar;
    private AlertDialog actionDialog;
    private ViewFinderOverlay viewFinder;
    private boolean finderOpening;
    private long finderLaunchEpoch;
    private WeakReference<Object> pauseGuardPlayer = new WeakReference<>(null);
    private volatile String pauseGuardAid;
    /** Set before the overlay is attached so a focus-triggered resume cannot race its assignment. */
    private volatile boolean pauseGuardActive;
    private volatile FinderGesture pendingFinderGesture;
    private volatile Function<String, Boolean> pausedState = ignored -> null;
    private ViewTreeObserver rootObserver;
    private final ViewTreeObserver.OnPreDrawListener rootMonitor = () -> {
        if (activeRoot() == null) { dismissToolbar(); dismissDialog(); dismissViewFinder(); restoreOwnedSpeed(); }
        else if (toolbar == null && !finderOpening && (viewFinder == null || viewFinder.isClosed())
                && options != null && options.enabled("toolbar_enabled")) refreshToolbar();
        return true;
    };
    private Consumer<Boolean> componentVisibility = ignored -> { };
    private java.util.function.BooleanSupplier componentsShown = () -> false;
    private int longPressCallbacks;
    private int doubleTapCallbacks;
    private int gestureActions;

    private FeedGestureToolsHook(Application application, LocalOptions options, Consumer<String> log,
                                 Method awemeAccessor, Field rootField, Field itemParams,
                                 Field feedContext, Class<?> touchType) {
        this.application = application;
        this.options = options;
        this.log = log;
        this.awemeAccessor = awemeAccessor;
        this.rootField = rootField;
        this.itemParams = itemParams;
        this.feedContext = feedContext;
        this.touchType = touchType;
    }

    public static FeedGestureToolsHook install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, Context context,
                                                LocalOptions options, Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        FeedGestureToolsHook hook = null;
        try {
            Context app = context.getApplicationContext();
            if (!(app instanceof Application)) throw new IllegalStateException("Host Application unavailable");
            Method selected = HostTargets.method(report, "feed_holder_page_selected", loader);
            Method paused = HostTargets.method(report, "feed_holder_pause", loader);
            Method resume = HostTargets.method(report, "feed_holder_resume", loader);
            Method aweme = HostTargets.method(report, "feed_aweme_accessor", loader);
            if (!selected.getDeclaringClass().equals(paused.getDeclaringClass())
                    || !selected.getDeclaringClass().equals(resume.getDeclaringClass())
                    || !selected.getDeclaringClass().equals(aweme.getDeclaringClass())) {
                throw new IllegalStateException("Selected feed holder relationship changed");
            }
            HostTargets.candidate(report, "feed_holder_main_root");
            Field root = HostTargets.field(selected.getDeclaringClass(), "w");
            Field params = HostTargets.field(selected.getDeclaringClass(), "A2");
            Class<?> paramsType = HostTargets.type(report, "adv_gesture_item_params", loader);
            if (params.getType() != paramsType || !View.class.isAssignableFrom(root.getType())) {
                throw new IllegalStateException("Selected feed holder fields changed");
            }
            Field panel = HostTargets.field(paramsType, "mFeedContext");
            Class<?> touch = HostTargets.type(report, "adv_gesture_touch_layout", loader);
            hook = new FeedGestureToolsHook((Application) app, options, sink, aweme, root, params, panel, touch);
            hook.framework = framework;
            FeedGestureToolsHook installed = hook;
            BehaviorHookGroup lifecycle = hook.newGroup("toolbar_enabled");
            for (Method target : new Method[] { selected, resume }) {
                lifecycle.add(framework, target, chain -> {
                    Object result = chain.proceed();
                    if (!installed.closed) installed.onSelected(chain.getThisObject());
                    return result;
                });
            }
            lifecycle.add(framework, paused, chain -> {
                Object result = chain.proceed();
                Object holder = chain.getThisObject();
                installed.onMain(() -> {
                    if (installed.selectedHolder.get() == holder) {
                        installed.pausedHolder = new WeakReference<>(holder);
                        installed.clearSelected();
                    }
                });
                return result;
            });
            hook.installCurrentHolderRecovery(framework, report, loader);
            hook.installLongPress(framework, report, loader);
            hook.installDoubleTap(framework, report, loader);
            hook.installSpeed(framework, report, loader);
            hook.application.registerActivityLifecycleCallbacks(hook);
            hook.safeLog("Feed gesture tools registered; longPress=" + hook.supports("long_press_action")
                    + ", doubleTap=" + hook.supports("double_tap_action")
                    + ", currentHolderRecovery=" + hook.supports("gesture_current_holder")
                    + ", configured=" + hook.configuredRegions());
            return hook;
        } catch (Throwable failure) {
            if (hook != null) hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setComponentVisibility(Consumer<Boolean> setter,
                                        java.util.function.BooleanSupplier getter) {
        componentVisibility = setter == null ? ignored -> { } : setter;
        componentsShown = getter == null ? () -> false : getter;
    }

    public void setPausedStateProvider(Function<String, Boolean> provider) {
        pausedState = provider == null ? ignored -> null : provider;
    }

    public boolean supports(String key) {
        BehaviorHookGroup group = groups.get(key);
        return group != null && group.isActive();
    }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        this.options = options;
        safeLog("Feed gesture configuration changed: " + configuredRegions());
        onMain(() -> { dismissDialog(); refreshToolbar(); });
    }

    private void installCurrentHolderRecovery(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        BehaviorHookGroup group = newGroup("gesture_current_holder");
        try {
            Method current = HostTargets.method(report, "auto_slide_current_holder", loader);
            if (!feedContext.getType().isAssignableFrom(current.getDeclaringClass())) {
                throw new IllegalStateException("Current feed holder panel relationship changed");
            }
            group.add(framework, current, chain -> {
                Object holder = chain.proceed();
                recoverHolder(holder, chain.getThisObject(), current, group);
                return holder;
            });
            group.add(framework, awemeAccessor, chain -> {
                Object result = chain.proceed();
                recoverHolder(chain.getThisObject(), null, current, group);
                return result;
            });
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void recoverHolder(Object holder, Object expectedPanel, Method current, BehaviorHookGroup group) {
        if (closed || !group.isActive() || !awemeAccessor.getDeclaringClass().isInstance(holder)
                || !holderRecoveryQueued.compareAndSet(false, true)) return;
        onMain(() -> {
            try {
                if (closed || !group.isActive() || selectedHolder.get() != null || pausedHolder.get() == holder
                        || !recoverableRoot(holder)) return;
                Object panel = panel(holder);
                if (panel != null && (expectedPanel == null || panel == expectedPanel)
                        && current.getDeclaringClass().isInstance(panel) && current.invoke(panel) == holder) {
                    onSelected(holder);
                }
            } catch (ReflectiveOperationException | RuntimeException failure) { group.fail(failure); }
            finally { holderRecoveryQueued.set(false); }
        });
    }

    private boolean recoverableRoot(Object holder) throws ReflectiveOperationException {
        Object value = rootField.get(holder);
        if (!(value instanceof View)) return false;
        View root = (View) value;
        Activity owner = activity(root.getContext());
        return root.isAttachedToWindow() && root.isShown() && root.hasWindowFocus()
                && root.getGlobalVisibleRect(new Rect()) && owner != null && pausedActivity.get() != owner
                && !owner.isFinishing() && !owner.isDestroyed();
    }

    static String selectedAction(LocalOptions options, String prefix, float rawX, float rawY,
                                 int left, int top, int width, int height) {
        int region = selectedRegion(rawX, rawY, left, top, width, height);
        return region < 0 ? "none" : regionAction(options, prefix, region);
    }

    static int selectedRegion(float rawX, float rawY, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0 || !Float.isFinite(rawX) || !Float.isFinite(rawY)) return -1;
        float x = rawX - left;
        float y = rawY - top;
        if (x < 0 || x >= width || y < 0 || y >= height) return -1;
        int column = Math.min(2, (int) (x * 3f / width));
        int row = Math.min(2, (int) (y * 3f / height));
        return row * 3 + column;
    }

    public static String regionAction(LocalOptions options, String prefix, int region) {
        if (options == null || region < 0 || region >= REGION_IDS.length) return "none";
        String legacy = options.text(prefix + "_" + LEGACY_COLUMNS[region % 3],
                options.text(prefix + "_action", "none"));
        String action = options.text(prefix + "_" + REGION_IDS[region], legacy);
        for (String id : ACTION_IDS) if (id.equals(action)) return action;
        return "none";
    }

    private void installLongPress(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        BehaviorHookGroup group = newGroup("long_press_action");
        try {
            Method run = HostTargets.method(report, "adv_gesture_long_press", loader);
            Field layout = HostTargets.field(run.getDeclaringClass(), "a");
            if (layout.getType() != touchType || !Runnable.class.isAssignableFrom(run.getDeclaringClass())) {
                throw new IllegalStateException("Feed long-press runnable relationship changed");
            }
            Field discardCurrentEvent = HostTargets.field(touchType, "discardCurrentEvent");
            finderDiscardCurrentEvent = discardCurrentEvent;
            if (discardCurrentEvent.getType() != boolean.class) {
                throw new IllegalStateException("Long-press release-consumption field changed");
            }
            Method touchEvent = touchType.getDeclaredMethod("onTouchEvent", MotionEvent.class);
            Method dispatchEvent = touchType.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            finderTouchDispatch = dispatchEvent;
            finderLongPressRunnable = HostTargets.field(touchType, "longPressRunnable");
            finderLongPressInterval = HostTargets.field(touchType, "mTimeInterval");
            finderRawX = HostTargets.field(touchType, "lastRawX");
            finderRawY = HostTargets.field(touchType, "lastRawY");
            if (!Runnable.class.isAssignableFrom(finderLongPressRunnable.getType())
                    || finderLongPressInterval.getType() != int.class) {
                throw new IllegalStateException("Finder gesture timer contract changed");
            }
            if (touchEvent.getReturnType() != boolean.class) {
                throw new IllegalStateException("Long-press touch callback contract changed");
            }
            group.add(framework, dispatchEvent, XposedInterface.PRIORITY_HIGHEST, this::dispatchFinderTouch);
            group.add(framework, touchEvent, XposedInterface.PRIORITY_HIGHEST, chain -> {
                Object event = chain.getArg(0);
                Object target = chain.getThisObject();
                if (event instanceof MotionEvent && touchType.isInstance(target)) {
                    recordFinderGesture((View) target, (MotionEvent) event);
                    int action = ((MotionEvent) event).getActionMasked();
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        synchronized (pendingReleaseDiscard) {
                            if (pendingReleaseDiscard.remove((View) target) != null) {
                                discardCurrentEvent.setBoolean(target, false);
                                // Do this before the host receives ACTION_UP. Some feed touch
                                // layers start playback from their own UP handler even when the
                                // release-consumption flag is set.
                                finishFinderGesture((View) target, (MotionEvent) event);
                                return false;
                            }
                        }
                    } else if (action == MotionEvent.ACTION_DOWN) {
                        synchronized (pendingReleaseDiscard) {
                            if (pendingReleaseDiscard.remove((View) target) != null) {
                                discardCurrentEvent.setBoolean(target, false);
                            }
                        }
                    }
                }
                try { return chain.proceed(); }
                finally {
                    if (event instanceof MotionEvent && target instanceof View) {
                        finishFinderGesture((View) target, (MotionEvent) event);
                    }
                }
            });
            Field lastRawX = HostTargets.field(touchType, "lastRawX");
            Field lastRawY = HostTargets.field(touchType, "lastRawY");
            if (lastRawX.getType() != float.class || lastRawY.getType() != float.class) {
                throw new IllegalStateException("Feed long-press coordinate fields changed");
            }
            group.add(framework, run, chain -> {
                if (closed || !group.isActive()) return chain.proceed();
                try {
                    longPressCallbacks++;
                    View touch = (View) layout.get(chain.getThisObject());
                    View root = activeRoot();
                    if (root == null || !touchType.isInstance(touch) || !belongsToRoot(touch, root)
                            || !touch.isShown()) {
                        logGestureGate("long_press", longPressCallbacks, root,
                                "callback_layout_inactive_or_foreign");
                        return chain.proceed();
                    }
                    float rawX = lastRawX.getFloat(touch);
                    float rawY = lastRawY.getFloat(touch);
                    int region = region(touch, rawX, rawY);
                    String action = region < 0 ? "none" : regionAction(options, "long_press", region);
                    if ("none".equals(action)) {
                        if (options != null && options.interceptNativeLongPress()) {
                            // Consume the host runnable and its pending UP so the native gesture
                            // cannot turn into a playback tap after the long press is intercepted.
                            discardCurrentEvent.setBoolean(touch, true);
                            synchronized (pendingReleaseDiscard) {
                                pendingReleaseDiscard.put(touch, Boolean.TRUE);
                            }
                            gestureActions++;
                            logGestureAction("long_press", "intercept_native", region);
                            safeLog("Feed native long-press intercepted: region=" + regionLabel(region));
                            return null;
                        }
                        if (longPressCallbacks <= 12) safeLog("Feed gesture long_press delegated: region="
                                + regionLabel(region) + ", reason=unconfigured");
                        return chain.proceed();
                    }
                    // Replace the native runnable before its speed/menu interceptors execute.
                    gestureActions++;
                    logGestureAction("long_press", action, region);
                    if ("view_finder".equals(action)) {
                        FinderGesture gesture = pendingFinderGesture;
                        if (gesture != null && gesture.matches(touch, selectedHolder.get())) {
                            gesture.confirmed = true;
                        }
                        discardCurrentEvent.setBoolean(touch, true);
                        synchronized (pendingReleaseDiscard) {
                            pendingReleaseDiscard.put(touch, Boolean.TRUE);
                        }
                        safeLog("View finder consumed pending long-press release");
                    }
                    dispatch(action, selectedHolder.get(), root);
                    return null;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    group.fail(failure);
                    return chain.proceed();
                }
            });
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installDoubleTap(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        BehaviorHookGroup group = newGroup("double_tap_action");
        try {
            Method target = HostTargets.method(report, "adv_ui_double_tap", loader);
            group.add(framework, target, XposedInterface.PRIORITY_HIGHEST, chain -> {
                if (closed || !group.isActive()) return chain.proceed();
                try {
                    doubleTapCallbacks++;
                    Object holder = selectedHolder.get();
                    View root = activeRoot();
                    if (holder == null || root == null || panel(holder) != chain.getThisObject()) {
                        logGestureGate("double_tap", doubleTapCallbacks, root, "inactive_or_foreign_panel");
                        return chain.proceed();
                    }
                    Object event = chain.getArg(0);
                    if (!(event instanceof MotionEvent)) return chain.proceed();
                    View touch = uniqueTouch(root);
                    if (touch == null || !touch.isShown()) return chain.proceed();
                    MotionEvent tap = (MotionEvent) event;
                    int region = region(touch, tap.getRawX(), tap.getRawY());
                    String action = region < 0 ? "none" : regionAction(options, "double_tap", region);
                    if ("none".equals(action)) return chain.proceed();
                    gestureActions++;
                    logGestureAction("double_tap", action, region);
                    dispatch(action, holder, root);
                    return null;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    group.fail(failure);
                    return chain.proceed();
                }
            });
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installSpeed(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        try {
            playerAccessor = HostTargets.method(report, "adv_gesture_panel_player", loader);
            speedHolderAccessor = HostTargets.method(report, "auto_slide_current_holder", loader);
            setSpeed = HostTargets.method(report, "adv_gesture_player_set_speed", loader);
            getSpeed = HostTargets.method(report, "adv_gesture_player_get_speed", loader);
            isPlaying = HostTargets.method(report, "adv_gesture_player_is_playing", loader);
            managerResume = HostTargets.method(report, "adv_gesture_player_resume", loader);
            managerPlay = HostTargets.method(report, "adv_gesture_player_play", loader);
            managerPause = HostTargets.method(report, "adv_gesture_player_pause", loader);
            Class<?> manager = setSpeed.getDeclaringClass();
            Class<?> info = getSpeed.getDeclaringClass();
            if (!manager.isInterface() || !info.isInterface() || !info.isAssignableFrom(manager)
                    || !manager.isAssignableFrom(playerAccessor.getReturnType())
                    || speedHolderAccessor.getDeclaringClass() != playerAccessor.getDeclaringClass()
                    || setSpeed.getReturnType() != void.class || setSpeed.getParameterCount() != 1
                    || setSpeed.getParameterTypes()[0] != float.class
                    || getSpeed.getReturnType() != float.class || getSpeed.getParameterCount() != 0
                    || isPlaying.getDeclaringClass() != info || isPlaying.getReturnType() != boolean.class
                    || isPlaying.getParameterCount() != 0 || managerResume.getReturnType() != void.class
                    || managerResume.getParameterCount() != 0
                    || managerResume.getDeclaringClass() != managerPlay.getDeclaringClass()
                    || managerPlay.getReturnType() != String.class
                    || managerPause.getDeclaringClass() != manager || managerPause.getParameterCount() != 0
                    || managerPause.getReturnType() != void.class) {
                throw new IllegalStateException("Gesture speed relationship changed");
            }
            installFinderPauseGuard(framework, report, loader);
            safeLog("Gesture speed interfaces ready: " + manager.getName() + ", " + info.getName());
        } catch (Exception failure) {
            playerAccessor = null; speedHolderAccessor = null; setSpeed = null; getSpeed = null;
            isPlaying = null; managerResume = null; managerPlay = null; managerPause = null;
            safeLog("Gesture speed unavailable: " + failure.getMessage());
        }
    }

    private void installFinderPauseGuard(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        // The scan contract points at IPlayerManager, whose methods are abstract on
        // current host builds. LSPosed rejects those methods, so install the guard
        // lazily on the concrete player implementation used by the selected holder.
        BehaviorHookGroup guard = newGroup("finder_pause_guard");
        try {
            Method hide = HostTargets.method(report, "adv_gesture_panel_hide_play_control", loader);
            showPlayControl = HostTargets.method(report, "adv_gesture_panel_show_play_control", loader);
            if (hide.getDeclaringClass() != playerAccessor.getDeclaringClass()
                    || showPlayControl.getDeclaringClass() != hide.getDeclaringClass()
                    || hide.getReturnType() != void.class || hide.getParameterCount() != 0
                    || showPlayControl.getReturnType() != void.class || showPlayControl.getParameterCount() != 0) {
                throw new IllegalStateException("Pause play-control contract changed");
            }
            guard.add(framework, hide, XposedInterface.PRIORITY_HIGHEST, chain -> {
                Object holder = selectedHolder.get();
                if (holder != null && panel(holder) == chain.getThisObject()
                        && isGuardedPausedPlayer(currentPlayer())) {
                    deferFinderPlayback(chain, hide);
                    safeLog("View finder preserved the paused play control");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable failure) {
            showPlayControl = null;
            safeLog("Could not protect paused play control: " + failure.getMessage());
        }
        try {
            Method toggle = HostTargets.method(report, "adv_gesture_panel_toggle_play", loader);
            if (toggle.getDeclaringClass() != playerAccessor.getDeclaringClass()
                    || toggle.getReturnType() != void.class || toggle.getParameterCount() != 3) {
                throw new IllegalStateException("Native playback toggle contract changed");
            }
            guard.add(framework, toggle, XposedInterface.PRIORITY_HIGHEST, chain -> {
                Object holder = selectedHolder.get();
                String aid = currentAid();
                if (holder != null && panel(holder) == chain.getThisObject()
                        && aid != null && aid.equals(readAid(chain.getArg(0)))
                        && isGuardedPausedPlayer(currentPlayer())) {
                    deferFinderPlayback(chain, toggle);
                    safeLog("View finder suppressed native panel playback toggle");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable failure) {
            safeLog("Could not hook native finder playback toggle: " + failure.getMessage());
        }
    }

    private void ensureFinderPauseGuard(Object player) {
        if (player == null || framework == null || managerResume == null || managerPlay == null) return;
        BehaviorHookGroup guard = groups.get("finder_pause_guard");
        if (guard == null) return;
        Method resume = concreteImplementation(player.getClass(), managerResume);
        Method play = concreteImplementation(player.getClass(), managerPlay);
        if (resume == null && play == null) {
            safeLog("View finder pause guard unavailable: concrete player methods not found");
            return;
        }
        for (Method target : new Method[] { resume, play }) {
            if (target == null) continue;
            synchronized (pauseGuardMethods) {
                if (pauseGuardMethods.containsKey(target)) continue;
                pauseGuardMethods.put(target, Boolean.TRUE);
            }
            try {
                if (target == resume) {
                    guard.add(framework, target, XposedInterface.PRIORITY_HIGHEST, chain -> {
                        if (isGuardedPausedPlayer(chain.getThisObject())) {
                            deferFinderPlayback(chain, target);
                            safeLog("View finder suppressed resume on previously paused player");
                            return null;
                        }
                        return chain.proceed();
                    });
                } else {
                    guard.add(framework, target, XposedInterface.PRIORITY_HIGHEST, chain -> {
                        if (isGuardedPausedPlayer(chain.getThisObject())) {
                            deferFinderPlayback(chain, target);
                            safeLog("View finder suppressed play on previously paused player");
                            return null;
                        }
                        return chain.proceed();
                    });
                }
            } catch (Throwable failure) {
                synchronized (pauseGuardMethods) { pauseGuardMethods.remove(target); }
                safeLog("Could not hook concrete view finder player method: " + failure.getMessage());
            }
        }
        if (guard.isActive()) safeLog("View finder pause guard registered on " + player.getClass().getName());
    }

    private static Method concreteImplementation(Class<?> type, Method contract) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Method candidate = current.getDeclaredMethod(contract.getName(), contract.getParameterTypes());
                int modifiers = candidate.getModifiers();
                if (!Modifier.isAbstract(modifiers) && !Modifier.isNative(modifiers)) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            } catch (NoSuchMethodException ignored) { }
            catch (RuntimeException ignored) { return null; }
        }
        try {
            Method candidate = type.getMethod(contract.getName(), contract.getParameterTypes());
            return Modifier.isAbstract(candidate.getModifiers()) ? null : candidate;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private void recordFinderGesture(View touch, MotionEvent event) {
        if (closed || event == null || Boolean.TRUE.equals(replayingFinderTouch.get())) return;
        FinderGesture pending = pendingFinderGesture;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN) {
            if (action == MotionEvent.ACTION_CANCEL) return;
            if (pending != null && pending.touch == touch && !pending.confirmed
                    && (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN
                    || (action == MotionEvent.ACTION_MOVE
                    && Math.hypot(event.getRawX() - pending.x, event.getRawY() - pending.y)
                    > ViewConfiguration.get(touch.getContext()).getScaledTouchSlop()))) {
                releaseFinderGesture(true);
            }
            return;
        }
        if (pending != null && pending.touch == touch && pending.downTime == event.getDownTime()) return;
        releaseFinderGesture(false);
        View root = activeRoot();
        Object holder = selectedHolder.get();
        if (root == null || holder == null || !belongsToRoot(touch, root) || !touch.isShown()
                || !"view_finder".equals(regionAction(options, "long_press",
                region(touch, event.getRawX(), event.getRawY())))) return;
        try {
            String aid = readAid(awemeAccessor.invoke(holder));
            Object feedPanel = panel(holder);
            Object player = playerAccessor == null || feedPanel == null ? null : playerAccessor.invoke(feedPanel);
            boolean paused = Boolean.TRUE.equals(aid == null ? null : pausedState.apply(aid));
            if (player != null && isPlaying != null) paused |= !((Boolean) isPlaying.invoke(player));
            safeLog("View finder touch snapshot: paused=" + paused + ", player="
                    + (player == null ? "unavailable" : player.getClass().getName()));
            if (!paused || aid == null || player == null) return;
            FinderGesture gesture = new FinderGesture(touch, holder, player, aid, event);
            pendingFinderGesture = gesture;
            gesture.timeout = () -> {
                if (pendingFinderGesture == gesture && !gesture.confirmed) releaseFinderGesture(false);
            };
            main.postDelayed(gesture.timeout, FINDER_GESTURE_TIMEOUT_MILLIS);
            ensureFinderPauseGuard(player);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not capture view finder touch state: " + failure.getMessage());
        }
    }

    private void deferFinderPlayback(XposedInterface.Chain chain, Method target) {
        FinderGesture pending = pendingFinderGesture;
        if (pending != null && !pending.confirmed) {
            synchronized (pending) {
                pending.deferredMethod = target;
                pending.deferredArguments = chain.getArgs().toArray();
                pending.deferredOwner = chain.getThisObject();
            }
        }
    }

    private void finishFinderGesture(View touch, MotionEvent event) {
        FinderGesture pending = pendingFinderGesture;
        if (pending == null || pending.touch != touch || event == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            releaseFinderGesture(action == MotionEvent.ACTION_UP && !pending.confirmed);
        }
    }

    private void releaseFinderGesture(boolean replay) {
        FinderGesture pending = pendingFinderGesture;
        pendingFinderGesture = null;
        if (pending != null && pending.timeout != null) main.removeCallbacks(pending.timeout);
        if (pending != null && pending.longPress != null) main.removeCallbacks(pending.longPress);
        if (pending != null && pending.downEvent != null) {
            pending.downEvent.recycle();
            pending.downEvent = null;
        }
        if (pending == null || !replay || pending.confirmed || pending.holder != selectedHolder.get()
                || !pending.aid.equals(currentAid()) || pending.player != currentPlayer()) return;
        synchronized (pending) {
            if (pending.deferredMethod == null) return;
            try { pending.deferredMethod.invoke(pending.deferredOwner, pending.deferredArguments); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                safeLog("Could not restore short-tap playback: " + failure.getMessage());
            }
        }
    }

    private Object dispatchFinderTouch(XposedInterface.Chain chain) throws Throwable {
        if (closed || Boolean.TRUE.equals(replayingFinderTouch.get())) return chain.proceed();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        View touch = (View) chain.getThisObject();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            synchronized (pendingReleaseDiscard) {
                if (pendingReleaseDiscard.remove(touch) != null) {
                    finderDiscardCurrentEvent.setBoolean(touch, false);
                    releaseFinderGesture(false);
                    return true;
                }
            }
        }
        FinderGesture gesture = pendingFinderGesture;
        if (gesture != null && gesture.touch == touch && gesture.downEvent != null) {
            if (action == MotionEvent.ACTION_CANCEL) {
                releaseFinderGesture(false);
                return true;
            }
            boolean moved = action == MotionEvent.ACTION_MOVE
                    && Math.hypot(event.getRawX() - gesture.x, event.getRawY() - gesture.y)
                    > ViewConfiguration.get(touch.getContext()).getScaledTouchSlop();
            if (action == MotionEvent.ACTION_UP || moved || event.getPointerCount() > 1) {
                MotionEvent down = gesture.downEvent;
                gesture.downEvent = null;
                releaseFinderGesture(false);
                replayingFinderTouch.set(true);
                try {
                    finderTouchDispatch.invoke(touch, down);
                    return chain.proceed();
                } finally {
                    down.recycle();
                    replayingFinderTouch.remove();
                }
            }
            return true;
        }
        recordFinderGesture(touch, event);
        gesture = pendingFinderGesture;
        if (action == MotionEvent.ACTION_DOWN && gesture != null && gesture.touch == touch) {
            Runnable nativeLongPress = (Runnable) finderLongPressRunnable.get(touch);
            if (nativeLongPress != null) {
                finderRawX.setFloat(touch, event.getRawX());
                finderRawY.setFloat(touch, event.getRawY());
                gesture.downEvent = MotionEvent.obtain(event);
                FinderGesture expected = gesture;
                gesture.longPress = () -> {
                    if (pendingFinderGesture == expected && expected.holder == selectedHolder.get()
                            && expected.aid.equals(currentAid()) && expected.player == currentPlayer()
                            && activeRoot() != null && touch.isShown()
                            && "view_finder".equals(regionAction(options, "long_press",
                            region(touch, expected.x, expected.y)))) {
                        nativeLongPress.run();
                    } else if (pendingFinderGesture == expected) releaseFinderGesture(false);
                };
                main.postDelayed(gesture.longPress, Math.max(1, finderLongPressInterval.getInt(touch)));
                safeLog("View finder held paused touch before host UI listeners");
                return true;
            }
        }
        try { return chain.proceed(); }
        finally { finishFinderGesture(touch, event); }
    }

    private String currentAid() {
        Object holder = selectedHolder.get();
        if (holder == null) return null;
        try { return readAid(awemeAccessor.invoke(holder)); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private Object currentPlayer() {
        Object holder = selectedHolder.get();
        if (holder == null || playerAccessor == null) return null;
        try {
            Object feedPanel = panel(holder);
            return feedPanel == null ? null : playerAccessor.invoke(feedPanel);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static final class FinderGesture {
        final View touch;
        final Object holder;
        final Object player;
        final String aid;
        final long downTime;
        final float x;
        final float y;
        volatile boolean confirmed;
        Method deferredMethod;
        Object[] deferredArguments;
        Object deferredOwner;
        Runnable timeout;
        Runnable longPress;
        MotionEvent downEvent;

        FinderGesture(View touch, Object holder, Object player, String aid, MotionEvent event) {
            this.touch = touch;
            this.holder = holder;
            this.player = player;
            this.aid = aid;
            downTime = event.getDownTime();
            x = event.getRawX();
            y = event.getRawY();
        }

        boolean matches(View touch, Object holder) { return this.touch == touch && this.holder == holder; }
    }

    private boolean isGuardedPausedPlayer(Object player) {
        if (closed || player == null) return false;
        FinderGesture pending = pendingFinderGesture;
        if (pending != null && pending.player == player && pending.holder == selectedHolder.get()
                && pending.aid.equals(currentAid()) && pending.player == currentPlayer()) return true;
        if ((!pauseGuardActive && (viewFinder == null || viewFinder.isClosed())) || player == null) return false;
        String aid = pauseGuardAid;
        Object holder = selectedHolder.get();
        if (aid == null || holder == null) return false;
        try {
            if (!aid.equals(readAid(awemeAccessor.invoke(holder)))) return false;
            Object feedPanel = panel(holder);
            return feedPanel != null && playerAccessor.invoke(feedPanel) == player;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private int region(View root, float rawX, float rawY) {
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        return selectedRegion(rawX, rawY, location[0], location[1], root.getWidth(), root.getHeight());
    }

    private static String regionLabel(int region) {
        return region < 0 || region >= REGION_LABELS.length ? "outside" : REGION_LABELS[region];
    }

    private static boolean belongsToRoot(View candidate, View root) {
        for (View current = candidate; current != null; ) {
            if (current == root) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private Object panel(Object holder) throws ReflectiveOperationException {
        Object params = itemParams.get(holder);
        return params == null ? null : feedContext.get(params);
    }

    private void onSelected(Object holder) {
        onMain(() -> {
            if (closed) return;
            try {
                Object value = rootField.get(holder);
                if (!(value instanceof View)) return;
                View root = (View) value;
                Activity activity = activity(root.getContext());
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                pausedHolder.clear();
                recoverForeground(root, activity);
                if (selectedHolder.get() == holder && selectedRoot.get() == root) return;
                if (selectedHolder.get() != holder) restoreOwnedSpeed();
                selectedHolder = new WeakReference<>(holder);
                selectedRoot = new WeakReference<>(root);
                safeLog("Feed gesture holder selected: foreground=" + (resumed.get() == activity)
                        + ", attached=" + root.isAttachedToWindow()
                        + ", touchMatches=" + touchCount(root));
                ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer != rootObserver && observer.isAlive()) {
                    detachRootMonitor();
                    rootObserver = observer;
                    observer.addOnPreDrawListener(rootMonitor);
                }
                refreshToolbar();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                BehaviorHookGroup lifecycle = groups.get("toolbar_enabled");
                if (lifecycle != null) lifecycle.fail(failure);
                clearSelected();
            }
        });
    }

    private View activeRoot() {
        View root = selectedRoot.get();
        if (root != null) recoverForeground(root, activity(root.getContext()));
        Activity foreground = resumed.get();
        return !closed && foreground != null && root != null && root.isAttachedToWindow()
                && root.isShown() && activity(root.getContext()) == foreground
                && !foreground.isFinishing() && !foreground.isDestroyed() ? root : null;
    }

    private void recoverForeground(View root, Activity owner) {
        // Scanning may register callbacks after the host Activity already resumed.
        if (resumed.get() == null && owner != null && pausedActivity.get() != owner
                && root.isAttachedToWindow() && root.isShown() && root.hasWindowFocus()
                && !owner.isFinishing() && !owner.isDestroyed()) {
            resumed = new WeakReference<>(owner);
            safeLog("Feed gesture foreground recovered from focused host root");
        }
    }

    private int touchCount(View root) {
        List<View> matches = new ArrayList<>();
        collectTouch(root, matches);
        return matches.size();
    }

    private View uniqueTouch(View root) {
        List<View> matches = new ArrayList<>();
        collectTouch(root, matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void collectTouch(View view, List<View> matches) {
        if (!view.isShown()) return;
        if (touchType.isInstance(view)) { matches.add(view); return; }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTouch(group.getChildAt(i), matches);
        }
    }

    private void dispatch(String action, Object holder, View root) {
        onMain(() -> {
            if (closed || holder == null || holder != selectedHolder.get() || root != activeRoot()) return;
            try {
                Object aweme = awemeAccessor.invoke(holder);
                if (aweme == null) { toast(root.getContext(), "当前视频数据不可用"); return; }
                switch (action) {
                    case "menu": showMenu(root, holder); break;
                    case "copy_text": copyText(root.getContext(), aweme); break;
                    case "save_media": saveMedia(root.getContext(), aweme); break;
                    case "open_comments": openComments(root); break;
                    case "toggle_components": componentVisibility.accept(!componentsShown.getAsBoolean()); break;
                    case "speed": applyConfiguredSpeed(root, holder); break;
                    case "view_finder": showViewFinder(root, holder); break;
                    default: break;
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                if ("view_finder".equals(action)) dismissViewFinder();
                safeLog("Feed tool action failed: " + failure.getMessage());
                toast(root.getContext(), "工具操作失败");
            }
        });
    }

    private void showMenu(View root, Object holder) {
        dismissDialog();
        String[] labels = java.util.Arrays.copyOfRange(ACTION_LABELS, 2, ACTION_LABELS.length);
        actionDialog = new AlertDialog.Builder(root.getContext()).setTitle("DouAi")
                .setItems(labels, (dialog, index) -> dispatch(ACTION_IDS[index + 2], holder, root))
                .setNegativeButton("取消", null).create();
        actionDialog.show();
    }

    private void showViewFinder(View root, Object holder) throws ReflectiveOperationException {
        dismissDialog();
        dismissToolbar();
        Activity owner = activity(root.getContext());
        if (owner == null || owner != resumed.get()) {
            toast(root.getContext(), "当前页面暂不可查找视图");
            return;
        }
        String aid = readAid(awemeAccessor.invoke(holder));
        FinderGesture gesture = pendingFinderGesture;
        boolean pausedOnDown = gesture != null && gesture.holder == holder && gesture.aid.equals(aid);
        dismissViewFinder();
        Object player = null;
        boolean paused = pausedOnDown;
        if (playerAccessor != null) {
            Object feedPanel = panel(holder);
            if (feedPanel != null && playerAccessor.getDeclaringClass().isInstance(feedPanel)) {
                player = playerAccessor.invoke(feedPanel);
                if (player != null && isPlaying != null && isPlaying.getDeclaringClass().isInstance(player)) {
                    paused = paused || !((Boolean) isPlaying.invoke(player));
                }
            }
        }
        try {
            Boolean hostPaused = aid == null ? null : pausedState.apply(aid);
            paused = paused || Boolean.TRUE.equals(hostPaused);
        } catch (RuntimeException failure) {
            safeLog("Could not read current paused state: " + failure.getMessage());
        }
        pauseGuardPlayer = new WeakReference<>(paused ? player : null);
        pauseGuardAid = paused ? aid : null;
        if (paused) ensureFinderPauseGuard(player);
        pauseGuardActive = paused;
        boolean restoredPause = false;
        if (paused && player != null && managerPause != null && isPlaying != null
                && Boolean.TRUE.equals(isPlaying.invoke(player))) {
            managerPause.invoke(player);
            if (showPlayControl != null) showPlayControl.invoke(panel(holder));
            restoredPause = true;
            safeLog("View finder restored the pre-touch paused state");
        }
        safeLog("View finder launch state: paused=" + paused + ", player="
                + (player == null ? "unavailable" : player.getClass().getName()));
        if (restoredPause) {
            finderOpening = true;
            long epoch = finderLaunchEpoch;
            Object expectedPlayer = player;
            root.postDelayed(() -> root.postOnAnimation(() -> root.postOnAnimation(() -> {
                if (closed || epoch != finderLaunchEpoch) return;
                if (holder != selectedHolder.get() || !java.util.Objects.equals(aid, currentAid())
                        || expectedPlayer != currentPlayer() || root != activeRoot() || owner != resumed.get()) {
                    dismissViewFinder();
                    return;
                }
                attachViewFinder(owner, root);
            })), 120);
        } else attachViewFinder(owner, root);
    }

    private void attachViewFinder(Activity owner, View root) {
        finderOpening = false;
        ViewFinderOverlay.Listener listener = new ViewFinderOverlay.Listener() {
            @Override public boolean onConfirm(CustomViewLocator locator) {
                return persistCustomView(locator);
            }
            @Override public List<CustomViewLocator> existingCustomViews() {
                try {
                    return ComponentHideSettings.readFrom(new HostSettingsStore(application)).customViews;
                } catch (RuntimeException failure) {
                    safeLog("Could not read custom component rules: " + failure.getMessage());
                    return Collections.emptyList();
                }
            }
            @Override public void onDismiss() {
                pauseGuardActive = false;
                pauseGuardPlayer.clear();
                pauseGuardAid = null;
            }
        };
        viewFinder = ViewFinderOverlay.show(owner, owner.getWindow().getDecorView(), root, listener);
        if (viewFinder == null) {
            pauseGuardActive = false;
            pauseGuardPlayer.clear();
            pauseGuardAid = null;
            toast(root.getContext(), "视图查找器打开失败");
        }
        else safeLog("View finder opened: " + viewFinder.diagnosticSummary());
    }

    private boolean persistCustomView(CustomViewLocator locator) {
        try {
            HostSettingsStore store = new HostSettingsStore(application);
            ComponentHideSettings.initialize(store);
            List<CustomViewLocator> existing = ComponentHideSettings.readFrom(store).customViews;
            ArrayList<CustomViewLocator> updated = new ArrayList<>(existing);
            for (CustomViewLocator item : existing) {
                if (item.identityKey().equals(locator.identityKey())) {
                    return application.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,
                            Context.MODE_PRIVATE).edit().putBoolean(ComponentHideSettings.ENABLED, true).commit();
                }
            }
            if (updated.size() >= 128) return false;
            updated.add(locator);
            boolean saved = application.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,
                    Context.MODE_PRIVATE).edit()
                    .putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(updated))
                    .putBoolean(ComponentHideSettings.ENABLED, true).commit();
            if (!saved) return false;
            safeLog("Custom view hide saved: " + locator.label + "; class=" + locator.viewClassName);
            return true;
        } catch (RuntimeException failure) {
            safeLog("Could not save custom view hide: " + failure.getMessage());
            return false;
        }
    }

    private void dismissViewFinder() {
        finderLaunchEpoch++;
        finderOpening = false;
        ViewFinderOverlay current = viewFinder;
        viewFinder = null;
        pauseGuardActive = false;
        pauseGuardPlayer.clear();
        pauseGuardAid = null;
        releaseFinderGesture(false);
        if (current != null) current.close();
    }

    private void copyText(Context context, Object aweme) {
        Object value = DownloadActionHook.jsonValue(aweme, "desc");
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            toast(context, "当前视频没有文案"); return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) { toast(context, "剪贴板不可用"); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("抖音文案", (String) value));
        toast(context, "文案已复制");
    }

    private void saveMedia(Context context, Object aweme) {
        LocalOptions current = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        if (current.downloadSaveTree().isEmpty() && Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            toast(context, "请先授予抖音存储权限"); return;
        }
        if (new com.douai.module.config.ThirdPartyDownloadSettings(current).thirdParty) {
            ThirdPartyDownload.start(context, aweme, current, this::safeLog);
            return;
        }
        List<DownloadActionHook.MediaAsset> assets = DownloadActionHook.mediaAssets(aweme,
                current.enabled("remove_watermark", true), current);
        if (assets.isEmpty()) { toast(context, "当前视频没有可保存的媒体地址"); return; }
        for (DownloadActionHook.MediaAsset asset : assets) {
            boolean queued = asset.image
                    ? MediaStoreDownloader.downloadImage(context, asset.url, asset.name, current.downloadSaveTree(), this::safeLog)
                    : MediaStoreDownloader.download(context, asset.url, asset.name, false, current.downloadSaveTree(), this::safeLog);
            if (!queued) toast(context, "下载地址无效");
        }
    }

    @SuppressLint("ResourceType")
    private void openComments(View root) {
        View container = HostResourceCatalog.findVerifiedView(root, 2131371412);
        if (container == null) container = HostResourceCatalog.findVerifiedView(root, 2131371297);
        List<View> candidates = new ArrayList<>();
        collectClicks(container, candidates);
        if (candidates.size() != 1) { toast(root.getContext(), "评论入口未唯一定位"); return; }
        candidates.get(0).performClick();
    }

    private static void collectClicks(View view, List<View> candidates) {
        if (view == null || !view.isEnabled()) return;
        if (view.isClickable()) { candidates.add(view); return; }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectClicks(group.getChildAt(i), candidates);
        }
    }

    private void applyConfiguredSpeed(View root, Object holder) throws ReflectiveOperationException {
        if (playerAccessor == null || speedHolderAccessor == null || setSpeed == null || getSpeed == null) {
            toast(root.getContext(), "倍速入口未适配"); return;
        }
        Object panel = panel(holder);
        if (panel == null || !playerAccessor.getDeclaringClass().isInstance(panel)
                || speedHolderAccessor.invoke(panel) != holder) {
            toast(root.getContext(), "当前播放器不可用"); return;
        }
        Object player = playerAccessor.invoke(panel);
        if (player == null) { toast(root.getContext(), "当前播放器不可用"); return; }
        if (!setSpeed.getDeclaringClass().isInstance(player) || !getSpeed.getDeclaringClass().isInstance(player)) {
            safeLog("Gesture speed rejected player type=" + player.getClass().getName());
            toast(root.getContext(), "当前播放器未适配"); return;
        }
        dismissDialog();
        float currentSpeed = readCurrentSpeed(player);
        if (closed || holder != selectedHolder.get() || root != activeRoot()
                || panel(holder) != panel || speedHolderAccessor.invoke(panel) != holder
                || playerAccessor.invoke(panel) != player) return;

        Object aweme = awemeAccessor.invoke(holder);
        String aid = readAid(aweme);
        if (ownedSpeedPlayer.get() != null && !sameManualTarget(holder, aid, player)) {
            restoreOwnedSpeed();
        }

        if (ownedSpeedPlayer.get() != player) {
            restoreOwnedSpeed();
            currentSpeed = readCurrentSpeed(player);
            originalSpeed = validSpeed(currentSpeed) ? currentSpeed : 1f;
        } else if (validSpeed(currentSpeed) && !sameSpeed(currentSpeed, appliedSpeed)
                && (speedReadbackConfirmed || !sameSpeed(currentSpeed, originalSpeed))) {
            originalSpeed = currentSpeed;
        }
        cancelSpeedReadback();
        LocalOptions current = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        boolean enable = !manualSpeedEnabled;
        float requested = enable ? PlaybackBehaviorHooks.playbackSpeed(current) : 1f;
        ownedSpeedPlayer = new WeakReference<>(player);
        appliedSpeed = requested;
        speedReadbackConfirmed = false;
        if (speedOverride == null) speedOverride = new GestureSpeedOverride();
        try {
            speedOverride.apply(requested, () -> setSpeed.invoke(player, requested));
            manualSpeedEnabled = enable;
            manualSpeedHolder = new WeakReference<>(holder);
            manualSpeedAid = aid;
            safeLog("Gesture speed submitted=" + requested + "; player=" + player.getClass().getName());
            scheduleSpeedReadback(holder, root, player, requested, speedRevision, 0);
            toast(root.getContext(), enable ? "已开启倍速：" + speedLabel(requested) + "x"
                    : "已关闭倍速，恢复 1x");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            restoreOwnedSpeed();
            safeLog("Gesture speed failed: " + failure.getMessage());
            toast(root.getContext(), "倍速设置失败");
        }
    }

    private float readCurrentSpeed(Object player) {
        try {
            return ((Number) getSpeed.invoke(player)).floatValue();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Gesture speed baseline unavailable: " + failure.getMessage());
            return Float.NaN;
        }
    }

    private String readAid(Object aweme) {
        if (aweme == null) return null;
        try {
            Object value = aweme.getClass().getMethod("getAid").invoke(aweme);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }

    private boolean sameManualTarget(Object holder, String aid, Object player) {
        return manualSpeedHolder.get() == holder && ownedSpeedPlayer.get() == player
                && java.util.Objects.equals(manualSpeedAid, aid);
    }

    private static boolean validSpeed(float value) { return Float.isFinite(value) && value > 0; }

    private static String speedLabel(float value) {
        return new java.math.BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }

    private void scheduleSpeedReadback(Object holder, View root, Object player,
                                       float requested, long revision, int attempt) {
        WeakReference<Object> expectedHolder = new WeakReference<>(holder);
        WeakReference<View> expectedRoot = new WeakReference<>(root);
        WeakReference<Object> expectedPlayer = new WeakReference<>(player);
        speedReadback = () -> {
            speedReadback = null;
            Object currentHolder = expectedHolder.get();
            View currentRoot = expectedRoot.get();
            Object currentPlayer = expectedPlayer.get();
            if (closed || revision != speedRevision || currentHolder == null || currentRoot == null
                    || currentPlayer == null || selectedHolder.get() != currentHolder
                    || activeRoot() != currentRoot || ownedSpeedPlayer.get() != currentPlayer) return;
            try {
                Object currentPanel = panel(currentHolder);
                if (currentPanel == null || speedHolderAccessor.invoke(currentPanel) != currentHolder
                        || playerAccessor.invoke(currentPanel) != currentPlayer) return;
                float reported = ((Number) getSpeed.invoke(currentPlayer)).floatValue();
                if (revision != speedRevision || ownedSpeedPlayer.get() != currentPlayer) return;
                if (sameSpeed(reported, requested)) {
                    speedReadbackConfirmed = true;
                    safeLog("Gesture speed readback confirmed: requested=" + requested + "; reported=" + reported);
                    return;
                }
                if (attempt == 2) {
                    safeLog("Gesture speed readback unconfirmed: requested=" + requested + "; reported="
                            + reported + "; setter completed, playback may update asynchronously");
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                if (attempt == 2) safeLog("Gesture speed readback unavailable: " + failure.getMessage());
            }
            if (attempt < 2 && revision == speedRevision) scheduleSpeedReadback(currentHolder, currentRoot, currentPlayer,
                    requested, revision, attempt + 1);
        };
        main.postDelayed(speedReadback, attempt == 0 ? 150L : attempt == 1 ? 350L : 500L);
    }

    private void cancelSpeedReadback() {
        speedRevision++;
        if (speedReadback != null) main.removeCallbacks(speedReadback);
        speedReadback = null;
    }

    private static boolean sameSpeed(float first, float second) {
        return Float.isFinite(first) && Float.isFinite(second) && Math.abs(first - second) <= 0.001f;
    }

    private void refreshToolbar() {
        dismissToolbar();
        View root = activeRoot();
        LocalOptions current = options;
        if (root == null || current == null || !current.enabled("toolbar_enabled")
                || !supports("toolbar_enabled")) return;
        LinearLayout bar = new LinearLayout(root.getContext());
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xDD202124);
        int size = dp(root, 40);
        int[] icons = { android.R.drawable.ic_menu_edit, android.R.drawable.ic_menu_save,
                android.R.drawable.ic_menu_send, android.R.drawable.ic_menu_view,
                android.R.drawable.ic_media_ff, android.R.drawable.ic_menu_search };
        WeakReference<Object> holder = new WeakReference<>(selectedHolder.get());
        for (int index = 0; index < icons.length; index++) {
            final String action = ACTION_IDS[index + 2];
            ImageButton button = new ImageButton(root.getContext());
            button.setImageResource(icons[index]);
            button.setContentDescription(ACTION_LABELS[index + 2]);
            button.setTooltipText(ACTION_LABELS[index + 2]);
            button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            button.setOnClickListener(view -> dispatch(action, holder.get(), root));
            bar.addView(button, new LinearLayout.LayoutParams(size, size));
        }
        toolbar = new PopupWindow(bar, size, size * icons.length, false);
        toolbar.setOutsideTouchable(false);
        toolbar.setClippingEnabled(true);
        try { toolbar.showAtLocation(root, Gravity.END | Gravity.CENTER_VERTICAL, dp(root, 12), 0); }
        catch (RuntimeException failure) {
            dismissToolbar();
            groups.get("toolbar_enabled").fail(failure);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(view.getResources().getDisplayMetrics().density * value);
    }

    private void restoreOwnedSpeed() {
        cancelSpeedReadback();
        manualSpeedEnabled = false;
        manualSpeedHolder.clear();
        manualSpeedAid = null;
        Object player = ownedSpeedPlayer.get();
        ownedSpeedPlayer.clear();
        GestureSpeedOverride override = speedOverride;
        speedOverride = null;
        try {
            if (player == null || getSpeed == null || setSpeed == null) return;
            float actual = readCurrentSpeed(player);
            // A lagging getter may still report the baseline while the last setter is queued.
            if (sameSpeed(actual, appliedSpeed)
                    || (!speedReadbackConfirmed && (sameSpeed(actual, originalSpeed) || !validSpeed(actual)))) {
                float baseline = override == null ? originalSpeed : override.restorationSpeed(originalSpeed);
                float restored = validSpeed(baseline) ? baseline : 1f;
                if (override == null) setSpeed.invoke(player, restored);
                else override.restore(() -> setSpeed.invoke(player, restored));
                safeLog("Gesture speed restored=" + restored + "; player=" + player.getClass().getName());
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not restore gesture speed: " + failure.getMessage());
        } finally {
            if (override != null) override.close();
        }
    }

    private void clearSelected() {
        dismissToolbar(); dismissDialog(); dismissViewFinder(); restoreOwnedSpeed();
        detachRootMonitor();
        selectedHolder.clear(); selectedRoot.clear();
    }

    private void detachRootMonitor() {
        if (rootObserver != null && rootObserver.isAlive()) rootObserver.removeOnPreDrawListener(rootMonitor);
        rootObserver = null;
    }

    private void dismissToolbar() {
        if (toolbar != null) { toolbar.dismiss(); toolbar = null; }
    }

    private void dismissDialog() {
        if (actionDialog != null) { actionDialog.dismiss(); actionDialog = null; }
    }

    private BehaviorHookGroup newGroup(String key) {
        BehaviorHookGroup group = new BehaviorHookGroup(key, log);
        groups.put(key, group);
        return group;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private void safeLog(String text) {
        try { log.accept(text); } catch (Throwable ignored) { }
    }

    private String configuredRegions() {
        StringBuilder result = new StringBuilder();
        for (String prefix : new String[] { "long_press", "double_tap" }) {
            int configured = 0;
            for (int region = 0; region < REGION_IDS.length; region++) {
                if (!"none".equals(regionAction(options, prefix, region))) configured++;
            }
            if (result.length() > 0) result.append(", ");
            result.append(prefix).append('=').append(configured).append("/9");
        }
        return result.toString();
    }

    private void logGestureGate(String kind, int callbacks, View root, String reason) {
        if (callbacks <= 3) safeLog("Feed gesture " + kind + " skipped: reason=" + reason
                + ", holder=" + (selectedHolder.get() != null) + ", foreground=" + (resumed.get() != null)
                + ", touchMatches=" + (root == null ? 0 : touchCount(root)));
    }

    private void logGestureAction(String kind, String action) {
        logGestureAction(kind, action, -1);
    }

    private void logGestureAction(String kind, String action, int region) {
        if (gestureActions <= 20) safeLog("Feed gesture " + kind + " handled: action=" + action
                + ", region=" + regionLabel(region) + ", handled=" + gestureActions);
    }

    @Override public void onActivityResumed(Activity activity) {
        onMain(() -> {
            if (!closed) {
                if (pausedActivity.get() == activity) pausedActivity.clear();
                resumed = new WeakReference<>(activity);
                refreshToolbar();
            }
        });
    }
    @Override public void onActivityPaused(Activity activity) {
        onMain(() -> {
            pausedActivity = new WeakReference<>(activity);
            if (resumed.get() == activity) {
                resumed.clear(); dismissToolbar(); dismissDialog(); dismissViewFinder(); restoreOwnedSpeed();
            }
        });
    }
    @Override public void onActivityDestroyed(Activity activity) {
        onActivityPaused(activity);
        View root = selectedRoot.get();
        if (root != null && activity(root.getContext()) == activity) onMain(this::clearSelected);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        application.unregisterActivityLifecycleCallbacks(this);
        for (BehaviorHookGroup group : groups.values()) group.close();
        onMain(() -> { resumed.clear(); pausedActivity.clear(); pausedHolder.clear(); clearSelected(); });
    }
}

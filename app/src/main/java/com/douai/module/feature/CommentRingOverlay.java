package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** A single decor-attached ring, with a saved free position and isolated touch handling. */
public final class CommentRingOverlay {
    private static final String POSITION_X = "comment_ring_x";
    private static final String POSITION_Y = "comment_ring_y";
    private final SharedPreferences preferences;
    private final CommentRingView ring;
    private final Runnable click;
    private final Runnable longPress;
    private final int touchSlop;
    private final View.OnLayoutChangeListener layoutListener = (view, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom) -> {
        if (!this.dragging) applyPosition();
    };
    private final Runnable hold;
    private ViewGroup parent;
    private boolean touching;
    private boolean dragging;
    private boolean held;
    private boolean multiTouch;
    private float downX;
    private float downY;
    private float initialX;
    private float initialY;
    private float normalizedX;
    private float normalizedY;
    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    public CommentRingOverlay(Context app, Runnable click, Runnable longPress) {
        if (app == null || click == null || longPress == null) throw new IllegalArgumentException("Context and actions are required");
        Context application = app.getApplicationContext();
        if (application == null) application = app;
        preferences = application.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE);
        ring = new CommentRingView(application);
        this.click = click;
        this.longPress = longPress;
        hold = () -> {
            if (!touching || dragging || multiTouch) return;
            held = true;
            ring.setPressed(false);
        };
        touchSlop = ViewConfiguration.get(application).getScaledTouchSlop();
        normalizedX = normalized(preferences.getFloat(POSITION_X, 0));
        normalizedY = normalized(preferences.getFloat(POSITION_Y, .35f));
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // touch() calls performClick after excluding drag and hold.
    public void show(Activity activity, int queueCount, int retriesLeft, String phase, float progress) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        if (parent != decor) {
            detach();
            parent = (ViewGroup) decor;
            int size = dp(CommentRingView.TOUCH_SIZE_DP);
            parent.addView(ring, new FrameLayout.LayoutParams(size, size));
            parent.addOnLayoutChangeListener(layoutListener);
            ring.setOnApplyWindowInsetsListener((view, insets) -> {
                updateInsets(insets);
                applyPosition();
                return insets;
            });
            updateInsets(parent.getRootWindowInsets());
            ring.setOnClickListener(view -> this.click.run());
            ring.setOnLongClickListener(view -> { this.longPress.run(); return true; });
            ring.setOnTouchListener((view, event) -> touch(event));
            ring.requestApplyInsets();
            applyPosition();
        }
        ring.setState(queueCount, retriesLeft, phase, progress);
        ring.setVisibility(View.VISIBLE);
        ring.bringToFront();
    }

    public void detach() {
        ring.removeCallbacks(hold);
        touching = dragging = held = multiTouch = false;
        ring.setPressed(false);
        ring.setOnTouchListener(null);
        ring.setOnClickListener(null);
        ring.setOnLongClickListener(null);
        ring.setClickable(false);
        ring.setLongClickable(false);
        ring.setOnApplyWindowInsetsListener(null);
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
            parent.removeOnLayoutChangeListener(layoutListener);
            parent.removeView(ring);
            parent = null;
        }
        insetLeft = insetTop = insetRight = insetBottom = 0;
    }

    public CommentRingView view() { return ring; }

    private boolean touch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                dragging = held = multiTouch = false;
                downX = event.getRawX();
                downY = event.getRawY();
                initialX = ring.getTranslationX();
                initialY = ring.getTranslationY();
                ring.setPressed(true);
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                ring.removeCallbacks(hold);
                ring.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                ring.removeCallbacks(hold);
                ring.setPressed(false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touching || multiTouch) return true;
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) {
                    dragging = true;
                    held = false;
                    ring.removeCallbacks(hold);
                    ring.setPressed(false);
                }
                if (dragging) move(initialX + dx, initialY + dy);
                return true;
            case MotionEvent.ACTION_UP:
                ring.removeCallbacks(hold);
                ring.setPressed(false);
                if (touching && dragging) savePosition();
                boolean cancelLatest = touching && held && !dragging && !multiTouch;
                boolean activate = touching && !dragging && !held && !multiTouch;
                touching = dragging = held = multiTouch = false;
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                if (cancelLatest) ring.performLongClick();
                else if (activate) ring.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                ring.removeCallbacks(hold);
                ring.setPressed(false);
                touching = dragging = held = multiTouch = false;
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                applyPosition();
                return true;
            default:
                return true;
        }
    }

    private void updateInsets(WindowInsets insets) {
        if (insets == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            insetLeft = safe.left;
            insetTop = safe.top;
            insetRight = safe.right;
            insetBottom = safe.bottom;
        } else {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getSystemWindowInsetBottom();
            if (insets.getDisplayCutout() != null) {
                insetLeft = Math.max(insetLeft, insets.getDisplayCutout().getSafeInsetLeft());
                insetTop = Math.max(insetTop, insets.getDisplayCutout().getSafeInsetTop());
                insetRight = Math.max(insetRight, insets.getDisplayCutout().getSafeInsetRight());
                insetBottom = Math.max(insetBottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
        }
    }

    private void applyPosition() {
        if (dragging || parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return;
        float[] bounds = bounds();
        ring.setTranslationX(bounds[0] + normalizedX * (bounds[2] - bounds[0]) - ring.getLeft());
        ring.setTranslationY(bounds[1] + normalizedY * (bounds[3] - bounds[1]) - ring.getTop());
    }

    private void move(float x, float y) {
        float[] bounds = bounds();
        ring.setTranslationX(clamp(x + ring.getLeft(), bounds[0], bounds[2]) - ring.getLeft());
        ring.setTranslationY(clamp(y + ring.getTop(), bounds[1], bounds[3]) - ring.getTop());
    }

    private void savePosition() {
        float[] bounds = bounds();
        normalizedX = fraction(ring.getX(), bounds[0], bounds[2]);
        normalizedY = fraction(ring.getY(), bounds[1], bounds[3]);
        preferences.edit().putFloat(POSITION_X, normalizedX).putFloat(POSITION_Y, normalizedY).apply();
    }

    private float[] bounds() {
        int margin = dp(12);
        int width = parent == null ? 0 : parent.getWidth();
        int height = parent == null ? 0 : parent.getHeight();
        int size = dp(CommentRingView.TOUCH_SIZE_DP);
        float left = Math.min(insetLeft + margin, Math.max(0, width - size));
        float top = Math.min(insetTop + margin, Math.max(0, height - size));
        return new float[]{left, top, Math.max(left, width - insetRight - margin - size),
                Math.max(top, height - insetBottom - margin - size)};
    }

    private static float fraction(float value, float min, float max) { return max <= min ? 0 : normalized((value - min) / (max - min)); }
    private static float normalized(float value) { return Float.isFinite(value) ? clamp(value, 0, 1) : 0; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private int dp(int value) { return Math.round(value * ring.getResources().getDisplayMetrics().density); }
}

package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.function.IntConsumer;

/** Applies the same visible host margins to each level of the settings windows. */
public final class HostDialogBounds {
    private HostDialogBounds() { }

    public static void install(Activity owner, Dialog dialog, boolean wrapHeight) {
        install(owner, dialog, wrapHeight, ignored -> { });
    }

    public static void install(Activity owner, Dialog dialog, boolean wrapHeight, IntConsumer onHeight) {
        Window window = dialog.getWindow();
        if (window == null) return;
        // AlertDialog cannot discover Compose text editors while creating its native content.
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setGravity(Gravity.CENTER);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View decor = window.getDecorView();
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            apply(owner, window, wrapHeight, insets, onHeight);
            return insets;
        });
        apply(owner, window, wrapHeight, decor.getRootWindowInsets(), onHeight);
        decor.requestApplyInsets();
    }

    private static void apply(Activity owner, Window window, boolean wrapHeight,
                              WindowInsets insets, IntConsumer onHeight) {
        android.util.DisplayMetrics metrics = owner.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = owner.getWindowManager().getCurrentWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            View host = owner.getWindow().getDecorView();
            if (host.getWidth() > 0) width = host.getWidth();
            if (host.getHeight() > 0) height = host.getHeight();
        }
        int horizontal = 0;
        int vertical = 0;
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                horizontal = safe.left + safe.right;
                vertical = safe.top + safe.bottom;
            } else {
                horizontal = insets.getSystemWindowInsetLeft() + insets.getSystemWindowInsetRight();
                vertical = insets.getSystemWindowInsetTop() + insets.getSystemWindowInsetBottom();
            }
        }
        int boundedWidth = Math.max(1, Math.min(Math.round(560 * metrics.density),
                width - horizontal - Math.round(32 * metrics.density)));
        int boundedHeight = Math.max(1, Math.min(Math.round(720 * metrics.density),
                Math.min(Math.round(height * 0.82f), height - vertical - Math.round(48 * metrics.density))));
        int windowHeight = wrapHeight ? WindowManager.LayoutParams.WRAP_CONTENT : boundedHeight;
        WindowManager.LayoutParams current = window.getAttributes();
        if (current.width != boundedWidth || current.height != windowHeight) {
            window.setLayout(boundedWidth, windowHeight);
        }
        onHeight.accept(boundedHeight);
    }
}

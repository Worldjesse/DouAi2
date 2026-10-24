package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Captures only the visible video surface to bridge its short detach/decode handoff. */
final class PlaybackTransitionFrame {
    private static final int MAX_SURFACE_NODES = 1024;
    private static final int MAX_CAPTURE_WIDTH = 1440;

    private PlaybackTransitionFrame() { }

    static void capture(View holderRoot, Handler main, Consumer<Overlay> callback) {
        if (holderRoot == null || main == null || callback == null || !holderRoot.isAttachedToWindow()) {
            callback.accept(null);
            return;
        }
        Activity activity = activity(holderRoot.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            callback.accept(null);
            return;
        }
        SurfaceView surface = videoSurface(holderRoot);
        if (surface == null) {
            callback.accept(null);
            return;
        }
        SurfaceHolder holder = surface.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            callback.accept(null);
            return;
        }
        int sourceWidth = surface.getWidth(), sourceHeight = surface.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            callback.accept(null);
            return;
        }
        int width = Math.min(sourceWidth, MAX_CAPTURE_WIDTH);
        int height = Math.max(1, Math.round(sourceHeight * (width / (float) sourceWidth)));
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            callback.accept(null);
            return;
        }
        int[] surfaceLocation = new int[2], decorLocation = new int[2];
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            bitmap.recycle();
            callback.accept(null);
            return;
        }
        surface.getLocationOnScreen(surfaceLocation);
        decor.getLocationOnScreen(decorLocation);
        Rect location = new Rect(surfaceLocation[0] - decorLocation[0],
                surfaceLocation[1] - decorLocation[1],
                surfaceLocation[0] - decorLocation[0] + sourceWidth,
                surfaceLocation[1] - decorLocation[1] + sourceHeight);
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            PixelCopy.request(surface, bitmap, result -> {
                if (!delivered.compareAndSet(false, true)) {
                    bitmap.recycle();
                    return;
                }
                if (result != PixelCopy.SUCCESS || !surface.isAttachedToWindow()
                        || holderRoot.getWindowToken() == null) {
                    bitmap.recycle();
                    callback.accept(null);
                    return;
                }
                callback.accept(new Overlay((FrameLayout) decor, bitmap, location));
            }, main);
        } catch (RuntimeException | LinkageError failure) {
            delivered.set(true);
            bitmap.recycle();
            callback.accept(null);
        }
    }

    private static SurfaceView videoSurface(View root) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        SurfaceView best = null;
        long bestArea = 0;
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_SURFACE_NODES) {
            View view = pending.removeFirst();
            if (!view.isShown() || view.getAlpha() < 0.1f) continue;
            if (view instanceof SurfaceView) {
                SurfaceView candidate = (SurfaceView) view;
                SurfaceHolder holder = candidate.getHolder();
                if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    long area = (long) candidate.getWidth() * candidate.getHeight();
                    if (area > bestArea) { best = candidate; bestArea = area; }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View child = group.getChildAt(index);
                    if (child != null) pending.addLast(child);
                }
            }
        }
        return best;
    }

    private static Activity activity(android.content.Context context) {
        while (context instanceof android.content.ContextWrapper && !(context instanceof Activity)) {
            android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    static final class Overlay implements AutoCloseable {
        private final FrameLayout decor;
        private final Bitmap bitmap;
        private ImageView image;
        private boolean closed;

        Overlay(FrameLayout decor, Bitmap bitmap, Rect location) {
            this.decor = decor;
            this.bitmap = bitmap;
            image = new ImageView(decor.getContext());
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setClickable(false);
            image.setFocusable(false);
            image.setEnabled(false);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(location.width(), location.height());
            params.leftMargin = location.left;
            params.topMargin = location.top;
            decor.addView(image, params);
            image.bringToFront();
        }

        void fadeAndClose(long durationMillis) {
            if (closed || image == null) { close(); return; }
            ImageView current = image;
            current.animate().alpha(0f).setDuration(Math.max(0, durationMillis))
                    .withEndAction(this::close).start();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (image != null) {
                image.animate().cancel();
                if (image.getParent() == decor) decor.removeView(image);
                image.setImageDrawable(null);
                image = null;
            }
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }
}

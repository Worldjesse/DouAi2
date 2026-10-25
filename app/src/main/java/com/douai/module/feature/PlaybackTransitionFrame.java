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
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayDeque;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Captures only the visible video surface to bridge its short detach/decode handoff. */
final class PlaybackTransitionFrame {
    private static final int MAX_SURFACE_NODES = 1024;
    private static final int MAX_CAPTURE_WIDTH = 1440;
    private static final int MAX_CAPTURE_HEIGHT = 4096;
    static final int MAX_CAPTURE_PIXELS = 1440 * 3200;
    private static final Map<View, Object> IN_FLIGHT = new WeakHashMap<>();
    private static final Map<View, SurfaceCache> SURFACES = new WeakHashMap<>();

    interface Copier {
        void request(SurfaceView source, Bitmap target, PixelCopy.OnPixelCopyFinishedListener listener, Handler handler);
    }

    private PlaybackTransitionFrame() { }

    static void capture(View holderRoot, Handler main, Consumer<Overlay> callback) {
        capture(holderRoot, main, () -> true, callback);
    }

    static void capture(View holderRoot, Handler main, BooleanSupplier current, Consumer<Overlay> callback) {
        capture(holderRoot, main, current, callback, PixelCopy::request);
    }

    static void capture(View holderRoot, Handler main, BooleanSupplier current, Consumer<Overlay> callback, Copier copier) {
        if (callback == null) return;
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
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            callback.accept(null);
            return;
        }
        Object flight = new Object();
        synchronized (IN_FLIGHT) {
            if (IN_FLIGHT.containsKey(decor)) {
                callback.accept(null);
                return;
            }
            IN_FLIGHT.put(decor, flight);
        }
        int[] size = captureSize(sourceWidth, sourceHeight);
        int width = size[0], height = size[1];
        MediaMemoryBudget.Reservation memory = MediaMemoryBudget.tryAcquire((long) width * height * 4);
        if (memory == null) {
            releaseFlight(decor, flight);
            callback.accept(null);
            return;
        }
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            memory.close();
            releaseFlight(decor, flight);
            callback.accept(null);
            return;
        }
        int[] surfaceLocation = new int[2], decorLocation = new int[2];
        surface.getLocationOnScreen(surfaceLocation);
        decor.getLocationOnScreen(decorLocation);
        Rect location = new Rect(surfaceLocation[0] - decorLocation[0],
                surfaceLocation[1] - decorLocation[1],
                surfaceLocation[0] - decorLocation[0] + sourceWidth,
                surfaceLocation[1] - decorLocation[1] + sourceHeight);
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            // An owner's removeCallbacksAndMessages() must not discard PixelCopy's release callback.
            Handler results = new Handler(main.getLooper());
            copier.request(surface, bitmap, result -> {
                if (!delivered.compareAndSet(false, true)) return;
                releaseFlight(decor, flight);
                if (result != PixelCopy.SUCCESS || !surface.isAttachedToWindow()
                        || holderRoot.getWindowToken() == null || !current.getAsBoolean()) {
                    release(bitmap, memory);
                    callback.accept(null);
                    return;
                }
                Overlay overlay;
                try { overlay = new Overlay((FrameLayout) decor, bitmap, location, memory); }
                catch (RuntimeException | LinkageError failure) {
                    release(bitmap, memory);
                    callback.accept(null);
                    return;
                }
                try { callback.accept(overlay); }
                catch (RuntimeException | LinkageError failure) { overlay.close(); }
            }, results);
        } catch (RuntimeException | LinkageError failure) {
            if (delivered.compareAndSet(false, true)) {
                releaseFlight(decor, flight);
                release(bitmap, memory);
                callback.accept(null);
            }
        }
    }

    static int[] captureSize(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) throw new IllegalArgumentException("Invalid surface size");
        double scale = Math.min(1d, Math.min(MAX_CAPTURE_WIDTH / (double) sourceWidth,
                MAX_CAPTURE_HEIGHT / (double) sourceHeight));
        scale = Math.min(scale, Math.sqrt(MAX_CAPTURE_PIXELS / ((double) sourceWidth * sourceHeight)));
        return new int[]{Math.max(1, (int) Math.floor(sourceWidth * scale)),
                Math.max(1, (int) Math.floor(sourceHeight * scale))};
    }

    private static void releaseFlight(View decor, Object flight) {
        synchronized (IN_FLIGHT) { if (IN_FLIGHT.get(decor) == flight) IN_FLIGHT.remove(decor); }
    }

    private static void release(Bitmap bitmap, MediaMemoryBudget.Reservation memory) {
        if (!bitmap.isRecycled()) bitmap.recycle();
        memory.close();
    }

    static void invalidateSurfaceCache(View root) {
        if (root == null) return;
        SurfaceCache previous = SURFACES.remove(root);
        if (previous != null) previous.remove();
    }

    static SurfaceView videoSurface(View root) {
        SurfaceCache previous = SURFACES.get(root);
        if (previous != null) {
            SurfaceView cached = previous.resolve(root);
            if (cached != null) return cached;
            invalidateSurfaceCache(root);
        }
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        SurfaceView best = null;
        long bestArea = 0;
        List<WeakReference<SurfaceView>> candidates = new ArrayList<>();
        List<WeakReference<View>> excluded = new ArrayList<>();
        List<WeakReference<ViewGroup>> groups = new ArrayList<>();
        List<Integer> childCounts = new ArrayList<>();
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_SURFACE_NODES) {
            View view = pending.removeFirst();
            if (!visibleInRoot(view, root)) {
                excluded.add(new WeakReference<>(view));
                continue;
            }
            if (view instanceof SurfaceView) {
                SurfaceView candidate = (SurfaceView) view;
                candidates.add(new WeakReference<>(candidate));
                SurfaceHolder holder = candidate.getHolder();
                if (visibleInRoot(candidate, root) && holder != null && holder.getSurface() != null
                        && holder.getSurface().isValid()) {
                    long area = (long) candidate.getWidth() * candidate.getHeight();
                    if (area > bestArea) { best = candidate; bestArea = area; }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                groups.add(new WeakReference<>(group));
                childCounts.add(group.getChildCount());
                for (int index = 0; index < group.getChildCount(); index++) {
                    View child = group.getChildAt(index);
                    if (child != null) pending.addLast(child);
                }
            }
        }
        if (best != null && pending.isEmpty()) {
            SurfaceCache cache = new SurfaceCache(root, candidates, excluded, groups, childCounts);
            SURFACES.put(root, cache);
        }
        return best;
    }

    private static boolean visibleInRoot(View view, View root) {
        if (!view.isShown()) return false;
        View current = view;
        for (int depth = 0; depth < 64; depth++) {
            if (current.getAlpha() < 0.1f) return false;
            if (current == root) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private static final class SurfaceCache implements ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        final WeakReference<View> root;
        final WeakReference<ViewTreeObserver> observer;
        final List<WeakReference<SurfaceView>> candidates;
        final List<WeakReference<View>> excluded;
        final List<WeakReference<ViewGroup>> groups;
        final List<Integer> childCounts;
        boolean dirty;

        SurfaceCache(View root, List<WeakReference<SurfaceView>> candidates, List<WeakReference<View>> excluded,
                     List<WeakReference<ViewGroup>> groups, List<Integer> childCounts) {
            this.root = new WeakReference<>(root);
            this.candidates = candidates;
            this.excluded = excluded;
            this.groups = groups;
            this.childCounts = childCounts;
            ViewTreeObserver tree = root.getViewTreeObserver();
            observer = new WeakReference<>(tree);
            if (tree.isAlive()) tree.addOnGlobalLayoutListener(this);
            root.addOnAttachStateChangeListener(this);
        }

        SurfaceView resolve(View expectedRoot) {
            if (dirty || root.get() != expectedRoot || expectedRoot.isLayoutRequested()) return null;
            for (WeakReference<View> reference : excluded) {
                View view = reference.get();
                if (view == null || visibleInRoot(view, expectedRoot)) return null;
            }
            for (int index = 0; index < groups.size(); index++) {
                ViewGroup group = groups.get(index).get();
                if (group == null || group.getChildCount() != childCounts.get(index)) return null;
            }
            SurfaceView best = null;
            long area = 0;
            for (WeakReference<SurfaceView> reference : candidates) {
                SurfaceView candidate = reference.get();
                if (candidate == null || !visibleInRoot(candidate, expectedRoot)) continue;
                SurfaceHolder holder = candidate.getHolder();
                long nextArea = (long) candidate.getWidth() * candidate.getHeight();
                if (nextArea > area && holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    best = candidate;
                    area = nextArea;
                }
            }
            return best;
        }

        @Override public void onGlobalLayout() { dirty = true; }
        @Override public void onViewAttachedToWindow(View view) { dirty = true; }
        @Override public void onViewDetachedFromWindow(View view) { invalidateSurfaceCache(view); }

        void remove() {
            View view = root.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
            ViewTreeObserver tree = observer.get();
            if (tree != null && tree.isAlive()) tree.removeOnGlobalLayoutListener(this);
        }
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
        private final MediaMemoryBudget.Reservation memory;
        private ImageView image;
        private boolean closed;

        Overlay(FrameLayout decor, Bitmap bitmap, Rect location, MediaMemoryBudget.Reservation memory) {
            this.decor = decor;
            this.bitmap = bitmap;
            this.memory = memory;
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
            release(bitmap, memory);
        }
    }
}

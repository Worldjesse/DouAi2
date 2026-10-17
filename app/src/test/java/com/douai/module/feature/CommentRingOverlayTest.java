package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module", qualifiers = "w296dp-h560dp-mdpi")
public final class CommentRingOverlayTest {
    private Activity activity;
    private CommentRingOverlay overlay;
    private AtomicInteger clicks;
    private AtomicInteger cancellations;

    @Before public void setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE)
                .edit().clear().commit();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        clicks = new AtomicInteger();
        cancellations = new AtomicInteger();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
    }

    @After public void cleanup() {
        overlay.detach();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void ringAppearsImmediatelyAndStateUpdatesDoNotResizeIt() {
        show("generating", -1);
        CommentRingView ring = overlay.view();
        assertSame(ring, activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"));
        assertEquals(32, ring.getWidth());
        assertEquals(32, ring.getHeight());
        assertEquals(3, ring.getQueueCount());
        assertEquals(2, ring.getRetriesLeft());
        assertEquals("generating", ring.getPhase());
        overlay.show(activity, 10000, 0, "publishing", -1);
        layout();
        assertEquals(32, ring.getWidth());
        assertEquals(32, ring.getHeight());
        assertFalse(ring.getContentDescription().toString().contains("评论内容"));
        assertTrue(ring.getContentDescription().toString().contains("发布中"));
    }

    @Test public void independentReplyPositionAndBlueStyleDoNotChangeTheSavedCommentPosition() {
        CommentRingOverlay reply = new CommentRingOverlay(activity, () -> { }, () -> { }, true);
        overlay.avoid(reply);
        reply.avoid(overlay);
        try {
            show("preview", .8f);
            reply.show(activity, 2, 8, "preview", .8f);
            layout();
            float commentX = overlay.view().getX(), commentY = overlay.view().getY();
            CommentRingView ring = reply.view();
            assertEquals("automation.reply.ring", ring.getTag());
            assertEquals(Color.rgb(100, 178, 255), ring.getPrimaryColor());
            assertEquals(Color.rgb(112, 229, 211), overlay.view().getPrimaryColor());
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 16, 16, 0);
            MotionEvent move = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, 86, 66, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, 86, 66, 0);
            try { ring.dispatchTouchEvent(down); ring.dispatchTouchEvent(move); ring.dispatchTouchEvent(up); }
            finally { down.recycle(); move.recycle(); up.recycle(); }
            float savedX = ring.getX(), savedY = ring.getY();
            assertEquals(commentX, overlay.view().getX(), .1f);
            assertEquals(commentY, overlay.view().getY(), .1f);
            assertTrue(activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).contains("reply_ring_x"));
            assertFalse(activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).contains("comment_ring_x"));
            reply.detach();
            reply = new CommentRingOverlay(activity, () -> { }, () -> { }, true);
            overlay.avoid(reply);
            reply.avoid(overlay);
            reply.show(activity, 1, 0, "publishing", -1);
            layout();
            assertEquals(savedX, reply.view().getX(), .1f);
            assertEquals(savedY, reply.view().getY(), .1f);
        } finally { reply.detach(); }
    }

    @Test public void ringsAvoidOverlapEvenWhenTheirSavedPositionsAreIdentical() {
        overlay.detach();
        activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).edit()
                .putFloat("comment_ring_x", .2f).putFloat("comment_ring_y", .35f)
                .putFloat("reply_ring_x", .2f).putFloat("reply_ring_y", .35f).commit();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
        CommentRingOverlay reply = new CommentRingOverlay(activity, () -> { }, () -> { }, true);
        overlay.avoid(reply);
        reply.avoid(overlay);
        try {
            show("preview", .8f);
            reply.show(activity, 1, 8, "preview", .8f);
            layout();
            CommentRingView first = overlay.view(), second = reply.view();
            assertTrue(Math.abs(first.getX() - second.getX()) >= first.getWidth()
                    || Math.abs(first.getY() - second.getY()) >= first.getHeight());
        } finally { reply.detach(); }
    }

    @Test public void tapOpensDetailsAndTouchDoesNotReachHost() {
        show("preview", .8f);
        AtomicInteger hostTouches = new AtomicInteger();
        View host = new View(activity);
        host.setOnTouchListener((view, event) -> { hostTouches.incrementAndGet(); return true; });
        ((FrameLayout) activity.findViewById(android.R.id.content)).addView(host);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_UP, 16, 16);
        assertEquals(1, clicks.get());
        assertEquals(0, cancellations.get());
        assertEquals(0, hostTouches.get());
    }

    @Test public void longPressCancelsOnceAndReleaseDoesNotOpenDetails() {
        show("generating", -1);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        assertEquals("Long-press is committed on release so a late drag can still start", 0, cancellations.get());
        gesture(MotionEvent.ACTION_UP, 16, 16);
        assertEquals(1, cancellations.get());
        assertEquals(0, clicks.get());
    }

    @Test public void movementAfterHoldDelayContinuesIntoDragInsteadOfCancelling() {
        show("generating", -1);
        CommentRingView ring = overlay.view();
        float oldX = ring.getX();
        float oldY = ring.getY();
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        gesture(MotionEvent.ACTION_MOVE, 76, 54);
        gesture(MotionEvent.ACTION_UP, 76, 54);
        assertEquals(oldX + 60, ring.getX(), .1f);
        assertEquals(oldY + 38, ring.getY(), .1f);
        assertEquals(0, cancellations.get());
        assertEquals(0, clicks.get());
    }

    @Test public void transparentTouchPaddingIsSmallAndDoesNotBlockTheRestOfTheVideo() {
        AtomicInteger hostTouches = new AtomicInteger();
        View host = new View(activity);
        host.setOnTouchListener((view, event) -> { hostTouches.incrementAndGet(); return true; });
        ((FrameLayout) activity.findViewById(android.R.id.content)).addView(host,
                new FrameLayout.LayoutParams(-1, -1));
        show("preview", .8f);
        View decor = activity.getWindow().getDecorView();
        float left = overlay.view().getX();
        float top = overlay.view().getY();
        parentGesture(decor, MotionEvent.ACTION_DOWN, left + 2, top + 16);
        parentGesture(decor, MotionEvent.ACTION_UP, left + 2, top + 16);
        assertEquals(1, clicks.get());
        assertEquals(0, hostTouches.get());
        parentGesture(decor, MotionEvent.ACTION_DOWN, left + 34, top + 16);
        parentGesture(decor, MotionEvent.ACTION_UP, left + 34, top + 16);
        assertEquals(1, clicks.get());
        assertEquals(2, hostTouches.get());
    }

    @Test public void draggingMovesFreelySuppressesActionsAndRestoresPosition() {
        show("generating", -1);
        CommentRingView ring = overlay.view();
        float oldX = ring.getX();
        float oldY = ring.getY();
        gesture(MotionEvent.ACTION_DOWN, 20, 20);
        gesture(MotionEvent.ACTION_MOVE, 102, 64);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        gesture(MotionEvent.ACTION_UP, 102, 64);
        assertEquals(oldX + 82, ring.getX(), .1f);
        assertEquals(oldY + 44, ring.getY(), .1f);
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
        float savedX = ring.getX();
        float savedY = ring.getY();
        overlay.detach();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
        show("retrying", -1);
        assertEquals(savedX, overlay.view().getX(), .1f);
        assertEquals(savedY, overlay.view().getY(), .1f);
    }

    @Test public void decorDispatchedDragMovesRingAndStillSuppressesClickAndHold() {
        show("generating", -1);
        View decor = activity.getWindow().getDecorView();
        float oldX = overlay.view().getX();
        float oldY = overlay.view().getY();
        parentGesture(decor, MotionEvent.ACTION_DOWN, oldX + 16, oldY + 16);
        parentGesture(decor, MotionEvent.ACTION_MOVE, oldX + 96, oldY + 64);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        parentGesture(decor, MotionEvent.ACTION_UP, oldX + 96, oldY + 64);
        assertEquals(oldX + 80, overlay.view().getX(), .1f);
        assertEquals(oldY + 48, overlay.view().getY(), .1f);
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
    }

    @Test public void savedPositionIsNormalizedAcrossRotationAndClampedInsideInsets() {
        show("waiting", 1);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_MOVE, 1000, 1000);
        gesture(MotionEvent.ACTION_UP, 1000, 1000);
        overlay.detach();
        layout(640, 360);
        overlay.show(activity, 1, 0, "failed", 0);
        layout(640, 360);
        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(28, 18, 32, 24)).build();
        overlay.view().dispatchApplyWindowInsets(insets);
        assertEquals(640 - 32 - 12 - 32, overlay.view().getX(), .1f);
        assertEquals(360 - 24 - 12 - 32, overlay.view().getY(), .1f);
        assertTrue(overlay.view().getX() >= 28 + 12);
        assertTrue(overlay.view().getY() >= 18 + 12);
    }

    @Test public void invalidStoredCoordinatesCannotPutRingOutsideTheScreen() {
        overlay.detach();
        activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).edit()
                .putFloat("comment_ring_x", Float.NaN).putFloat("comment_ring_y", 20).commit();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
        show("waiting", 1);
        assertEquals(12, overlay.view().getX(), .1f);
        assertEquals(560 - 12 - 32, overlay.view().getY(), .1f);
    }

    @Test public void detachingDuringPressRemovesPendingCancellationAndListeners() {
        show("generating", -1);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        CommentRingView ring = overlay.view();
        overlay.detach();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
        assertNull(ring.getParent());
        assertFalse(ring.performClick());
        assertFalse(ring.performLongClick());
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
    }

    @Test public void cancelledDragAndMultitouchDoNotActivateActions() {
        show("generating", -1);
        float before = overlay.view().getX();
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_MOVE, 90, 70);
        gesture(MotionEvent.ACTION_CANCEL, 90, 70);
        assertEquals(before, overlay.view().getX(), .1f);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_POINTER_DOWN, 16, 16);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        gesture(MotionEvent.ACTION_UP, 16, 16);
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeNarrowPreviewIsCircularNonblankAndUsesReadableContrast() { renderPreview(); }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeLandscapePreviewKeepsStableCircleWithinScreen() { renderPreview(); }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void largeFontAndLargeQueueKeepTextInsideFixedCircularBounds() {
        android.content.res.Configuration configuration = new android.content.res.Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 2;
        CommentRingView ring = new CommentRingView(activity.createConfigurationContext(configuration));
        ring.setState(10000, 100, "preview", 1);
        ring.measure(View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY));
        ring.layout(0, 0, 32, 32);
        assertEquals(32, ring.getWidth());
        assertEquals(32, ring.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        ring.draw(new Canvas(bitmap));
        String previewFolder = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (previewFolder != null) save(bitmap, new File(previewFolder, "comment-ring-large-font.png"));
        int whiteText = 0;
        for (int y = 9; y <= 17; y++) for (int x = 0; x < 32; x++) {
            int color = bitmap.getPixel(x, y);
            if (Color.red(color) > 170 && Color.green(color) > 170 && Color.blue(color) > 170) {
                assertTrue("Queue text remains within the inner circle: " + x + ',' + y, x >= 10 && x <= 22);
                whiteText++;
            }
        }
        assertTrue(whiteText > 5);
        bitmap.recycle();
    }

    private void renderPreview() {
        View decor = activity.getWindow().getDecorView();
        decor.setBackgroundColor(Color.rgb(45, 54, 57));
        show("preview", .72f);
        CommentRingView ring = overlay.view();
        Bitmap isolated = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        ring.draw(new Canvas(isolated));
        assertEquals(0, Color.alpha(isolated.getPixel(0, 0)));
        assertTrue(Color.alpha(isolated.getPixel(16, 16)) > 220);
        int minPainted = 32;
        int maxPainted = -1;
        int lightPixels = 0;
        int accentPixels = 0;
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int color = isolated.getPixel(x, y);
            if (Color.alpha(color) != 0) { minPainted = Math.min(minPainted, x); maxPainted = Math.max(maxPainted, x); }
            if (Color.red(color) > 170 && Color.green(color) > 170 && Color.blue(color) > 170) lightPixels++;
            if (Color.green(color) > 180 && Color.red(color) < 160 && Color.blue(color) > 150) accentPixels++;
        }
        assertEquals("Visible ring must stay camera-sized", 18, maxPainted - minPainted + 1);
        assertTrue("Readable text should render", lightPixels > 10);
        assertTrue("Progress arc should render", accentPixels > 8);
        isolated.recycle();
        assertTrue(ring.getX() >= 0 && ring.getY() >= 0);
        assertTrue(ring.getX() + ring.getWidth() <= decor.getWidth());
        assertTrue(ring.getY() + ring.getHeight() <= decor.getHeight());
        String output = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (output == null) return;
        File folder = new File(output);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap preview = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(preview));
        save(preview, new File(folder, "comment-ring-" + decor.getWidth() + "x" + decor.getHeight() + ".png"));
        preview.recycle();
        Bitmap strip = Bitmap.createBitmap(192, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(strip);
        canvas.drawColor(Color.rgb(45, 54, 57));
        String[] phases = {"waiting", "generating", "preview", "retrying", "failed"};
        for (int i = 0; i < phases.length; i++) {
            ring.setState(3, Math.max(0, 2 - i / 2), phases[i], i == 2 ? .72f : -1);
            canvas.save();
            canvas.translate(i * 36 + 8, 8);
            ring.draw(canvas);
            canvas.restore();
        }
        save(strip, new File(folder, "comment-ring-states.png"));
        strip.recycle();
    }

    private static void save(Bitmap bitmap, File file) {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private void show(String phase, float progress) {
        layout();
        overlay.show(activity, 3, 2, phase, progress);
        layout();
        ShadowLooper.idleMainLooper();
        layout();
    }

    private void layout() {
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        layout(metrics.widthPixels, metrics.heightPixels);
    }

    private void layout(int width, int height) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }

    private void gesture(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        assertTrue(overlay.view().dispatchTouchEvent(event));
        event.recycle();
    }

    private static void parentGesture(View parent, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        assertTrue(parent.dispatchTouchEvent(event));
        event.recycle();
    }
}

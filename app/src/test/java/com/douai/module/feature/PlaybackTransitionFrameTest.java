package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@LooperMode(LooperMode.Mode.PAUSED)
public final class PlaybackTransitionFrameTest {
    @Test public void ordinaryPortraitFramesKeepTheirResolutionAndExtremeSizesStayBounded() {
        assertArrayEquals(new int[]{1080, 2400}, PlaybackTransitionFrame.captureSize(1080, 2400));
        assertArrayEquals(new int[]{1440, 3200}, PlaybackTransitionFrame.captureSize(1440, 3200));
        for (int[] source : new int[][]{{1440, 9000}, {9000, 1440}, {1, Integer.MAX_VALUE},
                {Integer.MAX_VALUE, 1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            int[] size = PlaybackTransitionFrame.captureSize(source[0], source[1]);
            assertTrue(size[0] > 0 && size[0] <= 1440);
            assertTrue(size[1] > 0 && size[1] <= 4096);
            assertTrue((long) size[0] * size[1] <= PlaybackTransitionFrame.MAX_CAPTURE_PIXELS);
        }
    }

    @Test public void oneWindowKeepsOneCopyInFlightAndReleasesItsBudgetOnFailure() {
        try (Fixture f = new Fixture()) {
            FakeCopier first = new FakeCopier(), duplicate = new FakeCopier();
            int[] failures = {0};
            PlaybackTransitionFrame.capture(f.root, f.owner, () -> true,
                    frame -> { assertNull(frame); failures[0]++; }, first);
            assertEquals(1, first.requests);
            assertBudgetInUse();
            PlaybackTransitionFrame.capture(f.root, f.owner, () -> true,
                    frame -> { assertNull(frame); failures[0]++; }, duplicate);
            assertEquals(0, duplicate.requests);
            assertEquals(1, failures[0]);
            first.complete(PixelCopy.ERROR_SOURCE_NO_DATA);
            assertEquals(2, failures[0]);
            assertTrue(first.bitmap.isRecycled());
            assertBudgetAvailable();

            PlaybackTransitionFrame.capture(f.root, f.owner, () -> true,
                    frame -> { assertNull(frame); }, duplicate);
            assertEquals(1, duplicate.requests);
            duplicate.complete(PixelCopy.ERROR_SOURCE_NO_DATA);
            assertBudgetAvailable();
        }
    }

    @Test public void aLateCopyKeepsMemoryUntilItsCallbackAndNeverCreatesAnOverlay() {
        try (Fixture f = new Fixture()) {
            FakeCopier copy = new FakeCopier();
            AtomicBoolean current = new AtomicBoolean(true);
            int children = f.decor.getChildCount();
            int[] delivered = {0};
            PlaybackTransitionFrame.capture(f.root, f.owner, current::get,
                    frame -> { assertNull(frame); delivered[0]++; }, copy);
            current.set(false);
            assertFalse("PixelCopy may still be writing after the caller times out", copy.bitmap.isRecycled());
            assertBudgetInUse();
            copy.complete(PixelCopy.SUCCESS);
            assertEquals(1, delivered[0]);
            assertEquals(children, f.decor.getChildCount());
            assertTrue(copy.bitmap.isRecycled());
            assertBudgetAvailable();
        }
    }

    @Test public void ownerCallbackRemovalCannotDiscardPixelCopyCleanup() {
        try (Fixture f = new Fixture()) {
            FakeCopier copy = new FakeCopier();
            PlaybackTransitionFrame.capture(f.root, f.owner, () -> false,
                    frame -> { assertNull(frame); }, copy);
            assertNotSame("Copy completion must survive owner handler shutdown", f.owner, copy.handler);
            copy.handler.post(() -> copy.complete(PixelCopy.SUCCESS));
            f.owner.removeCallbacksAndMessages(null);
            ShadowLooper.shadowMainLooper().idle();
            assertTrue(copy.bitmap.isRecycled());
            assertBudgetAvailable();
        }
    }

    @Test public void displayedFrameOwnsItsReservationThroughFadeAndDuplicateCallbacks() {
        try (Fixture f = new Fixture()) {
            FakeCopier copy = new FakeCopier();
            PlaybackTransitionFrame.Overlay[] received = {null};
            PlaybackTransitionFrame.capture(f.root, f.owner, () -> true, frame -> received[0] = frame, copy);
            copy.complete(PixelCopy.SUCCESS);
            assertNotNull(received[0]);
            assertFalse(copy.bitmap.isRecycled());
            assertBudgetInUse();
            copy.complete(PixelCopy.SUCCESS);
            assertFalse("A duplicate callback cannot recycle a displayed frame", copy.bitmap.isRecycled());
            received[0].fadeAndClose(180);
            assertFalse(copy.bitmap.isRecycled());
            assertBudgetInUse();
            received[0].close();
            received[0].close();
            assertTrue(copy.bitmap.isRecycled());
            assertBudgetAvailable();
        }
    }

    @Test public void throwingCopyAndThrowingConsumerBothReleaseMemory() {
        try (Fixture f = new Fixture()) {
            PlaybackTransitionFrame.capture(f.root, f.owner, () -> true, frame -> assertNull(frame),
                    (source, target, listener, handler) -> { throw new IllegalArgumentException("no surface"); });
            assertBudgetAvailable();
            FakeCopier copy = new FakeCopier();
            PlaybackTransitionFrame.capture(f.root, f.owner, () -> true,
                    frame -> { throw new IllegalStateException("caller closed"); }, copy);
            copy.complete(PixelCopy.SUCCESS);
            assertTrue(copy.bitmap.isRecycled());
            assertBudgetAvailable();
        }
    }

    @Test public void aBusySharedMediaBudgetRejectsTheEffectBeforeStartingPixelCopy() {
        try (Fixture f = new Fixture()) {
            MediaMemoryBudget.Reservation busy = MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES);
            assertNotNull(busy);
            try {
                FakeCopier copy = new FakeCopier();
                PlaybackTransitionFrame.capture(f.root, f.owner, () -> true, frame -> assertNull(frame), copy);
                assertEquals(0, copy.requests);
            } finally { busy.close(); }
            assertBudgetAvailable();
        }
    }

    @Test public void stableSurfaceLookupReusesKnownSurfacesAndInvalidatesForNewOrRevealedBranches() {
        try (Fixture f = new Fixture()) {
            assertSame(f.surface, PlaybackTransitionFrame.videoSurface(f.root));
            int reads = f.root.childReads;
            assertSame(f.surface, PlaybackTransitionFrame.videoSurface(f.root));
            assertEquals("Stable lookup must not traverse every child again", reads, f.root.childReads);
            ValidSurfaceView larger = new ValidSurfaceView(f.activity);
            f.root.addView(larger, new FrameLayout.LayoutParams(400, 400));
            larger.layout(0, 0, 400, 400);
            assertSame(larger, PlaybackTransitionFrame.videoSurface(f.root));

            FrameLayout hidden = new FrameLayout(f.activity);
            hidden.setVisibility(View.INVISIBLE);
            ValidSurfaceView hiddenSurface = new ValidSurfaceView(f.activity);
            hidden.addView(hiddenSurface, new FrameLayout.LayoutParams(500, 500));
            f.root.addView(hidden);
            f.layout();
            hiddenSurface.layout(0, 0, 500, 500);
            assertSame(larger, PlaybackTransitionFrame.videoSurface(f.root));
            hidden.setVisibility(View.VISIBLE);
            assertSame(hiddenSurface, PlaybackTransitionFrame.videoSurface(f.root));
        }
    }

    private static void assertBudgetInUse() {
        MediaMemoryBudget.Reservation all = MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES);
        if (all != null) { all.close(); fail("Live frame must retain its shared memory reservation"); }
    }

    private static void assertBudgetAvailable() {
        MediaMemoryBudget.Reservation all = MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES);
        assertNotNull("Every completed frame must return the entire shared budget", all);
        all.close();
    }

    private static final class FakeCopier implements PlaybackTransitionFrame.Copier {
        int requests;
        Bitmap bitmap;
        Handler handler;
        PixelCopy.OnPixelCopyFinishedListener listener;
        @Override public void request(SurfaceView source, Bitmap target,
                                      PixelCopy.OnPixelCopyFinishedListener listener, Handler handler) {
            requests++;
            bitmap = target;
            this.listener = listener;
            this.handler = handler;
        }
        void complete(int result) { listener.onPixelCopyFinished(result); }
    }

    private static final class CountingRoot extends FrameLayout {
        int childReads;
        CountingRoot(Activity activity) { super(activity); }
        @Override public View getChildAt(int index) { childReads++; return super.getChildAt(index); }
    }

    private static final class ValidSurface extends Surface {
        ValidSurface() { super(new android.graphics.SurfaceTexture(0)); }
        @Override public boolean isValid() { return true; }
    }

    private static final class ValidSurfaceView extends SurfaceView {
        private final Surface validSurface = new ValidSurface();
        private final SurfaceHolder validHolder = (SurfaceHolder) Proxy.newProxyInstance(
                SurfaceHolder.class.getClassLoader(), new Class<?>[]{SurfaceHolder.class}, (proxy, method, args) -> {
                    if ("getSurface".equals(method.getName())) return validSurface;
                    if ("isCreating".equals(method.getName())) return false;
                    return null;
                });
        ValidSurfaceView(Activity activity) { super(activity); }
        @Override public SurfaceHolder getHolder() { return validHolder; }
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final CountingRoot root = new CountingRoot(activity);
        final ValidSurfaceView surface = new ValidSurfaceView(activity);
        final FrameLayout decor;
        final Handler owner = new Handler(Looper.getMainLooper());
        Fixture() {
            root.addView(surface, new FrameLayout.LayoutParams(240, 320));
            activity.setContentView(root);
            decor = (FrameLayout) activity.getWindow().getDecorView();
            layout();
            assertTrue(root.isAttachedToWindow());
            assertTrue(surface.isShown());
        }
        void layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 2400);
        }
        @Override public void close() {
            PlaybackTransitionFrame.invalidateSurfaceCache(root);
            activity.finish();
            ShadowLooper.shadowMainLooper().idle();
        }
    }
}

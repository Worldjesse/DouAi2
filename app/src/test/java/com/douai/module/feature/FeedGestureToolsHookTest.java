package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import io.github.libxposed.api.XposedInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public final class FeedGestureToolsHookTest {
    @org.junit.After public void cancelDownloadsStartedByTheTest() {
        for (DownloadTasks.Snapshot task : DownloadTasks.snapshots()) if (task.cancellable) DownloadTasks.cancel(task.id);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (DownloadTasks.snapshots().stream().anyMatch(task -> "running".equals(task.status)
                || "queued".equals(task.status) || "cancelling".equals(task.status)) && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
        }
    }
    public static final class Aweme {
        final String aid;
        public String share_url;
        public DownloadActionHookTest.VideoFixture video;
        Aweme(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }
    public static final class ItemParams { public Panel mFeedContext; }
    public static final class Holder {
        public FrameLayout w;
        public ItemParams A2;
        public String aid = "video-1";
        Aweme downloadAweme;
        public Aweme getAweme() { return downloadAweme == null ? new Aweme(aid) : downloadAweme; }
        public void selected() { }
        public void resume() { }
        public void pause() { }
    }
    public static final class Panel {
        public Holder current;
        public IPlayerManager currentPlayer = new PlayerManager(new FakeFeedWrapper(1.25f));
        int nativeDoubleTaps;
        View playControl;
        int hidePlayControlCalls;
        int showPlayControlCalls;
        public Holder currentHolder() { return current; }
        public IPlayerManager player() { return currentPlayer; }
        public void doubleTap(MotionEvent event) { nativeDoubleTaps++; }
        public void togglePlayback(Aweme aweme, boolean fromTap, boolean updateUi) {
            FakeFeedWrapper delegate = ((PlayerManager) currentPlayer).delegate;
            delegate.playing = !delegate.playing;
        }
        public void hidePlayControl() {
            hidePlayControlCalls++;
            if (playControl != null) playControl.setVisibility(View.GONE);
        }
        public void showPlayControl() {
            showPlayControlCalls++;
            if (playControl != null) playControl.setVisibility(View.VISIBLE);
        }
    }
    public interface IPlayerInfo {
        float getSpeed();
        default boolean isPlaying() { return true; }
    }
    public interface IPlayerManager extends IPlayerInfo {
        void setSpeed(float speed);
        void resumePlay();
        String play();
        default void pausePlay() { }
    }
    public static final class FakeFeedWrapper implements IPlayerManager {
        float speed;
        int speedChanges;
        boolean playing = true;
        int resumeCalls;
        int playCalls;
        int pauseCalls;
        boolean failPause;
        Runnable pauseUiAction;
        FakeFeedWrapper(float speed) { this.speed = speed; }
        public float getSpeed() { return speed; }
        public boolean isPlaying() { return playing; }
        public void setSpeed(float value) { speed = value; speedChanges++; }
        public void resumePlay() { resumeCalls++; playing = true; }
        public String play() { playCalls++; playing = true; return "started"; }
        public void pausePlay() {
            if (failPause) throw new IllegalStateException("pause unavailable");
            pauseCalls++;
            playing = false;
            if (pauseUiAction != null) pauseUiAction.run();
        }
    }
    public static final class PlayerManager implements IPlayerManager {
        final FakeFeedWrapper delegate;
        PlayerManager(FakeFeedWrapper delegate) { this.delegate = delegate; }
        @Override public float getSpeed() { return delegate.getSpeed(); }
        @Override public boolean isPlaying() { return delegate.isPlaying(); }
        @Override public void setSpeed(float speed) { delegate.setSpeed(speed); }
        @Override public void resumePlay() { delegate.resumePlay(); }
        @Override public String play() { return delegate.play(); }
        @Override public void pausePlay() { delegate.pausePlay(); }
    }
    public static final class DelayedPlayerManager implements IPlayerManager {
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean deferPlayback;
        float actualSpeed = 1.25f;
        float reportedSpeed = 1.25f;
        int speedChanges;
        int revision;
        DelayedPlayerManager(boolean deferPlayback) { this.deferPlayback = deferPlayback; }
        @Override public void resumePlay() { }
        @Override public String play() { return null; }
        @Override public float getSpeed() { return reportedSpeed; }
        @Override public void setSpeed(float speed) {
            speedChanges++;
            int request = ++revision;
            if (!deferPlayback) actualSpeed = speed;
            main.postDelayed(() -> {
                if (request != revision) return;
                actualSpeed = speed;
                reportedSpeed = speed;
            }, 400);
        }
    }
    public static final class UnreportedPlayerManager implements IPlayerManager {
        float actualSpeed = 1f;
        @Override public void resumePlay() { }
        @Override public String play() { return null; }
        @Override public float getSpeed() { return 0f; }
        @Override public void setSpeed(float speed) { actualSpeed = speed; }
    }
    public static final class TouchLayout extends View {
        public float lastRawX;
        public float lastRawY;
        public boolean discardCurrentEvent;
        public int nativeUpCalls;
        public boolean enableLongClick = true;
        public Runnable longPressRunnable;
        public int mTimeInterval = 500;
        Runnable nativeDownAction;
        TouchLayout(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            return super.dispatchTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && nativeDownAction != null) {
                nativeDownAction.run();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                nativeUpCalls++;
                if (discardCurrentEvent) {
                    discardCurrentEvent = false;
                    return false;
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getActionMasked() == MotionEvent.ACTION_DOWN) discardCurrentEvent = false;
            return true;
        }
    }
    public static final class LongPress implements Runnable {
        public final TouchLayout a;
        int nativeCalls;
        LongPress(TouchLayout layout) { a = layout; }
        @Override public void run() { nativeCalls++; }
    }
    private static final class FeedRoot extends FrameLayout {
        boolean focused = true;
        FeedRoot(Context context) { super(context); }
        @Override public boolean hasWindowFocus() { return focused; }
    }

    @Test @Config(sdk = 28)
    public void gestureMenuSaveUsesOnlyThirdPartyAndNeverFallsBackAfterFailure() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"id\":\"gesture_work\",\"text\":\"caption\",\"video\":[\""
                            + server.url("/third.mp4") + "\"]}}"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4")
                    .setBody("third-party-video"));
            Map<String, Object> values = new HashMap<>();
            values.put("long_press_top_left", "menu");
            values.put(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
            values.put(ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse") + "?ID=75107541&link={url}");
            values.put(ThirdPartyDownloadSettings.APP_SECRET, "old-secret");
            values.put(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE);
            values.put(ThirdPartyDownloadSettings.NAMING_FIELDS, "[\"data.id\"]");
            values.put(LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE);
            DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
            try (Fixture f = new Fixture(values)) {
                Aweme work = new Aweme("7696151596120622001");
                work.share_url = "https://v.douyin.com/XVo-IAGSo2s/";
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/official.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                clickSaveFromGestureMenu(f);
                okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("/parse", request.getRequestUrl().encodedPath());
                assertEquals(work.share_url, request.getRequestUrl().queryParameter("link"));
                assertEquals("75107541", request.getRequestUrl().queryParameter("ID"));
                assertNull(request.getRequestUrl().queryParameter("app_id"));
                assertNull(request.getHeader("Sign"));
                assertEquals("/third.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("已保存");
                assertEquals(3, documents.names.size());
                assertEquals(2, documents.files.size());
                assertTrue(documents.names.containsValue("gesture_work.txt"));

                server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
                clickSaveFromGestureMenu(f);
                assertEquals("/parse", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getRequestUrl().encodedPath());
                awaitDownloadToast("第三方下载失败");
                assertEquals(3, server.getRequestCount());
                android.app.DownloadManager manager = (android.app.DownloadManager)
                        f.activity.getSystemService(Context.DOWNLOAD_SERVICE);
                org.robolectric.shadows.ShadowDownloadManager downloads = org.robolectric.shadow.api.Shadow.extract(manager);
                assertEquals(0, downloads.getRequestCount());
            }
        }
    }

    @Test @Config(sdk = 28)
    public void gestureMenuSaveInOfficialModeNeverRequestsConfiguredThirdPartyApi() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4").setBody("official-video"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "menu", "download_method", "official",
                    ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse") + "?url={url}"))) {
                org.robolectric.shadows.ShadowApplication application =
                        org.robolectric.shadow.api.Shadow.extract(f.activity.getApplication());
                application.grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                Aweme work = new Aweme("work");
                work.share_url = "https://v.douyin.com/XVo-IAGSo2s/";
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/official.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                clickSaveFromGestureMenu(f);
                assertEquals("/official.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("已加入系统下载队列");
                assertEquals(1, server.getRequestCount());
                android.app.DownloadManager manager = (android.app.DownloadManager)
                        f.activity.getSystemService(Context.DOWNLOAD_SERVICE);
                org.robolectric.shadows.ShadowDownloadManager downloads = org.robolectric.shadow.api.Shadow.extract(manager);
                assertEquals(1, downloads.getRequestCount());
            }
        }
    }

    private static void clickSaveFromGestureMenu(Fixture fixture) throws Throwable {
        fixture.longPress(0, 0);
        android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("保存媒体", dialog.getListView().getAdapter().getItem(1));
        dialog.getListView().performItemClick(null, 1, 1);
        ShadowLooper.idleMainLooper();
    }

    @Test @Config(sdk = 28)
    public void officialGestureSaveUsesTheSameCustomDirectoryAsThirdParty() throws Throwable {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4").setBody("official-video"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "menu", "download_method", "official",
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE))) {
                Aweme work = new Aweme("work");
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/official.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                clickSaveFromGestureMenu(f);
                assertEquals("/official.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("媒体已保存到所选目录");
                assertEquals(1, documents.names.size());
                String id = documents.files.keySet().iterator().next();
                assertEquals("root", documents.parents.get(id));
                assertEquals("official-video", java.nio.file.Files.readString(documents.files.get(id).toPath()));
            }
        }
    }

    @Test @Config(sdk = 28)
    public void parameterlessThirdPartyApiIsRequestedExactlyWithoutRequiringAWorkLink() throws Throwable {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"id\":\"fixed_api\",\"video\":[\""
                            + server.url("/media.mp4") + "\"]}}"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4").setBody("video"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "menu", "download_method", "third_party",
                    ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/latest").toString(),
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE))) {
                f.select();
                clickSaveFromGestureMenu(f);
                assertEquals("/latest", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                assertEquals("/media.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("已保存");
                assertEquals(2, documents.files.size());
                assertTrue(documents.names.containsValue("fixed_api.txt"));
            }
        }
    }

    private static void awaitDownloadToast(String expected) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            ShadowLooper.idleMainLooper();
            String toast = ShadowToast.getTextOfLatestToast();
            if (toast != null && toast.contains(expected)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
        } while (System.nanoTime() < deadline);
        assertTrue("Expected " + expected + ", received " + ShadowToast.getTextOfLatestToast(), false);
    }

    @Test public void lateInstallationRecoversForegroundFromSelectedFocusedHolder() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            // The Activity resumed before install, so no module lifecycle resume callback is sent.
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void alreadySelectedHolderIsRecoveredFromHostCurrentHolderCallback() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void alreadySelectedHolderIsRecoveredWhenNativeReadsItsAweme() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            f.harness.invoke(Holder.class.getMethod("getAweme"), f.holder);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void backgroundModelReadRecoversOnlyAfterReturningToMainThread() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread reader = new Thread(() -> {
                try { f.harness.invoke(Holder.class.getMethod("getAweme"), f.holder); }
                catch (Throwable error) { failure.set(error); }
            });
            reader.start();
            reader.join(2000);
            assertFalse(reader.isAlive());
            assertNull(failure.get());
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void offscreenHostReadDoesNotPreventLaterForegroundRecovery() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            f.root.focused = false;
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(0, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
            f.root.focused = true;
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
        }
    }

    @Test public void hiddenAlternativeTouchLayoutDoesNotMakeActiveLayoutAmbiguous() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            TouchLayout hidden = new TouchLayout(f.activity);
            hidden.setVisibility(View.GONE);
            f.root.addView(hidden);
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void twoVisibleLayoutsUseTheTouchLayerThatScheduledTheRunnable() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            f.root.addView(new TouchLayout(f.activity));
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void nativeLongPressSwitchDoesNotDisableConfiguredModuleAction() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "toggle_components"))) {
            f.hook.onActivityResumed(f.activity);
            f.select();
            f.touch.enableLongClick = false;
            f.longPress(2, 2);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void interceptNativeLongPressConsumesUnconfiguredActionAndRelease() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, true))) {
            f.select();
            f.longPress(1, 1);

            assertEquals(0, f.press.nativeCalls);
            assertTrue("The intercepted long press must consume the pending release",
                    f.touch.discardCurrentEvent);

            MotionEvent up = MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, 150, 300, 0);
            try {
                assertEquals(Boolean.FALSE, f.harness.invoke(
                        TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class), f.touch, up));
            } finally { up.recycle(); }
            assertEquals("An intercepted release must not reach the host touch handler", 0,
                    f.touch.nativeUpCalls);
            assertFalse(f.touch.discardCurrentEvent);
        }
    }

    @Test public void nativeLongPressRemainsAvailableWhenInterceptionIsDisabled() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.press.nativeCalls);
            assertFalse(f.touch.discardCurrentEvent);
        }
    }

    @Test public void actualLongPressCallbackRoutesEveryCellAndPreservesUnconfiguredNeighbor() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.hook.onActivityResumed(f.activity);
            f.select();
            for (int region = 0; region < 9; region++) {
                f.hook.setOptions(new LocalOptions(Map.of("long_press_"
                        + FeedGestureToolsHook.REGION_IDS[region], "toggle_components")));
                f.longPress(region % 3, region / 3);
                assertEquals(FeedGestureToolsHook.REGION_IDS[region], region + 1, f.componentToggles);
                int neighbor = (region + 1) % 9;
                f.longPress(neighbor % 3, neighbor / 3);
                assertEquals(region + 1, f.press.nativeCalls);
                assertEquals(region + 1, f.componentToggles);
            }
        }
    }

    @Test public void backgroundAndForeignLayoutKeepNativeLongPress() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            f.hook.onActivityResumed(f.activity);
            f.select();
            LongPress foreign = new LongPress(new TouchLayout(f.activity));
            f.harness.invoke(LongPress.class.getMethod("run"), foreign);
            assertEquals(1, foreign.nativeCalls);
            f.hook.onActivityPaused(f.activity);
            f.root.focused = false;
            f.longPress(1, 1);
            assertEquals(1, f.press.nativeCalls);
            assertEquals(0, f.componentToggles);
        }
    }

    @Test public void pausedHolderCannotBeRevivedByStaleCurrentHolderCallbacks() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            f.select();
            f.harness.invoke(Holder.class.getMethod("pause"), f.holder);
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            f.harness.invoke(Holder.class.getMethod("getAweme"), f.holder);
            ShadowLooper.idleMainLooper();
            f.longPress(1, 1);
            assertEquals(0, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
        }
    }

    @Test public void doubleTapUsesTheSameForegroundAndGridAndNonePreservesNative() throws Throwable {
        try (Fixture f = new Fixture(Map.of("double_tap_bottom_left", "toggle_components"))) {
            f.select();
            f.doubleTap(0, 2);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.panel.nativeDoubleTaps);
            f.doubleTap(1, 2);
            assertEquals(1, f.panel.nativeDoubleTaps);
            f.hook.close();
            assertTrue(f.harness.interceptors.isEmpty());
        }
    }

    @Test public void bottomRightLongPressImmediatelyAppliesTheConfiguredNumericSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "2.75"))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(0, f.press.nativeCalls);
            assertEquals(2.75f, f.player.getSpeed(), 0f);
            assertEquals(1, f.player.delegate.speedChanges);
            assertNull("The configured speed action must not open another selector",
                    ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void unsetNumericValueAppliesTheSharedDefaultSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(2f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void configuredNumericValueUsesTheSamePlaybackBounds() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "8"))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(4f, f.player.getSpeed(), 0f);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "0.1")));
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(0.25f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void manuallyRequestedSpeedWorksWhileAutomaticSpeedIsDisabled() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3.25", "auto_speed", false))) {
            f.select();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(3.25f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void longPressTogglesConfiguredSpeedAndNormalWithToastForBothAutoFlags() throws Throwable {
        for (boolean automatic : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "2.75", "auto_speed", automatic))) {
                f.select();
                f.longPress(2, 2);
                assertEquals(2.75f, f.player.getSpeed(), 0f);
                assertEquals("已开启倍速：2.75x", ShadowToast.getTextOfLatestToast());
                f.longPress(2, 2);
                assertEquals(1f, f.player.getSpeed(), 0f);
                assertEquals("已关闭倍速，恢复 1x", ShadowToast.getTextOfLatestToast());
                f.longPress(2, 2);
                assertEquals(2.75f, f.player.getSpeed(), 0f);
                assertEquals("已开启倍速：2.75x", ShadowToast.getTextOfLatestToast());
            }
        }
    }

    @Test public void manualToggleIsScopedToTheCurrentVideoEvenWhenHolderIsReused() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2.75", "auto_speed", true))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(2.75f, f.player.getSpeed(), 0f);
            f.holder.aid = "video-2";
            f.longPress(2, 2);
            assertEquals(2.75f, f.player.getSpeed(), 0f);
            assertEquals("已开启倍速：2.75x", ShadowToast.getTextOfLatestToast());
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertEquals("已关闭倍速，恢复 1x", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void unavailableReadbackCannotBlockTheIndependentToggle() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2.5", "auto_speed", false))) {
            UnreportedPlayerManager player = new UnreportedPlayerManager();
            f.panel.currentPlayer = player;
            f.select();
            f.longPress(2, 2);
            assertEquals(2.5f, player.actualSpeed, 0f);
            assertEquals("已开启倍速：2.5x", ShadowToast.getTextOfLatestToast());
            f.longPress(2, 2);
            assertEquals(1f, player.actualSpeed, 0f);
            assertEquals("已关闭倍速，恢复 1x", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void doubleTapUsesTheConfiguredSpeedWithoutASelector() throws Throwable {
        try (Fixture f = new Fixture(Map.of("double_tap_bottom_right", "speed", "playback_speed", "2.5"))) {
            f.select();
            f.doubleTap(2, 2);
            assertEquals(0, f.panel.nativeDoubleTaps);
            assertEquals(2.5f, f.player.getSpeed(), 0f);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void directWrapperAlsoUsesTheSamePlayerInterfaces() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.panel.currentPlayer = f.player.delegate;
            f.select();
            f.longPress(2, 2);
            assertEquals(2f, f.player.delegate.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.delegate.getSpeed(), 0f);
        }
    }

    @Test public void requestingAgainAfterAnExternalChangeRestoresThatNewBaseline() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.player.setSpeed(3f);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "1.5")));
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(3f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void leavingTheForegroundRestoresTheSpeedBeforeTheGesture() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(2, f.player.delegate.speedChanges);
        }
    }

    @Test public void pausingTheSelectedHolderRestoresTheOriginalSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.harness.invoke(Holder.class.getMethod("pause"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(2, f.player.delegate.speedChanges);
        }
    }

    @Test public void closingHooksRestoresOwnedSpeedAndUnregistersCallbacks() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(2, f.player.delegate.speedChanges);
            assertTrue(f.harness.interceptors.isEmpty());
        }
    }

    @Test public void restoringGestureSpeedPreservesAnExternalSpeedChange() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.player.setSpeed(3f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(3f, f.player.getSpeed(), 0f);
            assertEquals(2, f.player.delegate.speedChanges);
        }
    }

    @Test public void aReplacedCurrentHolderCannotApplyTheSpeedToAnotherVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.panel.current = new Holder();
            f.longPress(2, 2);
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(0, f.player.delegate.speedChanges);
        }
    }

    @Test public void speedUsesTheCurrentPlayerAfterManagerReplacement() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            PlayerManager replacement = new PlayerManager(new FakeFeedWrapper(1.5f));
            f.panel.currentPlayer = replacement;
            f.longPress(2, 2);
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(0, f.player.delegate.speedChanges);
            assertEquals(2f, replacement.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(1.5f, replacement.getSpeed(), 0f);
        }
    }

    @Test public void liveSpeedOptionChangesKeepTheInitialRestoreValueAcrossRepeatedGestures() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "1.5")));
            assertEquals(2f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(4, f.player.delegate.speedChanges);
        }
    }

    @Test public void delayedSpeedReadbackDoesNotShowAFalseRejectionOrLoseRestoreOwnership() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "2.5"))) {
            DelayedPlayerManager delayed = new DelayedPlayerManager(false);
            f.panel.currentPlayer = delayed;
            f.select();
            f.longPress(2, 2);
            assertEquals(2.5f, delayed.actualSpeed, 0f);
            assertEquals(1.25f, delayed.getSpeed(), 0f);
            assertNoSpeedRejection();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(2.5f, delayed.getSpeed(), 0f);
            assertNoSpeedRejection();
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1.25f, delayed.actualSpeed, 0f);
        }
    }

    @Test public void repeatedRequestsBeforeDelayedReadbackKeepTheInitialRestoreValue() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "2.5"))) {
            DelayedPlayerManager delayed = new DelayedPlayerManager(false);
            f.panel.currentPlayer = delayed;
            f.select();
            f.longPress(2, 2);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "3.5")));
            f.longPress(2, 2);
            assertEquals("已关闭倍速，恢复 1x", ShadowToast.getTextOfLatestToast());
            f.longPress(2, 2);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(3.5f, delayed.actualSpeed, 0f);
            assertNoSpeedRejection();
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1.25f, delayed.actualSpeed, 0f);
        }
    }

    @Test public void pausingBeforeAnAsyncSetterFinishesCancelsItsPendingSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "2.5"))) {
            DelayedPlayerManager delayed = new DelayedPlayerManager(true);
            f.panel.currentPlayer = delayed;
            f.select();
            f.longPress(2, 2);
            assertEquals(1.25f, delayed.actualSpeed, 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(2, delayed.speedChanges);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1.25f, delayed.actualSpeed, 0f);
            assertNoSpeedRejection();
        }
    }

    private static void assertNoSpeedRejection() {
        assertFalse("A successful setter with delayed readback must not be reported as rejected",
                "当前播放器没有接受该倍速".equals(ShadowToast.getTextOfLatestToast()));
    }

    @Test public void defaultsPreserveNativeGesturesAndHideToolbar() {
        LocalOptions options = new LocalOptions(Collections.emptyMap());
        assertFalse(options.enabled("toolbar_enabled"));
        assertEquals("none", select(options, "long_press", 150, 300));
        assertEquals("none", select(options, "double_tap", 150, 300));
    }

    @Test public void nineRegionsUseBothCoordinatesRelativeToTheVideoBounds() {
        Map<String, Object> values = new java.util.HashMap<>();
        for (int index = 0; index < 9; index++) {
            values.put("long_press_" + FeedGestureToolsHook.REGION_IDS[index],
                    FeedGestureToolsHook.ACTION_IDS[index % FeedGestureToolsHook.ACTION_IDS.length]);
        }
        LocalOptions options = new LocalOptions(values);
        for (int index = 0; index < 9; index++) {
            float x = 40 + 50 + index % 3 * 100;
            float y = 80 + 100 + index / 3 * 200;
            assertEquals(FeedGestureToolsHook.REGION_IDS[index],
                    FeedGestureToolsHook.ACTION_IDS[index % FeedGestureToolsHook.ACTION_IDS.length],
                    FeedGestureToolsHook.selectedAction(options, "long_press", x, y, 40, 80, 300, 600));
        }
    }

    @Test public void legacyColumnsApplyToEachRowUntilThatRegionHasAnExplicitAction() {
        LocalOptions options = new LocalOptions(Map.of("long_press_left", "copy_text",
                "long_press_center", "open_comments", "long_press_right", "save_media",
                "long_press_middle_left", "none", "long_press_bottom_right", "speed"));
        for (int row = 0; row < 3; row++) {
            int y = row * 200 + 100;
            assertEquals(row == 1 ? "none" : "copy_text", select(options, "long_press", 50, y));
            assertEquals("open_comments", select(options, "long_press", 150, y));
            assertEquals(row == 2 ? "speed" : "save_media", select(options, "long_press", 250, y));
        }
    }

    @Test public void regionOverrideCanPreserveNativeActionWithoutChangingNeighborRegions() {
        LocalOptions options = new LocalOptions(Map.of("double_tap_action", "menu",
                "double_tap_top_left", "none", "double_tap_middle_right", "speed"));
        assertEquals("none", select(options, "double_tap", 10, 10));
        assertEquals("menu", select(options, "double_tap", 10, 300));
        assertEquals("menu", select(options, "double_tap", 250, 10));
        assertEquals("speed", select(options, "double_tap", 250, 300));
    }

    @Test public void cellBoundariesRouteToTheFollowingColumnAndRow() {
        LocalOptions options = new LocalOptions(Map.of("double_tap_top_left", "menu",
                "double_tap_top_center", "copy_text", "double_tap_middle_center", "save_media",
                "double_tap_middle_right", "speed", "double_tap_bottom_right", "open_comments"));
        assertEquals("menu", select(options, "double_tap", 0, 0));
        assertEquals("copy_text", select(options, "double_tap", 100, 199));
        assertEquals("save_media", select(options, "double_tap", 100, 200));
        assertEquals("speed", select(options, "double_tap", 200, 200));
        assertEquals("open_comments", select(options, "double_tap", 299, 599));
    }

    @Test public void unknownActionAndInvalidCoordinateCannotReplaceNativeGesture() {
        LocalOptions options = new LocalOptions(Map.of("long_press_action", "unsupported"));
        assertEquals("none", select(options, "long_press", 10, 10));
        options = new LocalOptions(Map.of("long_press_action", "copy_text"));
        assertEquals("none", select(options, "long_press", Float.NaN, 10));
        assertEquals("none", select(options, "long_press", 10, Float.POSITIVE_INFINITY));
        assertEquals("none", select(options, "long_press", -1, 10));
        assertEquals("none", select(options, "long_press", 10, -1));
        assertEquals("none", select(options, "long_press", 300, 10));
        assertEquals("none", select(options, "long_press", 10, 600));
        assertEquals("none", FeedGestureToolsHook.selectedAction(options, "long_press", 0, 0, 0, 0, 0, 600));
        assertEquals("none", FeedGestureToolsHook.selectedAction(options, "long_press", 0, 0, 0, 0, 300, 0));
    }

    @Test public void viewFinderIsAvailableInLongPressAndDoubleTapActionChoices() {
        assertEquals("view_finder", FeedGestureToolsHook.ACTION_IDS[
                FeedGestureToolsHook.ACTION_IDS.length - 1]);
        assertEquals("视图查找", FeedGestureToolsHook.ACTION_LABELS[
                FeedGestureToolsHook.ACTION_LABELS.length - 1]);
        LocalOptions options = new LocalOptions(Map.of(
                "long_press_bottom_right", "view_finder",
                "double_tap_top_left", "view_finder"));
        assertEquals("view_finder", select(options, "long_press", 299, 599));
        assertEquals("view_finder", select(options, "double_tap", 0, 0));
    }

    @Test public void topRightViewFinderWinsOverBottomRightSpeedWithMultipleTouchLayers() throws Throwable {
        Map<String, Object> values = new HashMap<>();
        values.put("long_press_top_right", "view_finder");
        values.put("long_press_bottom_right", "speed");
        values.put("playback_speed", "2.5");
        try (Fixture f = new Fixture(values)) {
            f.root.addView(new TouchLayout(f.activity), new FrameLayout.LayoutParams(300, 600));
            f.select();
            f.longPress(2, 0);

            assertEquals("Top-right finder must consume the host long-press runnable", 0,
                    f.press.nativeCalls);
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertEquals("Top-right must not execute bottom-right speed", 0,
                    f.player.delegate.speedChanges);
            assertTrue("Finder must discard the pending UP event so paused video is not tapped",
                    f.touch.discardCurrentEvent);
            MotionEvent up = MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, 250, 50, 0);
            try {
                assertEquals(Boolean.FALSE, f.harness.invoke(
                        TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class), f.touch, up));
            } finally { up.recycle(); }
            assertEquals("A view-finder release must not reach the host touch handler", 0,
                    f.touch.nativeUpCalls);
            assertFalse(f.touch.discardCurrentEvent);

            f.longPress(2, 2);
            assertTrue("Bottom-right must retain its configured speed action",
                    f.player.delegate.speedChanges > 0);
        }
    }

    @Test public void finderSuppressesResumeAndPlayWhenTheCurrentVideoWasPaused() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.longPress(2, 0);
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertNull(f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player));
            assertNull(f.harness.invoke(PlayerManager.class.getMethod("play"), f.player));
            assertEquals(0, f.player.delegate.resumeCalls);
            assertEquals(0, f.player.delegate.playCalls);
            assertFalse(f.player.delegate.playing);

            ViewFinderOverlay overlay = (ViewFinderOverlay) f.activity.getWindow().getDecorView()
                    .findViewWithTag("douai.view_finder");
            overlay.close();
            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertEquals(1, f.player.delegate.resumeCalls);
            assertTrue(f.player.delegate.playing);
        }
    }

    @Test public void finderPreservesPausedPlayerWhenTheEventStateHasNotCaughtUp() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.hook.setPausedStateProvider(ignored -> false);
            f.select();
            f.longPress(2, 0);

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertFalse("A stale playing event must not override a paused current player",
                    f.player.delegate.playing);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pausedFinderGestureBlocksHostResumeBeforeLongPressCallback() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.hook.setPausedStateProvider(ignored -> !f.player.delegate.playing);
            f.select();
            f.touch.nativeDownAction = () -> {
                try {
                    f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player);
                } catch (Throwable failure) { throw new AssertionError(failure); }
            };
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    location[0] + 250, location[1] + 100, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class),
                        f.touch, down);
            } finally { down.recycle(); }
            f.longPress(2, 0);

            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertFalse("The DOWN listener must not resume a paused finder gesture",
                    f.player.delegate.playing);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pausedFinderRegionShortTapStillResumesVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touch.nativeDownAction = () -> {
                try { f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            assertFalse(f.player.delegate.playing);
            f.touchEvent(MotionEvent.ACTION_UP, 2, 0);

            assertTrue("An ordinary tap keeps the host playback action", f.player.delegate.playing);
            assertEquals(1, f.player.delegate.resumeCalls);
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void finderSnapshotsPauseBeforeDispatchTouchListener() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touch.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    try { f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player); }
                    catch (Throwable failure) { throw new AssertionError(failure); }
                }
                return false;
            });
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    location[0] + 250, location[1] + 100, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("dispatchTouchEvent", MotionEvent.class),
                        f.touch, down);
            } finally { down.recycle(); }
            f.longPress(2, 0);
            assertFalse(f.player.delegate.playing);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pausedFinderDispatchPreservesPlayControlBeforeAnyHostTouchListener() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            View playControl = new View(f.activity);
            f.root.addView(playControl, new FrameLayout.LayoutParams(50, 50));
            playControl.setVisibility(View.VISIBLE);
            f.touch.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) playControl.setVisibility(View.GONE);
                return false;
            });
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    location[0] + 250, location[1] + 100, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("dispatchTouchEvent", MotionEvent.class),
                        f.touch, down);
            } finally { down.recycle(); }

            assertEquals("A finder press must preserve the original paused UI", View.VISIBLE,
                    playControl.getVisibility());
            f.longPress(2, 0);
            assertEquals(View.VISIBLE, playControl.getVisibility());
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void restoredPauseWaitsForPlayControlLayoutBeforeFinderSnapshot() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            View playControl = new View(f.activity);
            f.root.addView(playControl, new FrameLayout.LayoutParams(50, 50));
            playControl.setVisibility(View.GONE);
            f.player.delegate.pauseUiAction = () -> new Handler(Looper.getMainLooper()).postDelayed(() -> {
                playControl.setVisibility(View.VISIBLE);
                playControl.layout(100, 200, 150, 250);
            }, 16);
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.harness.invoke(LongPress.class.getMethod("run"), f.press);

            assertNull("The picker must wait for pause controls to render",
                    f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertFalse(ViewFinderOverlay.screenBounds(playControl).isEmpty());
        }
    }

    @Test public void heldFinderGestureOpensFromTimerWithoutDeliveringHostDownOrUp() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                return false;
            });
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(510));
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            f.dispatchTouch(MotionEvent.ACTION_UP, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            assertEquals(0, f.press.nativeCalls);
            assertEquals(0, f.player.delegate.resumeCalls);
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void heldFinderShortTapReplaysTheOriginalTouchStream() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            f.resumeOnDown();
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                return false;
            });
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            assertFalse(f.player.delegate.playing);
            f.dispatchTouch(MotionEvent.ACTION_UP, 250, 100);
            assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), nativeEvents);
            assertTrue(f.player.delegate.playing);
            assertEquals(1, f.player.delegate.resumeCalls);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(510));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void heldFinderMoveHandsTheTouchStreamBackAndCancelDiscardsIt() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                return false;
            });
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            f.dispatchTouch(MotionEvent.ACTION_MOVE, 250, 200);
            assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), nativeEvents);
            f.dispatchTouch(MotionEvent.ACTION_CANCEL, 250, 200);
            nativeEvents.clear();
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            f.dispatchTouch(MotionEvent.ACTION_CANCEL, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(510));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void finderPreservesNativePlayControlHideAndCancelsStaleDeferredOpening() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.panel.playControl = new View(f.activity);
            f.root.addView(f.panel.playControl, new FrameLayout.LayoutParams(50, 50));
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.harness.invoke(Panel.class.getMethod("hidePlayControl"), f.panel);
            assertEquals(0, f.panel.hidePlayControlCalls);
            assertEquals(View.VISIBLE, f.panel.playControl.getVisibility());
            f.player.delegate.playing = true;
            f.harness.invoke(LongPress.class.getMethod("run"), f.press);
            assertEquals(1, f.panel.showPlayControlCalls);
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            f.harness.invoke(Panel.class.getMethod("hidePlayControl"), f.panel);
            assertEquals(1, f.panel.hidePlayControlCalls);
        }
    }

    @Test public void pauseLayoutWaitDoesNotOpenOnAReplacementPlayer() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.harness.invoke(LongPress.class.getMethod("run"), f.press);
            f.panel.currentPlayer = new PlayerManager(new FakeFeedWrapper(1f));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void finderGuardReleasesWhenHolderIsReusedForAnotherVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.longPress(2, 0);
            f.holder.aid = "another-video";

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertTrue(f.player.delegate.playing);
            assertEquals(1, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pendingFinderBlocksNativePanelPlaybackThatBypassesPlayerManager() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            f.harness.invokeOrProceed(Panel.class.getMethod("togglePlayback", Aweme.class,
                    boolean.class, boolean.class), f.panel, f.holder.getAweme(), true, true);
            f.longPress(2, 0);
            assertFalse("The native panel path must not resume before the finder callback",
                    f.player.delegate.playing);
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void finderRestoresPauseIfAnEarlierNativePlaybackPathWasNotIntercepted() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.longPress(2, 0);
            assertFalse("The DOWN pause snapshot must restore the original state", f.player.delegate.playing);
            assertEquals(1, f.player.delegate.pauseCalls);
        }
    }

    @Test public void pendingFinderProtectionExpiresWithoutTouchCompletion() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.resumeOnDown();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(3100));
            assertEquals(0, f.player.delegate.resumeCalls);

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertEquals(1, f.player.delegate.resumeCalls);
            assertTrue(f.player.delegate.playing);
        }
    }

    @Test public void failedPauseRestorationDoesNotLeavePlaybackGuardActive() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.player.delegate.failPause = true;
            f.longPress(2, 0);
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertEquals(1, f.player.delegate.resumeCalls);
        }
    }

    @Test public void multiPointerCancelDoesNotReplayDeferredPlayback() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.resumeOnDown();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = 250;
                coordinates[i].y = 100;
            }
            MotionEvent cancel = MotionEvent.obtain(0, 100, MotionEvent.ACTION_CANCEL, 2,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class),
                        f.touch, cancel);
            } finally { cancel.recycle(); }
            assertEquals(0, f.player.delegate.resumeCalls);
            assertFalse(f.player.delegate.playing);
        }
    }

    @Test public void nativePanelShortTapStillResumesAndPlayingFinderDoesNotPause() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.harness.invoke(Panel.class.getMethod("togglePlayback", Aweme.class,
                    boolean.class, boolean.class), f.panel, f.holder.getAweme(), true, true);
            assertFalse(f.player.delegate.playing);
            f.touchEvent(MotionEvent.ACTION_UP, 2, 0);
            assertTrue(f.player.delegate.playing);

            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.longPress(2, 0);
            assertTrue("An originally playing video keeps playing during inspection", f.player.delegate.playing);
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void cancelledFinderGestureDoesNotReplayPlaybackOnAnotherVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touch.nativeDownAction = () -> {
                try { f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.holder.aid = "next-video";
            f.touchEvent(MotionEvent.ACTION_UP, 2, 0);
            assertEquals(0, f.player.delegate.resumeCalls);
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.touchEvent(MotionEvent.ACTION_CANCEL, 2, 0);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    private static String select(LocalOptions options, String prefix, float x, float y) {
        return FeedGestureToolsHook.selectedAction(options, prefix, x, y, 0, 0, 300, 600);
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FeedRoot root = new FeedRoot(activity);
        final TouchLayout touch = new TouchLayout(activity);
        final LongPress press = new LongPress(touch);
        final Panel panel = new Panel();
        final PlayerManager player = (PlayerManager) panel.currentPlayer;
        final Holder holder = new Holder();
        final Harness harness = new Harness();
        final FeedGestureToolsHook hook;
        int componentToggles;

        Fixture(Map<String, ?> values) throws Exception {
            touch.longPressRunnable = () -> {
                try { harness.invoke(LongPress.class.getMethod("run"), press); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            root.addView(touch, new FrameLayout.LayoutParams(300, 600));
            activity.setContentView(root);
            root.layout(0, 0, 300, 600);
            touch.layout(0, 0, 300, 600);
            holder.w = root;
            holder.A2 = new ItemParams();
            holder.A2.mFeedContext = panel;
            panel.current = holder;
            hook = FeedGestureToolsHook.install(harness.framework, report(), getClass().getClassLoader(),
                    activity, new LocalOptions(values), ignored -> { });
            hook.setComponentVisibility(shown -> componentToggles++, () -> false);
            assertTrue(root.isAttachedToWindow());
            assertTrue(root.isShown());
        }

        void select() throws Throwable {
            harness.invoke(Holder.class.getMethod("selected"), holder);
            ShadowLooper.idleMainLooper();
        }

        void longPress(int column, int row) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            touch.lastRawX = location[0] + column * 100 + 50;
            touch.lastRawY = location[1] + row * 200 + 100;
            harness.invoke(LongPress.class.getMethod("run"), press);
            ShadowLooper.idleMainLooper();
            if (player.delegate.pauseCalls > 0) {
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            }
        }

        void touchEvent(int action, int column, int row) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(0, 100, action,
                    location[0] + column * 100 + 50, location[1] + row * 200 + 100, 0);
            try {
                harness.invoke(TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class), touch, event);
            } finally { event.recycle(); }
        }

        void resumeOnDown() {
            touch.nativeDownAction = () -> {
                try { harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), player); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
        }

        void dispatchTouch(int action, int x, int y) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(0, 100, action, location[0] + x, location[1] + y, 0);
            try {
                harness.invoke(TouchLayout.class.getMethod("dispatchTouchEvent", MotionEvent.class), touch, event);
            } finally { event.recycle(); }
        }

        void doubleTap(int column, int row) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(0, 100, MotionEvent.ACTION_UP,
                    location[0] + column * 100 + 50, location[1] + row * 200 + 100, 0);
            try { harness.invoke(Panel.class.getMethod("doubleTap", MotionEvent.class), panel, event); }
            finally { event.recycle(); }
            ShadowLooper.idleMainLooper();
        }

        @Override public void close() {
            hook.close();
            ShadowLooper.idleMainLooper();
            activity.finish();
        }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> results = new ArrayList<>();
        add(results, "feed_holder_page_selected", Holder.class.getMethod("selected"));
        add(results, "feed_holder_resume", Holder.class.getMethod("resume"));
        add(results, "feed_holder_pause", Holder.class.getMethod("pause"));
        add(results, "feed_aweme_accessor", Holder.class.getMethod("getAweme"));
        addType(results, "feed_holder_main_root", Holder.class);
        addType(results, "adv_gesture_item_params", ItemParams.class);
        addType(results, "adv_gesture_touch_layout", TouchLayout.class);
        add(results, "adv_gesture_long_press", LongPress.class.getMethod("run"));
        add(results, "adv_ui_double_tap", Panel.class.getMethod("doubleTap", MotionEvent.class));
        add(results, "auto_slide_current_holder", Panel.class.getMethod("currentHolder"));
        add(results, "adv_gesture_panel_player", Panel.class.getMethod("player"));
        add(results, "adv_player_set_speed", FakeFeedWrapper.class.getMethod("setSpeed", float.class));
        add(results, "adv_player_get_speed", FakeFeedWrapper.class.getMethod("getSpeed"));
        add(results, "adv_gesture_player_set_speed", IPlayerManager.class.getMethod("setSpeed", float.class));
        add(results, "adv_gesture_player_get_speed", IPlayerInfo.class.getMethod("getSpeed"));
        add(results, "adv_gesture_player_is_playing", IPlayerInfo.class.getMethod("isPlaying"));
        add(results, "adv_gesture_player_resume", IPlayerManager.class.getMethod("resumePlay"));
        add(results, "adv_gesture_player_play", IPlayerManager.class.getMethod("play"));
        add(results, "adv_gesture_player_pause", IPlayerManager.class.getMethod("pausePlay"));
        add(results, "adv_gesture_panel_toggle_play", Panel.class.getMethod("togglePlayback", Aweme.class,
                boolean.class, boolean.class));
        add(results, "adv_gesture_panel_hide_play_control", Panel.class.getMethod("hidePlayControl"));
        add(results, "adv_gesture_panel_show_play_control", Panel.class.getMethod("showPlayControl"));
        return new HostScanReport("fixture", results, false);
    }

    private static void add(List<RuleScanResult> results, String id, Method target) {
        results.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(target.getDeclaringClass()),
                target.getName(), HostTargets.descriptor(target)))));
    }

    private static void addType(List<RuleScanResult> results, String id, Class<?> type) {
        results.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(type), "", ""))));
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { XposedInterface.class }, (proxy, operation, values) -> {
                    if (!operation.getName().equals("hook")) return null;
                    Method target = (Method) values[0];
                    if (Modifier.isAbstract(target.getModifiers())) {
                        throw new IllegalArgumentException("Cannot hook abstract methods: " + target);
                    }
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class }, (builder, action, arguments) -> {
                                if (!action.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) arguments[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class }, (handle, call, args) -> {
                                            if (call.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        Object invokeOrProceed(Method target, Object owner, Object... arguments) throws Throwable {
            if (interceptors.containsKey(target)) return invoke(target, owner, arguments);
            try { return target.invoke(owner, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }

        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Hooker hook = interceptors.get(target);
            assertNotNull("Expected installed hook for " + target, hook);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, operation, values) -> {
                        switch (operation.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArg": return arguments[(Integer) values[0]];
                            case "getArgs": return Arrays.asList(arguments);
                            case "proceed":
                                try { return target.invoke(owner, values == null ? arguments : (Object[]) values[0]); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            default: return null;
                        }
                    });
            return hook.intercept(chain);
        }
    }
}

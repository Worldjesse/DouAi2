package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;

import com.douai.module.config.LocalOptions;
import com.google.gson.annotations.SerializedName;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Dynamic-video selection must preserve sparse image indexes and defer every request until saving. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class OfficialDynamicVideoSelectionTest {
    @org.junit.Before public void requireDrainedDownloadTasks() { DownloadTaskTestIdle.beforeTest(); }

    @After public void stopPendingDownloads() {
        DownloadTaskTestIdle.afterTest();
    }

    @Test public void mixedWorkStartsWithThreeMiuixCategoriesAndDoesNotDownloadDuringNavigation() throws Exception {
        try (MockWebServer server = server(); Screen screen = new Screen(mixed(server))) {
            assertMiuix(latest());
            assertEquals(List.of("图片（5）", "动态视频（3）", "实况（1）"), labels());
            assertNoRequest(server, screen);
            chooseCategory("动态视频");
            assertMiuix(latest());
            assertEquals(List.of("保存当前动态视频", "选择动态视频保存", "保存全部动态视频"), labels());
            assertNoRequest(server, screen);
            choose("选择动态视频保存");
            assertMiuix(latest());
            assertEquals(3, labels().size());
            assertTrue(labels().get(0).contains("2"));
            assertTrue(labels().get(1).contains("4"));
            assertTrue(labels().get(2).contains("5"));
            assertNoRequest(server, screen);
        }
    }

    @Test public void selectedDynamicVideosUseOnlyTheirOwnHostShapedSourcesAndOriginalNumbers() throws Exception {
        try (MockWebServer server = server(); Screen screen = new Screen(mixed(server))) {
            chooseCategory("动态视频");
            choose("选择动态视频保存");
            check(2);
            check(0);
            assertNoRequest(server, screen);
            clickButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals(Set.of("/motion-second.mp4", "/motion-fifth.mp4"), requests(server, 2));
            assertSaved(screen, Map.of("2.mp4", "/motion-second.mp4", "5.mp4", "/motion-fifth.mp4"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void saveAllKeepsEveryDynamicItemAndNeverUsesTheWorkLevelVideo() throws Exception {
        try (MockWebServer server = server(); Screen screen = new Screen(mixed(server))) {
            chooseCategory("动态视频");
            choose("保存全部动态视频");
            assertEquals(Set.of("/motion-second.mp4", "/motion-fourth.mp4", "/motion-fifth.mp4"),
                    requests(server, 3));
            assertSaved(screen, Map.of("2.mp4", "/motion-second.mp4", "4.mp4", "/motion-fourth.mp4",
                    "5.mp4", "/motion-fifth.mp4"));
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void currentDynamicActionUsesTheCurrentOriginalImageIndex() throws Exception {
        try (MockWebServer server = server()) {
            Work work = mixed(server);
            work.photosCurPos = 3;
            try (Screen screen = new Screen(work)) {
                chooseCategory("动态视频");
                choose("保存当前动态视频");
                assertEquals(Set.of("/motion-fourth.mp4"), requests(server, 1));
                assertSaved(screen, Map.of("4.mp4", "/motion-fourth.mp4"));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test public void ordinaryCurrentPageCannotMasqueradeAsCurrentDynamicButOtherVideosRemainSelectable() throws Exception {
        try (MockWebServer server = server()) {
            Work work = mixed(server);
            work.photosCurPos = 2;
            try (Screen screen = new Screen(work)) {
                chooseCategory("动态视频");
                assertFalse(labels().contains("保存当前动态视频"));
                assertEquals(List.of("选择动态视频保存", "保存全部动态视频"), labels());
                choose("选择动态视频保存");
                check(1);
                assertNoRequest(server, screen);
                clickButton(AlertDialog.BUTTON_POSITIVE);
                assertEquals(Set.of("/motion-fourth.mp4"), requests(server, 1));
                assertSaved(screen, Map.of("4.mp4", "/motion-fourth.mp4"));
            }
        }
    }

    @Test public void invalidSelectedSourceIsRecheckedBeforeAnyVideoIsQueued() throws Exception {
        try (MockWebServer server = server()) {
            Work work = mixed(server);
            try (Screen screen = new Screen(work)) {
                chooseCategory("动态视频");
                choose("选择动态视频保存");
                check(0);
                check(2);
                Set<String> before = taskIds();
                // A model can lose its playable source while the picker is visible.
                images(work).get(4).video = null;
                clickButton(AlertDialog.BUTTON_POSITIVE);
                assertNoRequest(server, screen);
                assertEquals("Preflight must prevent a partial selected-video submission", before, taskIds());
            }
        }
    }

    @Test public void emptySelectionStaysOpenAndCancelNeverStartsSelectedVideos() throws Exception {
        try (MockWebServer server = server(); Screen screen = new Screen(mixed(server))) {
            chooseCategory("动态视频");
            choose("选择动态视频保存");
            AlertDialog picker = latest();
            clickButton(AlertDialog.BUTTON_POSITIVE);
            assertTrue(picker.isShowing());
            check(1);
            assertNoRequest(server, screen);
            clickButton(AlertDialog.BUTTON_NEGATIVE);
            assertFalse(picker.isShowing());
            assertNoRequest(server, screen);
        }
    }

    @Test public void returningThroughBothLevelsDiscardsSelectionWithoutStartingARequest() throws Exception {
        try (MockWebServer server = server(); Screen screen = new Screen(mixed(server))) {
            chooseCategory("动态视频");
            choose("选择动态视频保存");
            check(0);
            clickButton(AlertDialog.BUTTON_NEUTRAL);
            assertEquals(List.of("保存当前动态视频", "选择动态视频保存", "保存全部动态视频"), labels());
            assertNoRequest(server, screen);
            clickButton(AlertDialog.BUTTON_NEUTRAL);
            assertEquals(List.of("图片（5）", "动态视频（3）", "实况（1）"), labels());
            assertNoRequest(server, screen);
            clickButton(AlertDialog.BUTTON_NEGATIVE);
            assertNoRequest(server, screen);
        }
    }

    @Test public void switchingAwayBeforeConfirmationInvalidatesThePickerAndDoesNotDownload() throws Exception {
        try (MockWebServer server = server(); Screen screen = new Screen(mixed(server))) {
            chooseCategory("动态视频");
            choose("选择动态视频保存");
            check(2);
            AlertDialog picker = latest();
            screen.valid.set(false);
            clickButton(AlertDialog.BUTTON_POSITIVE);
            assertFalse(picker.isShowing());
            assertNoRequest(server, screen);
        }
    }

    @Test public void singleAnimatedClipHasOneDynamicSaveActionAndNoNativeLiveCategory() throws Exception {
        try (MockWebServer server = server()) {
            Work work = new Work();
            Image image = new Image(server.url("/single.jpg").toString(), 3);
            image.video = new BytevcVideo(server.url("/single-motion.mp4").toString());
            work.images = List.of(image);
            work.video = new BytevcVideo(server.url("/wrong-top-level.mp4").toString());
            try (Screen screen = new Screen(work)) {
                assertEquals(List.of("图片（1）", "动态视频（1）"), labels());
                chooseCategory("动态视频");
                assertEquals(List.of("保存动态视频"), labels());
                assertMiuix(latest());
                assertNoRequest(server, screen);
                choose("保存动态视频");
                assertEquals(Set.of("/single-motion.mp4"), requests(server, 1));
                assertSaved(screen, Map.of("1.mp4", "/single-motion.mp4"));
            }
        }
    }

    private static Work mixed(MockWebServer server) {
        Work work = new Work();
        List<Image> images = new ArrayList<>();
        for (int index = 0; index < 5; index++) images.add(new Image(server.url("/image-" + index + ".jpg").toString(), 0));
        images.get(1).clipType = 1;
        images.get(1).video = new BytevcVideo(server.url("/motion-second.mp4").toString());
        images.get(3).clipType = 5;
        images.get(3).video = new BitrateVideo(server.url("/motion-fourth.mp4").toString());
        images.get(4).clipType = 4;
        images.get(4).video = new LowbrVideo(server.url("/motion-fifth.mp4").toString());
        work.images = images;
        work.photosCurPos = 1;
        work.video = new BytevcVideo(server.url("/wrong-top-level.mp4").toString());
        return work;
    }

    @SuppressWarnings("unchecked")
    private static List<Image> images(Work work) { return (List<Image>) work.images; }

    private static MockWebServer server() throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().addHeader("Content-Type", "video/mp4").setBody(request.getPath());
            }
        });
        server.start();
        return server;
    }

    private static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < latest().getListView().getCount(); index++) {
            labels.add(String.valueOf(latest().getListView().getAdapter().getItem(index)));
        }
        return labels;
    }

    private static void assertMiuix(AlertDialog dialog) {
        assertTrue("Every level must use the Miuix dialog", dialog.getClass().getName().contains("MiuixAlertDialog"));
    }

    private static void chooseCategory(String prefix) {
        String label = labels().stream().filter(item -> item.startsWith(prefix + "（")).findFirst().orElse(null);
        assertNotNull("Missing media category: " + prefix, label);
        choose(label);
    }

    private static void choose(String label) {
        int index = labels().indexOf(label);
        assertTrue("Missing choice: " + label, index >= 0);
        latest().getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }

    private static void check(int index) {
        latest().getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }

    private static void clickButton(int which) {
        assertNotNull(latest().getButton(which));
        latest().getButton(which).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static Set<String> requests(MockWebServer server, int expected) throws Exception {
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < expected; index++) {
            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull("A confirmed download did not request its media", request);
            paths.add(request.getPath());
        }
        assertEquals(expected, paths.size());
        return paths;
    }

    private static void assertNoRequest(MockWebServer server, Screen screen) throws Exception {
        assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
        assertEquals(0, server.getRequestCount());
        assertTrue(screen.documents.names.isEmpty());
    }

    private static void assertSaved(Screen screen, Map<String, String> expected) throws Exception {
        awaitIdle();
        Map<String, String> actual = new HashMap<>();
        for (String id : screen.documents.files.keySet()) {
            assertEquals("root", screen.documents.parents.get(id));
            assertEquals("video/mp4", screen.documents.types.get(id));
            actual.put(screen.documents.names.get(id), Files.readString(screen.documents.files.get(id).toPath()));
        }
        assertEquals(expected, actual);
    }

    private static Set<String> taskIds() {
        return DownloadTasks.snapshots().stream().map(task -> task.id).collect(Collectors.toSet());
    }

    private static void awaitIdle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            ShadowLooper.idleMainLooper();
            if (DownloadTasks.snapshots().stream().noneMatch(task -> "running".equals(task.status)
                    || "queued".equals(task.status) || "cancelling".equals(task.status))) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (System.nanoTime() < deadline);
        fail("Download tasks did not finish");
    }

    public static final class Work {
        public String aweme_id = "dynamic-work", desc = "caption";
        public int photosCurPos;
        public Object images, video;
    }

    public static final class Image {
        @SerializedName("clip_type") public int clipType;
        @SerializedName("url_list") public List<String> urlList;
        public Object video;
        Image(String url, int clip) { urlList = List.of(url); clipType = clip; }
    }

    public static final class Url {
        @SerializedName("url_list") public List<String> urls;
        Url(String url) { urls = List.of(url); }
    }

    public static final class BytevcVideo {
        @SerializedName("play_addr_bytevc1") public Url T;
        BytevcVideo(String url) { T = new Url(url); }
    }

    public static final class LowbrVideo {
        @SerializedName("play_addr_lowbr") public Url O;
        LowbrVideo(String url) { O = new Url(url); }
    }

    public static final class BitrateVideo {
        @SerializedName("bit_rate") public List<Bitrate> y;
        BitrateVideo(String url) { y = List.of(new Bitrate(url)); }
    }

    public static final class Bitrate {
        @SerializedName("play_addr_265") public Url playAddr265;
        Bitrate(String url) { playAddr265 = new Url(url); }
    }

    private static final class Screen implements AutoCloseable {
        final ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        final DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final OfficialDownloadDialog session;
        Screen(Work work) {
            LocalOptions options = new LocalOptions(Map.of("download_method", "official",
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE,
                    "download_custom_name", true, "download_name_template", "#index#"));
            session = new OfficialDownloadDialog(activity.get(), work, options, ignored -> { }, valid::get);
            session.show();
            ShadowLooper.idleMainLooper();
        }
        @Override public void close() {
            session.close();
            activity.pause().stop().destroy();
        }
    }
}

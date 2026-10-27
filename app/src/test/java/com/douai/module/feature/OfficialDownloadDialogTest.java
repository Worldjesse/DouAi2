package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;

import com.douai.module.config.LocalOptions;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class OfficialDownloadDialogTest {
    @org.junit.Before public void requireDrainedDownloadTasks() { DownloadTaskTestIdle.beforeTest(); }

    public static final class Work {
        public String aweme_id = "work";
        public int photosCurPos;
        public List<Image> images;
        public DownloadActionHookTest.VideoFixture video;
        public Music music;
    }

    public static final class Image {
        public List<String> url_list;
        public List<String> watermark_free_download_url_list;
        public List<String> download_url_list;
        public int clip_type;
        public DownloadActionHookTest.VideoFixture video;
        Image(String url) { url_list = List.of(url); }
    }

    public static final class Music {
        public DownloadActionHookTest.UrlFixture play_url;
    }

    @After public void stopPendingDownloads() {
        DownloadTaskTestIdle.afterTest();
    }

    @Test public void openingAndCancellingVideoChoicesNeverStartsARequest() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            Work work = video(server);
            try (OfficialDownloadDialog session = screen.open(work)) {
                assertEquals(2, latest().getListView().getCount());
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                assertTrue(screen.documents.names.isEmpty());
            }
        }
    }

    @Test public void choosingAudioSavesOnlyAudioToTheConfiguredTree() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(media("audio/mpeg", "audio-body"));
            try (OfficialDownloadDialog session = screen.open(video(server))) {
                choose("保存音频（原声/配乐）");
                assertEquals("/sound.mp3", request(server).getPath());
                awaitSaved(screen, 1);
                String id = screen.documents.names.keySet().iterator().next();
                assertEquals("audio/mpeg", screen.documents.types.get(id));
                assertEquals("root", screen.documents.parents.get(id));
                assertEquals("audio-body", Files.readString(screen.documents.files.get(id).toPath()));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test public void currentPictureUsesTheOriginalPositionAndCleanSource() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(media("image/png", "picture-three"));
            Work work = gallery(server);
            work.photosCurPos = 2;
            work.images.get(2).watermark_free_download_url_list = List.of(server.url("/clean-third.png").toString());
            try (OfficialDownloadDialog session = screen.open(work)) {
                choose("保存当前图片");
                assertEquals("/clean-third.png", request(server).getPath());
                awaitSaved(screen, 1);
                assertEquals(Set.of("3.png"), new HashSet<>(screen.documents.names.values()));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test public void selectedPicturesWaitForConfirmationAndKeepTheirSourceNumbers() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(media("image/png", "first-body"));
            server.enqueue(media("image/png", "third-body"));
            try (OfficialDownloadDialog session = screen.open(gallery(server))) {
                choose("选择图片保存");
                check(2);
                check(0);
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertEquals(Set.of("/first.png", "/third.png"),
                        Set.of(request(server).getPath(), request(server).getPath()));
                awaitSaved(screen, 2);
                assertEquals(Set.of("1.png", "3.png"), new HashSet<>(screen.documents.names.values()));
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    @Test public void selectingNoPicturesAndCancellingThePickerCreatesNoDownload() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            try (OfficialDownloadDialog session = screen.open(gallery(server))) {
                choose("选择图片保存");
                AlertDialog picker = latest();
                picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(picker.isShowing());
                check(1);
                picker.cancel();
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                assertTrue(screen.documents.names.isEmpty());
            }
        }
    }

    @Test public void savingAllPicturesQueuesEachSourceOnceWithItsOriginalNumber() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            for (int i = 0; i < 3; i++) server.enqueue(media("image/png", "image-body"));
            try (OfficialDownloadDialog session = screen.open(gallery(server))) {
                choose("保存全部图片");
                Set<String> paths = new HashSet<>();
                for (int i = 0; i < 3; i++) paths.add(request(server).getPath());
                assertEquals(Set.of("/first.png", "/second.png", "/third.png"), paths);
                awaitSaved(screen, 3);
                assertEquals(Set.of("1.png", "2.png", "3.png"), new HashSet<>(screen.documents.names.values()));
                assertEquals(3, server.getRequestCount());
            }
        }
    }

    @Test public void invalidSourceInTheSelectionDoesNotStartAPartialGallerySave() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            Work work = gallery(server);
            work.images.get(1).url_list = List.of("file:///private/image.png");
            try (OfficialDownloadDialog session = screen.open(work)) {
                choose("保存全部图片");
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                assertTrue(screen.documents.names.isEmpty());
            }
        }
    }

    @Test public void invalidCurrentPositionDoesNotSilentlySaveTheFirstImage() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            Work work = gallery(server);
            work.photosCurPos = 99;
            try (OfficialDownloadDialog session = screen.open(work)) {
                OfficialDownloadTestUi.openGroup("图片");
                assertEquals(-1, indexOf("保存当前图片"));
                assertEquals(2, latest().getListView().getCount());
                latest().cancel();
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                assertTrue(screen.documents.names.isEmpty());
            }
        }
    }

    @Test public void losingTheCurrentWorkBeforePickerConfirmationPreventsDownload() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            try (OfficialDownloadDialog session = screen.open(gallery(server))) {
                choose("选择图片保存");
                check(1);
                AlertDialog picker = latest();
                screen.valid.set(false);
                picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertFalse(picker.isShowing());
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                assertTrue(screen.documents.names.isEmpty());
            }
        }
    }

    @Test public void highestQualityChoiceShowsMetadataAndDownloadsSelectedRendition() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(media("video/mp4", "highest-body"));
            Work work = video(server);
            work.video.bit_rate = List.of(
                    new DownloadActionHookTest.BitRateFixture(900_000L, 1280, 720, 8_000_000L,
                            new DownloadActionHookTest.UrlFixture(server.url("/720p.mp4").toString())),
                    new DownloadActionHookTest.BitRateFixture(4_800_000L, 1920, 1080, 42_000_000L,
                            new DownloadActionHookTest.UrlFixture(server.url("/1080p.mp4").toString())));
            try (OfficialDownloadDialog session = screen.open(work)) {
                OfficialDownloadTestUi.openGroup("视频");
                String highest = "保存最高画质（平台源 · 1920×1080 · 4.8 Mbps · H.264）";
                assertTrue(indexOf(highest) >= 0);
                assertTrue(indexOf("保存兼容播放源视频") >= 0);
                choose(highest);
                assertEquals("/1080p.mp4", request(server).getPath());
                awaitSaved(screen, 1);
            }
        }
    }

    @Test public void disablingHighestQualityRestoresCompatiblePlaybackChoice() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen(Map.of(
                LocalOptions.PREFER_HIGHEST_QUALITY, false))) {
            server.start();
            server.enqueue(media("video/mp4", "compatible-body"));
            Work work = video(server);
            work.video.bit_rate = List.of(new DownloadActionHookTest.BitRateFixture(
                    4_800_000L, 1920, 1080, 42_000_000L,
                    new DownloadActionHookTest.UrlFixture(server.url("/1080p.mp4").toString())));
            try (OfficialDownloadDialog session = screen.open(work)) {
                OfficialDownloadTestUi.openGroup("视频");
                assertEquals(-1, indexOf("保存最高画质（平台源 · 1920×1080 · 4.8 Mbps · H.264）"));
                assertEquals(-1, indexOf("保存兼容播放源视频"));
                choose("保存视频");
                assertEquals("/video.mp4", request(server).getPath());
                awaitSaved(screen, 1);
            }
        }
    }

    @Test public void closePreventsAnOldDialogCallbackFromSubmittingARequest() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            OfficialDownloadDialog session = screen.open(video(server));
            OfficialDownloadTestUi.openGroup("视频");
            AlertDialog previous = latest();
            int which = indexOf("保存视频");
            session.close();
            previous.getListView().performItemClick(null, which, which);
            assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
            assertTrue(screen.documents.names.isEmpty());
        }
    }

    @Test public void livePhotoDynamicVideoChoiceSavesOnlyItsImageLevelVideo() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(media("video/mp4", "motion-body"));
            Work work = new Work();
            Image image = new Image(server.url("/cover.png").toString());
            image.clip_type = 5;
            image.video = new DownloadActionHookTest.VideoFixture();
            image.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/motion.mp4").toString());
            work.images = List.of(image);
            try (OfficialDownloadDialog session = screen.open(work)) {
                assertEquals(3, latest().getListView().getCount());
                assertTrue(OfficialDownloadTestUi.hasGroup("图片"));
                assertTrue(OfficialDownloadTestUi.hasGroup("实况"));
                assertTrue(OfficialDownloadTestUi.hasGroup("动态视频"));
                assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
                choose("保存动态视频");
                assertEquals("/motion.mp4", request(server).getPath());
                awaitSaved(screen, 1);
                assertEquals(Set.of("1.mp4"), new HashSet<>(screen.documents.names.values()));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test public void thirdPartyConfigurationCannotStartOfficialSavingThroughThisDialog() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            OfficialDownloadDialog session = new OfficialDownloadDialog(screen.activity.get(), video(server),
                    new LocalOptions(Map.of("download_method", "third_party")), ignored -> { }, () -> true);
            session.show();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertNull(server.takeRequest(120, TimeUnit.MILLISECONDS));
            assertTrue(screen.documents.names.isEmpty());
            session.close();
        }
    }

    @Test public void mixedGalleryCanSaveOnlyTheCurrentLiveItemsVideo() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(media("video/mp4", "second-motion"));
            Work work = gallery(server);
            work.photosCurPos = 1;
            Image image = work.images.get(1);
            image.clip_type = 5;
            image.video = new DownloadActionHookTest.VideoFixture();
            image.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/second-motion.mp4").toString());
            try (OfficialDownloadDialog session = screen.open(work)) {
                OfficialDownloadTestUi.openGroup("图片");
                assertTrue(indexOf("保存当前图片") >= 0);
                assertTrue(indexOf("选择图片保存") >= 0);
                assertTrue(indexOf("保存全部图片") >= 0);
                OfficialDownloadTestUi.back();
                OfficialDownloadTestUi.openGroup("实况");
                assertTrue(indexOf("保存当前实况") >= 0);
                OfficialDownloadTestUi.back();
                choose("保存当前动态视频");
                assertEquals("/second-motion.mp4", request(server).getPath());
                awaitSaved(screen, 1);
                assertEquals(Set.of("2.mp4"), new HashSet<>(screen.documents.names.values()));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    private static Work video(MockWebServer server) {
        Work work = new Work();
        work.video = new DownloadActionHookTest.VideoFixture();
        work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/video.mp4").toString());
        work.music = new Music();
        work.music.play_url = new DownloadActionHookTest.UrlFixture(server.url("/sound.mp3").toString());
        return work;
    }

    private static Work gallery(MockWebServer server) {
        Work work = new Work();
        work.images = Arrays.asList(new Image(server.url("/first.png").toString()),
                new Image(server.url("/second.png").toString()), new Image(server.url("/third.png").toString()));
        return work;
    }

    private static MockResponse media(String type, String body) {
        return new MockResponse().addHeader("Content-Type", type).setBody(body);
    }

    private static RecordedRequest request(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        return dialog;
    }

    private static int indexOf(String label) {
        for (int i = 0; i < latest().getListView().getCount(); i++) {
            if (label.equals(latest().getListView().getAdapter().getItem(i))) return i;
        }
        return -1;
    }

    private static void choose(String label) {
        OfficialDownloadTestUi.chooseAction(label);
    }

    private static void check(int which) {
        latest().getListView().performItemClick(null, which, which);
    }

    private static void awaitSaved(Screen screen, int expected) {
        awaitIdle();
        assertEquals(expected, screen.documents.files.size());
        assertEquals(expected, screen.documents.names.size());
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

    private static final class Screen implements AutoCloseable {
        final ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        final DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final LocalOptions options;
        Screen() { this(Map.of()); }
        Screen(Map<String, ?> overrides) {
            Map<String, Object> values = new HashMap<>();
            values.put("download_method", "official");
            values.put(LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE);
            values.put("download_custom_name", true);
            values.put("download_name_template", "#index#");
            values.putAll(overrides);
            options = new LocalOptions(values);
        }
        OfficialDownloadDialog open(Work work) {
            OfficialDownloadDialog session = new OfficialDownloadDialog(activity.get(), work, options,
                    ignored -> { }, valid::get);
            session.show();
            return session;
        }
        @Override public void close() { activity.pause().stop().destroy(); }
    }
}

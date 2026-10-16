package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;

import com.douai.module.config.LocalOptions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class OfficialGalleryLiveSelectionTest {
    @Test public void galleryOffersLiveChoicesWhenTheCurrentPictureIsOrdinary() {
        Work work = work(false, true, false, true);
        work.photosCurPos = 0;
        try (Screen screen = new Screen(work)) {
            assertEquals(List.of("图片（4）", "动态视频（2）", "实况（2）"), labels());
            OfficialDownloadTestUi.openGroup("实况");
            assertTrue(labels().containsAll(List.of("选择实况保存", "保存全部实况")));
            assertFalse(labels().contains("保存当前实况"));
            assertTrue(screen.started.isEmpty());
        }
    }

    @Test public void singleLivePhotoWithUnreadableMotionStillOffersBothLiveActions() {
        Work work = work(true);
        work.images.get(0).video = null;
        Set<String> tasks = taskIds();
        try (Screen screen = new Screen(work)) {
            assertEquals(List.of("图片（1）", "动态视频（1）", "实况（1）"), labels());
            choose("保存实况");
            assertEquals("所选实况缺少可下载的图片或动态视频", ShadowToast.getTextOfLatestToast());
            assertTrue(screen.started.isEmpty());
            assertEquals(tasks, taskIds());
        }
    }

    @Test public void unreadableDynamicVideoReportsMissingSourceWithoutCreatingTask() {
        Work work = work(true);
        work.images.get(0).video = null;
        Set<String> tasks = taskIds();
        try (Screen screen = new Screen(work)) {
            choose("保存动态视频");
            assertEquals("所选动态视频缺少可下载的媒体地址", ShadowToast.getTextOfLatestToast());
            assertTrue(screen.started.isEmpty());
            assertEquals(tasks, taskIds());
        }
    }

    @Test public void currentLiveItemWithoutSourcesKeepsCurrentAndGalleryLiveChoices() {
        Work work = work(false, true, false);
        work.photosCurPos = 1;
        work.images.get(1).video = null;
        work.images.get(1).url_list = List.of();
        try (Screen screen = new Screen(work)) {
            assertTrue(OfficialDownloadTestUi.hasGroup("动态视频"));
            OfficialDownloadTestUi.openGroup("实况");
            assertTrue(labels().containsAll(List.of("保存当前实况", "选择实况保存", "保存全部实况")));
            choose("保存当前实况");
            assertEquals("所选实况缺少可下载的图片或动态视频", ShadowToast.getTextOfLatestToast());
            assertTrue(screen.started.isEmpty());
        }
    }

    @Test public void livePickerQueuesOnlySelectedLiveItemsWithTheirOriginalNumbers() {
        Work work = work(false, true, false, true, true);
        work.images.get(3).video = null;
        try (Screen screen = new Screen(work)) {
            choose("选择实况保存");
            assertEquals(List.of("第 2 张实况", "第 4 张实况", "第 5 张实况"), labels());
            check(2);
            check(0);
            assertTrue(screen.started.isEmpty());
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(List.of(1, 4), screen.started);
            assertEquals(List.of("2.jpg", "5.jpg"), screen.names);
        }
    }

    @Test public void galleryWithOnlyOneLiveItemStillOffersThePickerAndAllLiveAction() {
        Work work = work(false, true, false);
        try (Screen screen = new Screen(work)) {
            OfficialDownloadTestUi.openGroup("实况");
            assertTrue(labels().containsAll(List.of("选择实况保存", "保存全部实况")));
            choose("选择实况保存");
            assertEquals(List.of("第 2 张实况"), labels());
            check(0);
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(List.of(1), screen.started);
            assertEquals(List.of("2.jpg"), screen.names);
        }
        try (Screen screen = new Screen(work)) {
            choose("保存全部实况");
            assertEquals(List.of(1), screen.started);
        }
    }

    @Test public void invalidSourceInAllLiveItemsPreventsEverySubmission() {
        Work work = work(false, true, false, true);
        work.images.get(3).video = null;
        Set<String> tasks = taskIds();
        try (Screen screen = new Screen(work)) {
            choose("保存全部实况");
            assertTrue("A later invalid source must be found before the first task starts", screen.started.isEmpty());
            assertEquals("所选实况缺少可下载的图片或动态视频", ShadowToast.getTextOfLatestToast());
            assertEquals(tasks, taskIds());
        }
    }

    @Test public void livePickerRequiresASelectionAndRechecksCurrentWorkBeforeSaving() {
        Work work = work(false, true, true);
        try (Screen screen = new Screen(work)) {
            choose("选择实况保存");
            AlertDialog picker = latest();
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(picker.isShowing());
            assertEquals("请先选择实况", ShadowToast.getTextOfLatestToast());
            assertTrue(screen.started.isEmpty());
            check(0);
            screen.valid.set(false);
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(picker.isShowing());
            assertTrue(screen.started.isEmpty());
        }
    }

    @Test public void rejectedLiveSubmissionIsReportedToTheUser() {
        try (Screen screen = new Screen(work(false, true))) {
            screen.accept = false;
            choose("保存全部实况");
            assertEquals(List.of(1), screen.started);
            assertEquals("实况未加入下载，请检查下载任务", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void readableAnimatedImageVideoDoesNotAddARealLivePhotoOption() {
        for (int clip : new int[]{1, 3, 4}) {
            Work work = work(false, true);
            work.photosCurPos = 1;
            work.images.get(1).clip_type = clip;
            try (Screen screen = new Screen(work)) {
                assertFalse(OfficialDownloadTestUi.hasGroup("实况"));
                OfficialDownloadTestUi.openGroup("动态视频");
                assertTrue(labels().contains("保存当前动态视频"));
                assertFalse(labels().contains("保存当前实况"));
                assertFalse(labels().contains("选择实况保存"));
                assertFalse(labels().contains("保存全部实况"));
                assertTrue(screen.started.isEmpty());
            }
        }
    }

    @Test public void diagnosticsContainOnlyBoundedMetadataAndFixedChoiceLabels() {
        Work work = new Work();
        work.desc = "private-work-description";
        for (int index = 0; index < 40; index++) work.images.add(new Image(index % 2 == 0, index));
        work.photosCurPos = 7;
        try (Screen screen = new Screen(work)) {
            assertEquals(1, screen.logs.size());
            String diagnostic = screen.logs.get(0);
            assertTrue(diagnostic.contains("images=40"));
            assertTrue(diagnostic.contains("is_live_photo=0"));
            assertTrue(diagnostic.contains("currentIndex=7"));
            assertTrue(diagnostic.contains("motionCount=20"));
            assertTrue(diagnostic.contains("liveCount=20"));
            assertTrue(diagnostic.contains("实况（20）"));
            assertTrue(diagnostic.contains("host=" + screen.activity.get().getPackageName()));
            assertTrue(diagnostic.contains("versionName=40.6.0"));
            assertFalse(diagnostic.contains("media.test"));
            assertFalse(diagnostic.contains("https://"));
            assertFalse(diagnostic.contains("private-token"));
            assertFalse(diagnostic.contains("private-work-description"));
            String clips = diagnostic.substring(diagnostic.indexOf("clipTypes=[") + 11);
            clips = clips.substring(0, clips.indexOf(']'));
            assertEquals(32, clips.split(",").length);
            String livePhotoTypes = diagnostic.substring(diagnostic.indexOf("livePhotoTypes=[") + 16);
            livePhotoTypes = livePhotoTypes.substring(0, livePhotoTypes.indexOf(']'));
            assertEquals(32, livePhotoTypes.split(",").length);
        }
    }

    private static Work work(boolean... live) {
        Work work = new Work();
        for (int index = 0; index < live.length; index++) work.images.add(new Image(live[index], index));
        return work;
    }

    private static Set<String> taskIds() {
        return DownloadTasks.snapshots().stream().map(task -> task.id).collect(Collectors.toSet());
    }

    private static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        return dialog;
    }

    private static List<String> labels() {
        ArrayList<String> result = new ArrayList<>();
        for (int index = 0; index < latest().getListView().getCount(); index++) {
            result.add(String.valueOf(latest().getListView().getAdapter().getItem(index)));
        }
        return result;
    }

    private static void choose(String label) {
        OfficialDownloadTestUi.chooseAction(label);
    }

    private static void check(int index) {
        latest().getListView().performItemClick(null, index, index);
    }

    public static final class Work {
        public String aweme_id = "work";
        public String desc = "caption";
        public int photosCurPos;
        public int is_live_photo;
        public List<Image> images = new ArrayList<>();
    }

    public static final class Image {
        public int clip_type;
        public int live_photo_type;
        public List<String> url_list;
        public Video video;
        Image(boolean live, int index) {
            clip_type = live ? 5 : 0;
            url_list = List.of("https://media.test/image-" + index + ".jpg?token=private-token");
            if (live) {
                video = new Video();
                video.play_addr = new Url("https://media.test/motion-" + index + ".mp4?token=private-token");
            }
        }
    }

    public static final class Video { public Url play_addr; }
    public static final class Url {
        public List<String> url_list;
        Url(String url) { url_list = List.of(url); }
    }

    private static final class Screen implements AutoCloseable {
        final ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final List<Integer> started = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final OfficialDownloadDialog session;
        boolean accept = true;
        Screen(Work work) {
            PackageInfo hostInfo = new PackageInfo();
            hostInfo.packageName = activity.get().getPackageName();
            hostInfo.versionName = "40.6.0";
            org.robolectric.shadows.ShadowPackageManager packages =
                    org.robolectric.shadow.api.Shadow.extract(activity.get().getPackageManager());
            packages.installPackage(hostInfo);
            LocalOptions options = new LocalOptions(Map.of("download_method", "official",
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE,
                    "download_custom_name", true, "download_name_template", "#index#"));
            session = new OfficialDownloadDialog(activity.get(), work, options, logs::add, valid::get,
                    (context, aweme, index, settings, logger) -> {
                        started.add(index);
                        names.add(OfficialLivePhotoDownload.plan(aweme, index, settings).name);
                        return accept;
                    });
            session.show();
        }
        @Override public void close() {
            session.close();
            activity.pause().stop().destroy();
        }
    }
}

package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class ThirdPartyDownloadDialogTest {
    @Test public void categoriesContainOnlyConfiguredAssetsAndNeverMetadataOrHostCurrentActions() throws Exception {
        try (Screen screen = new Screen(planFixture(3, 2))) {
            assertEquals(Arrays.asList("图片（3）", "视频（1）", "动态视频（2）", "音频（1）", "文件（1）"), labels());
            click(0);
            assertEquals(Arrays.asList("选择图片保存", "保存全部图片"), labels());
            assertTrue(labels().stream().noneMatch(label -> label.contains("当前")));
            click(0);
            assertEquals(Arrays.asList("data.images · 第 1 项", "data.images · 第 3 项", "data.images · 第 4 项"), labels());
            assertEquals(0, screen.saves.get());
            assertEquals(0, screen.cancels.get());
        }
    }

    @Test public void sparseSelectionRetainsOriginalNamesFieldPositionsAndMetadataAndClosesBeforeSave() throws Exception {
        ThirdPartyDownloadPlan original = planFixture(3, 2);
        try (Screen screen = new Screen(original)) {
            click(0);
            click(0);
            AlertDialog picker = latest();
            click(2);
            click(0);
            assertNull(screen.saved.get());
            button(AlertDialog.BUTTON_POSITIVE);
            ThirdPartyDownloadPlan selected = screen.saved.get();
            assertNotNull(selected);
            assertFalse(picker.isShowing());
            assertTrue(screen.closedWhenSaved.get());
            assertEquals(1, screen.saves.get());
            assertEquals(0, screen.cancels.get());
            assertEquals(original.baseName, selected.baseName);
            assertEquals(original.metadata, selected.metadata);
            assertEquals(2, selected.assets.size());
            assertSame(original.assets.get(0), selected.assets.get(0));
            assertSame(original.assets.get(2), selected.assets.get(1));
            assertEquals(original.assets.get(2).name, selected.assets.get(1).name);
            assertEquals(3, selected.assets.get(1).sourceIndex);
            screen.dialog.close();
            picker.dismiss();
            assertEquals(1, screen.saves.get());
            assertEquals(0, screen.cancels.get());
        }
    }

    @Test public void returnFromPickerAndCategoryDoesNotSaveOrCancelAndUserCancelFiresOnce() throws Exception {
        try (Screen screen = new Screen(planFixture(3, 2))) {
            click(0);
            click(0);
            click(1);
            button(AlertDialog.BUTTON_NEUTRAL);
            assertEquals(Arrays.asList("选择图片保存", "保存全部图片"), labels());
            button(AlertDialog.BUTTON_NEUTRAL);
            assertEquals(5, labels().size());
            assertEquals(0, screen.saves.get());
            assertEquals(0, screen.cancels.get());
            AlertDialog top = latest();
            button(AlertDialog.BUTTON_NEGATIVE);
            assertFalse(top.isShowing());
            top.cancel();
            top.dismiss();
            screen.dialog.close();
            assertEquals(1, screen.cancels.get());
            assertEquals(0, screen.saves.get());
        }
    }

    @Test public void saveAllInOneCategoryDoesNotAddOtherTypes() throws Exception {
        ThirdPartyDownloadPlan original = planFixture(3, 2);
        try (Screen screen = new Screen(original)) {
            click(2);
            assertEquals(Arrays.asList("选择动态视频保存", "保存全部动态视频"), labels());
            click(1);
            ThirdPartyDownloadPlan selected = screen.saved.get();
            assertNotNull(selected);
            assertEquals(2, selected.assets.size());
            assertTrue(selected.assets.stream().allMatch(asset -> asset.category() == ThirdPartyDownloadPlan.Category.MOTION));
            assertEquals(original.metadata, selected.metadata);
            assertEquals(1, screen.saves.get());
            assertEquals(0, screen.cancels.get());
        }
    }

    @Test public void aSingleVideoGetsAnExplicitSaveActionWithoutInventingACurrentIndex() throws Exception {
        try (Screen screen = new Screen(planFixture(3, 2))) {
            click(1);
            assertEquals(List.of("保存视频 · data.video · 第 1 项"), labels());
            click(0);
            assertEquals(1, screen.saved.get().assets.size());
            assertEquals(ThirdPartyDownloadPlan.Category.VIDEO, screen.saved.get().assets.get(0).category());
            assertEquals(0, screen.cancels.get());
        }
    }

    @Test public void emptySelectionKeepsThePickerOpenAndCancelDoesNotSave() throws Exception {
        try (Screen screen = new Screen(planFixture(3, 2))) {
            click(0);
            click(0);
            AlertDialog picker = latest();
            button(AlertDialog.BUTTON_POSITIVE);
            assertTrue(picker.isShowing());
            assertEquals(0, screen.saves.get());
            button(AlertDialog.BUTTON_NEGATIVE);
            assertFalse(picker.isShowing());
            assertEquals(1, screen.cancels.get());
            assertNull(screen.saved.get());
        }
    }

    @Test public void systemDismissCancelsExactlyOnceWhileProgrammaticCloseNeverCancels() throws Exception {
        try (Screen screen = new Screen(planFixture(3, 2))) {
            latest().dismiss();
            ShadowLooper.idleMainLooper();
            latest().dismiss();
            screen.dialog.close();
            assertEquals(1, screen.cancels.get());
            assertEquals(0, screen.saves.get());
        }
        try (Screen screen = new Screen(planFixture(3, 2))) {
            AlertDialog old = latest();
            screen.dialog.close();
            screen.dialog.close();
            old.getListView().performItemClick(null, 0, 0);
            old.cancel();
            ShadowLooper.idleMainLooper();
            assertEquals(0, screen.cancels.get());
            assertEquals(0, screen.saves.get());
        }
    }

    @Test public void validityLossBeforeConfirmationClosesAndCancelsInsteadOfSaving() throws Exception {
        try (Screen screen = new Screen(planFixture(3, 2))) {
            click(0);
            click(0);
            click(1);
            AlertDialog picker = latest();
            screen.valid.set(false);
            button(AlertDialog.BUTTON_POSITIVE);
            assertFalse(picker.isShowing());
            assertEquals(1, screen.cancels.get());
            assertEquals(0, screen.saves.get());
            picker.cancel();
            assertEquals(1, screen.cancels.get());
        }
    }

    static ThirdPartyDownloadPlan planFixture(int images, int motions) throws Exception {
        JSONObject data = new JSONObject().put("id", "selection-work").put("caption", "metadata stays with the selection");
        JSONArray imageUrls = new JSONArray();
        for (int i = 0; i < images; i++) {
            imageUrls.put("https://media.example/image-" + (i + 1) + ".png");
            if (i == 0) imageUrls.put("https://media.example/image-1.png");
        }
        JSONArray motionUrls = new JSONArray();
        for (int i = 0; i < motions; i++) motionUrls.put("https://media.example/motion-" + (i + 1) + ".mp4");
        data.put("images", imageUrls).put("video", "https://media.example/video.mp4")
                .put("live_video", motionUrls).put("audio", "https://media.example/audio.mp3")
                .put("file", "https://media.example/asset.bin")
                .put("unselected_images", new JSONArray().put("https://media.example/never-selected.jpg"));
        ThirdPartyDownloadSettings settings = new ThirdPartyDownloadSettings(new LocalOptions(Map.of(
                ThirdPartyDownloadSettings.DOWNLOAD_FIELDS,
                "[\"data.images\",\"data.video\",\"data.live_video\",\"data.audio\",\"data.file\"]",
                ThirdPartyDownloadSettings.NAMING_FIELDS, "[\"data.id\"]")));
        return ThirdPartyDownloadPlan.create(ThirdPartyResponse.parse(new JSONObject().put("data", data).toString()), settings, 100);
    }

    private static AlertDialog latest() { return (AlertDialog) ShadowDialog.getLatestDialog(); }

    private static List<String> labels() {
        AlertDialog dialog = latest();
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < dialog.getListView().getCount(); i++) labels.add(dialog.getListView().getAdapter().getItem(i).toString());
        return labels;
    }

    private static void click(int index) {
        latest().getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }

    private static void button(int which) {
        latest().getButton(which).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static final class Screen implements AutoCloseable {
        final ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        final AtomicReference<ThirdPartyDownloadPlan> saved = new AtomicReference<>();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final AtomicBoolean closedWhenSaved = new AtomicBoolean();
        final ThirdPartyDownloadDialog dialog;
        Screen(ThirdPartyDownloadPlan plan) {
            dialog = new ThirdPartyDownloadDialog(activity.get(), plan, selected -> {
                closedWhenSaved.set(!latest().isShowing());
                saved.set(selected);
                saves.incrementAndGet();
            }, cancels::incrementAndGet, valid::get);
            dialog.show();
            ShadowLooper.idleMainLooper();
        }
        @Override public void close() {
            dialog.close();
            ShadowLooper.idleMainLooper();
            activity.pause().stop().destroy();
        }
    }
}

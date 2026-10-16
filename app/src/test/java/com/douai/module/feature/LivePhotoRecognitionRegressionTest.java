package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;

import com.douai.module.config.LocalOptions;
import com.google.gson.annotations.SerializedName;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Host-shaped media sources must not make an explicit LivePhoto appear as a static-only image. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class LivePhotoRecognitionRegressionTest {
    static final class Work {
        List<Image> images = Collections.singletonList(new Image());
        @SerializedName("is_live_photo") int isLivePhoto;
    }
    static final class Image {
        @SerializedName("clip_type") int clipType = 5;
        @SerializedName("url_list") List<String> urlList = List.of("https://media.example/live.jpg");
        Object video;
    }
    static final class BytevcVideo {
        @SerializedName("play_addr_bytevc1") Url T = new Url();
    }
    static final class LowbrVideo {
        @SerializedName("play_addr_lowbr") Url O = new Url();
    }
    static final class BitrateVideo {
        @SerializedName("bit_rate") List<Bitrate> y = List.of(new Bitrate());
    }
    static final class Bitrate {
        @SerializedName("play_addr_265") Url playAddr265 = new Url();
        @SerializedName("play_addr_bytevc1") Url playAddrBytevc1;
    }
    static class Url {
        @SerializedName("url_list") List<String> urls = List.of("https://media.example/motion.mp4");
    }

    @Test public void explicitLivePhotoWithHostBytevc1SourceOffersLiveSaving() {
        Work work = new Work();
        work.images.get(0).video = new BytevcVideo();
        assertLiveChoicesAndPlan(work);
    }

    @Test public void lowBitrateAndBitrateListSourcesReachBothMenuAndEncoderPlan() {
        Work work = new Work();
        work.images.get(0).video = new LowbrVideo();
        assertLiveChoicesAndPlan(work);
        work.images.get(0).video = new BitrateVideo();
        assertLiveChoicesAndPlan(work);
        BitrateVideo bytevcRates = new BitrateVideo();
        bytevcRates.y.get(0).playAddr265 = null;
        bytevcRates.y.get(0).playAddrBytevc1 = new Url();
        work.images.get(0).video = bytevcRates;
        assertLiveChoicesAndPlan(work);
    }

    @Test public void workLevelLiveMarkerAndMotionSourceAlsoOfferLiveSaving() {
        Work work = new Work();
        work.isLivePhoto = 1;
        work.images.get(0).clipType = 0;
        work.images.get(0).video = new BytevcVideo();
        assertLiveChoicesAndPlan(work);
    }

    @Test public void workLevelMarkerDoesNotConvertStaticPagesIntoLivePhotos() {
        Work work = new Work();
        work.isLivePhoto = 1;
        work.images.get(0).clipType = 0;
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
             OfficialDownloadDialog session = new OfficialDownloadDialog(activity.get(), work,
                     new LocalOptions(Collections.emptyMap()), ignored -> { }, () -> true)) {
            session.show();
            assertFalse(OfficialDownloadTestUi.hasGroup("实况"));
        }
    }

    @Test public void alternateSourceKeysExistOnTheOriginalHostModels() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", path != null);
        assertTrue(new File(path).isFile());
        Set<String> videoKeys = new HashSet<>(), bitrateKeys = new HashSet<>(), workKeys = new HashSet<>();
        try (ZipFile archive = new ZipFile(path)) {
            for (String dexName : List.of("classes16.dex", "classes55.dex")) {
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(archive.getEntry(dexName)))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        Set<String> keys;
                        if (type.getType().equals("Lcom/ss/android/ugc/aweme/feed/model/Video;")) keys = videoKeys;
                        else if (type.getType().equals("Lcom/ss/android/ugc/aweme/feed/model/BitRate;")) keys = bitrateKeys;
                        else if (type.getType().equals("Lcom/ss/android/ugc/aweme/feed/model/Aweme;")) keys = workKeys;
                        else continue;
                        for (Field field : type.getFields()) for (var annotation : field.getAnnotations()) {
                            if (!annotation.getType().equals("Lcom/google/gson/annotations/SerializedName;")) continue;
                            for (var element : annotation.getElements()) {
                                if (element.getName().equals("value") && element.getValue() instanceof StringEncodedValue) {
                                    keys.add(((StringEncodedValue) element.getValue()).getValue());
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(videoKeys.containsAll(List.of("play_addr_bytevc1", "play_addr_lowbr", "bit_rate")));
        assertTrue(bitrateKeys.containsAll(List.of("play_addr", "play_addr_265", "play_addr_bytevc1")));
        assertTrue(workKeys.contains("is_live_photo"));
    }

    private static void assertLiveChoicesAndPlan(Work work) {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
             OfficialDownloadDialog session = new OfficialDownloadDialog(activity.get(), work,
                     new LocalOptions(Collections.emptyMap()), ignored -> { }, () -> true)) {
            session.show();
            assertTrue("An explicit LivePhoto must not be shown as static-only", OfficialDownloadTestUi.hasGroup("实况"));
            assertTrue(OfficialDownloadTestUi.hasGroup("动态视频"));
            OfficialDownloadTestUi.openGroup("实况");
            assertTrue(hasChoice(ShadowAlertDialog.getLatestAlertDialog(), "保存实况"));
            OfficialLivePhotoDownload.Plan plan = OfficialLivePhotoDownload.plan(work, 0,
                    new LocalOptions(Collections.emptyMap()));
            assertEquals("https://media.example/motion.mp4", plan.videoUrl);
        }
    }

    private static boolean hasChoice(AlertDialog dialog, String label) {
        for (int i = 0; i < dialog.getListView().getCount(); i++) {
            if (label.equals(dialog.getListView().getAdapter().getItem(i))) return true;
        }
        return false;
    }
}

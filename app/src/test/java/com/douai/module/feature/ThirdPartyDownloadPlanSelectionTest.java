package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ThirdPartyDownloadPlanSelectionTest {
    @Test public void onlyConfiguredDownloadFieldsBecomeSelectableAssets() {
        ThirdPartyDownloadPlan plan = plan("{\"data\":{\"id\":\"work\","
                + "\"images\":[\"https://media.test/a.jpg\"],\"video\":\"https://media.test/v.mp4\","
                + "\"audio\":\"https://media.test/a.mp3\",\"desc\":\"caption\"}}",
                List.of("data.images"), List.of("data.id"));
        assertEquals(1, plan.assets.size());
        ThirdPartyDownloadPlan.Asset image = plan.assets.get(0);
        assertEquals("data.images", image.fieldPath);
        assertEquals(0, image.sourceIndex);
        assertEquals(1, image.sequence);
        assertEquals(ThirdPartyDownloadPlan.Category.IMAGE, image.category());
        assertFalse(plan.metadata.contains("https://media.test/a.jpg"));
        assertTrue(plan.metadata.contains("https://media.test/v.mp4"));
        assertTrue(plan.metadata.contains("https://media.test/a.mp3"));
        assertTrue(plan.metadata.contains("caption"));
    }

    @Test public void deduplicationKeepsFirstFieldAndOriginalArrayPositionBeforeDeduplication() {
        ThirdPartyDownloadPlan plan = plan("{\"images\":[\"https://media.test/a.jpg\","
                + "\"https://media.test/a.jpg\",\"https://media.test/b.jpg\"],"
                + "\"video\":[\"https://media.test/a.jpg\",\"https://media.test/c.mp4\"]}",
                List.of("images", "video"), List.of());
        assertEquals(3, plan.assets.size());
        assertEquals(List.of("images", "images", "video"), fields(plan));
        assertEquals(List.of(0, 2, 1), sourceIndexes(plan));
        assertEquals(List.of(1, 2, 3), sequences(plan));
        assertTrue(plan.assets.get(0).name.contains("_1_images"));
        assertTrue(plan.assets.get(1).name.contains("_2_images"));
        assertTrue(plan.assets.get(2).name.contains("_3_video"));
        assertEquals(3, new HashSet<>(names(plan)).size());
    }

    @Test public void sparseSelectionSortsOriginalIndexesAndDoesNotRenumberFiles() {
        ThirdPartyDownloadPlan original = fivePictures();
        ThirdPartyDownloadPlan selected = original.selectAssets(List.of(4, 1, 4));
        assertEquals(2, selected.assets.size());
        assertSame(original.assets.get(1), selected.assets.get(0));
        assertSame(original.assets.get(4), selected.assets.get(1));
        assertEquals(List.of(1, 4), sourceIndexes(selected));
        assertEquals(List.of(2, 5), sequences(selected));
        assertEquals(List.of(original.assets.get(1).name, original.assets.get(4).name), names(selected));
        assertEquals(5, original.assets.size());
        assertEquals(original.baseName, selected.baseName);
        assertEquals(original.metadata, selected.metadata);
    }

    @Test public void multipleConfiguredFieldsKeepGlobalNamingOrderAndSubsetMetadataRules() {
        ThirdPartyDownloadPlan original = plan("{\"data\":{\"id\":\"7696151596120622001\","
                + "\"author\":\"author/name\",\"caption\":\"description\","
                + "\"images\":[\"https://media.test/a.jpg\",\"https://media.test/b.jpg\"],"
                + "\"video\":\"https://media.test/v.mp4\",\"audio\":\"https://media.test/s.mp3\"}}",
                List.of("data.video", "data.images"), List.of("data.id", "data.author"));
        assertEquals("7696151596120622001_author_name", original.baseName);
        assertEquals(List.of("data.video", "data.images", "data.images"), fields(original));
        assertEquals(List.of(1, 2, 3), sequences(original));
        ThirdPartyDownloadPlan subset = original.selectAssets(new int[]{2, 0});
        assertEquals(List.of(original.assets.get(0).name, original.assets.get(2).name), names(subset));
        assertEquals(original.metadata, subset.metadata);
        assertTrue(subset.metadata.contains("description"));
        assertTrue(subset.metadata.contains("https://media.test/s.mp3"));
        assertFalse(subset.metadata.contains("https://media.test/a.jpg"));
        assertTrue(subset.assets.get(1).name.contains("_3_data_images"));
        assertNotEquals(subset.assets.get(0).name, subset.assets.get(1).name);
    }

    @Test public void subsetAndOriginalRemainImmutableWhenCallerChangesItsSelection() {
        ThirdPartyDownloadPlan original = fivePictures();
        List<Integer> indexes = new ArrayList<>(List.of(3, 0));
        ThirdPartyDownloadPlan selected = original.selectAssets(indexes);
        indexes.clear();
        indexes.add(2);
        assertEquals(List.of(1, 4), sequences(selected));
        assertEquals(List.of(1, 2, 3, 4, 5), sequences(original));
        try { selected.assets.clear(); fail("selected assets must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        try { original.assets.add(selected.assets.get(0)); fail("source assets must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        ThirdPartyDownloadPlan nested = selected.selectAssets(new int[]{1});
        assertEquals(4, nested.assets.get(0).sequence);
        assertEquals(original.assets.get(3).name, nested.assets.get(0).name);
    }

    @Test public void nullEmptyAndOutOfBoundsSelectionsFailWithoutChangingThePlan() {
        ThirdPartyDownloadPlan plan = fivePictures();
        List<List<Integer>> invalid = Arrays.asList(Collections.emptyList(), List.of(-1), List.of(5),
                Arrays.asList(0, null), List.of(0, 99));
        for (List<Integer> indexes : invalid) assertInvalid(() -> plan.selectAssets(indexes));
        assertInvalid(() -> plan.selectAssets((List<Integer>) null));
        assertInvalid(() -> plan.selectAssets((int[]) null));
        assertInvalid(() -> plan.selectAssets(new int[0]));
        assertInvalid(() -> plan.selectAssets(new int[]{-1, 0}));
        assertEquals(List.of(1, 2, 3, 4, 5), sequences(plan));
    }

    @Test public void categoryUsesMimeAndExplicitConfiguredMotionFieldsWithoutInventingLivePhotoPairs() {
        ThirdPartyDownloadPlan plan = plan("{\"image\":\"https://media.test/picture.jpg\","
                + "\"video\":\"https://media.test/video.mp4\",\"audio\":\"https://media.test/sound.mp3\","
                + "\"file\":\"https://media.test/raw.bin\",\"motion_video\":\"https://media.test/motion.mp4\"}",
                List.of("image", "video", "audio", "file", "motion_video"), List.of());
        assertEquals(List.of(ThirdPartyDownloadPlan.Category.IMAGE, ThirdPartyDownloadPlan.Category.VIDEO,
                ThirdPartyDownloadPlan.Category.AUDIO, ThirdPartyDownloadPlan.Category.FILE,
                ThirdPartyDownloadPlan.Category.MOTION), categories(plan));
        assertEquals(Set.of("IMAGE", "VIDEO", "MOTION", "AUDIO", "FILE"),
                Arrays.stream(ThirdPartyDownloadPlan.Category.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals(ThirdPartyDownloadPlan.Category.VIDEO, plan.selectAssets(new int[]{0, 1}).assets.get(1).category());
    }

    @Test public void motionMarkersReferToFieldMeaningRatherThanUrlTextOrUnrelatedWords() {
        for (String field : List.of("data.motion_video", "data.dynamicVideo", "data.live_video", "data.liveVideo")) {
            assertEquals(field, ThirdPartyDownloadPlan.Category.MOTION,
                    new ThirdPartyDownloadPlan.Asset("https://media.test/v.mp4", "v.mp4", "video/mp4", field, 0, 1).category());
        }
        for (String field : List.of("data.video", "data.promotion_video", "data.live_photo")) {
            assertEquals(field, ThirdPartyDownloadPlan.Category.VIDEO,
                    new ThirdPartyDownloadPlan.Asset("https://media.test/motion/live_video.mp4", "v.mp4", "video/mp4", field, 0, 1).category());
        }
        assertEquals(ThirdPartyDownloadPlan.Category.IMAGE,
                new ThirdPartyDownloadPlan.Asset("https://media.test/p.jpg", "p.jpg", "image/jpeg", "data.live_video", 0, 1).category());
        assertEquals(ThirdPartyDownloadPlan.Category.FILE,
                new ThirdPartyDownloadPlan.Asset("https://media.test/raw", "raw.bin", "application/octet-stream", "motion", 0, 1).category());
    }

    @Test public void legacyAssetConstructorRemainsCompatibleAndDoesNotInferMotionFromFilename() {
        ThirdPartyDownloadPlan.Asset legacy = new ThirdPartyDownloadPlan.Asset("https://media.test/motion.mp4",
                "live_video.mp4", "VIDEO/MP4");
        assertEquals("", legacy.fieldPath);
        assertEquals(0, legacy.sourceIndex);
        assertEquals(1, legacy.sequence);
        assertEquals(ThirdPartyDownloadPlan.Category.VIDEO, legacy.category());
        assertEquals(ThirdPartyDownloadPlan.Category.FILE,
                new ThirdPartyDownloadPlan.Asset("https://media.test/x", "x", null).category());
    }

    @Test public void sixtyFourUniqueResourcesStillRespectTheOriginalLimitWhileDuplicatesDoNotConsumeSlots() throws Exception {
        JSONArray urls = new JSONArray();
        for (int index = 0; index < 64; index++) urls.put("https://media.test/" + index + ".jpg");
        urls.put("https://media.test/0.jpg");
        ThirdPartyDownloadPlan plan = plan(new JSONObject().put("images", urls).toString(), List.of("images"), List.of());
        assertEquals(64, plan.assets.size());
        assertEquals(64, plan.selectAssets(new int[]{63}).assets.get(0).sequence);
        assertEquals(63, plan.selectAssets(new int[]{63}).assets.get(0).sourceIndex);
        urls.put("https://media.test/64.jpg");
        assertInvalid(() -> plan(new JSONObject().put("images", urls).toString(), List.of("images"), List.of()));
    }

    private static ThirdPartyDownloadPlan fivePictures() {
        return plan("{\"id\":\"work\",\"caption\":\"metadata\",\"images\":["
                + "\"https://media.test/0.jpg\",\"https://media.test/1.jpg\",\"https://media.test/2.jpg\","
                + "\"https://media.test/3.jpg\",\"https://media.test/4.jpg\"]}", List.of("images"), List.of("id"));
    }

    private static ThirdPartyDownloadPlan plan(String response, List<String> downloads, List<String> names) {
        Map<String, Object> options = new HashMap<>();
        options.put(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, ThirdPartyDownloadSettings.encodeFields(downloads));
        options.put(ThirdPartyDownloadSettings.NAMING_FIELDS, ThirdPartyDownloadSettings.encodeFields(names));
        return ThirdPartyDownloadPlan.create(ThirdPartyResponse.parse(response),
                new ThirdPartyDownloadSettings(new LocalOptions(options)), 100);
    }

    private static List<String> fields(ThirdPartyDownloadPlan plan) {
        List<String> values = new ArrayList<>();
        for (ThirdPartyDownloadPlan.Asset asset : plan.assets) values.add(asset.fieldPath);
        return values;
    }
    private static List<Integer> sourceIndexes(ThirdPartyDownloadPlan plan) {
        List<Integer> values = new ArrayList<>();
        for (ThirdPartyDownloadPlan.Asset asset : plan.assets) values.add(asset.sourceIndex);
        return values;
    }
    private static List<Integer> sequences(ThirdPartyDownloadPlan plan) {
        List<Integer> values = new ArrayList<>();
        for (ThirdPartyDownloadPlan.Asset asset : plan.assets) values.add(asset.sequence);
        return values;
    }
    private static List<String> names(ThirdPartyDownloadPlan plan) {
        List<String> values = new ArrayList<>();
        for (ThirdPartyDownloadPlan.Asset asset : plan.assets) values.add(asset.name);
        return values;
    }
    private static List<ThirdPartyDownloadPlan.Category> categories(ThirdPartyDownloadPlan plan) {
        List<ThirdPartyDownloadPlan.Category> values = new ArrayList<>();
        for (ThirdPartyDownloadPlan.Asset asset : plan.assets) values.add(asset.category());
        return values;
    }
    private interface Action { void run() throws Exception; }
    private static void assertInvalid(Action action) {
        try { action.run(); fail("invalid selection or bundle must fail clearly"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        catch (Exception unexpected) { throw new AssertionError(unexpected); }
    }
}

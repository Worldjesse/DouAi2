package com.douai.module.config;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutomationHistoryStoreTest {
    private Context context;
    private AutomationHistoryStore store;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit().clear().commit();
        store = new AutomationHistoryStore(context);
    }

    @Test public void statusUpdatesReplaceAnEntryAndRetainItsOriginalTime() {
        store.upsert(entry("a", "reply", "pending", 10, "输入", "回复"));
        store.upsert(entry("b", "reply", "sent", 20, "另一个输入", "另一个回复"));
        store.upsert(entry("a", "reply", "sent", 10, "输入", "回复"));
        List<AutomationHistoryStore.Entry> entries = new AutomationHistoryStore(context).read("reply");
        assertEquals(2, entries.size());
        assertEquals("b", entries.get(0).id);
        assertEquals("a", entries.get(1).id);
        assertEquals("sent", entries.get(1).status);
        assertEquals(10, entries.get(1).createdAt);
        assertEquals("输入", entries.get(1).input);
        assertEquals("目标用户", entries.get(1).targetName);
    }

    @Test public void repliesAndCommentsHaveSeparateRetentionAndClearControls() {
        for (int i = 0; i < 205; i++) store.upsert(entry("r" + i, "reply", "sent", i, "输入", "回复"));
        store.upsert(entry("r0", "comment", "submitted", 500, "视频内容", "评论"));
        assertEquals(200, store.read("reply").size());
        assertEquals("r204", store.read("reply").get(0).id);
        assertEquals("r5", store.read("reply").get(199).id);
        assertEquals("submitted", store.read("comment").get(0).status);
        store.clear("reply");
        assertTrue(store.read("reply").isEmpty());
        assertEquals(1, store.read("comment").size());
    }

    @Test public void fieldAndUtf8StorageBoundsPruneTheOldestRecords() {
        String large = "汉".repeat(10000);
        for (int i = 0; i < 70; i++) store.upsert(entry("large" + i, "reply", "sent", i, large, large));
        List<AutomationHistoryStore.Entry> entries = store.read("reply");
        assertTrue(entries.size() < 70);
        assertEquals("large69", entries.get(0).id);
        assertEquals(8000, entries.get(0).input.length());
        assertEquals(4000, entries.get(0).output.length());
        String json = context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .getString("entries_v1", "");
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024);
    }

    @Test public void malformedHistoryDoesNotBreakFutureSaves() {
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .edit().putString("entries_v1", "not-json").commit();
        assertTrue(store.read("reply").isEmpty());
        store.upsert(entry("a", "reply", "cancelled", 1, "", ""));
        assertEquals("cancelled", store.read("reply").get(0).status);
    }

    @Test public void returnedHistoryCannotBeMutatedAndInvalidStatusesAreRejected() {
        store.upsert(entry("a", "reply", "failed", 1, "", ""));
        assertThrows(UnsupportedOperationException.class, () -> store.read("reply").clear());
        assertThrows(IllegalArgumentException.class, () -> store.read("unknown"));
        assertThrows(IllegalArgumentException.class, () -> entry("a", "reply", "success", 1, "", ""));
    }

    @Test public void clearedRecordCannotReappearFromAnOlderPendingCallback() {
        long created = System.currentTimeMillis() - 100;
        store.upsert(entry("old", "reply", "pending", created, "输入", "回复"));
        store.clear("reply");
        store.upsert(entry("old", "reply", "sent", created, "输入", "回复"));
        assertTrue(store.read("reply").isEmpty());
        store.upsert(entry("new", "reply", "sent", System.currentTimeMillis() + 1, "新消息", "新回复"));
        assertEquals("new", store.read("reply").get(0).id);
    }

    @Test public void legacyRecordsKeepUnknownImageRequestMetadata() throws Exception {
        JSONObject legacy = new JSONObject().put("id", "legacy").put("kind", "comment")
                .put("targetId", "target-id").put("targetName", "目标用户")
                .put("input", "作品内容").put("output", "评论内容")
                .put("modelName", "test-model").put("status", "sent")
                .put("createdAt", 10).put("updatedAt", 20);
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit()
                .putString("entries_v1", new JSONArray().put(legacy).toString()).commit();

        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment").get(0);
        assertEquals(-1, loaded.requestImageCount);
        assertFalse(loaded.modelRequestSent);
        store.upsert(entry("legacy", "comment", "sent", 10, "作品内容", "评论内容"));
        loaded = new AutomationHistoryStore(context).read("comment").get(0);
        assertEquals(-1, loaded.requestImageCount);
        assertFalse(loaded.modelRequestSent);
    }

    @Test public void sentImageAndTextOnlyRequestsPersistAcrossStoreInstances() {
        int[] imageCounts = {0, 1, 3};
        for (int imageCount : imageCounts) {
            String id = "request-" + imageCount;
            store.upsert(entry(id, "comment", "pending", imageCount + 10, "作品内容", ""));
            store.markModelRequestSent("comment", id, imageCount);
        }

        List<AutomationHistoryStore.Entry> loaded = new AutomationHistoryStore(context).read("comment");
        assertEquals(3, loaded.size());
        for (AutomationHistoryStore.Entry entry : loaded) {
            assertTrue(entry.modelRequestSent);
            assertEquals(Integer.parseInt(entry.id.substring("request-".length())), entry.requestImageCount);
            assertEquals("pending", entry.status);
            assertEquals("作品内容", entry.input);
        }
    }

    @Test public void knownUnsentRequestStaysDistinctFromLegacyUnknownMetadata() {
        store.upsert(new AutomationHistoryStore.Entry("never-sent", "comment", "target-id", "目标用户",
                "作品内容", "", "test-model", "cancelled", 10, 20, 0, false));

        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment").get(0);
        assertEquals(0, loaded.requestImageCount);
        assertFalse(loaded.modelRequestSent);
        assertEquals("cancelled", loaded.status);
    }

    @Test public void statusUpdatesRetainAlreadySentRequestMetadata() {
        store.upsert(entry("request", "comment", "pending", 10, "作品内容", ""));
        store.markModelRequestSent("comment", "request", 2);
        for (String status : new String[]{"sending", "submitted", "sent", "failed", "cancelled"}) {
            store.upsert(entry("request", "comment", status, 10, "作品内容", "生成评论"));

            AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment").get(0);
            assertTrue(status, loaded.modelRequestSent);
            assertEquals(status, 2, loaded.requestImageCount);
            assertEquals(status, loaded.status);
            assertEquals("生成评论", loaded.output);
            assertEquals(10, loaded.createdAt);
        }
    }

    @Test public void lateRequestMetadataCannotRestoreClearedRecordsOrAffectAnotherKind() {
        long created = System.currentTimeMillis() - 100;
        store.upsert(entry("same-id", "comment", "pending", created, "作品内容", ""));
        store.upsert(entry("same-id", "reply", "sent", created, "消息", "回复"));
        store.markModelRequestSent("comment", "same-id", 1);
        assertFalse(store.read("reply").get(0).modelRequestSent);

        store.clear("comment");
        new AutomationHistoryStore(context).markModelRequestSent("comment", "same-id", 3);
        store.upsert(new AutomationHistoryStore.Entry("same-id", "comment", "target-id", "目标用户",
                "作品内容", "生成评论", "test-model", "sent", created, created + 10, 3, true));

        assertTrue(new AutomationHistoryStore(context).read("comment").isEmpty());
        assertEquals(1, store.read("reply").size());
        assertEquals(-1, store.read("reply").get(0).requestImageCount);
        assertFalse(store.read("reply").get(0).modelRequestSent);
    }

    private static AutomationHistoryStore.Entry entry(String id, String kind, String status,
                                                      long createdAt, String input, String output) {
        return new AutomationHistoryStore.Entry(id, kind, "target-id", "目标用户", input, output,
                "test-model", status, createdAt, createdAt + 10);
    }
}

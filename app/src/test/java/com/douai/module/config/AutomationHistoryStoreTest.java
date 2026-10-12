package com.douai.module.config;

import android.content.Context;

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

    private static AutomationHistoryStore.Entry entry(String id, String kind, String status,
                                                      long createdAt, String input, String output) {
        return new AutomationHistoryStore.Entry(id, kind, "target-id", "目标用户", input, output,
                "test-model", status, createdAt, createdAt + 10);
    }
}

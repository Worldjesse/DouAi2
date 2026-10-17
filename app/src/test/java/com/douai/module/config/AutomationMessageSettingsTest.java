package com.douai.module.config;

import com.douai.module.feature.ContentScopePolicy;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class AutomationMessageSettingsTest {
    @Test public void legacyScopesMigrateIndependentlyWithoutLosingExistingChoices() {
        MemoryStore store = new MemoryStore();
        store.putString("reply_scope_mode", "blacklist");
        store.putString("reply_scope_relations", "following");
        store.putString("reply_targets", "101");
        store.putString("reply_scope_keywords", "茶");
        store.putString("reply_scope_fields", "author,title");
        store.putString("comment_scope_mode", "whitelist");
        store.putString("comment_scope_keywords", "咖啡");
        store.putString("comment_scope_fields", "title");
        AutomationMessageSettings reply = new AutomationMessageSettings(store, null);
        AutomationMessageSettings comment = new AutomationMessageSettings(store, null);
        LocalOptions previous = reply.scope("reply");
        reply.prepareContentFilter("reply");
        comment.prepareContentFilter("comment");
        assertEquals("blacklist", store.getString("reply_scope_mode", ""));
        assertEquals("exclude", store.getString("reply_filter_mode", ""));
        assertEquals("author,caption", store.getString("reply_filter_fields", ""));
        assertEquals("author,title", store.getString("reply_scope_fields", ""));
        assertEquals("101", store.getString("reply_targets", ""));
        assertEquals("following", store.getString("reply_scope_relations", ""));
        assertEquals("all", store.getString("comment_scope_mode", ""));
        assertEquals("include", store.getString("comment_filter_mode", ""));
        assertEquals("title", store.getString("comment_filter_fields", ""));
        assertEquals("咖啡", store.getString("comment_scope_keywords", ""));

        reply.putString("reply_filter_keywords", "新消息词");
        comment.putString("comment_filter_keywords", "新作品词");
        reply.prepareContentFilter("reply");
        comment.prepareContentFilter("comment");
        assertEquals("新消息词", store.getString("reply_filter_keywords", ""));
        assertEquals("新作品词", store.getString("comment_filter_keywords", ""));
        assertEquals("茶", ContentScopePolicy.keywords("reply", previous));
        assertEquals("exclude", ContentScopePolicy.filterMode("reply", previous));
    }

    @Test public void initializedFiltersAndUnknownLegacyModesRemainUnchangedOnOpen() {
        MemoryStore store = new MemoryStore();
        store.putString("reply_scope_mode", "legacy-unknown");
        store.putString("comment_scope_mode", "blacklist");
        store.putString("comment_filter_mode", "include");
        store.putString("comment_filter_keywords", "现有作品词");
        store.putString("comment_filter_fields", "");
        Map<String, Object> oldComment = new HashMap<>(store.values);
        AutomationMessageSettings session = new AutomationMessageSettings(store, null);
        session.prepareContentFilter("reply");
        session.prepareContentFilter("comment");
        assertEquals("legacy-unknown", store.getString("reply_scope_mode", ""));
        for (String key : oldComment.keySet()) assertEquals(key, oldComment.get(key), store.values.get(key));
        assertEquals("", ContentScopePolicy.fields("comment", session.scope("comment")));
    }

    @Test public void newScopeAndLimitEditsValidateBeforeChangingTheStore() {
        MemoryStore store = new MemoryStore();
        AutomationMessageSettings session = new AutomationMessageSettings(store, null);
        String[][] invalid = {{"reply_scope_mode", "unknown"}, {"comment_filter_mode", "unknown"},
                {"reply_filter_fields", "title"}, {"reply_scope_relations", "anyone"},
                {"reply_daily_limit", "1000001"}, {"comment_user_cooldown_minutes", "525601"},
                {"reply_delay_seconds", "1"}, {"comment_notice_seconds", "121"},
                {"reply_daily_limit", "NaN"}, {"reply_daily_limit", "-1"}};
        for (String[] input : invalid) {
            assertThrows(input[0], IllegalArgumentException.class, () -> session.putString(input[0], input[1]));
            assertFalse(store.values.containsKey(input[0]));
        }
        session.putString("reply_daily_limit", "0002");
        session.putString("comment_filter_fields", "author,caption,title");
        session.putString("reply_delay_seconds", "3600");
        assertEquals("2", store.getString("reply_daily_limit", ""));
        assertEquals("author,caption,title", store.getString("comment_filter_fields", ""));
        assertEquals("3600", store.getString("reply_delay_seconds", ""));
    }

    @Test public void closingOneSessionLeavesOtherSessionEditableAndRejectsLateChanges() {
        MemoryStore store = new MemoryStore();
        AutomationMessageSettings reply = new AutomationMessageSettings(store, null);
        AutomationMessageSettings comment = new AutomationMessageSettings(store, null);
        reply.putString("reply_filter_keywords", "回复词");
        reply.close();
        reply.putString("reply_filter_keywords", "迟到词");
        reply.putBoolean("reply_voice", true);
        reply.setEnabled("reply", true);
        reply.prepareContentFilter("reply");
        comment.putString("comment_filter_keywords", "评论词");
        comment.setEnabled("comment", true);
        assertEquals("回复词", store.getString("reply_filter_keywords", ""));
        assertFalse(store.getBoolean("reply_voice", false));
        assertFalse(store.getBoolean("native_auto_reply", false));
        assertFalse(store.values.containsKey("reply_filter_mode"));
        assertEquals("评论词", store.getString("comment_filter_keywords", ""));
        assertTrue(store.getBoolean("native_auto_comment", false));
        assertTrue(store.load().autoCommentEnabled);
    }

    @Test public void commentReplyReceptionDoesNotEnableTheMasterOrChangeExistingSettings() {
        MemoryStore store = new MemoryStore();
        store.features = new FeatureSettings(true, true, false, false, true, false, false,
                false, "原配置", false, 0, false, 0, false, true, false, true, true);
        store.putBoolean("native_auto_reply", false);
        store.putBoolean("native_auto_comment", true);
        FeatureSettings original = store.load();
        AutomationMessageSettings session = new AutomationMessageSettings(store, null);
        session.setEnabled("comment_reply", true);
        assertTrue(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false));
        assertFalse(store.load().automationEnabled);
        assertSame(original, store.load());
        assertFalse(store.getBoolean("native_auto_reply", true));
        assertTrue(store.getBoolean("native_auto_comment", false));
        assertTrue(store.load().autoCommentEnabled);
        assertTrue(store.load().autoMessageEnabled);
        assertTrue(store.load().autoLikeEnabled);
        assertEquals("原配置", store.load().keyword);
        session.setEnabled("comment_reply", false);
        assertFalse(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, true));
        assertFalse(store.load().automationEnabled);
        assertSame(original, store.load());
        assertTrue(store.load().autoMessageEnabled);
        session.close();
        session.setEnabled("comment_reply", true);
        assertFalse(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, true));
    }

    @Test public void legacyCommentReplySettingsRemainCompatibleWithoutChangingSharedReplySettings() {
        MemoryStore store = new MemoryStore();
        store.putString("reply_daily_limit", "7");
        store.putString("reply_prompt", "共享提示词");
        AutomationMessageSettings session = new AutomationMessageSettings(store, null);
        String[][] invalid = {{"comment_reply_thread_limit", "0"}, {"comment_reply_thread_limit", "101"},
                {"comment_reply_tip_seconds", "1"}, {"comment_reply_tip_seconds", "121"},
                {"comment_reply_daily_limit", "-1"}, {"comment_reply_user_cooldown_minutes", "NaN"}};
        for (String[] input : invalid) {
            assertThrows(input[0], IllegalArgumentException.class, () -> session.putString(input[0], input[1]));
            assertFalse(store.values.containsKey(input[0]));
        }
        session.putString("comment_reply_daily_limit", "0020");
        session.putString("comment_reply_user_cooldown_minutes", "01");
        session.putString("comment_reply_thread_limit", "003");
        session.putString("comment_reply_tip_seconds", "003");
        assertEquals("20", store.getString("comment_reply_daily_limit", ""));
        assertEquals("1", store.getString("comment_reply_user_cooldown_minutes", ""));
        assertEquals("3", store.getString("comment_reply_thread_limit", ""));
        assertEquals("3", store.getString("comment_reply_tip_seconds", ""));
        assertEquals("7", store.getString("reply_daily_limit", ""));
        assertEquals("共享提示词", store.getString("reply_prompt", ""));
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings features = FeatureSettings.defaults();
        public FeatureSettings load() { return features; }
        public void save(FeatureSettings value) { features = value; }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public void putString(String key, String value) { values.put(key, value); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
    }
}

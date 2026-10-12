package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ContentScopePolicyTest {
    @Before public void reset() { FollowListSnapshot.reset(); }
    @After public void cleanup() { FollowListSnapshot.reset(); }
    private static LocalOptions options(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return new LocalOptions(values);
    }
    @Test public void commentAuthorAndWorkFilterMustBothMatch() {
        LocalOptions value = options("comment_scope_mode", "whitelist", "comment_scope_users", "200",
                "comment_filter_mode", "include", "comment_filter_keywords", "tea", "comment_filter_fields", "caption");
        assertTrue(ContentScopePolicy.allows("comment", value, "200", "Alice", "tea", "", null));
        assertFalse(ContentScopePolicy.allows("comment", value, "300", "Bob", "tea", "", null));
        assertFalse(ContentScopePolicy.allows("comment", value, "200", "Alice", "coffee", "", null));
    }
    @Test public void commentTitleRemainsSeparateFromCaptionWhileReplyLegacyTitleMapsToMessage() {
        LocalOptions value = options("comment_scope_mode", "all", "comment_filter_mode", "include",
                "comment_filter_keywords", "tea", "comment_filter_fields", "title");
        assertTrue(ContentScopePolicy.allows("comment", value, "200", "Alice", "coffee", "tea", null));
        assertFalse(ContentScopePolicy.allows("comment", value, "200", "Alice", "tea", "coffee", null));
        assertEquals("title", ContentScopePolicy.fields("comment", options("comment_scope_fields", "title")));
        assertEquals("caption", ContentScopePolicy.fields("reply", options("reply_scope_fields", "title")));
    }
    @Test public void legacyKeywordOnlyCommentsPreserveTheirTargetContent() {
        LocalOptions value = options("comment_scope_mode", "whitelist", "comment_scope_keywords", "tea",
                "comment_scope_fields", "title");
        assertEquals("all", ContentScopePolicy.scopeMode("comment", value));
        assertTrue(ContentScopePolicy.allows("comment", value, "300", "Bob", "", "tea", null));
        assertFalse(ContentScopePolicy.allows("comment", value, "300", "Bob", "tea", "", null));
    }
    @Test public void allCommentAuthorsDoNotActivateStoredLegacyScopeKeywords() {
        LocalOptions value = options("comment_scope_mode", "all", "comment_scope_keywords", "tea", "comment_scope_fields", "caption");
        assertEquals("off", ContentScopePolicy.filterMode("comment", value));
        assertTrue(ContentScopePolicy.allows("comment", value, "300", "Bob", "coffee", "", null));
    }

    @Test public void missingLegacyKeywordFieldsDoNotWidenScopeWhenUpgrading() {
        LocalOptions value = options("comment_scope_mode", "whitelist", "comment_scope_keywords", "tea");
        assertEquals("", ContentScopePolicy.fields("comment", value));
        assertFalse(ContentScopePolicy.allows("comment", value, "300", "Bob", "tea", "tea", null));
    }
}

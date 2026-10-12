package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ReplyScopePolicyTest {
    @Before public void reset() { FollowListSnapshot.reset(); }
    @After public void cleanup() { FollowListSnapshot.reset(); }
    private static LocalOptions options(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return new LocalOptions(values);
    }
    private static boolean allows(LocalOptions options, String uid, String sender, String text, boolean following) {
        return ReplyScopePolicy.allows(options, uid, sender, text, "",
                new AutomationScopePolicy.Relation(true, following, false, !following));
    }
    @Test public void includeMessageFilterCannotBypassAUserWhitelist() {
        LocalOptions value = options("reply_scope_mode", "whitelist", "reply_scope_relations", "following",
                "reply_filter_mode", "include", "reply_filter_keywords", "tea");
        assertTrue(allows(value, "200", "Alice", "tea", true));
        assertFalse(allows(value, "200", "Alice", "coffee", true));
        assertFalse(allows(value, "300", "Bob", "tea", false));
    }
    @Test public void identityBlacklistAndExcludedMessageTermsAreCumulative() {
        LocalOptions value = options("reply_scope_mode", "blacklist", "reply_targets", "200",
                "reply_filter_mode", "exclude", "reply_filter_keywords", "spam");
        assertFalse(allows(value, "200", "Alice", "normal", false));
        assertFalse(allows(value, "300", "Bob", "spam", false));
        assertTrue(allows(value, "300", "Bob", "normal", false));
    }
    @Test public void allUsersCanStillUseASeparateMessageFilter() {
        LocalOptions value = options("reply_scope_mode", "all", "reply_filter_mode", "include", "reply_filter_keywords", "tea");
        assertTrue(allows(value, "300", "Bob", "tea", false));
        assertFalse(allows(value, "300", "Bob", "coffee", false));
    }
    @Test public void senderAndMessageFieldsDoNotMatchEachOther() {
        LocalOptions sender = options("reply_scope_mode", "all", "reply_filter_mode", "include",
                "reply_filter_keywords", "Alice", "reply_filter_fields", "author");
        assertTrue(allows(sender, "300", "Alice", "hello", false));
        assertFalse(allows(sender, "300", "Bob", "Alice", false));
        LocalOptions message = options("reply_scope_mode", "all", "reply_filter_mode", "include",
                "reply_filter_keywords", "Alice", "reply_filter_fields", "caption");
        assertFalse(allows(message, "300", "Alice", "hello", false));
        assertTrue(allows(message, "300", "Bob", "Alice", false));
    }
    @Test public void disablingAFilterKeepsStoredTermsButDoesNotApplyThem() {
        LocalOptions value = options("reply_scope_mode", "all", "reply_filter_mode", "off", "reply_filter_keywords", "tea");
        assertTrue(allows(value, "300", "Bob", "coffee", false));
        assertEquals("tea", value.text("reply_filter_keywords", ""));
    }
    @Test public void legacyKeywordOnlyWhitelistDoesNotBecomeAnEmptyUserWhitelist() {
        LocalOptions value = options("reply_scope_mode", "whitelist", "reply_scope_keywords", "tea", "reply_scope_fields", "title");
        assertEquals("all", ReplyScopePolicy.scopeMode(value));
        assertEquals("include", ReplyScopePolicy.filterMode(value));
        assertEquals("caption", ReplyScopePolicy.fields(value));
        assertTrue(allows(value, "300", "Bob", "tea", false));
        assertFalse(allows(value, "300", "Bob", "coffee", false));
    }

    @Test public void unsetLegacyScopeModeUsesTheSameFallbackInUiAndRuntime() {
        LocalOptions value = options("reply_scope_mode", "", "reply_scope_keywords", "tea", "reply_scope_fields", "caption", "reply_global", false);
        assertEquals("all", ReplyScopePolicy.scopeMode(value));
        assertEquals("include", ReplyScopePolicy.filterMode(value));
        assertTrue(allows(value, "300", "Bob", "tea", false));
        assertFalse(allows(value, "300", "Bob", "coffee", false));
    }
    @Test public void legacyBlacklistKeywordsAreRetainedAndLegacyAllKeepsThemInactive() {
        assertEquals("exclude", ReplyScopePolicy.filterMode(options("reply_scope_mode", "blacklist", "reply_scope_keywords", "spam")));
        assertEquals("off", ReplyScopePolicy.filterMode(options("reply_scope_mode", "all", "reply_scope_keywords", "spam")));
    }
    @Test public void emptyWhitelistOrUnconfiguredActiveIncludeFilterDoesNotReply() {
        assertFalse(allows(options("reply_scope_mode", "whitelist"), "300", "Bob", "hello", false));
        assertFalse(allows(options("reply_scope_mode", "all", "reply_filter_mode", "include"), "300", "Bob", "hello", false));
    }
    @Test public void explicitlyDeselectedFieldsDoNotSilentlyRestoreMessageMatching() {
        LocalOptions value = options("reply_scope_mode", "all", "reply_filter_mode", "include", "reply_filter_keywords", "tea",
                "reply_filter_fields", "");
        assertEquals("", ReplyScopePolicy.fields(value));
        assertFalse(allows(value, "300", "Bob", "tea", false));
    }
}

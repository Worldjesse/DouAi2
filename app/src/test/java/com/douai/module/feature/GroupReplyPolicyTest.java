package com.douai.module.feature;

import android.content.ContentValues;
import com.douai.module.config.LocalOptions;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class GroupReplyPolicyTest {
    private static LocalOptions options(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return new LocalOptions(values);
    }
    private static boolean allows(LocalOptions options, String ext, String author) {
        ContentValues values = new ContentValues();
        if (ext != null) values.put("ext", ext);
        return GroupReplyPolicy.allows(values, 100, "group-1", 200, author, options);
    }
    @Test public void allMembersDoesNotUsePrivateFollowRelationOrUserScope() {
        assertTrue(allows(options("reply_scope_mode", "whitelist", "reply_scope_relations", "following"), null, "Alice"));
    }
    @Test public void groupWhitelistAndBlacklistUseConversationId() {
        assertTrue(allows(options("reply_group_scope_mode", "whitelist", "reply_group_targets", "group-1"), null, "Alice"));
        assertFalse(allows(options("reply_group_scope_mode", "whitelist", "reply_group_targets", "200"), null, "Alice"));
        assertFalse(allows(options("reply_group_scope_mode", "blacklist", "reply_group_targets", "group-1"), null, "Alice"));
        assertTrue(allows(options("reply_group_scope_mode", "blacklist", "reply_group_targets", "200"), null, "Alice"));
    }
    @Test public void selectedMembersMatchExactUidOrCompleteNickname() {
        assertTrue(allows(options("reply_group_mode", "selected", "reply_group_members", "200"), null, "Alice"));
        assertTrue(allows(options("reply_group_mode", "selected", "reply_group_members", "Alice Smith,张三"), null, "Alice Smith"));
        assertFalse(allows(options("reply_group_mode", "selected", "reply_group_members", "Alice"), null, "Alice Smith"));
        assertFalse(allows(options("reply_group_mode", "selected", "reply_group_members", "201"), null, "Alice"));
        assertFalse(allows(options("reply_group_mode", "selected"), null, "Alice"));
    }
    @Test public void mentionModeUsesExactServerUidAndNeverTextGuessing() {
        LocalOptions value = options("reply_group_mode", "mentions");
        assertTrue(allows(value, "{\"s:mentioned_users\":\"200,100\"}", "Alice"));
        assertFalse(allows(value, "{\"s:mentioned_users\":\"1000,0\"}", "Alice"));
        assertFalse(allows(value, "{\"text\":\"@100\"}", "Alice"));
        assertFalse(allows(value, "bad json", "Alice"));
        assertFalse(allows(value, null, "Alice"));
    }
    @Test public void groupScopeAndReplyTriggerMustBothMatch() {
        assertFalse(allows(options("reply_group_scope_mode", "whitelist", "reply_group_targets", "other",
                "reply_group_mode", "mentions"), "{\"s:mentioned_users\":\"100\"}", "Alice"));
        assertFalse(allows(options("reply_group_mode", "invalid"), null, "Alice"));
    }
}

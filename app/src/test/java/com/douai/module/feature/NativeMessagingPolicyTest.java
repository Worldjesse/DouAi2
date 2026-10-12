package com.douai.module.feature;

import android.content.ContentValues;
import com.douai.module.config.LocalOptions;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class NativeMessagingPolicyTest {
    private static final long NOW = 1_800_000_000_000L;

    private static LocalOptions options(Object... entries) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) values.put((String) entries[i], entries[i + 1]);
        return new LocalOptions(values);
    }

    private static ContentValues incoming() {
        ContentValues values = new ContentValues();
        values.put("conversation_id", "0:1:100:200");
        values.put("conversation_type", 1);
        values.put("msg_server_id", "700001");
        values.put("sender", 200L);
        values.put("created_time", NOW / 1000);
        values.put("type", 7);
        values.put("content", "{\"text\":\"您好\"}");
        return values;
    }

    @Test public void readsTheReal406DaoColumnsForANewSelectedSingleChat() {
        ReplyPolicy.Incoming message = ReplyPolicy.incoming(incoming(), 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true, "reply_targets", "200"));
        assertNotNull(message);
        assertEquals("0:1:100:200", message.conversation);
        assertEquals("您好", message.text);
        assertEquals("700001", message.serverId);
        assertFalse(message.group);
    }

    @Test public void groupChatsAreOptInAndUseIndependentConversationScopes() {
        ContentValues group = incoming();
        group.put("conversation_type", 2);
        group.put("sender_name", "群友甲");
        assertNull(ReplyPolicy.incoming(group, 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true,
                        "reply_global", true)));
        ReplyPolicy.Incoming enabled = ReplyPolicy.incoming(group, 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true,
                        "reply_group_chats", true, "reply_scope_mode", "whitelist",
                        "reply_scope_fields", "author", "reply_scope_keywords", "群友"));
        assertNotNull(enabled);
        assertTrue(enabled.group);
        assertEquals("群友甲", enabled.author);
        assertNull(ReplyPolicy.incoming(group, 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true,
                        "reply_group_chats", true, "reply_group_scope_mode", "blacklist",
                        "reply_group_targets", "0:1:100:200",
                        "reply_scope_fields", "caption", "reply_scope_keywords", "您好")));
    }

    @Test public void directAndGroupChannelTogglesAreIndependent() {
        LocalOptions disabledDirect = options("automation_enabled", true, "native_auto_reply", true,
                "reply_global", true, "reply_private_chats", false, "reply_group_chats", true);
        assertNull(ReplyPolicy.incoming(incoming(), 100, NOW, disabledDirect));
        ContentValues group = incoming(); group.put("conversation_type", 2);
        assertTrue(ReplyPolicy.incoming(group, 100, NOW, disabledDirect).group);
    }

    @Test public void replyWhitelistClassifiesKnownRelationshipAndKeepsUnknownOut() {
        ContentValues source = incoming();
        source.put("follow_status", 1);
        source.put("follower_status", 0);
        LocalOptions whitelist = options("automation_enabled", true, "native_auto_reply", true,
                "reply_scope_mode", "whitelist", "reply_scope_relations", "following");
        assertNotNull(ReplyPolicy.incoming(source, 100, NOW, whitelist));
        source.remove("follow_status"); source.remove("follower_status");
        assertNull(ReplyPolicy.incoming(source, 100, NOW, whitelist));
    }

    @Test public void oldDraftFlagsCannotEnableNativeSending() {
        assertNull(ReplyPolicy.incoming(incoming(), 100, NOW,
                options("automation_enabled", true, "auto_message_enabled", true, "reply_global", true)));
        assertNull(ReplyPolicy.incoming(incoming(), 100, NOW,
                options("native_auto_reply", true, "reply_global", true)));
        assertNull(ReplyPolicy.incoming(incoming(), 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true)));
    }

    @Test public void selfHistoryGroupsUnknownTimeAndUnsentMessagesAreNotRepliedTo() {
        LocalOptions enabled = options("automation_enabled", true, "native_auto_reply", true, "reply_global", true);
        ContentValues source = incoming();
        source.put("sender", 100L);
        assertNull(ReplyPolicy.incoming(source, 100, NOW, enabled));
        source = incoming(); source.put("created_time", NOW / 1000 - 300);
        assertNull(ReplyPolicy.incoming(source, 100, NOW, enabled));
        source = incoming(); source.put("conversation_type", 2);
        assertNull(ReplyPolicy.incoming(source, 100, NOW, enabled));
        source = incoming(); source.remove("created_time");
        assertNull(ReplyPolicy.incoming(source, 100, NOW, enabled));
        source = incoming(); source.put("msg_server_id", "-1");
        assertNull(ReplyPolicy.incoming(source, 100, NOW, enabled));
    }

    @Test public void keywordRandomAndFixedModesUseStructuredPresets() {
        assertEquals("已收到", ReplyPolicy.reply("测试", options("auto_message_text", "已收到"), new Random(1)));
        assertEquals("晚上好", ReplyPolicy.reply("GOOD Evening", options("reply_mode", "keyword",
                "reply_keywords", "[{\"keyword\":\"good\",\"reply\":\"晚上好\"}]"), new Random(1)));
        assertEquals("", ReplyPolicy.reply("无匹配", options("reply_mode", "keyword", "reply_keywords", "[]"), new Random(1)));
        assertEquals("随机内容", ReplyPolicy.reply("测试", options("reply_mode", "random",
                "reply_random", "[\"随机内容\"]"), new Random(1)));
        assertEquals("", ReplyPolicy.reply("测试", options("reply_mode", "random", "reply_random", "broken"), new Random(1)));
    }

    @Test public void selectedAiReplyAcceptsAnImageOnlyWhenItsMediaUrlIsRecognized() {
        ContentValues picture = incoming();
        picture.put("type", 27);
        picture.put("content", "{\"resource_url\":{\"url_list\":[\"https://p3.douyinpic.com/photo.jpg\"]}}");
        ReplyPolicy.Incoming result = ReplyPolicy.incoming(picture, 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true, "reply_targets", "200", "reply_mode", "ai"));
        assertNotNull(result);
        assertEquals(1, result.imageUrls.size());
        assertTrue(result.text.contains("图片"));
        assertNull(ReplyPolicy.incoming(picture, 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true, "reply_global", true, "reply_mode", "fixed")));
        picture.put("content", "{\"resource_url\":{\"url_list\":[\"http://127.0.0.1/private\"]}}");
        assertNull(ReplyPolicy.incoming(picture, 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true, "reply_global", true, "reply_mode", "ai")));
    }

    @Test public void imageUrlsCannotSubstituteAnotherSchemeOrHostOrEmbedCredentials() {
        assertTrue(AutomationImages.isMediaUrl("https://p3-sign.douyinpic.com/path?auth=opaque"));
        assertFalse(AutomationImages.isMediaUrl("https://douyinpic.com.attacker.example/path"));
        assertFalse(AutomationImages.isMediaUrl("https://user:secret@p3.douyinpic.com/path"));
        assertFalse(AutomationImages.isMediaUrl("file:///private/photo.jpg"));
        assertFalse(AutomationImages.isMediaUrl("https://p3.douyinpic.com:8080/path"));
    }

    @Test public void imageGenerationInstructionIsNotTreatedAsTheSendersMessageForKeywordFiltering() {
        ContentValues picture = incoming();
        picture.put("type", 27);
        picture.put("content", "{\"resource_url\":{\"url_list\":[\"https://p3.douyinpic.com/photo.jpg\"]}}");
        assertNull(ReplyPolicy.incoming(picture, 100, NOW,
                options("automation_enabled", true, "native_auto_reply", true, "reply_scope_mode", "all", "reply_mode", "ai",
                        "reply_filter_mode", "include", "reply_filter_keywords", "图片", "reply_filter_fields", "caption")));
    }

    @Test public void overnightStreakWindowAndInvalidTimeAreHandled() {
        assertTrue(ReplyPolicy.inWindow("23:00", "06:00", LocalTime.of(1, 30)));
        assertTrue(ReplyPolicy.inWindow("23:00", "06:00", LocalTime.of(23, 30)));
        assertFalse(ReplyPolicy.inWindow("23:00", "06:00", LocalTime.NOON));
        assertFalse(ReplyPolicy.inWindow("bad", "06:00", LocalTime.NOON));
        assertTrue(ReplyPolicy.inWindow("", "", LocalTime.NOON));
    }

    @Test public void recallProtectionRetainsUnrelatedMetadataAndDoesNotMutateTheCaller() throws Exception {
        ContentValues original = new ContentValues();
        original.put("ext", "{\"s:is_recalled\":\"true\",\"s:recall_uid\":\"200\",\"a:enter_method\":\"chat\"}");
        original.put("content", "撤回提示");
        original.put("deleted", 1);
        original.put("type", 1000);
        original.put("read_status", 1);
        ContentValues result = MessageRecallPolicy.protect(original, 100,
                options("prevent_message_recall", true, "recall_other", true));
        assertNotSame(original, result);
        JSONObject extension = new JSONObject(result.getAsString("ext"));
        assertEquals("false", extension.getString("s:is_recalled"));
        assertEquals("chat", extension.getString("a:enter_method"));
        assertFalse(result.containsKey("content"));
        assertFalse(result.containsKey("type"));
        assertFalse(result.containsKey("deleted"));
        assertEquals(Integer.valueOf(1), result.getAsInteger("read_status"));
        assertEquals("撤回提示", original.getAsString("content"));
        assertEquals("true", new JSONObject(original.getAsString("ext")).getString("s:is_recalled"));
    }

    @Test public void selfOtherAndUnknownRecallActorsAreIndependent() throws Exception {
        LocalOptions onlyOther = options("prevent_message_recall", true, "recall_self", false, "recall_other", true);
        assertFalse(MessageRecallPolicy.shouldProtect(new JSONObject("{\"s:is_recalled\":\"true\",\"s:recall_uid\":\"100\"}"), 100, onlyOther));
        assertTrue(MessageRecallPolicy.shouldProtect(new JSONObject("{\"s:recalled\":\"true\",\"s:recall_uid\":\"200\"}"), 100, onlyOther));
        assertFalse(MessageRecallPolicy.shouldProtect(new JSONObject("{\"s:is_recalled\":\"true\"}"), 100, onlyOther));
        assertFalse(MessageRecallPolicy.shouldProtect(new JSONObject("{\"s:is_recalled\":\"true\",\"s:recall_uid\":\"200\"}"), 0, onlyOther));
    }

    @Test public void partialDatabaseUpdatesRecoverUuidOnlyFromAnExactSingleSelector() {
        ContentValues values = new ContentValues();
        assertEquals("uuid", MessageRecallPolicy.uuid(values, "msg_uuid = ?", new String[]{"uuid"}));
        assertNull(MessageRecallPolicy.uuid(values, "sender=?", new String[]{"uuid"}));
        assertNull(MessageRecallPolicy.uuid(values, "msg_uuid=? OR sender=?", new String[]{"uuid", "100"}));
        values.put("msg_uuid", "direct");
        assertEquals("direct", MessageRecallPolicy.uuid(values, null, null));
    }
}

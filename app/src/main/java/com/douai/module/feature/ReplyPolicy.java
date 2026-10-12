package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import android.content.ContentValues;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Random;

public final class ReplyPolicy {
    private ReplyPolicy() { }

    public static final class Incoming {
        public final String conversation;
        public final String serverId;
        public final long sender;
        public final String text;
        public final java.util.List<String> imageUrls;
        public final String author;
        public final boolean group;
        public final AutomationScopePolicy.Relation relation;
        Incoming(String conversation, String serverId, long sender, String text, java.util.List<String> imageUrls,
                 String author, boolean group, AutomationScopePolicy.Relation relation) {
            this.conversation = conversation;
            this.serverId = serverId;
            this.sender = sender;
            this.text = text;
            this.imageUrls = imageUrls;
            this.author = author;
            this.group = group;
            this.relation = relation;
        }
    }

    public static Incoming incoming(ContentValues values, long ownUid, long startedAt, LocalOptions options) {
        if (values == null || ownUid <= 0 || !options.enabled("automation_enabled")
                || !options.enabled("native_auto_reply")) return null;
        try {
            Long sender = values.getAsLong("sender");
            String conversation = values.getAsString("conversation_id");
            String id = values.getAsString("msg_server_id");
            Integer type = values.getAsInteger("type");
            Integer conversationType = values.getAsInteger("conversation_type");
            Long created = values.getAsLong("created_time");
            if (created != null && created > 0 && created < 1_000_000_000_000L) created *= 1000;
            if (sender == null || sender <= 0 || sender == ownUid || conversation == null || conversation.isEmpty()
                    || id == null || !id.matches("[1-9][0-9]*") || type == null
                    || type != 7 && !(type == 27 && "ai".equals(options.text("reply_mode", "fixed")))
                    || conversationType != null && conversationType != 1 && conversationType != 2
                    || created == null || created < startedAt - 30_000) return null;
            boolean group = conversationType != null && conversationType == 2;
            if (group && !options.enabled("reply_group_chats", false)) return null;
            if (!group && !options.enabled("reply_private_chats", true)) return null;
            JSONObject content = new JSONObject(values.getAsString("content"));
            java.util.List<String> images = type == 27 ? AutomationImages.incoming(content) : java.util.Collections.emptyList();
            String text = content.optString("text", "").trim();
            String messageText = text;
            if (type == 27 && images.isEmpty()) return null;
            if (text.isEmpty() && !images.isEmpty()) text = "请根据对方发来的图片回复对方。";
            if (text.isEmpty()) return null;
            String uid = sender.toString();
            AutomationScopePolicy.Relation relation = AutomationScopePolicy.fromIncoming(values, sender);
            String author = first(values, "sender_name", "nickname", "author_name", "user_name");
            if (author.isEmpty()) author = AutomationScopePolicy.cachedName(uid);
            if (author.isEmpty()) author = NativeMessagingHooks.availableContacts().getOrDefault(uid, uid);
            if (group) {
                if (!GroupReplyPolicy.allows(values, ownUid, conversation, sender, author, options)) return null;
            } else if (!ReplyScopePolicy.allows(options, uid, author, messageText,
                    content.optString("title", ""), relation)) return null;
            return new Incoming(conversation, id, sender, text, images, author, group, relation);
        } catch (org.json.JSONException | RuntimeException ignored) { return null; }
    }

    private static String first(ContentValues values, String... keys) {
        for (String key : keys) {
            String value = values.getAsString(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    public static Set<String> targets(String configured) {
        Set<String> result = new HashSet<>();
        for (String value : configured.split("[,，\\s]+")) {
            if (value.matches("[0-9]{1,20}")) result.add(value);
        }
        return result;
    }

    public static boolean inWindow(String start, String end, LocalTime now) {
        if (start.trim().isEmpty() && end.trim().isEmpty()) return true;
        try {
            LocalTime first = LocalTime.parse(start.trim());
            LocalTime last = LocalTime.parse(end.trim());
            if (first.equals(last)) return true;
            return first.isBefore(last) ? !now.isBefore(first) && !now.isAfter(last)
                    : !now.isBefore(first) || !now.isAfter(last);
        } catch (RuntimeException ignored) { return false; }
    }

    public static String reply(String incoming, LocalOptions options, Random random) {
        String mode = options.text("reply_mode", "fixed");
        if ("fixed".equals(mode)) return options.text("auto_message_text", "").trim();
        try {
            JSONArray entries = new JSONArray(options.text("keyword".equals(mode)
                    ? "reply_keywords" : "reply_random", "[]"));
            if ("keyword".equals(mode)) {
                String lower = incoming.toLowerCase(Locale.ROOT);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    String keyword = entry.optString("keyword", "").trim();
                    if (!keyword.isEmpty() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                        return entry.optString("reply", "").trim();
                    }
                }
            } else if ("random".equals(mode) && entries.length() > 0) {
                return entries.getString(random.nextInt(entries.length())).trim();
            }
        } catch (org.json.JSONException ignored) { }
        return "";
    }
}

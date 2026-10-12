package com.douai.module.feature;

import android.content.ContentValues;
import com.douai.module.config.LocalOptions;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONObject;

/** Group selection and triggers are independent of private-chat relationship scopes. */
public final class GroupReplyPolicy {
    private GroupReplyPolicy() { }

    public static Set<String> entries(String csv) {
        Set<String> values = new LinkedHashSet<>();
        if (csv != null) for (String part : csv.split(",", -1)) {
            if (!part.trim().isEmpty()) values.add(part.trim());
        }
        return values;
    }

    static boolean allows(ContentValues values, long self, String conversation, long sender,
                          String senderName, LocalOptions options) {
        String scope = options.text("reply_group_scope_mode", "all");
        boolean selected = entries(options.text("reply_group_targets", "")).contains(conversation);
        if ("whitelist".equals(scope) && !selected || "blacklist".equals(scope) && selected) return false;
        if (!"all".equals(scope) && !"whitelist".equals(scope) && !"blacklist".equals(scope)) return false;
        String mode = options.text("reply_group_mode", "all");
        if ("all".equals(mode)) return true;
        if ("selected".equals(mode)) {
            for (String entry : entries(options.text("reply_group_members", ""))) {
                if (entry.equals(Long.toString(sender)) || senderName != null && entry.equalsIgnoreCase(senderName.trim())) return true;
            }
            return false;
        }
        if (!"mentions".equals(mode)) return false;
        try {
            String ext = values.getAsString("ext");
            if (ext == null) return false;
            JSONObject extension = new JSONObject(ext);
            // 40.6.0 Message.LJIJJ parses this server field as comma-separated numeric UIDs.
            Object mentions = extension.opt("s:mentioned_users");
            return mentions instanceof String && entries((String) mentions).contains(Long.toString(self));
        } catch (org.json.JSONException | RuntimeException ignored) { return false; }
    }
}

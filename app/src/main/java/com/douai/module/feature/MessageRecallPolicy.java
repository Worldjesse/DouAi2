package com.douai.module.feature;

import android.content.ContentValues;
import com.douai.module.config.LocalOptions;
import org.json.JSONObject;

/** Preserves the row's original content while retaining unrelated IM extension metadata. */
public final class MessageRecallPolicy {
    private MessageRecallPolicy() { }

    public static boolean shouldProtect(JSONObject extension, long ownUid, LocalOptions options) {
        if (extension == null || ownUid <= 0 || !options.enabled("prevent_message_recall")) return false;
        if (!"true".equalsIgnoreCase(extension.optString("s:is_recalled", "false"))
                && !"true".equalsIgnoreCase(extension.optString("s:recalled", "false"))) return false;
        String recalledBy = extension.optString("s:recall_uid", "");
        if (recalledBy.isEmpty()) return false;
        boolean own = Long.toString(ownUid).equals(recalledBy);
        return options.enabled(own ? "recall_self" : "recall_other", true);
    }

    public static String uuid(ContentValues values, Object where, Object arguments) {
        String direct = values == null ? null : values.getAsString("msg_uuid");
        if (direct != null && !direct.isEmpty()) return direct;
        if (!(where instanceof String) || !(arguments instanceof String[])) return null;
        String[] selectors = (String[]) arguments;
        return selectors.length == 1 && "msg_uuid=?".equals(((String) where).replaceAll("\\s+", ""))
                ? selectors[0] : null;
    }

    public static ContentValues protect(ContentValues original, long ownUid, LocalOptions options) {
        if (original == null || ownUid <= 0 || !options.enabled("prevent_message_recall")) return original;
        try {
            String raw = original.getAsString("ext");
            if (raw == null || raw.trim().isEmpty()) return original;
            JSONObject extension = new JSONObject(raw);
            if (!shouldProtect(extension, ownUid, options)) return original;
            ContentValues copy = new ContentValues(original);
            extension.put("s:is_recalled", "false");
            extension.put("s:recalled", "false");
            extension.remove("s:recall_uid");
            extension.remove("s:recall_role");
            extension.remove("s:ignore_visible");
            copy.put("ext", extension.toString());
            for (String field : new String[]{"content", "type", "deleted", "msg_status"}) copy.remove(field);
            return copy;
        } catch (org.json.JSONException | RuntimeException ignored) { return original; }
    }
}

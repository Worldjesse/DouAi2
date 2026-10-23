package com.douai.module.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;

/** Redacts before a record reaches either storage or an external logging sink. */
final class DebugLogRedactor {
    static final int MAX_INPUT_CHARS = 262_144;
    private static final String MASK = "[REDACTED]";
    private static final String KEYS = "(?:[a-z0-9_.-]{0,128}(?:authorization|api[-_]?key|token|password|passwd|secret|cookie|signature|secret[-_]?key|encrypt(?:ion)?[-_]?key|aes[-_]?key|private[-_]?key|auth[-_]?key)|skey|tkey|key|sign)";
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(?:Bearer|Basic)\\s+[^\\s\"'\\\\,;}]+(?:=*)");
    private static final Pattern QUOTED = Pattern.compile("(?i)(\\b(?:" + KEYS + ")(?:\\\\*[\"'])?\\s*[:=]\\s*)(\\\\*[\"'])(.*?)(\\2)", Pattern.DOTALL);
    private static final Pattern VALUE = Pattern.compile("(?i)(\\b(?:" + KEYS + ")(?:\\\\*[\"'])?\\s*[:=]\\s*(?:\\\\*[\"'])?)[^\\s,;&}\\\"']+");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]{0,31}://[^\\s\"'<>?\\\\]+)\\?[^\\s\"'<>\\\\]*");
    private static final Pattern URL_USER = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]{0,31}://)[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern COOKIE_LINE = Pattern.compile("(?i)(\\b(?:set-cookie|cookie)\\s*[:=]\\s*)[^\\r\\n\"']*");

    private DebugLogRedactor() { }

    static String text(String value) {
        if (value == null) return "";
        int marker = value.indexOf("json=");
        if (marker >= 0) {
            String body = value.substring(marker + 5).trim();
            if (body.startsWith("{") || body.startsWith("[")) {
                return plain(value.substring(0, Math.min(marker + 5, 1024))) + json(body);
            }
        }
        String trimmed = value.trim();
        if (value.length() > MAX_INPUT_CHARS && (trimmed.startsWith("{") || trimmed.startsWith("["))) return "{\"_debug_json_truncated\":true}";
        if (value.length() > MAX_INPUT_CHARS) value = value.substring(0, MAX_INPUT_CHARS) + " [input truncated]";
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            String json = jsonOrNull(trimmed, 0);
            if (json != null) return json;
        }
        return plain(value);
    }

    static String json(String value) {
        if (value == null || value.length() > MAX_INPUT_CHARS) return "{\"_debug_json_truncated\":true}";
        String safe = jsonOrNull(value, 0);
        return safe == null ? "{\"_debug_json_unavailable\":true}" : safe;
    }

    private static String jsonOrNull(String value, int depth) {
        if (depth > 24) return "{\"_debug_json_depth_limited\":true}";
        try {
            JSONTokener reader = new JSONTokener(value);
            Object parsed = reader.nextValue();
            if (!(parsed instanceof JSONObject) && !(parsed instanceof JSONArray) || reader.nextClean() != 0) return null;
            return clean(parsed, depth).toString();
        } catch (JSONException | RuntimeException | StackOverflowError invalid) { return null; }
    }

    private static Object clean(Object value, int depth) throws JSONException {
        if (depth > 24) return "[DEPTH LIMITED]";
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject target = new JSONObject();
            Iterator<String> names = source.keys();
            while (names.hasNext()) {
                String key = names.next();
                target.put(key, secret(key) ? MASK : clean(source.get(key), depth + 1));
            }
            return target;
        }
        if (value instanceof JSONArray) {
            JSONArray target = new JSONArray();
            JSONArray source = (JSONArray) value;
            for (int index = 0; index < source.length(); index++) target.put(clean(source.get(index), depth + 1));
            return target;
        }
        if (value instanceof String) {
            String string = (String) value;
            String nested = jsonOrNull(string, depth + 1);
            return nested == null ? plain(string) : nested;
        }
        return value;
    }

    private static boolean secret(String name) {
        String key = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return key.endsWith("authorization") || key.endsWith("apikey") || key.endsWith("token")
                || key.endsWith("password") || key.endsWith("passwd") || key.endsWith("secret") || key.endsWith("cookie")
                || key.equals("key") || key.equals("skey") || key.equals("tkey") || key.endsWith("secretkey") || key.endsWith("encryptkey")
                || key.endsWith("encryptionkey") || key.endsWith("aeskey") || key.endsWith("privatekey") || key.endsWith("authkey")
                || key.endsWith("signature") || key.equals("sign");
    }

    private static String plain(String value) {
        String safe = BEARER.matcher(value).replaceAll("[AUTH_REDACTED]");
        safe = QUOTED.matcher(safe).replaceAll("$1$2[REDACTED]$2");
        safe = VALUE.matcher(safe).replaceAll("$1[REDACTED]");
        safe = COOKIE_LINE.matcher(safe).replaceAll("$1[REDACTED]");
        safe = URL_USER.matcher(safe).replaceAll("$1[REDACTED]@");
        return URL_QUERY.matcher(safe).replaceAll("$1?[QUERY_REDACTED]");
    }
}

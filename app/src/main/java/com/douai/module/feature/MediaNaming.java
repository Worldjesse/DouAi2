package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands the download template without letting metadata become path components. */
public final class MediaNaming {
    public static final String DEFAULT_TEMPLATE = "#author#_#desc#";
    private static final Pattern TOKEN = Pattern.compile("#([A-Za-z]+)#|\\$?\\{([A-Za-z]+)\\}");
    private MediaNaming() { }

    public static String forAweme(Object aweme, LocalOptions options, String type,
                                  int index, String extension) {
        Object author = DownloadActionHook.jsonValue(aweme, "author");
        Object music = DownloadActionHook.jsonValue(aweme, "music");
        Map<String, String> values = new HashMap<>();
        values.put("author", string(author, "nickname"));
        values.put("nickname", values.get("author"));
        values.put("desc", string(aweme, "desc"));
        values.put("title", string(aweme, "title"));
        values.put("caption", string(aweme, "caption"));
        values.put("aid", string(aweme, "aweme_id"));
        values.put("uid", string(author, "uid"));
        values.put("shortId", string(author, "short_id"));
        values.put("secUid", string(author, "sec_uid"));
        values.put("uniqueId", first(string(author, "unique_id"), values.get("shortId")));
        values.put("music", first(string(music, "title"), string(music, "music_name")));
        values.put("musicName", values.get("music"));
        values.put("type", type);
        values.put("index", index > 0 ? String.valueOf(index) : "");
        String fallback = first(values.get("desc"), "DouAi") + "_" + values.get("aid");
        String template = options.enabled("download_custom_name")
                ? options.text("download_name_template", DEFAULT_TEMPLATE) : fallback;
        if (index > 0 && !template.contains("#index#") && !template.contains("{index}")) {
            template += "_" + String.format(Locale.ROOT, "%02d", index);
        }
        String name = expand(template, values, System.currentTimeMillis(), extension);
        if (name.isEmpty()) name = sanitize(fallback, extension);
        return name;
    }

    public static String expand(String template, Map<String, String> metadata,
                                 long nowMillis, String extension) {
        Map<String, String> values = new HashMap<>(metadata);
        Date now = new Date(nowMillis);
        values.put("date", new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(now));
        values.put("time", new SimpleDateFormat("HHmmss", Locale.ROOT).format(now));
        values.put("datetime", new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now));
        values.put("timestamp", Long.toString(nowMillis));
        values.put("ext", extension == null ? "" : extension);
        Matcher matcher = TOKEN.matcher(template == null ? "" : template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            String value = values.get(key);
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(output);
        return sanitize(output.toString(), extension);
    }

    static String sanitize(String input, String extension) {
        String ext = extension == null ? "" : extension.replaceAll("[^A-Za-z0-9]", "");
        String name = input.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ").replaceAll("_+", "_").trim();
        if (!ext.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) {
            name = name.substring(0, name.length() - ext.length() - 1);
        }
        name = name.replaceAll("^[ ._-]+|[ ._-]+$", "");
        if (name.isEmpty()) return "";
        // MediaStore ultimately uses a filesystem with a byte-based filename limit.
        StringBuilder limited = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < name.length();) {
            int point = name.codePointAt(offset);
            String part = new String(Character.toChars(point));
            int size = part.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 180) break;
            limited.append(part);
            bytes += size;
            offset += Character.charCount(point);
        }
        return limited + (ext.isEmpty() ? "" : "." + ext);
    }

    private static String string(Object model, String key) {
        Object value = DownloadActionHook.jsonValue(model, key);
        return value == null ? "" : value.toString();
    }

    private static String first(String first, String fallback) {
        return first == null || first.trim().isEmpty() ? fallback : first;
    }
}

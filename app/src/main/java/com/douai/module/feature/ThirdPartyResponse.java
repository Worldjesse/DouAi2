package com.douai.module.feature;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Flattens structured responses without converting long work IDs to floating point. */
public final class ThirdPartyResponse {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FIELDS = 8192;
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\p{Z}<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TXT_FIELD = Pattern.compile("^([^:=：]{1,160})\\s*[:=：]\\s*(.*)$");
    private final Map<String, String> values;
    private final Map<String, List<String>> downloads;

    private ThirdPartyResponse(Map<String, String> values, Map<String, List<String>> downloads) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : downloads.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.downloads = Collections.unmodifiableMap(copy);
    }

    public Map<String, String> values() { return values; }
    public Map<String, List<String>> downloadFields() { return downloads; }

    public static ThirdPartyResponse parse(String body) {
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("解析接口返回内容为空");
        if (body.length() > MAX_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("解析内容超过 2 MiB");
        }
        String text = body.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, List<String>> downloads = new LinkedHashMap<>();
        if (text.startsWith("{") || text.startsWith("[") || text.startsWith("\"")) {
            Object root;
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                root = read(reader, 0, new int[]{0});
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            } catch (Exception failure) {
                throw new IllegalArgumentException("JSON 格式无效或嵌套过深");
            }
            checkStatus(root);
            flatten(root, "", values, downloads);
        } else {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<?xml")) {
                throw new IllegalArgumentException("解析接口返回了网页，请检查 GET 地址");
            }
            parseText(text, values, downloads);
        }
        if (values.isEmpty()) throw new IllegalArgumentException("响应中没有可解析的字段");
        return new ThirdPartyResponse(values, downloads);
    }

    public String metadataText(Set<String> downloadedFields) {
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (downloadedFields.contains(entry.getKey())) {
                List<String> urls = downloads.get(entry.getKey());
                if (urls == null || value.equals(jsonStrings(urls))) continue;
                for (String url : urls) value = value.replace(url, "");
                value = value.trim();
                if (value.isEmpty()) continue;
            }
            output.append(entry.getKey()).append(": ").append(value).append('\n');
        }
        return output.toString();
    }

    private static Object read(JsonReader reader, int depth, int[] count) throws Exception {
        if (depth > 48 || ++count[0] > MAX_FIELDS) throw new IllegalArgumentException();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                Map<String, Object> object = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.isEmpty() || object.containsKey(key)) throw new IllegalArgumentException();
                    object.put(key, read(reader, depth + 1, count));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                List<Object> array = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1, count));
                reader.endArray();
                return array;
            case STRING: return reader.nextString();
            case NUMBER: return new NumericValue(reader.nextString());
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return null;
            default: throw new IllegalArgumentException();
        }
    }

    private static void checkStatus(Object root) {
        if (!(root instanceof Map)) return;
        Map<?, ?> object = (Map<?, ?>) root;
        if (Boolean.FALSE.equals(object.get("success"))) {
            throw new IllegalStateException("解析接口返回失败状态");
        }
    }

    private static void flatten(Object value, String path, Map<String, String> values,
                                Map<String, List<String>> downloads) {
        String key = path.isEmpty() ? "value" : path;
        if (value instanceof Map) {
            Map<?, ?> object = (Map<?, ?>) value;
            if (object.isEmpty()) values.put(key, "{}");
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                String child = escapePath(entry.getKey().toString());
                flatten(entry.getValue(), path.isEmpty() ? child : path + "." + child, values, downloads);
            }
        } else if (value instanceof List) {
            List<?> array = (List<?>) value;
            List<String> urls = new ArrayList<>();
            boolean onlyUrls = true;
            for (Object item : array) {
                if (!(item instanceof String) || !validUrl((String) item)) { onlyUrls = false; break; }
                urls.add((String) item);
            }
            if (onlyUrls) {
                values.put(key, jsonStrings(urls));
                downloads.put(key, urls);
            } else {
                for (int i = 0; i < array.size(); i++) flatten(array.get(i), path + "[" + i + "]", values, downloads);
            }
        } else {
            String text = value == null ? "null" : value.toString();
            values.put(key, text);
            if (value instanceof String && validUrl(text)) downloads.put(key, Collections.singletonList(text));
        }
    }

    private static void parseText(String text, Map<String, String> values, Map<String, List<String>> downloads) {
        Map<String, StringBuilder> fields = new LinkedHashMap<>();
        StringBuilder rest = new StringBuilder();
        List<String> looseUrls = new ArrayList<>();
        String lastKey = null;
        for (String line : text.split("\\r?\\n")) {
            if (fields.size() + looseUrls.size() > MAX_FIELDS) throw new IllegalArgumentException("TXT 字段过多");
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher field = TXT_FIELD.matcher(trimmed);
            if (!trimmed.regionMatches(true, 0, "http", 0, 4) && field.matches()) {
                String base = escapePath(field.group(1).trim());
                String key = base;
                for (int n = 2; fields.containsKey(key); n++) key = base + "[" + n + "]";
                fields.put(key, new StringBuilder(field.group(2).trim()));
                lastKey = key;
            } else if (lastKey != null) {
                fields.get(lastKey).append('\n').append(trimmed);
            } else {
                List<String> found = urls(trimmed);
                if (found.size() == 1 && found.get(0).equals(trimmed)) looseUrls.addAll(found);
                else rest.append(trimmed).append('\n');
            }
        }
        for (Map.Entry<String, StringBuilder> field : fields.entrySet()) {
            String value = field.getValue().toString();
            if (value.startsWith("[") || value.startsWith("{")) {
                try (JsonReader reader = new JsonReader(new StringReader(value))) {
                    reader.setLenient(false);
                    Object parsed = read(reader, 0, new int[]{0});
                    if (reader.peek() == JsonToken.END_DOCUMENT) {
                        flatten(parsed, field.getKey(), values, downloads);
                        continue;
                    }
                } catch (Exception ignored) { }
            }
            List<String> found = urls(value);
            values.put(field.getKey(), value);
            if (!found.isEmpty()) downloads.put(field.getKey(), found);
        }
        if (!looseUrls.isEmpty()) {
            String key = unusedKey(values, "links");
            values.put(key, jsonStrings(looseUrls));
            downloads.put(key, looseUrls);
        }
        if (rest.length() > 0) {
            String key = unusedKey(values, "text");
            values.put(key, rest.toString().trim());
            List<String> found = urls(rest.toString());
            if (!found.isEmpty()) downloads.put(key, found);
        }
    }

    private static String unusedKey(Map<String, String> values, String name) {
        String key = name;
        for (int suffix = 2; values.containsKey(key); suffix++) key = name + "[" + suffix + "]";
        return key;
    }

    private static List<String> urls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            while (!value.isEmpty() && ".,;)]}，。；）】".indexOf(value.charAt(value.length() - 1)) >= 0) {
                value = value.substring(0, value.length() - 1);
            }
            if (validUrl(value)) urls.add(value);
        }
        return urls;
    }

    private static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static String escapePath(String key) {
        return key.replace("\\", "\\\\").replace(".", "\\.").replace("[", "\\[").replace("]", "\\]");
    }

    private static String jsonStrings(List<String> values) {
        return new org.json.JSONArray(values).toString().replace("\\/", "/");
    }

    private static final class NumericValue {
        private final String value;
        NumericValue(String value) { this.value = value; }
        @Override public String toString() { return value; }
    }
}

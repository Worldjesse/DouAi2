package com.douai.module.feature;

import android.util.JsonReader;
import android.util.JsonToken;

import com.douai.module.config.SpeechSettings;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses configurable JSON arrays/maps or explicit ID/name TXT rows. */
public final class VoiceCatalog {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 2048;
    private static final String[] LIST_NAMES = {"voices", "voice", "speakers", "list", "items", "data", "result"};
    private static final String[] ID_NAMES = {"id", "voice", "voice_id", "speaker_id", "speaker", "code", "value"};
    private static final String[] NAME_NAMES = {"name", "voice_name", "speaker_name", "title", "label", "nickname"};
    private static final Pattern TXT_ROW = Pattern.compile("^([^\\s:=：,，|]{1,256})\\s*[:=：,，|\\t ]\\s*(.+)$");
    private static final Pattern NUMBERED_TXT_ROW = Pattern.compile("^([0-9]{1,256})[.、．]\\s*(.+)$");

    private VoiceCatalog() { }

    public static final class Entry {
        public final String id;
        public final String name;

        public Entry(String id, String name) {
            if (id == null || id.trim().isEmpty() || id.length() > 256
                    || name == null || name.trim().isEmpty() || name.length() > 512) {
                throw new IllegalArgumentException("音色编号或名称无效");
            }
            this.id = id.trim();
            this.name = name.trim();
        }
    }

    public static List<Entry> parse(String body, SpeechSettings settings) {
        return parse(body, settings.voiceListPath, settings.voiceIdPath, settings.voiceNamePath);
    }

    public static List<Entry> parse(String body, String listPath, String idPath, String namePath) {
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("音色列表为空");
        if (body.length() > MAX_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("音色列表超过 2 MiB");
        }
        String text = body.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (text.startsWith("{") || text.startsWith("[")) {
            Object root;
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                root = readJson(reader, 0, new int[]{0});
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            } catch (Exception ignored) {
                throw new IllegalArgumentException("音色列表 JSON 格式无效或嵌套过深");
            }
            if (root instanceof Map && Boolean.FALSE.equals(((Map<?, ?>) root).get("success"))) {
                throw new IllegalArgumentException("音色接口返回失败状态");
            }
            Object list = empty(listPath) ? autoList(root, 0) : atPath(root, listPath);
            if (list == null) throw new IllegalArgumentException("未找到音色列表字段");
            if (list instanceof List) {
                for (Object item : (List<?>) list) addEntry(entries, item, null, idPath, namePath);
            } else if (list instanceof Map) {
                Map<?, ?> mapping = (Map<?, ?>) list;
                if (first(mapping, ID_NAMES) != null) addEntry(entries, list, null, idPath, namePath);
                else for (Map.Entry<?, ?> item : mapping.entrySet()) {
                    addEntry(entries, item.getValue(), item.getKey().toString(), idPath, namePath);
                }
            } else throw new IllegalArgumentException("音色列表应为数组或编号映射");
        } else {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.startsWith("<") || lower.startsWith("http://") || lower.startsWith("https://")) {
                throw new IllegalArgumentException("音色接口未返回 JSON/TXT 音色列表");
            }
            if (!empty(listPath) || !empty(idPath) || !empty(namePath)) {
                throw new IllegalArgumentException("TXT 音色列表无需设置 JSON 字段路径");
            }
            for (String line : text.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                Matcher numbered = NUMBERED_TXT_ROW.matcher(trimmed);
                if (numbered.matches()) {
                    put(entries, numbered.group(1), numbered.group(2));
                    continue;
                }
                Matcher row = TXT_ROW.matcher(trimmed);
                if (row.matches()) put(entries, row.group(1), row.group(2));
                else if (trimmed.matches("[0-9A-Za-z_-]{1,256}")) put(entries, trimmed, trimmed);
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("未找到有效音色编号，请检查列表及编号字段");
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    private static void addEntry(Map<String, Entry> entries, Object item, String mapId,
                                 String idPath, String namePath) {
        if (item instanceof Map) {
            Map<?, ?> object = (Map<?, ?>) item;
            String id = scalar(empty(idPath) ? first(object, ID_NAMES) : atPath(item, idPath));
            if (id == null && empty(idPath)) id = mapId;
            String name = scalar(empty(namePath) ? first(object, NAME_NAMES) : atPath(item, namePath));
            if (name == null && empty(namePath)) name = id;
            put(entries, id, name);
        } else if (empty(idPath) && empty(namePath)) {
            String value = scalar(item);
            put(entries, mapId == null ? value : mapId, value);
        }
    }

    private static void put(Map<String, Entry> entries, String id, String name) {
        if (id == null || name == null || id.trim().isEmpty() || name.trim().isEmpty()) return;
        Entry entry = new Entry(id, name);
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(entry.id)) {
            throw new IllegalArgumentException("音色数量超过 2048");
        }
        if (!entries.containsKey(entry.id)) entries.put(entry.id, entry);
    }

    private static Object autoList(Object value, int depth) {
        if (!(value instanceof Map) || depth > 6) return value;
        Map<?, ?> map = (Map<?, ?>) value;
        for (String name : LIST_NAMES) {
            Object candidate = map.get(name);
            if (candidate instanceof List || candidate instanceof Map) return autoList(candidate, depth + 1);
        }
        if (map.containsKey("code") || map.containsKey("status") || map.containsKey("message")
                || map.containsKey("msg") || map.containsKey("error") || map.containsKey("success")) return null;
        return value;
    }

    private static Object first(Map<?, ?> map, String[] keys) {
        for (String key : keys) if (scalar(map.get(key)) != null) return map.get(key);
        return null;
    }

    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number ? value.toString() : null;
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    static Object atPath(Object root, String path) {
        if (empty(path) || "$".equals(path)) return root;
        String value = path.startsWith("$.") ? path.substring(2) : path;
        Object current = root;
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) == '.') return null;
            if (value.charAt(i) != '[') {
                StringBuilder key = new StringBuilder();
                while (i < value.length() && value.charAt(i) != '.' && value.charAt(i) != '[') {
                    char character = value.charAt(i++);
                    if (character == '\\') {
                        if (i == value.length()) return null;
                        character = value.charAt(i++);
                    }
                    key.append(character);
                }
                if (!(current instanceof Map) || key.length() == 0) return null;
                current = ((Map<?, ?>) current).get(key.toString());
            }
            while (i < value.length() && value.charAt(i) == '[') {
                int end = value.indexOf(']', i);
                if (end < 0 || !(current instanceof List)) return null;
                int index;
                try { index = Integer.parseInt(value.substring(i + 1, end)); }
                catch (NumberFormatException ignored) { return null; }
                List<?> list = (List<?>) current;
                if (index < 0 || index >= list.size()) return null;
                current = list.get(index);
                i = end + 1;
            }
            if (i < value.length()) {
                if (value.charAt(i++) != '.' || i == value.length()) return null;
            }
        }
        return current;
    }

    private static Object readJson(JsonReader reader, int depth, int[] count) throws Exception {
        if (depth > 32 || ++count[0] > 16384) throw new IllegalArgumentException();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                Map<String, Object> object = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.containsKey(key)) throw new IllegalArgumentException();
                    object.put(key, readJson(reader, depth + 1, count));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                List<Object> array = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1, count));
                reader.endArray();
                return array;
            case STRING: case NUMBER: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return null;
            default: throw new IllegalArgumentException();
        }
    }
}

package com.douai.module.config;

import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Strict JSON for untrusted backups, including duplicate-name and trailing-data rejection. */
final class ConfigJson {
    private ConfigJson() { }
    static Object parse(String text) {
        return parse(text, ConfigTransfer.MAX_BYTES, 4096);
    }
    static Object parse(String text, int maxBytes, int maxArrayItems) {
        if (text == null || text.length() > maxBytes
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) throw invalid();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value = read(reader, 0, maxArrayItems);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return value;
        } catch (Exception failure) { throw invalid(); }
    }
    private static Object read(JsonReader reader, int depth, int maxArrayItems) throws Exception {
        if (depth > 32) throw invalid();
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JSONObject result = new JSONObject();
                Set<String> names = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!names.add(key) || names.size() > 4096) throw invalid();
                    result.put(key, read(reader, depth + 1, maxArrayItems));
                }
                reader.endObject(); return result;
            }
            case BEGIN_ARRAY: {
                JSONArray result = new JSONArray(); reader.beginArray();
                while (reader.hasNext()) {
                    if (result.length() >= maxArrayItems) throw invalid();
                    result.put(read(reader, depth + 1, maxArrayItems));
                }
                reader.endArray(); return result;
            }
            case STRING: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            case NUMBER: {
                String number = reader.nextString();
                if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
                if (!number.contains(".") && !number.contains("e") && !number.contains("E")) return Long.parseLong(number);
                double value = Double.parseDouble(number);
                if (!Double.isFinite(value)) throw invalid();
                return value;
            }
            default: throw invalid();
        }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("配置文件格式无效或内容不完整"); }
}

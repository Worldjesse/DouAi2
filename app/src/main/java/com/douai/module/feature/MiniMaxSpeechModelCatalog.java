package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Keeps API discovery and the public T2A schema separate from text-model catalogs. */
public final class MiniMaxSpeechModelCatalog {
    public static final String DOCUMENT_URL =
            "https://platform.minimax.cn/docs/api-reference/speech/t2a/api/openapi.json";
    private static final Pattern SPEECH_ID = Pattern.compile("speech-[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final List<String> BUILT_IN = Collections.unmodifiableList(Arrays.asList(
            "speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
            "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo"));

    private MiniMaxSpeechModelCatalog() { }

    public static List<String> builtInModels() { return BUILT_IN; }

    /** Parse the documented request's model enum, never unrelated examples or other enums. */
    public static List<String> parse(String document) {
        try {
            JSONObject root = object(document);
            JSONArray values = root.getJSONObject("components").getJSONObject("schemas")
                    .getJSONObject("T2aV2Req").getJSONObject("properties")
                    .getJSONObject("model").getJSONArray("enum");
            if (values.length() > 256) throw new IllegalArgumentException();
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < values.length(); i++) {
                Object id = values.get(i);
                if (!(id instanceof String) || !SPEECH_ID.matcher((String) id).matches()) {
                    throw new IllegalArgumentException();
                }
                ids.add((String) id);
            }
            if (ids.isEmpty()) throw new IllegalArgumentException();
            return Collections.unmodifiableList(new ArrayList<>(ids));
        } catch (Exception ignored) {
            throw new IllegalArgumentException("MiniMax 语音官方模型文档格式无效，请保留当前模型或手动填写");
        }
    }

    public static List<String> parseApi(String document) {
        try {
            JSONObject root = object(document);
            JSONArray values = root.getJSONArray("data");
            if (values.length() > 4096) throw new IllegalArgumentException();
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null || !(item.opt("id") instanceof String)) continue;
                String id = item.getString("id");
                if (SPEECH_ID.matcher(id).matches()) ids.add(id);
                if (ids.size() > 256) throw new IllegalArgumentException();
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("MiniMax 语音模型接口未返回语音模型，请刷新官方模型列表或手动填写");
            }
            return Collections.unmodifiableList(new ArrayList<>(ids));
        } catch (Exception failure) {
            String message = failure.getMessage();
            if (message != null && message.equals("MiniMax 语音模型接口未返回语音模型，请刷新官方模型列表或手动填写")) {
                throw new IllegalArgumentException(message);
            }
            throw new IllegalArgumentException("MiniMax 语音模型接口响应格式无效");
        }
    }

    private static JSONObject object(String document) throws Exception {
        if (document == null || document.length() > SpeechHttpClient.MAX_TEXT_BYTES
                || document.getBytes(StandardCharsets.UTF_8).length > SpeechHttpClient.MAX_TEXT_BYTES) {
            throw new IllegalArgumentException();
        }
        return new JSONObject(document);
    }
}

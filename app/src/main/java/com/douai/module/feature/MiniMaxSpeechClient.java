package com.douai.module.feature;

import com.douai.module.config.SpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** MiniMax's non-streaming T2A protocol; credentials are sent only to its configured API. */
public final class MiniMaxSpeechClient {
    private MiniMaxSpeechClient() { }

    public static SpeechHttpClient.Resource synthesize(SpeechHttpClient.Request request, String text,
                                                       SpeechSettings settings) throws IOException {
        request.ensureActive();
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("MiniMax 语音内容为空");
        if (text.codePointCount(0, text.length()) >= 10_000) {
            throw new IllegalArgumentException("MiniMax 语音文本需少于 10000 个字符");
        }
        try {
            try { settings.validateMiniMax(); }
            catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("MiniMax 语音配置无效：" + invalid.getMessage());
            }
            JSONObject body = new JSONObject().put("model", required(settings.minimaxModel, "模型"))
                    .put("text", text).put("stream", false).put("output_format", "url")
                    .put("voice_setting", new JSONObject()
                            .put("voice_id", settings.minimaxOptions.hasMixedVoices() ? ""
                                    : required(settings.minimaxVoiceId, "音色 ID"))
                            .put("speed", settings.minimaxSpeed).put("vol", settings.minimaxVolume)
                            .put("pitch", settings.minimaxPitch))
                    .put("audio_setting", new JSONObject().put("sample_rate", 32000).put("bitrate", 128000)
                            .put("format", "mp3").put("channel", 1));
            settings.minimaxOptions.applyTo(body);
            JSONObject response = post(request, settings, "t2a_v2", body);
            JSONObject data = response.optJSONObject("data");
            if (data == null || (data.has("status") && integer(data.opt("status")) != 2)) {
                throw new IllegalArgumentException("MiniMax 语音未返回完整音频");
            }
            Object value = data.opt("audio");
            if (!(value instanceof String) || ((String) value).isEmpty()) {
                throw new IllegalArgumentException("MiniMax 语音未返回音频");
            }
            String audio = (String) value;
            SpeechHttpClient.Resource resource;
            if (audio.startsWith("https://") || audio.startsWith("http://")) {
                // Signed media links work without API credentials, including on the API's own origin.
                resource = request.fetchResource(audio, "{}");
            } else {
                resource = new SpeechHttpClient.Resource(decodeHex(audio, request), "");
            }
            request.ensureActive();
            resource = MiniMaxAudioFormat.normalize(resource, settings.minimaxOptions.audioFormat,
                    settings.minimaxOptions.channel, request::ensureActive);
            if (!resource.isAudio()) throw new IllegalArgumentException("MiniMax 语音链接未返回音频文件");
            return resource;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw sanitized(failure);
        }
    }

    public static List<String> fetchModels(SpeechHttpClient.Request request,
                                           SpeechSettings settings) throws IOException {
        request.ensureActive();
        try {
            String json = request.fetchText(endpoint(settings.minimaxBaseUrl, "models"), apiHeaders(settings));
            request.ensureActive();
            JSONObject response = response(json, false);
            List<String> models = MiniMaxSpeechModelCatalog.parseApi(response.toString());
            request.ensureActive();
            return models;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw sanitized(failure, "模型列表");
        }
    }

    public static List<String> fetchDocumentModels(SpeechHttpClient.Request request) throws IOException {
        return fetchDocumentModels(request, MiniMaxSpeechModelCatalog.DOCUMENT_URL);
    }

    static List<String> fetchDocumentModels(SpeechHttpClient.Request request, String url) throws IOException {
        request.ensureActive();
        try {
            // A fixed public documentation endpoint is unrelated to the user's private API credentials.
            String document = request.fetchText(url, "{}");
            request.ensureActive();
            List<String> models = MiniMaxSpeechModelCatalog.parse(document);
            request.ensureActive();
            return models;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw sanitized(failure, "官方模型列表");
        }
    }

    public static List<VoiceCatalog.Entry> fetchVoices(SpeechHttpClient.Request request,
                                                       SpeechSettings settings) throws IOException {
        request.ensureActive();
        try {
            JSONObject response = post(request, settings, "get_voice", new JSONObject().put("voice_type", "all"));
            Map<String, VoiceCatalog.Entry> voices = new LinkedHashMap<>();
            for (String group : new String[]{"system_voice", "voice_cloning", "voice_generation"}) {
                Object value = response.opt(group);
                if (value == null || value == JSONObject.NULL) continue;
                if (!(value instanceof JSONArray)) throw new IllegalArgumentException("MiniMax 语音音色列表格式无效");
                JSONArray entries = (JSONArray) value;
                for (int index = 0; index < entries.length(); index++) {
                    request.ensureActive();
                    JSONObject entry = entries.optJSONObject(index);
                    if (entry == null || !(entry.opt("voice_id") instanceof String)) continue;
                    String id = entry.getString("voice_id").trim();
                    if (id.isEmpty()) continue;
                    Object nameValue = entry.opt("voice_name");
                    if (!(nameValue instanceof String)) nameValue = entry.opt("name");
                    String name = nameValue instanceof String ? ((String) nameValue).trim() : id;
                    if (name.isEmpty()) name = id;
                    if (voices.size() >= 2048 && !voices.containsKey(id)) {
                        throw new IllegalArgumentException("MiniMax 语音音色数量超过 2048");
                    }
                    if (!voices.containsKey(id)) voices.put(id, new VoiceCatalog.Entry(id, name));
                }
            }
            if (voices.isEmpty()) throw new IllegalArgumentException("MiniMax 语音音色列表为空，可手动填写音色 ID");
            return Collections.unmodifiableList(new ArrayList<>(voices.values()));
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw sanitized(failure);
        }
    }

    private static JSONObject post(SpeechHttpClient.Request request, SpeechSettings settings,
                                    String path, JSONObject body) throws Exception {
        String json = request.postJson(endpoint(settings.minimaxBaseUrl, path), apiHeaders(settings), body.toString());
        request.ensureActive();
        return response(json, true);
    }

    private static String apiHeaders(SpeechSettings settings) throws Exception {
        String key = required(settings.minimaxApiKey, "API Key");
        // Reject line breaks/control characters before constructing the header object.
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) <= 32 || key.charAt(i) >= 127) {
                throw new IllegalArgumentException("MiniMax 语音 API Key 格式无效");
            }
        }
        return new JSONObject().put("Authorization", "Bearer " + key).toString();
    }

    private static JSONObject response(String json, boolean requireStatus) {
        JSONObject response;
        try { response = new JSONObject(json); }
        catch (Exception ignored) { throw new IllegalArgumentException("MiniMax 语音响应不是有效的 JSON"); }
        JSONObject status = response.optJSONObject("base_resp");
        if (status == null) {
            if (!requireStatus && !response.has("base_resp")) return response;
            throw new IllegalArgumentException("MiniMax 语音响应缺少状态码");
        }
        int code = integer(status.opt("status_code"));
        if (code != 0) {
            // status_msg may contain submitted text or credentials. Report only a bounded numeric code.
            String hint;
            switch (code) {
                case 1001: hint = "，请求超时"; break;
                case 1002: case 1039: hint = "，请求过于频繁"; break;
                case 1004: case 2049: hint = "，请检查 API Key"; break;
                case 1008: hint = "，账户余额不足"; break;
                case 2013: case 20132: hint = "，请检查模型与音色参数"; break;
                case 2042: hint = "，当前账户无所选音色权限"; break;
                default: hint = "";
            }
            throw new IllegalArgumentException("MiniMax 语音接口返回错误码 " + code + hint);
        }
        return response;
    }

    private static String endpoint(String base, String path) {
        try {
            URI uri = new URI(base);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                throw new IllegalArgumentException();
            }
            String normalized = base.replaceAll("/+$", "");
            return normalized + (normalized.endsWith("/v1") ? "/" : "/v1/") + path;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("MiniMax 语音 API 地址必须是无查询参数的 HTTP(S) 基础地址");
        }
    }

    private static int integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("MiniMax 语音响应状态码无效");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > 999_999) {
            throw new IllegalArgumentException("MiniMax 语音响应状态码无效");
        }
        return (int) number;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("MiniMax 语音请先设置 " + name);
        return value.trim();
    }

    private static byte[] decodeHex(String hex, SpeechHttpClient.Request request) {
        if ((hex.length() & 1) != 0 || hex.length() / 2 > SpeechHttpClient.MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("MiniMax 语音音频编码无效或过大");
        }
        byte[] decoded = new byte[hex.length() / 2];
        for (int index = 0; index < decoded.length; index++) {
            if ((index & 8191) == 0) request.ensureActive();
            int high = Character.digit(hex.charAt(index * 2), 16);
            int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("MiniMax 语音音频编码无效");
            decoded[index] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private static IOException sanitized(Exception failure) {
        return sanitized(failure, "");
    }

    private static IOException sanitized(Exception failure, String stage) {
        String message = failure.getMessage();
        if (message != null && message.matches("第三方语音 GET 返回 HTTP [0-9]{3}")) {
            message = "MiniMax 语音" + stage + "返回 HTTP " + message.substring(message.length() - 3);
        } else if ("第三方语音 GET 请求超时".equals(message)) {
            message = "MiniMax 语音" + stage + "请求超时";
        } else if (message == null || !message.startsWith("MiniMax 语音")) {
            message = "MiniMax 语音" + stage + "请求失败，请检查接口与网络";
        }
        return new IOException(message);
    }
}

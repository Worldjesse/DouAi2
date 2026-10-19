package com.douai.module.config;

import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** A self-contained model profile. Optional parameters are omitted unless enabled. */
public final class AiModelConfig {
    public static final int MAX_CONTACT_AVATAR_BYTES = 16 * 1024;
    public static final String CHAT = "chat_completions";
    public static final String RESPONSES = "responses";
    public static final String ANTHROPIC = "anthropic";
    public static final String GEMINI = "gemini";
    public static final String[] PROTOCOLS = {CHAT, RESPONSES, ANTHROPIC, GEMINI};
    public static final String[] PROTOCOL_LABELS = {
            "OpenAI Chat Completions", "OpenAI Responses", "Claude Messages", "Gemini"};
    public static final String RESPONSE_SYNC = "non_streaming";
    public static final String RESPONSE_STREAM = "streaming";
    public static final String[] RESPONSE_MODES = {RESPONSE_SYNC, RESPONSE_STREAM};
    public static final String[] RESPONSE_LABELS = {"非流式", "流式"};
    public static final String REASONING_DEFAULT = "default";
    public static final String[] REASONING_EFFORTS = {REASONING_DEFAULT, "none", "minimal", "low", "medium", "high", "xhigh", "max"};
    public static final String[] REASONING_LABELS = {"模型默认", "none", "minimal", "low", "medium", "high", "xhigh", "max"};

    public final String id;
    public final String name;
    public final String provider;
    public final String protocol;
    public final String responseMode;
    public final String reasoningEffort;
    public final String apiEndpoint;
    public final String apiKey;
    public final String modelName;
    public final String modelsEndpoint;
    public final String systemPrompt;
    public final int contextRounds;
    public final int timeoutSeconds;
    public final boolean allowInsecureHttp;
    public final boolean temperatureEnabled;
    public final double temperature;
    public final boolean maxTokensEnabled;
    public final int maxTokens;
    public final boolean topPEnabled;
    public final double topP;
    public final String customHeaders;
    public final String customParameters;
    public final boolean messageContactEnabled;
    public final String messageContactName;
    /** Bounded inline JPEG/PNG, retained when the contact is disabled. */
    public final String messageContactAvatar;

    private AiModelConfig(JSONObject json) {
        id = json.optString("id", "").trim();
        name = json.optString("name", "").trim();
        provider = json.optString("provider", "custom").trim();
        protocol = json.optString("protocol", CHAT).trim();
        responseMode = json.has("responseMode") ? json.optString("responseMode", "").trim() : RESPONSE_SYNC;
        reasoningEffort = json.has("reasoningEffort") ? json.optString("reasoningEffort", "").trim() : REASONING_DEFAULT;
        apiEndpoint = json.optString("apiEndpoint", "").trim();
        apiKey = json.optString("apiKey", "").trim();
        modelName = json.optString("modelName", "").trim();
        modelsEndpoint = json.optString("modelsEndpoint", "").trim();
        systemPrompt = json.optString("systemPrompt", "");
        contextRounds = json.optInt("contextRounds", 10);
        timeoutSeconds = json.optInt("timeoutSeconds", 30);
        allowInsecureHttp = json.optBoolean("allowInsecureHttp", false);
        temperatureEnabled = json.optBoolean("temperatureEnabled", false);
        temperature = json.optDouble("temperature", 0.7);
        maxTokensEnabled = json.optBoolean("maxTokensEnabled", false);
        maxTokens = json.optInt("maxTokens", 1024);
        topPEnabled = json.optBoolean("topPEnabled", false);
        topP = json.optDouble("topP", 1.0);
        customHeaders = json.optString("customHeaders", "{}").trim();
        customParameters = json.optString("customParameters", "{}").trim();
        messageContactEnabled = json.optBoolean("messageContactEnabled", false);
        messageContactName = json.optString("messageContactName", "").trim();
        messageContactAvatar = json.optString("messageContactAvatar", "").trim();
    }

    public static AiModelConfig fromJson(JSONObject json) {
        if (json == null) throw new IllegalArgumentException("模型配置为空");
        return new AiModelConfig(json);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("provider", provider)
                    .put("protocol", protocol).put("responseMode", responseMode)
                    .put("reasoningEffort", reasoningEffort)
                    .put("apiEndpoint", apiEndpoint).put("apiKey", apiKey)
                    .put("modelName", modelName).put("modelsEndpoint", modelsEndpoint)
                    .put("systemPrompt", systemPrompt).put("contextRounds", contextRounds)
                    .put("timeoutSeconds", timeoutSeconds).put("allowInsecureHttp", allowInsecureHttp)
                    .put("temperatureEnabled", temperatureEnabled).put("temperature", temperature)
                    .put("maxTokensEnabled", maxTokensEnabled).put("maxTokens", maxTokens)
                    .put("topPEnabled", topPEnabled).put("topP", topP)
                    .put("customHeaders", customHeaders).put("customParameters", customParameters)
                    .put("messageContactEnabled", messageContactEnabled)
                    .put("messageContactName", messageContactName).put("messageContactAvatar", messageContactAvatar);
        } catch (Exception failure) { throw new IllegalStateException("模型配置无法序列化", failure); }
    }

    public void validate(boolean requireModel) {
        if (id.isEmpty() || id.length() > 128) throw new IllegalArgumentException("模型配置标识无效");
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("配置名称需要 1 至 80 个字符");
        if (messageContactName.length() > 80 || messageContactName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("联系人名字最多 80 个字符，不能包含换行或控制字符");
        }
        decodeContactAvatar(messageContactAvatar);
        boolean recognized = false;
        for (String value : PROTOCOLS) recognized |= value.equals(protocol);
        if (!recognized) throw new IllegalArgumentException("接口协议无效");
        if (!RESPONSE_SYNC.equals(responseMode) && !RESPONSE_STREAM.equals(responseMode)) {
            throw new IllegalArgumentException("响应方式无效");
        }
        boolean validEffort = false;
        for (String value : REASONING_EFFORTS) validEffort |= value.equals(reasoningEffort);
        if (!validEffort) throw new IllegalArgumentException("推理强度无效");
        boolean applicableEffort = false;
        for (String value : reasoningEfforts(protocol)) applicableEffort |= value.equals(reasoningEffort);
        if (!applicableEffort) throw new IllegalArgumentException("当前接口协议不适用推理强度：" + reasoningEffort);
        validateAddress(apiEndpoint, allowInsecureHttp);
        if (!modelsEndpoint.isEmpty()) validateAddress(modelsEndpoint, allowInsecureHttp);
        if (requireModel && modelName.isEmpty()) throw new IllegalArgumentException("请填写或获取模型名称");
        if (modelName.length() > 256 || modelName.contains("\r") || modelName.contains("\n")) {
            throw new IllegalArgumentException("模型名称无效");
        }
        if (apiKey.length() > 8192 || apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new IllegalArgumentException("API 密钥格式无效");
        }
        if (contextRounds < 0 || contextRounds > 50) throw new IllegalArgumentException("上下文轮数应为 0 至 50");
        if (timeoutSeconds < 5 || timeoutSeconds > 120) throw new IllegalArgumentException("超时应为 5 至 120 秒");
        if (temperatureEnabled && (!Double.isFinite(temperature) || temperature < 0
                || temperature > (ANTHROPIC.equals(protocol) ? 1 : 2))) {
            throw new IllegalArgumentException("温度超出当前协议范围");
        }
        if (maxTokensEnabled && (maxTokens < 1 || maxTokens > 131072)) {
            throw new IllegalArgumentException("最大输出 Token 应为 1 至 131072");
        }
        if (topPEnabled && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("Top P 应为 0 至 1");
        }
        headers();
        parameters();
    }

    public String messageContactDisplayName() {
        return messageContactName.isEmpty() ? name : messageContactName;
    }

    public boolean streaming() { return RESPONSE_STREAM.equals(responseMode); }
    public boolean hasReasoningEffort() { return !REASONING_DEFAULT.equals(reasoningEffort); }

    /** Protocol-level candidates; actual support still depends on the selected model service. */
    public static String[] reasoningEfforts(String protocol) {
        switch (protocol) {
            case CHAT:
            case RESPONSES: return REASONING_EFFORTS.clone();
            case ANTHROPIC: return new String[]{REASONING_DEFAULT, "low", "medium", "high", "xhigh", "max"};
            case GEMINI: return new String[]{REASONING_DEFAULT, "minimal", "low", "medium", "high"};
            default: return new String[]{REASONING_DEFAULT};
        }
    }

    public static String[] reasoningLabels(String protocol) {
        String[] labels = reasoningEfforts(protocol);
        labels[0] = "模型默认";
        return labels;
    }

    /** Decode only bounded inline image data; never resolve remote or content URIs. */
    public static byte[] decodeContactAvatar(String value) {
        if (value == null || value.isEmpty()) return new byte[0];
        final String jpeg = "data:image/jpeg;base64,", png = "data:image/png;base64,";
        String prefix = value.startsWith(jpeg) ? jpeg : value.startsWith(png) ? png : "";
        if (prefix.isEmpty() || value.length() > ((MAX_CONTACT_AVATAR_BYTES + 2) / 3) * 4 + prefix.length()) {
            throw new IllegalArgumentException("联系人头像需要 16 KiB 以内的内嵌 JPEG 或 PNG 图片");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value.substring(prefix.length()));
            boolean signature = prefix.equals(jpeg) ? bytes.length >= 3
                    && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255
                    : bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78
                    && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
            if (!signature || bytes.length > MAX_CONTACT_AVATAR_BYTES) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("联系人头像内容无效或超过 16 KiB");
        }
    }

    public static URI validateAddress(String value, boolean allowHttp) {
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()) && allowHttp)) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("API 地址无效，请填写 HTTPS 地址或启用 HTTP");
        }
    }

    public Map<String, String> headers() {
        JSONObject json = object(customHeaders, "请求头");
        if (json.length() > 32) throw new IllegalArgumentException("自定义请求头最多 32 项");
        Map<String, String> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = json.opt(key);
            if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !(raw instanceof String)) {
                throw new IllegalArgumentException("请求头名称或值无效");
            }
            String value = (String) raw;
            if (value.length() > 8192 || value.contains("\r") || value.contains("\n")
                    || key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Content-Length")
                    || key.equalsIgnoreCase("Connection")) {
                throw new IllegalArgumentException("请求头不支持该名称或值");
            }
            for (String existing : result.keySet()) {
                if (existing.equalsIgnoreCase(key)) throw new IllegalArgumentException("请求头名称重复");
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    public JSONObject parameters() {
        JSONObject json = object(customParameters, "自定义参数");
        for (String key : new String[]{"model", "messages", "input", "stream", "contents",
                "system", "systemInstruction", "instructions", "store"}) {
            if (json.has(key)) throw new IllegalArgumentException("自定义参数不能覆盖协议字段：" + key);
        }
        if (hasReasoningEffort()) {
            switch (protocol) {
                case CHAT: rejectReasoningParameter(json, "reasoning_effort"); break;
                case RESPONSES: rejectReasoningParameter(json, "reasoning", "effort"); break;
                case ANTHROPIC: rejectReasoningParameter(json, "output_config", "effort"); break;
                case GEMINI:
                    rejectReasoningParameter(json, "generationConfig", "thinkingConfig", "thinkingLevel");
                    rejectReasoningParameter(json, "generationConfig", "thinkingConfig", "thinkingBudget");
                    break;
                default: break;
            }
        }
        return json;
    }

    private static void rejectReasoningParameter(JSONObject json, String... path) {
        JSONObject current = json;
        StringBuilder label = new StringBuilder();
        for (int index = 0; index < path.length; index++) {
            if (index > 0) label.append('.');
            label.append(path[index]);
            if (!current.has(path[index])) return;
            if (index == path.length - 1) {
                throw new IllegalArgumentException("推理强度与自定义参数 " + label
                        + " 冲突，请移除该参数或将推理强度设为“模型默认”");
            }
            Object nested = current.opt(path[index]);
            if (!(nested instanceof JSONObject)) {
                throw new IllegalArgumentException("自定义参数 " + label
                        + " 必须是 JSON 对象，或将推理强度设为“模型默认”");
            }
            current = (JSONObject) nested;
        }
    }

    private static JSONObject object(String text, String label) {
        if (text.length() > 16000) throw new IllegalArgumentException(label + "过长");
        try { return new JSONObject(text.isEmpty() ? "{}" : text); }
        catch (Exception failure) { throw new IllegalArgumentException(label + "必须是 JSON 对象"); }
    }
}

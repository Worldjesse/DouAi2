package com.douai.module.config;

import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A self-contained model profile. Optional parameters are omitted unless enabled. */
public final class AiModelConfig {
    public static final String CHAT = "chat_completions";
    public static final String RESPONSES = "responses";
    public static final String ANTHROPIC = "anthropic";
    public static final String GEMINI = "gemini";
    public static final String[] PROTOCOLS = {CHAT, RESPONSES, ANTHROPIC, GEMINI};
    public static final String[] PROTOCOL_LABELS = {
            "OpenAI Chat Completions", "OpenAI Responses", "Claude Messages", "Gemini"};

    public final String id;
    public final String name;
    public final String provider;
    public final String protocol;
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

    private AiModelConfig(JSONObject json) {
        id = json.optString("id", "").trim();
        name = json.optString("name", "").trim();
        provider = json.optString("provider", "custom").trim();
        protocol = json.optString("protocol", CHAT).trim();
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
    }

    public static AiModelConfig fromJson(JSONObject json) {
        if (json == null) throw new IllegalArgumentException("模型配置为空");
        return new AiModelConfig(json);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("provider", provider)
                    .put("protocol", protocol).put("apiEndpoint", apiEndpoint).put("apiKey", apiKey)
                    .put("modelName", modelName).put("modelsEndpoint", modelsEndpoint)
                    .put("systemPrompt", systemPrompt).put("contextRounds", contextRounds)
                    .put("timeoutSeconds", timeoutSeconds).put("allowInsecureHttp", allowInsecureHttp)
                    .put("temperatureEnabled", temperatureEnabled).put("temperature", temperature)
                    .put("maxTokensEnabled", maxTokensEnabled).put("maxTokens", maxTokens)
                    .put("topPEnabled", topPEnabled).put("topP", topP)
                    .put("customHeaders", customHeaders).put("customParameters", customParameters);
        } catch (Exception failure) { throw new IllegalStateException("模型配置无法序列化", failure); }
    }

    public void validate(boolean requireModel) {
        if (id.isEmpty() || id.length() > 128) throw new IllegalArgumentException("模型配置标识无效");
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("配置名称需要 1 至 80 个字符");
        boolean recognized = false;
        for (String value : PROTOCOLS) recognized |= value.equals(protocol);
        if (!recognized) throw new IllegalArgumentException("接口协议无效");
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
        return json;
    }

    private static JSONObject object(String text, String label) {
        if (text.length() > 16000) throw new IllegalArgumentException(label + "过长");
        try { return new JSONObject(text.isEmpty() ? "{}" : text); }
        catch (Exception failure) { throw new IllegalArgumentException(label + "必须是 JSON 对象"); }
    }
}

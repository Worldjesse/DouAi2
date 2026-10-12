package com.douai.module.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Endpoint presets; users choose model IDs from the provider or enter them explicitly. */
public final class AiProviderPresets {
    private AiProviderPresets() { }

    public static final class Preset {
        public final String id;
        public final String name;
        public final String protocol;
        public final String apiEndpoint;

        Preset(String id, String name, String protocol, String apiEndpoint) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.apiEndpoint = apiEndpoint;
        }
    }

    public static final List<Preset> ALL = Collections.unmodifiableList(Arrays.asList(
            new Preset("custom", "自定义", AiModelConfig.CHAT, ""),
            new Preset("openai", "OpenAI", AiModelConfig.CHAT, "https://api.openai.com/v1"),
            new Preset("openai_responses", "OpenAI Responses", AiModelConfig.RESPONSES, "https://api.openai.com/v1"),
            new Preset("anthropic", "Anthropic Claude", AiModelConfig.ANTHROPIC, "https://api.anthropic.com/v1"),
            new Preset("google", "Google Gemini", AiModelConfig.GEMINI, "https://generativelanguage.googleapis.com/v1beta"),
            new Preset("deepseek", "DeepSeek", AiModelConfig.CHAT, "https://api.deepseek.com/v1"),
            new Preset("qwen", "通义千问", AiModelConfig.CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            new Preset("siliconflow", "硅基流动", AiModelConfig.CHAT, "https://api.siliconflow.cn/v1"),
            new Preset("openrouter", "OpenRouter", AiModelConfig.CHAT, "https://openrouter.ai/api/v1"),
            new Preset("moonshot", "Moonshot / Kimi", AiModelConfig.CHAT, "https://api.moonshot.cn/v1"),
            new Preset("zhipu", "智谱 GLM", AiModelConfig.CHAT, "https://open.bigmodel.cn/api/paas/v4"),
            new Preset("doubao", "豆包 / 火山引擎", AiModelConfig.CHAT, "https://ark.cn-beijing.volces.com/api/v3"),
            new Preset("ollama", "Ollama", AiModelConfig.CHAT, "http://127.0.0.1:11434/v1"),
            new Preset("lmstudio", "LM Studio", AiModelConfig.CHAT, "http://127.0.0.1:1234/v1")
    ));

    public static String name(String id) {
        for (Preset preset : ALL) if (preset.id.equals(id)) return preset.name;
        return "自定义";
    }
}

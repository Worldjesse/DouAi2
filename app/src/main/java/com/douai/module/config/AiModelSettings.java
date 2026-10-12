package com.douai.module.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Versioned model-profile collection stored under a dedicated preference key. */
public final class AiModelSettings {
    public static final String KEY = "ai_model_settings_v2";
    public final List<AiModelConfig> configs;
    public final String activeId;

    public AiModelSettings(List<AiModelConfig> configs, String activeId) {
        if (configs.size() > 50) throw new IllegalArgumentException("模型配置最多 50 项");
        HashSet<String> ids = new HashSet<>();
        for (AiModelConfig config : configs) {
            if (config.id.isEmpty() || !ids.add(config.id)) throw new IllegalArgumentException("模型配置标识重复或为空");
        }
        this.configs = Collections.unmodifiableList(new ArrayList<>(configs));
        this.activeId = activeId == null ? "" : activeId;
        if (!this.activeId.isEmpty() && !ids.contains(this.activeId)) {
            throw new IllegalArgumentException("当前模型配置不存在");
        }
    }

    public static AiModelSettings read(SettingsStore store) { return parse(store.getString(KEY, "")); }
    public static AiModelSettings read(LocalOptions options) { return parse(options.text(KEY, "")); }

    public static AiModelSettings parse(String serialized) {
        if (serialized == null || serialized.trim().isEmpty()) return new AiModelSettings(Collections.emptyList(), "");
        try {
            JSONObject root = new JSONObject(serialized);
            if (root.getInt("version") != 2) throw new IllegalArgumentException("模型配置版本不支持");
            JSONArray entries = root.getJSONArray("configs");
            List<AiModelConfig> configs = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) configs.add(AiModelConfig.fromJson(entries.getJSONObject(i)));
            return new AiModelSettings(configs, root.optString("activeId", ""));
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("模型配置无法读取", failure); }
    }

    public String serialize() {
        try {
            JSONArray entries = new JSONArray();
            for (AiModelConfig config : configs) entries.put(config.toJson());
            String serialized = new JSONObject().put("version", 2).put("activeId", activeId)
                    .put("configs", entries).toString();
            return serialized;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public void save(SettingsStore store) { store.putString(KEY, serialize()); }

    public AiModelConfig selected() {
        for (AiModelConfig config : configs) if (config.id.equals(activeId)) {
            config.validate(true);
            return config;
        }
        throw new IllegalStateException("请先添加并启用模型配置");
    }

    public AiModelSettings upsert(AiModelConfig config, boolean activate) {
        config.validate(true);
        List<AiModelConfig> next = new ArrayList<>();
        boolean replaced = false;
        for (AiModelConfig existing : configs) {
            if (existing.id.equals(config.id)) { next.add(config); replaced = true; }
            else next.add(existing);
        }
        if (!replaced) next.add(config);
        return new AiModelSettings(next, activate ? config.id : activeId);
    }

    public AiModelSettings activate(String id) {
        for (AiModelConfig config : configs) if (config.id.equals(id)) config.validate(true);
        return new AiModelSettings(configs, id);
    }

    public AiModelSettings delete(String id) {
        List<AiModelConfig> next = new ArrayList<>();
        for (AiModelConfig config : configs) if (!config.id.equals(id)) next.add(config);
        return new AiModelSettings(next, activeId.equals(id) ? "" : activeId);
    }
}

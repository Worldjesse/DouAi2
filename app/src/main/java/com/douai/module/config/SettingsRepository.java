package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Local settings store. The preference adapter can also be backed by module remote preferences. */
public final class SettingsRepository implements SettingsStore {
    public static final String PREFERENCES_NAME = "douai_module_settings";
    static final String HIDE_RECOMMENDATION_CARDS_KEY = "hide_recommendation_cards";
    static final String HIDE_RECOMMENDATION_MERCHANTS_KEY = "hide_recommendation_merchants";

    private final SharedPreferences preferences;
    private FrameworkConfigSync frameworkConfigSync;

    public SettingsRepository(Context context) {
        this(context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    public SettingsRepository(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public FeatureSettings load() {
        migrateRecommendationFilters();
        return FeatureSettings.readFrom(preferences);
    }

    private void migrateRecommendationFilters() {
        if (preferences.contains(HIDE_RECOMMENDATION_MERCHANTS_KEY)
                || !preferences.contains(HIDE_RECOMMENDATION_CARDS_KEY)) return;
        boolean legacyEnabled = preferences.getBoolean(HIDE_RECOMMENDATION_CARDS_KEY, false);
        preferences.edit().putBoolean(HIDE_RECOMMENDATION_MERCHANTS_KEY, legacyEnabled).apply();
    }

    public synchronized void save(FeatureSettings settings) {
        SharedPreferences.Editor editor = preferences.edit();
        settings.writeTo(editor);
        editor.apply();
        if (frameworkConfigSync != null) {
            frameworkConfigSync.publish(settings);
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        return preferences.getBoolean(key, fallback);
    }

    public String getString(String key, String fallback) {
        return preferences.getString(key, fallback);
    }

    public synchronized void putBoolean(String key, boolean value) {
        preferences.edit().putBoolean(key, value).apply();
        publishCurrentSettings();
    }

    public synchronized void putString(String key, String value) {
        preferences.edit().putString(key, value == null ? "" : value).apply();
        publishCurrentSettings();
    }

    public synchronized Map<String, ?> snapshot() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(preferences.getAll()));
    }

    @Override public synchronized void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
        Map<String, ?> previous = snapshot();
        if (!previous.equals(expected)) throw new IllegalStateException("设置已发生变化，请重新预览导入文件");
        SharedPreferences.Editor update = completeEditor(replacement);
        try (ConfigImportTransaction transaction = ConfigImportTransaction.begin(preferences, previous)) {
            boolean committed;
            try { committed = update.commit(); }
            catch (RuntimeException failure) { committed = false; }
            if (!committed) {
                boolean restored;
                try { restored = completeEditor(previous).commit(); }
                catch (RuntimeException failure) { restored = false; }
                throw new IllegalStateException(restored ? "导入写入失败，原配置已恢复" : "导入和恢复写入失败，请保留原备份并检查存储空间");
            }
            publishCurrentSettings();
        }
    }

    private SharedPreferences.Editor completeEditor(Map<String, ?> values) {
        // Validate everything before constructing an editor; a bad type cannot partially change memory.
        for (Object value : values.values()) {
            if (!(value instanceof Boolean || value instanceof String || value instanceof Integer
                    || value instanceof Long || value instanceof Float || value instanceof java.util.Set<?>)) {
                throw new IllegalArgumentException("配置包含不支持的存储类型");
            }
            if (value instanceof java.util.Set<?>) for (Object item : (java.util.Set<?>) value) {
                if (!(item instanceof String)) throw new IllegalArgumentException("配置集合必须为文本");
            }
        }
        SharedPreferences.Editor editor = preferences.edit().clear();
        values.forEach((key, value) -> {
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else {
                java.util.Set<String> strings = new java.util.HashSet<>();
                for (Object item : (java.util.Set<?>) value) strings.add((String) item);
                editor.putStringSet(key, strings);
            }
        });
        return editor;
    }

    public void setFrameworkConfigSync(FrameworkConfigSync sync) {
        if (frameworkConfigSync == sync) return;
        frameworkConfigSync = sync;
        if (sync != null) {
            sync.setSettingsRepository(this);
            sync.publish(load());
        }
    }

    private void publishCurrentSettings() {
        if (frameworkConfigSync != null) frameworkConfigSync.publish(load());
    }
}

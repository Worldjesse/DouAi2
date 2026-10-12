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
        return preferences.getAll();
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

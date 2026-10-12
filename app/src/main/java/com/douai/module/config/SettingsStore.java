package com.douai.module.config;

/** Settings access used by both the module app and its in-host settings surface. */
public interface SettingsStore {
    FeatureSettings load();
    void save(FeatureSettings settings);
    boolean getBoolean(String key, boolean fallback);
    String getString(String key, String fallback);
    void putBoolean(String key, boolean value);
    void putString(String key, String value);
    default boolean isInHost() { return false; }
    default void close() { }
}

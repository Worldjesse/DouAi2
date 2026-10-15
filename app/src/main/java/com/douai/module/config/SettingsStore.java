package com.douai.module.config;

/** Settings access used by both the module app and its in-host settings surface. */
public interface SettingsStore {
    FeatureSettings load();
    void save(FeatureSettings settings);
    boolean getBoolean(String key, boolean fallback);
    String getString(String key, String fallback);
    void putBoolean(String key, boolean value);
    void putString(String key, String value);
    /** A complete detached snapshot; unsupported adapters cannot perform configuration transfer. */
    default java.util.Map<String, ?> snapshot() { throw new UnsupportedOperationException("此设置入口不支持配置备份"); }
    /** Compare and replace one complete transaction, retaining the old snapshot on storage failure. */
    default void replaceSnapshot(java.util.Map<String, ?> expected, java.util.Map<String, ?> replacement) {
        throw new UnsupportedOperationException("此设置入口不支持配置导入");
    }
    default boolean isInHost() { return false; }
    default void close() { }
}

package com.douai.module.config;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Parses every feature from the same captured preferences transaction. */
public final class ConfigurationSnapshot {
    public final Map<String, ?> values;
    public final FeatureSettings features;
    public final ExtendedSettings extended;
    public final LocalOptions options;

    private ConfigurationSnapshot(Map<String, ?> captured) {
        Map<String, Object> copy = new HashMap<>();
        captured.forEach((key, value) -> copy.put(key, value instanceof Set<?>
                ? Collections.unmodifiableSet(new HashSet<>((Set<?>) value)) : value));
        values = Collections.unmodifiableMap(copy);
        SharedPreferences frozen = new FrozenPreferences(values);
        features = FeatureSettings.readFrom(frozen);
        extended = ExtendedSettings.readFrom(frozen);
        options = new LocalOptions(values);
    }

    public static ConfigurationSnapshot readFrom(SharedPreferences preferences) {
        return new ConfigurationSnapshot(preferences.getAll());
    }

    private static final class FrozenPreferences implements SharedPreferences {
        private final Map<String, ?> values;

        FrozenPreferences(Map<String, ?> values) { this.values = values; }

        private Object value(String key, Object fallback) {
            Object value = values.get(key);
            return value == null ? fallback : value;
        }

        @Override public Map<String, ?> getAll() { return values; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) value(key, fallback); }
        @Override public String getString(String key, String fallback) { return (String) value(key, fallback); }
        @Override public int getInt(String key, int fallback) { return (Integer) value(key, fallback); }
        @Override public long getLong(String key, long fallback) { return (Long) value(key, fallback); }
        @Override public float getFloat(String key, float fallback) { return (Float) value(key, fallback); }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            return (Set<String>) value(key, fallback);
        }
        @Override public Editor edit() { throw new UnsupportedOperationException("Read-only snapshot"); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            throw new UnsupportedOperationException("Read-only snapshot");
        }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            throw new UnsupportedOperationException("Read-only snapshot");
        }
    }
}

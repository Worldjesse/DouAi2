package com.douai.module.config;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable host-local snapshot for independently registered feature options. */
public final class LocalOptions {
    /** Prevents the host from handling long-press gestures when no module action is assigned. */
    public static final String INTERCEPT_NATIVE_LONG_PRESS = "intercept_native_long_press";

    private final Map<String, ?> values;

    public LocalOptions(Map<String, ?> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public static LocalOptions readFrom(SharedPreferences preferences) {
        return new LocalOptions(preferences.getAll());
    }

    public LocalOptions withDefault(String key, Object value) {
        if (values.containsKey(key)) return this;
        Map<String, Object> copy = new HashMap<>(values);
        copy.put(key, value);
        return new LocalOptions(copy);
    }

    public boolean enabled(String key) { return enabled(key, false); }

    public boolean enabled(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public boolean interceptNativeLongPress() {
        return enabled(INTERCEPT_NATIVE_LONG_PRESS);
    }

    public String text(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public int integer(String key, int fallback) {
        Object value = values.get(key);
        try {
            return value instanceof Number ? ((Number) value).intValue()
                    : value instanceof String ? Integer.parseInt(((String) value).trim()) : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    public long longValue(String key, long fallback) {
        Object value = values.get(key);
        try {
            return value instanceof Number ? ((Number) value).longValue()
                    : value instanceof String ? Long.parseLong(((String) value).trim()) : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    public float decimal(String key, float fallback) {
        Object value = values.get(key);
        try {
            float parsed = value instanceof Number ? ((Number) value).floatValue()
                    : value instanceof String ? Float.parseFloat(((String) value).trim()) : fallback;
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }
}

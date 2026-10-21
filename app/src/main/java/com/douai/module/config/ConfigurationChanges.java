package com.douai.module.config;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Keys whose persisted values differ between two complete settings snapshots. */
public final class ConfigurationChanges {
    private final Set<String> keys;

    private ConfigurationChanges(Set<String> keys) {
        this.keys = keys;
    }

    public static ConfigurationChanges between(Map<String, ?> previous, Map<String, ?> current) {
        Set<String> changed = new HashSet<>();
        if (current == null) throw new IllegalArgumentException("Current configuration is required");
        if (previous == null) {
            changed.addAll(current.keySet());
        } else {
            Set<String> all = new HashSet<>(previous.keySet());
            all.addAll(current.keySet());
            for (String key : all) {
                if (!previous.containsKey(key) || !current.containsKey(key)
                        || !java.util.Objects.equals(previous.get(key), current.get(key))) {
                    changed.add(key);
                }
            }
        }
        return new ConfigurationChanges(changed);
    }

    public boolean affects(ConfigurationCatalog.Feature feature) {
        return ConfigurationCatalog.affects(feature, this);
    }

    public boolean isEmpty() { return keys.isEmpty(); }

    public int changedKeyCount() { return keys.size(); }

    public boolean contains(String key) { return keys.contains(key); }

    public boolean any(String... candidates) {
        for (String candidate : candidates) if (keys.contains(candidate)) return true;
        return false;
    }

    public boolean hasPrefix(String prefix) {
        for (String key : keys) if (key.startsWith(prefix)) return true;
        return false;
    }
}

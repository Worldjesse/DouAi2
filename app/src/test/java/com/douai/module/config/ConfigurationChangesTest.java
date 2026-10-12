package com.douai.module.config;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class ConfigurationChangesTest {
    @Test public void initialSnapshotMarksItsPersistedKeysChanged() {
        ConfigurationChanges changes = ConfigurationChanges.between(null,
                Collections.singletonMap("playback_speed", "2"));

        assertTrue(changes.contains("playback_speed"));
        assertFalse(changes.isEmpty());
    }

    @Test public void unchangedValuesDoNotTriggerFeatureUpdates() {
        Map<String, Object> values = new HashMap<>();
        values.put("playback_speed", "2");
        values.put("component_alpha_like", "128");

        ConfigurationChanges changes = ConfigurationChanges.between(values, new HashMap<>(values));

        assertTrue(changes.isEmpty());
        assertFalse(changes.any("playback_speed", "auto_speed"));
    }

    @Test public void addedRemovedAndModifiedValuesAreReportedPrecisely() {
        Map<String, Object> before = new HashMap<>();
        before.put("ai_model_settings_v2", "old");
        before.put("component_alpha_like", "128");
        before.put("legacy", true);
        Map<String, Object> after = new HashMap<>(before);
        after.put("ai_model_settings_v2", "new");
        after.put("component_alpha_like", "64");
        after.remove("legacy");
        after.put("auto_speed", true);

        ConfigurationChanges changes = ConfigurationChanges.between(before, after);

        assertTrue(changes.any("ai_model_settings_v2", "auto_speed"));
        assertTrue(changes.hasPrefix("component_alpha_"));
        assertTrue(changes.contains("legacy"));
        assertEquals(4, changes.changedKeyCount());
    }
}

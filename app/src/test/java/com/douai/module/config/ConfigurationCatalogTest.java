package com.douai.module.config;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.douai.module.config.ConfigurationCatalog.Feature.*;
import static org.junit.Assert.*;

public final class ConfigurationCatalogTest {
    @Test public void globalHashLongPressRefreshesOnlyTheTwoManualSpeechAdapters() {
        assertEquals("boolean", ConfigKeys.TYPES.get(LocalOptions.HASH_LONG_PRESS_VOICE));
        assertEquals(false, ConfigurationCatalog.settings().get(LocalOptions.HASH_LONG_PRESS_VOICE).defaultValue);
        assertConsumers(LocalOptions.HASH_LONG_PRESS_VOICE, EnumSet.of(DIRECT_MESSAGE_SPEECH, NATIVE_COMMENT_SPEECH));
    }
    @Test public void debugModeTransfersWithFalseDefaultAndDoesNotRestartAutomation() {
        assertEquals("boolean", ConfigKeys.TYPES.get(LocalOptions.DEBUG_LOGGING));
        assertEquals(false, ConfigurationCatalog.settings().get(LocalOptions.DEBUG_LOGGING).defaultValue);
        assertConsumers(LocalOptions.DEBUG_LOGGING, EnumSet.of(DIAGNOSTICS));
    }
    @Test public void miniMaxTransportAndMixKeysParticipateInBackupAndSharedSpeechRefresh() {
        for (String key : new String[]{MiniMaxSpeechOptions.OUTPUT_FORMAT, MiniMaxSpeechOptions.TIMBRE_WEIGHTS}) {
            assertEquals("string", ConfigKeys.TYPES.get(key));
            assertConsumers(key, EnumSet.of(REPLY, COMMENT_AUTOMATION, COMMENT_REPLY,
                    AUTOMATION_DISPATCH, DIRECT_MESSAGE_SPEECH, NATIVE_COMMENT_SPEECH));
        }
    }

    @Test public void transferSchemaKeepsLegacySettingsButExcludesCommandsAndRuntimeData() {
        assertEquals("boolean", ConfigKeys.TYPES.get(LocalOptions.COMMENT_REPLY_ENABLED));
        assertEquals("string", ConfigKeys.TYPES.get("comment_reply_prompt"));
        assertEquals("string", ConfigKeys.TYPES.get("long_press_left"));
        assertEquals("string", ConfigKeys.TYPES.get("long_press_top_left"));
        assertEquals("int", ConfigKeys.TYPES.get("minimum_duration_seconds"));
        for (String command : new String[]{"streak_reset_token", "streak_retry_unknown_token",
                "relation_lists_refresh_token", "consent_accepted", "submission_ledger"}) {
            assertFalse(command, ConfigKeys.TYPES.containsKey(command));
        }
        ConfigurationCatalog.Setting reset = ConfigurationCatalog.settings().get("streak_reset_token");
        assertFalse(reset.transferable());
        assertTrue(reset.consumers.contains(REPLY));
        assertTrue(reset.consumers.contains(COMMENT_REPLY));
        assertThrows(UnsupportedOperationException.class, () -> reset.consumers.clear());
        assertThrows(UnsupportedOperationException.class, () -> ConfigurationCatalog.settings().clear());
    }

    @Test public void sharedSettingsRefreshTheirConsumersWithoutTouchingPlayerOrFeedUi() {
        assertConsumers("ai_model_settings_v2", EnumSet.of(REPLY, COMMENT_AUTOMATION, COMMENT_REPLY, AUTOMATION_DISPATCH));
        assertConsumers("tts_future_option", EnumSet.of(REPLY, COMMENT_AUTOMATION, COMMENT_REPLY,
                AUTOMATION_DISPATCH, DIRECT_MESSAGE_SPEECH, NATIVE_COMMENT_SPEECH));
        assertConsumers(LocalOptions.COMMENT_REPLY_ENABLED, EnumSet.of(COMMENT_REPLY, AUTOMATION_DISPATCH));
        assertConsumers("comment_prompt", EnumSet.of(COMMENT_AUTOMATION, AUTOMATION_DISPATCH));
        assertConsumers("reply_prompt", EnumSet.of(REPLY, COMMENT_REPLY, AUTOMATION_DISPATCH));
    }

    @Test public void removalsAndLegacyPrefixesUseTheSameDependenciesAsUpdates() {
        ConfigurationChanges removed = ConfigurationChanges.between(Map.of("playback_speed", "3"), Collections.emptyMap());
        assertTrue(removed.affects(PLAYBACK));
        assertTrue(removed.affects(GESTURE));
        assertFalse(removed.affects(AUTO_PLAY));
        assertConsumers("component_alpha_like", EnumSet.of(FEED_UI));
        assertConsumers("long_press_left", EnumSet.of(GESTURE));
        assertConsumers("third_party_get_template", EnumSet.of(DOWNLOAD, GESTURE));
        assertConsumers("relation_lists_refresh_token", EnumSet.of(FOLLOW_LISTS));
        assertConsumers("streak_retry_unknown_token", EnumSet.of(REPLY, COMMENT_REPLY, AUTOMATION_DISPATCH));
        assertConsumers("unrelated_runtime_key", EnumSet.noneOf(ConfigurationCatalog.Feature.class));
        ConfigurationChanges unchanged = ConfigurationChanges.between(Map.of("playback_speed", "2"), Map.of("playback_speed", "2"));
        for (ConfigurationCatalog.Feature feature : ConfigurationCatalog.Feature.values()) assertFalse(unchanged.affects(feature));
    }

    @Test public void defaultsAndPlaybackValidationShareOneContractWithoutBreakingOldBackups() {
        assertEquals("2", ConfigurationCatalog.settings().get("playback_speed").defaultValue);
        assertEquals("official", ConfigurationCatalog.settings().get("download_method").defaultValue);
        for (ConfigurationCatalog.Setting setting : ConfigurationCatalog.settings().values()) {
            if (setting.defaultValue != null) ConfigKeys.validate(setting.key, setting.defaultValue);
        }
        assertEquals("0.25", ConfigurationCatalog.validatePlaybackSpeed(" 0.25 "));
        assertEquals("4", ConfigurationCatalog.validatePlaybackSpeed("4"));
        for (String invalid : new String[]{"0", "4.01", "Infinity", "NaN", "text", ""}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> ConfigurationCatalog.validatePlaybackSpeed(invalid));
            assertEquals("2", ConfigurationCatalog.normalizedPlaybackSpeed(invalid));
            // Import remains type-compatible; runtime/settings retain their existing fallback behavior.
            ConfigKeys.validate("playback_speed", invalid);
        }
        assertEquals("2", ConfigurationCatalog.normalizedPlaybackSpeed(null));
        assertThrows(IllegalArgumentException.class, () -> ConfigKeys.validate("playback_speed", 2));
        assertThrows(IllegalArgumentException.class, () -> ConfigKeys.validate("minimum_duration_seconds", "2"));
    }

    private static void assertConsumers(String key, Set<ConfigurationCatalog.Feature> expected) {
        ConfigurationChanges changes = ConfigurationChanges.between(Collections.emptyMap(), Map.of(key, "changed"));
        Set<ConfigurationCatalog.Feature> actual = EnumSet.noneOf(ConfigurationCatalog.Feature.class);
        for (ConfigurationCatalog.Feature feature : ConfigurationCatalog.Feature.values()) {
            if (changes.affects(feature)) actual.add(feature);
        }
        assertEquals(key, expected, actual);
        ConfigurationCatalog.Setting setting = ConfigurationCatalog.settings().get(key);
        if (setting != null) assertEquals(key + " metadata", expected, setting.consumers);
    }
}

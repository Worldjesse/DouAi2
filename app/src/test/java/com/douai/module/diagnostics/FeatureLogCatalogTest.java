package com.douai.module.diagnostics;

import com.douai.module.config.ConfigurationCatalog;
import org.junit.Test;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public final class FeatureLogCatalogTest {
    @Test public void disabledAndUnsupportedRemainIndependentFacts() {
        FeatureLogCatalog.Entry download = FeatureLogCatalog.entries().stream()
                .filter(entry -> "download".equals(entry.id)).findFirst().orElseThrow();
        FeatureLogCatalog.State unsupported = FeatureLogCatalog.state(download,
                java.util.Map.of("download_enabled", false),
                java.util.Map.of("download_hook_installed", false, "download_hook_failure", "NoSuchMethodException"), true);
        assertFalse(unsupported.enabled);
        assertEquals("unavailable", unsupported.availability);
        assertEquals("installation_failed", unsupported.hookState);
        assertTrue(unsupported.failureReference.startsWith("ref_"));
        FeatureLogCatalog.State supported = FeatureLogCatalog.state(download,
                java.util.Map.of("download_enabled", false), java.util.Map.of("download_hook_installed", true), true);
        assertFalse(supported.enabled);
        assertEquals("partial", supported.availability);
        assertEquals("installed", supported.hookState);
        FeatureLogCatalog.State pending = FeatureLogCatalog.state(download,
                java.util.Map.of(), java.util.Map.of("download_hook_installed", false), false);
        assertEquals("pending", pending.availability);
        assertEquals("not_installed", pending.hookState);
        FeatureLogCatalog.State notAttempted = FeatureLogCatalog.state(download,
                java.util.Map.of("download_enabled", false), java.util.Map.of("download_hook_installed", false), true);
        assertEquals("unknown", notAttempted.availability);
        assertEquals("not_installed", notAttempted.hookState);
    }

    @Test public void installedHooksAndConfiguredModelsDoNotProveAccountPermissionsOrServiceReadiness() {
        FeatureLogCatalog.Entry reply = entry("native_auto_reply");
        FeatureLogCatalog.State configured = FeatureLogCatalog.state(reply,
                java.util.Map.of("auto_reply_enabled", true, "ai_model_settings_v2", "configured-private-model"),
                java.util.Map.of("feature.native_auto_reply.installed", true), true);
        assertTrue(configured.enabled);
        assertEquals("installed", configured.hookState);
        assertEquals("partial", configured.availability);
        assertEquals("unknown", configured.dependencyEvidence);
        FeatureLogCatalog.State loggedOut = FeatureLogCatalog.state(reply,
                java.util.Map.of("auto_reply_enabled", false),
                java.util.Map.of("feature.native_auto_reply.installed", true,
                        "feature.native_auto_reply.dependency.account", false), true);
        assertFalse(loggedOut.enabled);
        assertEquals("installed", loggedOut.hookState);
        assertEquals("dependency_missing", loggedOut.availability);
        assertTrue(loggedOut.dependencyEvidence.contains("account:missing"));
        FeatureLogCatalog.State accountOnly = FeatureLogCatalog.state(reply, java.util.Map.of(),
                java.util.Map.of("feature.native_auto_reply.installed", true,
                        "feature.native_auto_reply.dependency.account", true), true);
        assertEquals("partial", accountOnly.availability);
    }

    @Test public void uiEntryPointsHaveNoRequiredHookAndRuntimeEvidenceCanEstablishDependencies() {
        FeatureLogCatalog.State unknown = FeatureLogCatalog.state(entry("ai_chat"), java.util.Map.of(), java.util.Map.of(), true);
        assertEquals("not_required", unknown.hookState);
        assertEquals("unknown", unknown.availability);
        FeatureLogCatalog.State ready = FeatureLogCatalog.state(entry("ai_chat"), java.util.Map.of(),
                java.util.Map.of("feature.ai_chat.dependency_state", "ready"), true);
        assertEquals("supported", ready.availability);
        FeatureLogCatalog.State pending = FeatureLogCatalog.state(entry("ai_chat"), java.util.Map.of(),
                java.util.Map.of("feature.ai_chat.dependency_state", "pending"), true);
        assertEquals("pending", pending.availability);
    }

    @Test public void progressAndNavigationDoNotInheritTheFeedUiMasterAsTheirActualEnabledState() {
        assertFalse(FeatureLogCatalog.state(entry("short_progress"), java.util.Map.of(),
                java.util.Map.of("feed_ui_active", true, "short_progress_enabled", false), true).enabled);
        assertFalse(FeatureLogCatalog.state(entry("home_navigation"), java.util.Map.of(),
                java.util.Map.of("feed_ui_active", true, "home_navigation_enabled", false), true).enabled);
        assertTrue(FeatureLogCatalog.state(entry("short_progress"), java.util.Map.of(),
                java.util.Map.of("short_progress_enabled", true), true).enabled);
    }

    private static FeatureLogCatalog.Entry entry(String id) {
        return FeatureLogCatalog.entries().stream().filter(entry -> id.equals(entry.id)).findFirst().orElseThrow();
    }

    @Test public void inventoryCoversEveryConfigurationConsumerWithoutCountingRulesAsFeatures() {
        Set<String> ids = new HashSet<>();
        EnumSet<ConfigurationCatalog.Feature> consumers = EnumSet.noneOf(ConfigurationCatalog.Feature.class);
        for (FeatureLogCatalog.Entry entry : FeatureLogCatalog.entries()) {
            assertTrue("Duplicate business capability: " + entry.id, ids.add(entry.id));
            assertFalse(entry.source.isEmpty());
            if (entry.consumer != null) consumers.add(entry.consumer);
        }
        assertEquals(EnumSet.allOf(ConfigurationCatalog.Feature.class), consumers);
        assertTrue(ids.contains("hash_long_press_voice"));
        assertTrue(ids.contains("streak"));
        assertTrue(ids.contains("config_transfer"));
        assertTrue(ids.contains("local_analytics"));
        assertTrue(ids.contains("launcher"));
        assertTrue(ids.contains("module_startup"));
        assertTrue(ids.contains("host_scan"));
        assertTrue(ids.contains("speech_settings"));
        assertTrue(ids.contains("third_party_settings"));
    }

    @Test public void sharedSecretSettingsAreMappedByKeyAndNeverByTheirValues() {
        FeatureLogCatalog.Entry reply = FeatureLogCatalog.entries().stream()
                .filter(entry -> "native_auto_reply".equals(entry.id)).findFirst().orElseThrow();
        assertTrue(reply.configurationKeys().contains("reply_prompt"));
        assertTrue(reply.configurationKeys().contains("ai_model_settings_v2"));
        assertFalse(reply.configurationKeys().contains("hide_ads"));
        assertThrows(UnsupportedOperationException.class, () -> reply.configurationKeys().clear());
    }

    @Test public void groupedHooksMapToTheirUserFacingCapabilities() {
        assertEquals("auto_play", FeatureLogCatalog.forHook("DouAi.Advanced.auto_slide.1"));
        assertEquals("native_auto_reply", FeatureLogCatalog.forHook("DouAi.Advanced.native_reply.0"));
        assertEquals("gesture", FeatureLogCatalog.forHook("DouAi.Advanced.finder_pause_guard.1"));
        assertEquals("short_progress", FeatureLogCatalog.forHook("DouAi.Advanced.short_progress_pro.0"));
        assertEquals("local_analytics", FeatureLogCatalog.forHook("DouAi.Advanced.local_analytics_feed_holder.1"));
        assertEquals("dm_speech", FeatureLogCatalog.forHook("DouAi.PrivateSpeech.send"));
        assertEquals("comment_voice", FeatureLogCatalog.forHook("DouAi.CommentAudio.upload"));
        assertEquals("unmapped_hook", FeatureLogCatalog.forHook("host_new_unclassified_hook"));
    }
}

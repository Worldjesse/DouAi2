package com.douai.module.scan;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public final class HostAdaptationTest {
    @Test public void versionEvidenceRequiresPackageNameAndActualVersionCode() {
        assertTrue(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.6.0", 400601L));
        assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.6.0", 40609900L));
        assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.7.0", 400601L));
        assertFalse(HostAdaptation.matchesVersion("com.douai.module", "40.6.0", 400601L));
        assertFalse(HostAdaptation.matchesVersion(null, null, 0L));
    }

    @Test public void staticEvidenceBindsThePackagedRulesAndFeatureReferences() throws Exception {
        File file = new File("src/main/assets/" + HostAdaptation.RULES_ASSET);
        if (!file.isFile()) file = new File("app/src/main/assets/" + HostAdaptation.RULES_ASSET);
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(file)) { rules = HostRuleSet.parse(input); }
        assertEquals(HostAdaptation.VERIFIED_RULES_FINGERPRINT, rules.getFingerprint());
        assertFalse(HostAdaptation.matchesRules("changed"));
        assertFalse(HostAdaptation.matchesRules(null));
        Set<String> ids = new HashSet<>();
        for (HostRule rule : rules.getRules()) ids.add(rule.getId());
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            for (String rule : feature.rules) assertTrue(feature.id + " references " + rule, ids.contains(rule));
        }
        assertEquals(51, HostAdaptation.getFeedResources().size());
        assertEquals("d5s", HostAdaptation.getFeedResources().get(2131369826));
        assertEquals("jb1", HostAdaptation.getFeedResources().get(2131378315));
        assertEquals("jc5", HostAdaptation.getFeedResources().get(2131378359));
        assertEquals("jc7", HostAdaptation.getFeedResources().get(2131378361));
        assertEquals("comment_layout", HostAdaptation.getFeedResources().get(2131371412));
        assertEquals("j_l", HostAdaptation.getFeedResources().get(2131379757));
    }

    @Test public void speechCapabilitiesCoverTheirScanAndRuntimeContractTargets() {
        HostFeatureCatalog.Feature comment = feature("comment_tts");
        assertEquals("feature.comment_tts.installed", comment.installedKey);
        assertTrue(comment.rules.containsAll(java.util.Arrays.asList("comment_publish_model",
                "comment_attachment_model", "comment_audio_model", "comment_publish_model_copy",
                "comment_attachment_copy", "comment_publish_submit", "comment_publish_submit_checked")));

        HostFeatureCatalog.Feature message = feature("dm_speech");
        assertEquals("feature.dm_speech.installed", message.installedKey);
        assertTrue(message.rules.containsAll(java.util.Arrays.asList("dm_speech_send", "dm_speech_input_api",
                "dm_speech_content", "dm_speech_edit_text", "dm_speech_send_click", "dm_speech_send_confirm",
                "dm_speech_conversation", "dm_speech_audio_create", "dm_speech_audio_destroy",
                "dm_speech_record_event", "dm_speech_record_model", "dm_speech_record_state",
                "dm_speech_upload_enqueue", "dm_speech_message_local_ext", "dm_speech_message_conversation")));
    }

    private static HostFeatureCatalog.Feature feature(String id) {
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            if (id.equals(feature.id)) return feature;
        }
        throw new AssertionError("Missing capability diagnostic: " + id);
    }
}

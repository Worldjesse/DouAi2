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
}

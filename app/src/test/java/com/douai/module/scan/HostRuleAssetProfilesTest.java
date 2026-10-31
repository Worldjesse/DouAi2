package com.douai.module.scan;

import org.junit.Test;

import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** The new host profile must preserve the previously shipped host rules as a separate contract. */
public final class HostRuleAssetProfilesTest {
    @Test public void supportedProfilesHaveSeparateAssetsWithTheCompleteFeatureRuleCoverage() throws Exception {
        assertNotEquals(HostAdaptation.PROFILE_406.rulesAsset, HostAdaptation.PROFILE_408.rulesAsset);
        assertNotEquals(HostAdaptation.PROFILE_405.rulesAsset, HostAdaptation.PROFILE_406.rulesAsset);
        HostRuleSet older = rules(HostAdaptation.PROFILE_406);
        HostRuleSet newer = rules(HostAdaptation.PROFILE_408);
        HostRuleSet build405 = rules(HostAdaptation.PROFILE_405);
        assertEquals("16a3f626c942f94e819c215ff69e5d810ff76ce9ffbbd4b459c034203e931ab3",
                older.getFingerprint());
        assertEquals(HostAdaptation.PROFILE_406.fingerprint, older.getFingerprint());
        assertEquals(HostAdaptation.PROFILE_408.fingerprint, newer.getFingerprint());
        assertEquals(HostAdaptation.PROFILE_405.fingerprint, build405.getFingerprint());
        assertNotEquals(older.getFingerprint(), newer.getFingerprint());
        assertNotEquals(older.getFingerprint(), build405.getFingerprint());
        assertNotEquals(newer.getFingerprint(), build405.getFingerprint());
        assertEquals(233, older.getRules().size());
        assertEquals(233, build405.getRules().size());
        Set<String> olderIds = ids(older), newerIds = ids(newer), build405Ids = ids(build405);
        assertEquals(olderIds, newerIds);
        assertEquals(olderIds, build405Ids);
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            for (String id : feature.rules) {
                assertTrue("40.6.0 " + feature.id + " references missing rule " + id, olderIds.contains(id));
                assertTrue("40.8.0 " + feature.id + " references missing rule " + id, newerIds.contains(id));
                assertTrue("40.5.0 " + feature.id + " references missing rule " + id, build405Ids.contains(id));
            }
        }
    }

    private static HostRuleSet rules(HostAdaptation.Profile profile) throws Exception {
        try (FileInputStream input = new FileInputStream(HostApkTestProfile.assetFile(profile.rulesAsset))) {
            return HostRuleSet.parse(input);
        }
    }

    private static Set<String> ids(HostRuleSet rules) {
        Set<String> ids = new HashSet<>();
        for (HostRule rule : rules.getRules()) ids.add(rule.getId());
        return ids;
    }
}

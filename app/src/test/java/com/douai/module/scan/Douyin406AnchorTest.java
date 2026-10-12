package com.douai.module.scan;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class Douyin406AnchorTest {
    @Test
    public void verifiedFeedAnchorsAreUniqueAcrossTheLocalHostDexSet() throws Exception {
        String apkPath = System.getenv("DOUAI_TEST_APK");
        File apk = apkPath == null ? null : new File(apkPath);
        Assume.assumeTrue(apk != null && apk.isFile());

        File rulesFile = new File("src/main/assets/douyin-host-rules.tsv");
        if (!rulesFile.isFile()) {
            rulesFile = new File("app/src/main/assets/douyin-host-rules.tsv");
        }
        Assume.assumeTrue(rulesFile.isFile());
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(rulesFile)) {
            rules = HostRuleSet.parse(input);
        }
        HostScanReport report = new HostScanner().scan(Collections.singletonList(apk), rules);

        assertEquals(rules.getRules().size(), report.getRuleResults().size());
        for (Map.Entry<String, RuleScanResult> result : report.getResults().entrySet()) {
            assertEquals(result.getKey() + " should resolve uniquely", RuleScanResult.Status.UNIQUE,
                    result.getValue().getStatus());
        }
    }
}

package com.douai.module.diagnostics;

import android.content.pm.PackageInfo;
import android.os.Bundle;

import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostFeatureCatalog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostAdaptationDiagnosticsTest {
    @Test public void unknownVersionRemainsUnverifiedEvenWithUniqueRulesAndInstalledHooks() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("auto_follow_hook_installed", true);
        HostAdaptationDiagnostics.append(snapshot, version("40.7.0", 400701L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report(""));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        assertTrue(snapshot.getBoolean("capability.auto_follow.rules_unique"));
        assertTrue(snapshot.getBoolean("capability.auto_follow.installed"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("该宿主版本未验证"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("实际行为仍需真机确认"));
    }

    @Test public void versionMatchDoesNotVerifyAChangedRuleset() {
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, version("40.6.0", 400601L), "changed", report(""));
        assertTrue(snapshot.getBoolean("host_version_profile_matched"));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("规则配置未绑定已验证证据"));
    }

    @Test public void diagnosesAmbiguousRuleAndSeparatesInstallationFromRuleMatch() {
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, version("40.6.0", 400601L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report("feed_holder_quick_root"));
        assertTrue(snapshot.getBoolean("host_adaptation_static_verified"));
        assertFalse(snapshot.getBoolean("capability.auto_follow.rules_unique"));
        assertEquals("feed_holder_quick_root=AMBIGUOUS",
                snapshot.getString("capability.auto_follow.rule_gaps"));
        assertTrue(snapshot.getBoolean("capability.auto_like.rules_unique"));
        assertFalse(snapshot.getBoolean("capability.auto_like.installed"));
        String detail = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(detail.contains("自动关注规则缺口：feed_holder_quick_root=AMBIGUOUS"));
        assertTrue(detail.contains("自动点赞：规则唯一命中，Hook 未安装"));
    }

    @Test public void missingVersionAndReportHaveExplicitUnverifiedState() {
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, HostAdaptationDiagnostics.versionEvidence(null), null, null);
        assertFalse(snapshot.getBoolean("host_version_available"));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        assertEquals("feed_holder_selected=MISSING, feed_holder_quick_root=MISSING",
                snapshot.getString("capability.auto_follow.rule_gaps"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("读取失败，未验证"));
    }

    private static Bundle version(String name, long code) {
        PackageInfo info = new PackageInfo();
        info.packageName = HostAdaptation.TARGET_PACKAGE;
        info.versionName = name;
        info.setLongVersionCode(code);
        return HostAdaptationDiagnostics.versionEvidence(info);
    }

    private static HostScanReport report(String ambiguousRule) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) ids.addAll(feature.rules);
        List<RuleScanResult> results = new ArrayList<>();
        for (String id : ids) {
            HostCandidate first = new HostCandidate("base.apk", "classes.dex", "Lfixture/Owner;", id, "()V");
            List<HostCandidate> candidates = id.equals(ambiguousRule)
                    ? Arrays.asList(first, new HostCandidate("base.apk", "classes2.dex", "Lfixture/Owner;", id, "()V"))
                    : Collections.singletonList(first);
            results.add(new RuleScanResult(id, "fixture", candidates));
        }
        return new HostScanReport("fixture", results, false);
    }
}

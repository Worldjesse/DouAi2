package com.douai.module.scan;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class StartupHostCacheTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private File archive(String content) throws Exception {
        File apk = files.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(("dex\n035\0" + content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return apk;
    }

    private HostRuleSet rules(String id) {
        return HostRuleSet.of(Collections.singletonList(HostRule.builder(id, "basic", HostRule.Kind.CLASS)
                .classDescriptor("Lfixture/Target;").build()));
    }

    private void save(File cache, File apk, HostRuleSet rules, String id) throws Exception {
        String identity = new HostScanner().hostIdentity(Collections.singletonList(apk), rules);
        RuleScanResult match = new RuleScanResult(id, "basic", Collections.singletonList(
                new HostCandidate(apk.getName(), "classes.dex", "Lfixture/Target;", "", "")));
        new HostScanCache(cache).save(new HostScanReport(identity, Collections.singletonList(match), false));
    }

    @Test public void startupOnlyLoadsAVerifiedExistingCacheAndNeverScansAMissingOne() throws Exception {
        File apk = archive("original");
        File cache = new File(files.getRoot(), "cache");
        HostRuleSet rules = rules("gate");
        assertNull(StartupHostCache.load(cache, Collections.singletonList(apk), rules));
        save(cache, apk, rules, "gate");
        HostScanReport report = StartupHostCache.load(cache, Collections.singletonList(apk), rules);
        assertNotNull(report);
        assertTrue(report.isCacheHit());
        assertEquals(RuleScanResult.Status.UNIQUE, report.getResults().get("gate").getStatus());
    }

    @Test public void apkAndRuleChangesRejectTheStartupCache() throws Exception {
        File apk = archive("original");
        File cache = new File(files.getRoot(), "cache");
        HostRuleSet rules = rules("gate");
        save(cache, apk, rules, "gate");
        assertNull(StartupHostCache.load(cache, Collections.singletonList(archive("changed")), rules));
        assertNull(StartupHostCache.load(cache, Collections.singletonList(apk), rules("new-gate")));
    }

    @Test public void cachedResultIdsMustMatchThePackagedRules() throws Exception {
        File apk = archive("original");
        File cache = new File(files.getRoot(), "cache");
        HostRuleSet rules = rules("gate");
        save(cache, apk, rules, "wrong-gate");
        assertNull(StartupHostCache.load(cache, Collections.singletonList(apk), rules));
    }

    @Test public void installationUpdatesInvalidateCacheEvenWhenDexAndRulesAreUnchanged() throws Exception {
        File apk = archive("original");
        File cache = new File(files.getRoot(), "cache");
        HostRuleSet rules = rules("gate");
        String identity = new HostScanner().hostIdentity(Collections.singletonList(apk), rules, "module-18-host-40.6");
        RuleScanResult match = new RuleScanResult("gate", "basic", Collections.singletonList(
                new HostCandidate(apk.getName(), "classes.dex", "Lfixture/Target;", "", "")));
        new HostScanCache(cache).save(new HostScanReport(identity, Collections.singletonList(match), false));
        assertNotNull(StartupHostCache.load(cache, Collections.singletonList(apk), rules, "module-18-host-40.6"));
        assertNull(StartupHostCache.load(cache, Collections.singletonList(apk), rules, "module-19-host-40.6"));
        assertNull(StartupHostCache.load(cache, Collections.singletonList(apk), rules, "module-18-host-40.7"));
    }

    @Test public void ordinaryStartsLoadCacheAndOnlyUpdateStartsNotifyScanning() throws Exception {
        File apk = archive("header-only");
        HostRuleSet emptyRules = HostRuleSet.of(Collections.emptyList());
        HostScanCache cache = new HostScanCache(new File(files.getRoot(), "progress-cache"));
        int[] startsAndHits = {0, 0};
        HostScanner.ProgressListener listener = new HostScanner.ProgressListener() {
            @Override public void onCacheHit(int dexCount) { startsAndHits[1]++; }
            @Override public void onScanStarted(int dexCount) { startsAndHits[0]++; }
            @Override public void onDexScanned(int done, int total, String dexName) { }
        };
        HostScanner scanner = new HostScanner();
        assertFalse(scanner.scanCached(Collections.singletonList(apk), emptyRules, cache, listener, "18").isCacheHit());
        assertTrue(scanner.scanCached(Collections.singletonList(apk), emptyRules, cache, listener, "18").isCacheHit());
        assertEquals(1, startsAndHits[0]);
        assertEquals(1, startsAndHits[1]);
        assertFalse(scanner.scanCached(Collections.singletonList(apk), emptyRules, cache, listener, "19").isCacheHit());
        assertEquals(2, startsAndHits[0]);
    }
}

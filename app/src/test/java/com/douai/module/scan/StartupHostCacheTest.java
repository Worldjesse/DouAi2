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

    @Test public void earlyAndBackgroundPathsShareOneInventoryAndOneVerifiedReport() throws Exception {
        File apk = archive("original");
        File cache = new File(files.getRoot(), "shared-cache");
        HostRuleSet rules = rules("gate");
        save(cache, apk, rules, "gate");
        int[] inspectionsAndDecodes = {0, 0};
        HostScanner scanner = new HostScanner((owner, method) -> inspectionsAndDecodes[1]++,
                dexCount -> inspectionsAndDecodes[0]++);
        HostScanner.PreparedScan prepared = scanner.prepare(Collections.singletonList(apk), rules);
        HostScanReport early = StartupHostCache.load(cache, prepared);
        assertNotNull(early);
        // A second disk read would now fail. The process already validated this immutable result.
        try (FileOutputStream output = new FileOutputStream(cache)) { output.write(1); }
        int[] startsAndHits = {0, 0};
        HostScanReport background = scanner.scanCached(prepared, new HostScanCache(cache), progress(startsAndHits));
        assertSame(early, background);
        assertEquals("early and background enumerate DEX entries only once", 1, inspectionsAndDecodes[0]);
        assertEquals(0, inspectionsAndDecodes[1]);
        assertEquals(0, startsAndHits[0]);
        assertEquals(1, startsAndHits[1]);
    }

    @Test public void preparedScanRechecksHostFreshnessAndFallsBackToNewInventory() throws Exception {
        File apk = archive("original");
        HostRuleSet rules = HostRuleSet.of(Collections.emptyList());
        HostScanCache cache = new HostScanCache(new File(files.getRoot(), "changing-cache"));
        int[] inspections = {0};
        HostScanner scanner = new HostScanner(null, dexCount -> inspections[0]++);
        HostScanner.PreparedScan prepared = scanner.prepare(Collections.singletonList(apk), rules);
        HostScanReport original = scanner.scanCached(prepared, cache, null);
        overwrite(apk, "a changed and longer installed DEX");
        assertFalse(prepared.matches(Collections.singletonList(apk), rules));
        HostScanReport changed = scanner.scanCached(prepared, cache, null);
        assertFalse(changed.isCacheHit());
        assertNotEquals(original.getHostIdentity(), changed.getHostIdentity());
        assertEquals(2, inspections[0]);
    }

    @Test public void installationChangeDuringInventoryPreparationCannotPublishPreparedInputs() throws Exception {
        File apk = archive("original");
        HostScanner scanner = new HostScanner(null, dexCount -> {
            try { overwrite(apk, "changed between inventory and freshness validation"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertThrows(java.io.IOException.class, () -> scanner.prepare(Collections.singletonList(apk),
                HostRuleSet.of(Collections.emptyList())));
    }

    @Test public void installationChangedWhileCacheValidationWaitsCannotReuseTheOldReport() throws Exception {
        File apk = archive("original");
        File cache = new File(files.getRoot(), "concurrent-cache");
        HostRuleSet rules = rules("gate");
        save(cache, apk, rules, "gate");
        HostScanner.PreparedScan prepared = new HostScanner().prepare(Collections.singletonList(apk), rules);
        java.lang.reflect.Field validation = HostScanner.PreparedScan.class.getDeclaredField("cacheValidationLock");
        validation.setAccessible(true);
        Object lock = validation.get(prepared);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<HostScanReport> result;
            synchronized (lock) {
                result = worker.submit(() -> {
                    started.countDown();
                    return prepared.loadCached(new HostScanCache(cache));
                });
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                overwrite(apk, "changed before cached candidates can be accepted");
            }
            assertNull(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }

    @Test public void liveScanIdentitySurvivesUnrelatedModuleUpdatesButRejectsDifferentRules() throws Exception {
        File apk = archive("original");
        File module = files.newFile("module.apk");
        HostRuleSet rules = HostRuleSet.of(Collections.emptyList());
        HostScanCache cache = new HostScanCache(new File(files.getRoot(), "live-cache"));
        HostScanner scanner = new HostScanner();
        HostScanner.PreparedScan before = scanner.prepare(Collections.singletonList(apk), rules);
        String installationBefore = HostInstallation.identity("0.2.20:121", module, Collections.singletonList(apk));
        int[] startsAndHits = {0, 0};
        assertFalse(scanner.scanCached(before, cache, progress(startsAndHits)).isCacheHit());
        assertNotEquals(installationBefore,
                HostInstallation.identity("0.2.21:122", module, Collections.singletonList(apk)));
        HostScanner.PreparedScan after = scanner.prepare(Collections.singletonList(apk), rules);
        assertEquals(before.getIdentity(), after.getIdentity());
        assertTrue(scanner.scanCached(after, cache, progress(startsAndHits)).isCacheHit());
        HostRuleSet differentRules = rules("new-gate");
        assertFalse(after.matches(Collections.singletonList(apk), differentRules));
        assertNull(StartupHostCache.load(new File(files.getRoot(), "live-cache"),
                scanner.prepare(Collections.singletonList(apk), differentRules)));
        assertEquals(1, startsAndHits[0]);
        assertEquals(1, startsAndHits[1]);
    }

    @Test public void corruptEarlyCacheStillAllowsTheBackgroundScanAndCacheRepair() throws Exception {
        File apk = archive("original");
        File path = files.newFile("broken-cache");
        try (FileOutputStream output = new FileOutputStream(path)) { output.write(1); }
        HostRuleSet rules = HostRuleSet.of(Collections.emptyList());
        HostScanner scanner = new HostScanner();
        HostScanner.PreparedScan prepared = scanner.prepare(Collections.singletonList(apk), rules);
        assertThrows(java.io.IOException.class, () -> StartupHostCache.load(path, prepared));
        HostScanReport repaired = scanner.scanCached(prepared, new HostScanCache(path), null);
        assertFalse(repaired.isCacheHit());
        assertNotNull(new HostScanCache(path).load(prepared.getIdentity()));
    }

    private void overwrite(File apk, String content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(("dex\n035\0" + content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static HostScanner.ProgressListener progress(int[] startsAndHits) {
        return new HostScanner.ProgressListener() {
            @Override public void onCacheHit(int dexCount) { startsAndHits[1]++; }
            @Override public void onScanStarted(int dexCount) { startsAndHits[0]++; }
            @Override public void onDexScanned(int done, int total, String dexName) { }
        };
    }
}

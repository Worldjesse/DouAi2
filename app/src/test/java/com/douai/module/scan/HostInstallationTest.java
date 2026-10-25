package com.douai.module.scan;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class HostInstallationTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void restartsKeepIdentityAndModuleOrHostUpdatesChangeIt() throws Exception {
        File module = files.newFile("module.apk");
        File host = files.newFile("base.apk");
        File split = files.newFile("split.apk");
        String original = HostInstallation.identity("0.1.17:18", module, Arrays.asList(host, split));
        assertEquals(original, HostInstallation.identity("0.1.17:18", module, Arrays.asList(split, host)));
        assertNotEquals(original, HostInstallation.identity("0.1.18:19", module, Arrays.asList(host, split)));
        assertTrue(module.setLastModified(module.lastModified() + 10_000));
        assertNotEquals(original, HostInstallation.identity("0.1.17:18", module, Arrays.asList(host, split)));
        String updated = HostInstallation.identity("0.1.17:18", module, Arrays.asList(host, split));
        assertTrue(host.setLastModified(host.lastModified() + 10_000));
        assertNotEquals(updated, HostInstallation.identity("0.1.17:18", module, Arrays.asList(host, split)));
    }

    @Test public void missingApkMetadataIsRejected() throws Exception {
        try {
            HostInstallation.identity("18", new File(files.getRoot(), "missing"),
                    java.util.Collections.singletonList(files.newFile("host.apk")));
            fail("Missing module archive must not silently reuse cached adaptation");
        } catch (java.io.IOException expected) { }
    }

    @Test public void hostFreshnessIsOrderIndependentAndDoesNotDependOnModuleVersion() throws Exception {
        File module = files.newFile("module.apk");
        File host = files.newFile("host.apk");
        File split = files.newFile("split.apk");
        String original = HostInstallation.hostIdentity(Arrays.asList(host, split));
        assertEquals(original, HostInstallation.hostIdentity(Arrays.asList(split, host)));
        assertTrue(module.setLastModified(module.lastModified() + 10_000));
        assertEquals(original, HostInstallation.hostIdentity(Arrays.asList(host, split)));
        assertTrue(host.setLastModified(host.lastModified() + 10_000));
        assertNotEquals(original, HostInstallation.hostIdentity(Arrays.asList(host, split)));
        assertThrows(java.io.IOException.class, () -> HostInstallation.hostIdentity(
                java.util.Collections.singletonList(new File(files.getRoot(), "missing-host"))));
    }
}

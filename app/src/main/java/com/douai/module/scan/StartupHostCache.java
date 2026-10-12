package com.douai.module.scan;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates existing scan results without initiating DEX decoding on the startup thread. */
public final class StartupHostCache {
    private StartupHostCache() { }

    public static HostScanReport load(File cacheFile, List<File> archives, HostRuleSet rules) throws IOException {
        return load(cacheFile, archives, rules, "");
    }

    public static HostScanReport load(File cacheFile, List<File> archives, HostRuleSet rules,
                                      String installationIdentity) throws IOException {
        if (cacheFile == null || !cacheFile.isFile()) return null;
        String identity = new HostScanner().hostIdentity(archives, rules, installationIdentity);
        HostScanReport cached = new HostScanCache(cacheFile).load(identity);
        if (cached == null || cached.getRuleResults().size() != rules.getRules().size()) return null;
        Set<String> expected = new HashSet<>();
        for (HostRule rule : rules.getRules()) expected.add(rule.getId());
        for (RuleScanResult result : cached.getRuleResults()) {
            if (!expected.remove(result.getRuleId())) return null;
        }
        return expected.isEmpty() ? cached : null;
    }
}

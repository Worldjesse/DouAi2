package com.douai.module.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HostScanReport {
    private final String hostIdentity;
    private final Map<String, RuleScanResult> results;
    private final boolean cacheHit;

    public HostScanReport(String hostIdentity, List<RuleScanResult> results, boolean cacheHit) {
        this.hostIdentity = hostIdentity;
        LinkedHashMap<String, RuleScanResult> indexed = new LinkedHashMap<>();
        for (RuleScanResult result : results) indexed.put(result.getRuleId(), result);
        this.results = Collections.unmodifiableMap(indexed);
        this.cacheHit = cacheHit;
    }

    public String getHostIdentity() { return hostIdentity; }
    public Map<String, RuleScanResult> getResults() { return results; }
    public boolean isCacheHit() { return cacheHit; }

    public List<RuleScanResult> getRuleResults() {
        return Collections.unmodifiableList(new ArrayList<>(results.values()));
    }

    public String summary() {
        int unique = 0;
        int ambiguous = 0;
        int notFound = 0;
        for (RuleScanResult result : results.values()) {
            switch (result.getStatus()) {
                case UNIQUE: unique++; break;
                case AMBIGUOUS: ambiguous++; break;
                case NOT_FOUND: notFound++; break;
            }
        }
        return "rules=" + results.size() + ", unique=" + unique + ", ambiguous=" + ambiguous
                + ", notFound=" + notFound + ", cacheHit=" + cacheHit;
    }

    HostScanReport asCacheHit() {
        return new HostScanReport(hostIdentity, getRuleResults(), true);
    }
}

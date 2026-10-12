package com.douai.module.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuleScanResult {
    public enum Status { NOT_FOUND, UNIQUE, AMBIGUOUS }

    private final String ruleId;
    private final String feature;
    private final Status status;
    private final List<HostCandidate> candidates;

    public RuleScanResult(String ruleId, String feature, List<HostCandidate> candidates) {
        this.ruleId = ruleId;
        this.feature = feature;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.status = candidates.isEmpty() ? Status.NOT_FOUND
                : candidates.size() == 1 ? Status.UNIQUE : Status.AMBIGUOUS;
    }

    public String getRuleId() { return ruleId; }
    public String getFeature() { return feature; }
    public Status getStatus() { return status; }
    public List<HostCandidate> getCandidates() { return candidates; }
}

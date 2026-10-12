package com.douai.module.feature;

import java.util.Collection;

/** Selects a playable host-provided source and never fabricates or rewrites URLs. */
public final class MediaSourceSelector {
    private MediaSourceSelector() { }

    public static MediaSource choose(Collection<MediaSource> sources, boolean preferNoWatermark) {
        if (sources == null || sources.isEmpty()) return null;
        MediaSource fallback = null;
        MediaSource preferred = null;
        for (MediaSource source : sources) {
            if (source == null || !source.isUsable()) continue;
            if (source.defaultSource && (fallback == null || source.qualityRank > fallback.qualityRank)) {
                fallback = source;
            }
            if (!source.watermarked && (preferred == null || source.qualityRank > preferred.qualityRank)) {
                preferred = source;
            }
        }
        if (!preferNoWatermark) return fallback != null ? fallback : preferred;
        return preferred != null ? preferred : fallback;
    }
}

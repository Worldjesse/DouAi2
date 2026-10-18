package com.douai.module.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, local report. Counts are observed visits/actions, never inferred playback time. */
public final class AnalyticsSnapshot {
    public final long totalViews;
    public final long totalFeatureUses;
    public final long uniqueWorks;
    public final long advertisementViews;
    public final int activeDays;
    public final int currentStreak;
    public final long firstSeen;
    public final List<Bucket> trend;
    public final List<Rank> types;
    public final List<Rank> authors;
    public final List<Rank> topics;
    public final List<Rank> features;
    public final long[] hours;
    public final List<Recent> recent;
    public final PortraitSnapshot portrait;

    public AnalyticsSnapshot(long totalViews, long totalFeatureUses, long uniqueWorks,
            long advertisementViews, int activeDays, int currentStreak, long firstSeen,
            List<Bucket> trend, List<Rank> types, List<Rank> authors, List<Rank> topics,
            List<Rank> features, long[] hours, List<Recent> recent) {
        this(totalViews, totalFeatureUses, uniqueWorks, advertisementViews, activeDays, currentStreak,
                firstSeen, trend, types, authors, topics, features, hours, recent, PortraitSnapshot.empty());
    }

    public AnalyticsSnapshot(long totalViews, long totalFeatureUses, long uniqueWorks,
            long advertisementViews, int activeDays, int currentStreak, long firstSeen,
            List<Bucket> trend, List<Rank> types, List<Rank> authors, List<Rank> topics,
            List<Rank> features, long[] hours, List<Recent> recent, PortraitSnapshot portrait) {
        this.totalViews = totalViews;
        this.totalFeatureUses = totalFeatureUses;
        this.uniqueWorks = uniqueWorks;
        this.advertisementViews = advertisementViews;
        this.activeDays = activeDays;
        this.currentStreak = currentStreak;
        this.firstSeen = firstSeen;
        this.trend = immutable(trend);
        this.types = immutable(types);
        this.authors = immutable(authors);
        this.topics = immutable(topics);
        this.features = immutable(features);
        this.hours = hours.clone();
        this.recent = immutable(recent);
        this.portrait = portrait == null ? PortraitSnapshot.empty() : portrait;
    }

    public static AnalyticsSnapshot empty() {
        return new AnalyticsSnapshot(0, 0, 0, 0, 0, 0, 0, List.of(), List.of(),
                List.of(), List.of(), List.of(), new long[24], List.of());
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Bucket {
        public final String label;
        public final long count;
        public Bucket(String label, long count) { this.label = label; this.count = count; }
    }

    public static final class Rank {
        public final String label;
        public final long count;
        public Rank(String label, long count) { this.label = label; this.count = count; }
    }

    public static final class Recent {
        public final long timestamp;
        public final String label;
        public final String kind;
        public Recent(long timestamp, String label, String kind) {
            this.timestamp = timestamp;
            this.label = label;
            this.kind = kind;
        }
    }
}

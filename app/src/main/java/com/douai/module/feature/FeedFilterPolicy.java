package com.douai.module.feature;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;

import java.util.Locale;

/** Pure policy kept separate from the version-specific feed model adapter. */
public final class FeedFilterPolicy {
    private FeedFilterPolicy() { }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings) {
        boolean legacyRecommendation = item != null && item.recommendationCard;
        return shouldHide(item, settings, legacyRecommendation, legacyRecommendation);
    }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings,
                                     boolean recommendationCard, boolean recommendationMerchant) {
        if (item == null || settings == null || !settings.feedFilterEnabled) return false;
        if (settings.hideAds && item.advertisement) return true;
        if (settings.hideLives && item.live) return true;
        if (settings.hideImagePosts && item.imagePost) return true;
        if (settings.hideRecommendationCards && recommendationCard) return true;
        if (settings.hideRecommendationMerchants && recommendationMerchant) return true;
        if (settings.keywordFilterEnabled && containsKeyword(item.searchableText, settings.keyword)) return true;
        if (settings.minimumDurationEnabled && item.durationKnown
                && item.durationMillis < settings.minimumDurationSeconds * 1000L) return true;
        return settings.minimumLikesEnabled && item.likeCountKnown
                && item.likeCount < settings.minimumLikeCount;
    }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings,
                                     boolean recommendationCard, boolean recommendationMerchant,
                                     boolean game, int hostType, LocalOptions options) {
        if (item == null || settings == null || !settings.feedFilterEnabled) return false;
        if (shouldHide(item, settings, recommendationCard, recommendationMerchant)) return true;
        if (options == null) return false;
        if (options.enabled("hide_mini_games") && game) return true;
        if (options.enabled("hide_long_videos")) {
            int threshold = Math.max(1, options.integer("maximum_duration_seconds", 120));
            return hostType == 127 || hostType == 128
                    || item.durationKnown && item.durationMillis > threshold * 1000L;
        }
        return false;
    }

    private static boolean containsKeyword(String text, String configured) {
        if (configured == null || configured.trim().isEmpty()) return false;
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String term : configured.split("[,，\\n]+")) {
            String needle = term.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && haystack.contains(needle)) return true;
        }
        return false;
    }
}

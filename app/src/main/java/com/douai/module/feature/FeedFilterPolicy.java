package com.douai.module.feature;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;

import java.util.Locale;

/** Pure policy kept separate from the version-specific feed model adapter. */
public final class FeedFilterPolicy {
    /** First matching rule in the established short-circuit order; no item contents leave this policy. */
    public enum Reason {
        NONE, ADVERTISEMENT, LIVE, IMAGE_POST, RECOMMENDATION_CARD, RECOMMENDATION_MERCHANT,
        KEYWORD, MINIMUM_DURATION, MINIMUM_LIKES, PRODUCT_CARD, HOTSPOT_RANK_CARD, MINI_GAME, LONG_VIDEO;
        public boolean hides() { return this != NONE; }
    }
    private FeedFilterPolicy() { }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings) {
        boolean legacyRecommendation = item != null && item.recommendationCard;
        return shouldHide(item, settings, legacyRecommendation, legacyRecommendation);
    }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings,
                                     boolean recommendationCard, boolean recommendationMerchant) {
        return reason(item, settings, recommendationCard, recommendationMerchant).hides();
    }

    public static Reason reason(FeedItem item, FeatureSettings settings,
                                boolean recommendationCard, boolean recommendationMerchant) {
        if (item == null || settings == null || !settings.feedFilterEnabled) return Reason.NONE;
        if (settings.hideAds && item.advertisement) return Reason.ADVERTISEMENT;
        if (settings.hideLives && item.live) return Reason.LIVE;
        if (settings.hideImagePosts && item.imagePost) return Reason.IMAGE_POST;
        if (settings.hideRecommendationCards && recommendationCard) return Reason.RECOMMENDATION_CARD;
        if (settings.hideRecommendationMerchants && recommendationMerchant) return Reason.RECOMMENDATION_MERCHANT;
        if (settings.keywordFilterEnabled && containsKeyword(item.searchableText, settings.keyword)) return Reason.KEYWORD;
        if (settings.minimumDurationEnabled && item.durationKnown
                && item.durationMillis < settings.minimumDurationSeconds * 1000L) return Reason.MINIMUM_DURATION;
        return settings.minimumLikesEnabled && item.likeCountKnown
                && item.likeCount < settings.minimumLikeCount ? Reason.MINIMUM_LIKES : Reason.NONE;
    }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings,
                                     boolean recommendationCard, boolean recommendationMerchant,
                                     boolean game, int hostType, LocalOptions options) {
        return reason(item, settings, recommendationCard, recommendationMerchant, game, hostType, options).hides();
    }

    public static Reason reason(FeedItem item, FeatureSettings settings,
                                boolean recommendationCard, boolean recommendationMerchant,
                                boolean game, int hostType, LocalOptions options) {
        if (item == null || settings == null || !settings.feedFilterEnabled) return Reason.NONE;
        Reason base = reason(item, settings, recommendationCard, recommendationMerchant);
        if (base.hides()) return base;
        if (options == null) return Reason.NONE;
        if (options.enabled("hide_product_cards") && (hostType == 112 || hostType == 9001)) return Reason.PRODUCT_CARD;
        if (options.enabled("hide_hotspot_rank_cards") && hostType == 104) return Reason.HOTSPOT_RANK_CARD;
        if (options.enabled("hide_mini_games") && game) return Reason.MINI_GAME;
        if (options.enabled("hide_long_videos")) {
            int threshold = Math.max(1, options.integer("maximum_duration_seconds", 120));
            return hostType == 127 || hostType == 128
                    || item.durationKnown && item.durationMillis > threshold * 1000L ? Reason.LONG_VIDEO : Reason.NONE;
        }
        return Reason.NONE;
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

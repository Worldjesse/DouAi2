package com.douai.module.config;

import android.content.SharedPreferences;

/** Immutable local configuration shared by the settings UI and module entry. */
public final class FeatureSettings {
    public final boolean downloadEnabled;
    public final boolean removeWatermark;
    public final boolean feedFilterEnabled;
    public final boolean hideAds;
    public final boolean hideLives;
    public final boolean hideImagePosts;
    public final boolean hideRecommendationCards;
    public final boolean hideRecommendationMerchants;
    public final boolean keywordFilterEnabled;
    public final String keyword;
    public final boolean minimumDurationEnabled;
    public final int minimumDurationSeconds;
    public final boolean minimumLikesEnabled;
    public final int minimumLikeCount;
    public final boolean automationEnabled;
    public final boolean autoLikeEnabled;
    public final boolean autoFollowEnabled;
    public final boolean autoCommentEnabled;
    public final boolean autoMessageEnabled;

    public FeatureSettings(
            boolean downloadEnabled,
            boolean removeWatermark,
            boolean feedFilterEnabled,
            boolean hideAds,
            boolean hideLives,
            boolean hideImagePosts,
            boolean hideRecommendationCards,
            boolean keywordFilterEnabled,
            String keyword,
            boolean minimumDurationEnabled,
            int minimumDurationSeconds,
            boolean minimumLikesEnabled,
            int minimumLikeCount,
            boolean automationEnabled,
            boolean autoLikeEnabled,
            boolean autoFollowEnabled,
            boolean autoCommentEnabled,
            boolean autoMessageEnabled) {
        this(downloadEnabled, removeWatermark, feedFilterEnabled, hideAds, hideLives,
                hideImagePosts, hideRecommendationCards, hideRecommendationCards,
                keywordFilterEnabled, keyword, minimumDurationEnabled, minimumDurationSeconds,
                minimumLikesEnabled, minimumLikeCount, automationEnabled, autoLikeEnabled,
                autoFollowEnabled, autoCommentEnabled, autoMessageEnabled);
    }

    public FeatureSettings(
            boolean downloadEnabled,
            boolean removeWatermark,
            boolean feedFilterEnabled,
            boolean hideAds,
            boolean hideLives,
            boolean hideImagePosts,
            boolean hideRecommendationCards,
            boolean hideRecommendationMerchants,
            boolean keywordFilterEnabled,
            String keyword,
            boolean minimumDurationEnabled,
            int minimumDurationSeconds,
            boolean minimumLikesEnabled,
            int minimumLikeCount,
            boolean automationEnabled,
            boolean autoLikeEnabled,
            boolean autoFollowEnabled,
            boolean autoCommentEnabled,
            boolean autoMessageEnabled) {
        this.downloadEnabled = downloadEnabled;
        this.removeWatermark = removeWatermark;
        this.feedFilterEnabled = feedFilterEnabled;
        this.hideAds = hideAds;
        this.hideLives = hideLives;
        this.hideImagePosts = hideImagePosts;
        this.hideRecommendationCards = hideRecommendationCards;
        this.hideRecommendationMerchants = hideRecommendationMerchants;
        this.keywordFilterEnabled = keywordFilterEnabled;
        this.keyword = keyword == null ? "" : keyword.trim();
        this.minimumDurationEnabled = minimumDurationEnabled;
        this.minimumDurationSeconds = Math.max(0, minimumDurationSeconds);
        this.minimumLikesEnabled = minimumLikesEnabled;
        this.minimumLikeCount = Math.max(0, minimumLikeCount);
        this.automationEnabled = automationEnabled;
        this.autoLikeEnabled = autoLikeEnabled;
        this.autoFollowEnabled = autoFollowEnabled;
        this.autoCommentEnabled = autoCommentEnabled;
        this.autoMessageEnabled = autoMessageEnabled;
    }

    public static FeatureSettings defaults() {
        return new FeatureSettings(
                false, false, false,
                false, false, false, false,
                false,
                false, "", false, 0,
                false, 0,
                false, false, false, false, false);
    }

    public static FeatureSettings readFrom(SharedPreferences preferences) {
        FeatureSettings defaults = defaults();
        return new FeatureSettings(
                preferences.getBoolean("download_enabled", defaults.downloadEnabled),
                preferences.getBoolean("remove_watermark", defaults.removeWatermark),
                preferences.getBoolean("feed_filter_enabled", defaults.feedFilterEnabled),
                preferences.getBoolean("hide_ads", defaults.hideAds),
                preferences.getBoolean("hide_lives", defaults.hideLives),
                preferences.getBoolean("hide_image_posts", defaults.hideImagePosts),
                preferences.getBoolean("hide_recommendation_cards", defaults.hideRecommendationCards),
                preferences.getBoolean("hide_recommendation_merchants",
                        preferences.getBoolean("hide_recommendation_cards", defaults.hideRecommendationMerchants)),
                preferences.getBoolean("keyword_filter_enabled", defaults.keywordFilterEnabled),
                preferences.getString("keyword", defaults.keyword),
                preferences.getBoolean("minimum_duration_enabled", defaults.minimumDurationEnabled),
                preferences.getInt("minimum_duration_seconds", defaults.minimumDurationSeconds),
                preferences.getBoolean("minimum_likes_enabled", defaults.minimumLikesEnabled),
                preferences.getInt("minimum_like_count", defaults.minimumLikeCount),
                preferences.getBoolean("automation_enabled", defaults.automationEnabled),
                preferences.getBoolean("auto_like_enabled", defaults.autoLikeEnabled),
                preferences.getBoolean("auto_follow_enabled", defaults.autoFollowEnabled),
                preferences.getBoolean("auto_comment_enabled", defaults.autoCommentEnabled),
                preferences.getBoolean("auto_message_enabled", defaults.autoMessageEnabled));
    }

    void writeTo(SharedPreferences.Editor editor) {
        editor.putBoolean("download_enabled", downloadEnabled)
                .putBoolean("remove_watermark", removeWatermark)
                .putBoolean("feed_filter_enabled", feedFilterEnabled)
                .putBoolean("hide_ads", hideAds)
                .putBoolean("hide_lives", hideLives)
                .putBoolean("hide_image_posts", hideImagePosts)
                .putBoolean("hide_recommendation_cards", hideRecommendationCards)
                .putBoolean("hide_recommendation_merchants", hideRecommendationMerchants)
                .putBoolean("keyword_filter_enabled", keywordFilterEnabled)
                .putString("keyword", keyword)
                .putBoolean("minimum_duration_enabled", minimumDurationEnabled)
                .putInt("minimum_duration_seconds", minimumDurationSeconds)
                .putBoolean("minimum_likes_enabled", minimumLikesEnabled)
                .putInt("minimum_like_count", minimumLikeCount)
                .putBoolean("automation_enabled", automationEnabled)
                .putBoolean("auto_like_enabled", autoLikeEnabled)
                .putBoolean("auto_follow_enabled", autoFollowEnabled)
                .putBoolean("auto_comment_enabled", autoCommentEnabled)
                .putBoolean("auto_message_enabled", autoMessageEnabled);
    }
}

package com.douai.module.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FeatureSettingsTest {
    @Test
    public void unverifiedHostFeaturesAndAutomationDefaultOff() {
        FeatureSettings settings = FeatureSettings.defaults();

        assertFalse(settings.downloadEnabled);
        assertFalse(settings.removeWatermark);
        assertFalse(settings.feedFilterEnabled);
        assertFalse(settings.hideAds);
        assertFalse(settings.hideLives);
        assertFalse(settings.hideImagePosts);
        assertFalse(settings.hideRecommendationCards);
        assertFalse(settings.hideRecommendationMerchants);
        assertFalse(settings.keywordFilterEnabled);
        assertFalse(settings.minimumDurationEnabled);
        assertFalse(settings.minimumLikesEnabled);
        assertFalse(settings.automationEnabled);
        assertFalse(settings.autoLikeEnabled);
        assertFalse(settings.autoFollowEnabled);
        assertFalse(settings.autoCommentEnabled);
        assertFalse(settings.autoMessageEnabled);
    }

    @Test
    public void normalizesTextAndNumericThresholds() {
        FeatureSettings settings = new FeatureSettings(
                true, true, true, false, false, false, false,
                true, "  猫咪  ", true, -3, true, -100,
                false, false, false, false, false);

        assertEquals("猫咪", settings.keyword);
        assertEquals(0, settings.minimumDurationSeconds);
        assertEquals(0, settings.minimumLikeCount);
    }

    @Test
    public void legacyConstructorKeepsTheCombinedRecommendationFilterBehavior() {
        FeatureSettings settings = new FeatureSettings(
                false, false, true, false, false, false, true,
                false, "", false, 0, false, 0,
                false, false, false, false, false);

        assertTrue(settings.hideRecommendationCards);
        assertTrue(settings.hideRecommendationMerchants);
    }
}

package com.douai.module.feature;

import com.douai.module.config.FeatureSettings;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeedFilterPolicyTest {
    @Test public void reasonUsesTheOriginalFirstMatchingRuleAndBooleanContract() {
        FeatureSettings allBase = settings(true, true, true, true, true, true, "private-keyword", true, 60, true, 1000);
        FeedItem overlapping = item(true, true, true, true, "private-keyword", 2, 0);
        assertEquals(FeedFilterPolicy.Reason.ADVERTISEMENT, FeedFilterPolicy.reason(overlapping, allBase, true, true));
        assertTrue(FeedFilterPolicy.shouldHide(overlapping, allBase, true, true));
        FeatureSettings onlyKeyword = settings(true, false, false, false, false, true, "private-keyword", false, 0, false, 0);
        assertEquals(FeedFilterPolicy.Reason.KEYWORD, FeedFilterPolicy.reason(overlapping, onlyKeyword, false, false));
        com.douai.module.config.LocalOptions options = new com.douai.module.config.LocalOptions(java.util.Map.of(
                "hide_product_cards", true, "hide_mini_games", true, "hide_long_videos", true));
        assertEquals(FeedFilterPolicy.Reason.PRODUCT_CARD, FeedFilterPolicy.reason(overlapping, recommendationSettings(false, false), false, false, true, 112, options));
        assertEquals(FeedFilterPolicy.Reason.MINI_GAME, FeedFilterPolicy.reason(overlapping, recommendationSettings(false, false), false, false, true, 127, options));
        assertEquals(FeedFilterPolicy.Reason.LONG_VIDEO, FeedFilterPolicy.reason(overlapping, recommendationSettings(false, false), false, false, false, 127, options));
        assertEquals(FeedFilterPolicy.Reason.NONE, FeedFilterPolicy.reason(overlapping, FeatureSettings.defaults(), true, true, true, 112, options));
    }

    private FeatureSettings settings(boolean enabled, boolean ads, boolean lives,
                                     boolean images, boolean cards, boolean keywords,
                                     String keyword, boolean duration, int minDuration,
                                     boolean likes, int minLikes) {
        return new FeatureSettings(true, true, enabled, ads, lives, images, cards,
                keywords, keyword, duration, minDuration, likes, minLikes,
                false, false, false, false, false);
    }

    private FeedItem item(boolean ad, boolean live, boolean image, boolean card,
                          String text, int seconds, long likes) {
        return new FeedItem(ad, live, image, card, text, seconds < 0 ? -1 : seconds * 1000L, likes);
    }

    @Test public void disabledMasterSwitchNeverHides() {
        assertFalse(FeedFilterPolicy.shouldHide(item(true, false, false, false, "", 2, 0),
                settings(false, true, true, true, true, true, "word", true, 60, true, 100)));
    }

    @Test public void independentCategoriesCanBeFiltered() {
        assertTrue(FeedFilterPolicy.shouldHide(item(true, false, false, false, "", 30, 200),
                settings(true, true, false, false, false, false, "", false, 0, false, 0)));
        assertTrue(FeedFilterPolicy.shouldHide(item(false, true, false, false, "", 30, 200),
                settings(true, false, true, false, false, false, "", false, 0, false, 0)));
        assertFalse(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", 30, 200),
                settings(true, false, true, false, false, false, "", false, 0, false, 0)));
    }

    @Test public void keywordAndThresholdsRespectBoundaries() {
        FeatureSettings keyword = settings(true, false, false, false, false, true,
                "sale, 直播", false, 0, false, 0);
        assertTrue(FeedFilterPolicy.shouldHide(item(false, false, false, false,
                "New SALE today", 20, 50), keyword));
        assertFalse(FeedFilterPolicy.shouldHide(item(false, false, false, false,
                "A regular video", 20, 50), keyword));

        FeatureSettings limits = settings(true, false, false, false, false, false, "",
                true, 15, true, 1000);
        assertTrue(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", 14, 1000), limits));
        assertTrue(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", 15, 999), limits));
        assertFalse(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", 15, 1000), limits));
        assertFalse(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", -1, -1), limits));
    }

    @Test public void minimumDurationUsesMillisecondBoundary() {
        FeatureSettings duration = settings(true, false, false, false, false, false, "",
                true, 15, false, 0);
        assertTrue(FeedFilterPolicy.shouldHide(new FeedItem(false, false, false, false,
                "", 14_999, 10), duration));
        assertFalse(FeedFilterPolicy.shouldHide(new FeedItem(false, false, false, false,
                "", 15_000, 10), duration));
    }

    @Test public void recommendationCardsAndMerchantsAreIndependent() {
        FeedItem recommendation = item(false, false, false, true, "", 20, 0);
        FeatureSettings cardsOnly = recommendationSettings(true, false);
        FeatureSettings merchantsOnly = recommendationSettings(false, true);

        assertTrue(FeedFilterPolicy.shouldHide(recommendation, cardsOnly, true, false));
        assertFalse(FeedFilterPolicy.shouldHide(recommendation, cardsOnly, false, true));
        assertFalse(FeedFilterPolicy.shouldHide(recommendation, merchantsOnly, true, false));
        assertTrue(FeedFilterPolicy.shouldHide(recommendation, merchantsOnly, false, true));
    }

    private static FeatureSettings recommendationSettings(boolean cards, boolean merchants) {
        return new FeatureSettings(false, false, true, false, false, false, cards, merchants,
                false, "", false, 0, false, 0,
                false, false, false, false, false);
    }

    @Test public void longVideoFilteringUsesTheMaximumBoundaryAndHostLongTypes() {
        FeatureSettings filters = recommendationSettings(false, false);
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("hide_long_videos", true);
        values.put("maximum_duration_seconds", "120");
        com.douai.module.config.LocalOptions options = new com.douai.module.config.LocalOptions(values);
        assertFalse(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", 120, 0), filters, false, false, false, 0, options));
        assertTrue(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", 121, 0), filters, false, false, false, 0, options));
        assertTrue(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", -1, 0), filters, false, false, false, 127, options));
        assertTrue(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", -1, 0), filters, false, false, false, 128, options));
        assertFalse(FeedFilterPolicy.shouldHide(item(false, false, false, false, "", -1, 0), filters, false, false, false, 0, options));
    }

    @Test public void gameFilteringDoesNotTreatAllAnchoredVideosAsGames() {
        FeatureSettings filters = recommendationSettings(false, false);
        com.douai.module.config.LocalOptions options = new com.douai.module.config.LocalOptions(java.util.Collections.singletonMap("hide_mini_games", true));
        FeedItem ordinary = item(false, false, false, false, "", 20, 0);
        assertFalse(FeedFilterPolicy.shouldHide(ordinary, filters, false, false, false, 0, options));
        assertTrue(FeedFilterPolicy.shouldHide(ordinary, filters, false, false, true, 0, options));
        assertFalse(FeedFilterPolicy.shouldHide(ordinary, FeatureSettings.defaults(), false, false, true, 0, options));
    }

    @Test public void productAndHotspotCardsUseIndependentVerifiedHostTypes() {
        FeatureSettings filters = recommendationSettings(false, false);
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("hide_product_cards", true);
        values.put("hide_hotspot_rank_cards", false);
        com.douai.module.config.LocalOptions options = new com.douai.module.config.LocalOptions(values);
        FeedItem item = item(false, false, false, false, "", 20, 0);

        assertTrue(FeedFilterPolicy.shouldHide(item, filters, false, false, false, 112, options));
        assertTrue(FeedFilterPolicy.shouldHide(item, filters, false, false, false, 9001, options));
        assertFalse(FeedFilterPolicy.shouldHide(item, filters, false, false, false, 104, options));
        assertFalse(FeedFilterPolicy.shouldHide(item, filters, true, false, false, 145, options));
        assertFalse(FeedFilterPolicy.shouldHide(item, filters, false, true, false, 140, options));

        values.put("hide_product_cards", false);
        values.put("hide_hotspot_rank_cards", true);
        options = new com.douai.module.config.LocalOptions(values);
        assertFalse(FeedFilterPolicy.shouldHide(item, filters, false, false, false, 112, options));
        assertFalse(FeedFilterPolicy.shouldHide(item, filters, false, false, false, 9001, options));
        assertTrue(FeedFilterPolicy.shouldHide(item, filters, false, false, false, 104, options));
    }
}

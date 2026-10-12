package com.douai.module.feature;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class FeedResponseFilterTest {
    private static final class Item {
        final boolean ad;

        Item(boolean ad) {
            this.ad = ad;
        }
    }

    @Test
    public void filtersMatchingItemsWithoutChangingOrderOrOriginalList() {
        Item first = new Item(false);
        Item ad = new Item(true);
        Item last = new Item(false);
        List<Item> source = Arrays.asList(first, ad, last);

        List<Item> filtered = FeedResponseFilter.filter(source, true, item -> item.ad);

        assertEquals(Arrays.asList(first, last), filtered);
        assertEquals(3, source.size());
        assertFalse(filtered == source);
    }

    @Test
    public void disabledNoMatchAndWouldEmptyResultsPreserveOriginal() {
        List<Item> source = Arrays.asList(new Item(true), new Item(true));
        assertSame(source, FeedResponseFilter.filter(source, false, item -> item.ad));
        assertSame(source, FeedResponseFilter.filter(source, true, item -> false));
        assertSame(source, FeedResponseFilter.filter(source, true, item -> true));
        assertSame(source, FeedResponseFilter.filter(source, true, item -> {
            throw new IllegalStateException("unavailable classifier");
        }));
    }

    @Test
    public void recognizesOnlyVerifiedFeedEndpointPaths() {
        assertTrue(FeedResponseFilter.isMainFeedUrl("https://aweme.example/aweme/v1/feed/?type=0"));
        assertTrue(FeedResponseFilter.isMainFeedUrl("https://aweme.example/aweme/v2/feed/?count=6"));
        assertFalse(FeedResponseFilter.isMainFeedUrl("https://aweme.example/path/aweme/v2/feed/child"));
        assertFalse(FeedResponseFilter.isMainFeedUrl("https://aweme.example/search/?next=/aweme/v2/feed/"));
        assertFalse(FeedResponseFilter.isMainFeedUrl("https://aweme.example/aweme/v1/familiar/feed/"));
        assertFalse(FeedResponseFilter.isMainFeedUrl("https://aweme.example/aweme/v2/preview/feed/"));
        assertFalse(FeedResponseFilter.isMainFeedUrl("not a URL"));
        assertFalse(FeedResponseFilter.isMainFeedUrl(null));
    }
}

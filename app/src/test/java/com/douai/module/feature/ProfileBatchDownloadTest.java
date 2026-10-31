package com.douai.module.feature;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Adapter reading and de-duplication rules for the profile batch download. */
public final class ProfileBatchDownloadTest {
    /** Mirrors the host model shape: a public aweme_id field is what the JSON reader resolves. */
    public static final class FakeAweme {
        public final String aweme_id;
        FakeAweme(String id) { this.aweme_id = id; }
    }

    public static final class FakeAdapter {
        private final List<Object> items;
        FakeAdapter(List<Object> items) { this.items = items; }
        public List<Object> getItems() { return items; }
    }

    @Test public void readsItemsFromAnAdapterGetter() {
        List<Object> source = Arrays.asList(new FakeAweme("1"), new FakeAweme("2"));
        List<Object> items = ProfileBatchDownload.readItems(new FakeAdapter(source));
        assertEquals(2, items.size());
        assertSame(source.get(1), items.get(1));
    }

    @Test public void readsItemsFromACollectionDirectly() {
        List<Object> items = ProfileBatchDownload.readItems(Arrays.asList(new FakeAweme("7")));
        assertEquals(1, items.size());
    }

    @Test public void unknownAdapterShapeYieldsNoItemsInsteadOfThrowing() {
        assertTrue(ProfileBatchDownload.readItems("not an adapter").isEmpty());
        assertTrue(ProfileBatchDownload.readItems(null).isEmpty());
    }

    @Test public void dedupeKeepsFirstOccurrenceOrderAndDropsUnknownIds() {
        Object first = new FakeAweme("a");
        Object duplicate = new FakeAweme("a");
        Object second = new FakeAweme("b");
        Object noId = new Object();
        List<Object> unique = ProfileBatchDownload.dedupe(
                new ArrayList<>(Arrays.asList(first, duplicate, noId, second)));
        assertEquals(2, unique.size());
        assertSame(first, unique.get(0));
        assertSame(second, unique.get(1));
    }

    @Test public void progressLabelNeverExceedsTheItemCap() {
        assertEquals("批量下载 3/12", ProfileBatchDownload.progressLabel(3, 12));
        assertEquals("批量下载 1/" + ProfileBatchDownload.MAX_ITEMS,
                ProfileBatchDownload.progressLabel(1, ProfileBatchDownload.MAX_ITEMS + 50));
    }
}

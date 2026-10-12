package com.douai.module.feature;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MediaSourceSelectorTest {
    @Test public void noWatermarkPreferenceSelectsBestAvailableCandidate() {
        MediaSource low = new MediaSource("https://cdn.example/clean", false, false, 1);
        MediaSource high = new MediaSource("https://cdn.example/clean-hd", false, false, 3);
        MediaSource original = new MediaSource("https://cdn.example/original", true, true, 4);
        assertSame(high, MediaSourceSelector.choose(Arrays.asList(low, high, original), true));
        assertSame(original, MediaSourceSelector.choose(Arrays.asList(low, high, original), false));
    }

    @Test public void invalidUrlsAreIgnoredAndNoCandidateReturnsNull() {
        MediaSource invalid = new MediaSource("file:///private/path", false, true, 9);
        MediaSource clean = new MediaSource("https://cdn.example/clean", false, false, 1);
        assertSame(clean, MediaSourceSelector.choose(Arrays.asList(invalid, clean), false));
        assertNull(MediaSourceSelector.choose(Arrays.asList(invalid), true));
        assertNull(MediaSourceSelector.choose(null, true));
    }
}

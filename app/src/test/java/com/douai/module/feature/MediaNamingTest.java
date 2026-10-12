package com.douai.module.feature;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MediaNamingTest {
    @Test public void supportsAllThreeTemplateFormsWithoutLeavingDollarPrefixes() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("author", "$author\\name");
        metadata.put("desc", "作品");
        metadata.put("aid", "123");
        assertEquals("$author_name_作品_123.mp4",
                MediaNaming.expand("#author#_{desc}_${aid}.{ext}", metadata, 0, "mp4"));
    }

    @Test public void metadataCannotBecomePathsAndUnknownTokensUseNoHostReflection() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("desc", "../../folder/\u0000?video");
        String result = MediaNaming.expand("#desc#_#unknown#", metadata, 0, "mp4");
        assertFalse(result.contains("/"));
        assertFalse(result.contains("\\"));
        assertFalse(result.contains("\u0000"));
        assertFalse(result.contains("unknown"));
        assertTrue(result.endsWith(".mp4"));
    }

    @Test public void filenameLimitsUtf8BytesAndDoesNotSplitEmojiSurrogates() {
        String name = MediaNaming.sanitize("作品".repeat(90) + "\ud83d\ude00", "png");
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 184);
        assertTrue(name.endsWith(".png"));
        assertEquals(name, new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test public void emptyTemplateCanBeDetectedForStableFallback() {
        assertEquals("", MediaNaming.expand("{missing}", new HashMap<>(), 0, "jpg"));
        assertEquals("movie.mp4", MediaNaming.sanitize("movie.MP4", "mp4"));
    }
}

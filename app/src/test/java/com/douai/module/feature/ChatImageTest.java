package com.douai.module.feature;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.*;

public final class ChatImageTest {
    @Test public void inlineImageKeepsImmutableEncodedSnapshot() {
        byte[] source = new byte[]{1, 2, 3};
        ChatImage image = new ChatImage("image/png", source);
        source[0] = 9;
        assertEquals("image/png", image.mimeType);
        assertEquals(3, image.sizeBytes());
        assertEquals("data:image/png;base64,AQID", image.dataUrl());
        assertArrayEquals(new byte[]{1, 2, 3}, Base64.getDecoder().decode(image.base64Data));
    }

    @Test public void supportedMimeTypesAndExactSizeLimitAreAccepted() {
        for (String mime : new String[]{"image/jpeg", "image/png", "image/webp", "image/gif"}) {
            assertEquals(mime, new ChatImage(mime, new byte[]{1}).mimeType);
        }
        assertEquals("image/jpeg", new ChatImage(" IMAGE/JPEG ", new byte[]{1}).mimeType);
        assertEquals(ChatImage.MAX_BYTES, new ChatImage("image/png", new byte[ChatImage.MAX_BYTES]).sizeBytes());
    }

    @Test public void invalidAndOversizedImagesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChatImage("image/svg+xml", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ChatImage(null, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ChatImage("image/png", null));
        assertThrows(IllegalArgumentException.class, () -> new ChatImage("image/png", new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatImage("image/png", new byte[ChatImage.MAX_BYTES + 1]));
    }
}

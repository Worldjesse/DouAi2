package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;

import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ChatImageLoaderTest {
    @Test public void transparentImageBecomesBoundedJpegWithWhiteBackground() throws Exception {
        ChatImage image = ChatImageLoader.decode(image(2400, 1200, Bitmap.CompressFormat.PNG));
        assertEquals("image/jpeg", image.mimeType);
        Bitmap decoded = decode(image);
        assertTrue(decoded.getWidth() <= 2048);
        assertTrue(decoded.getHeight() <= 2048);
        assertEquals(2, decoded.getWidth() / decoded.getHeight());
        int pixel = decoded.getPixel(0, 0);
        assertTrue(Color.red(pixel) > 250 && Color.green(pixel) > 250 && Color.blue(pixel) > 250);
        assertNotNull(ChatImageLoader.thumbnail(image));
        decoded.recycle();
    }

    @Test public void exifRotationIsAppliedBeforeSending() throws Exception {
        File file = File.createTempFile("douai-chat-image", ".jpg");
        try {
            Files.write(file.toPath(), image(120, 60, Bitmap.CompressFormat.JPEG));
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
            exif.saveAttributes();
            Bitmap decoded = decode(ChatImageLoader.decode(Files.readAllBytes(file.toPath())));
            assertEquals(60, decoded.getWidth());
            assertEquals(120, decoded.getHeight());
            decoded.recycle();
        } finally { Files.deleteIfExists(file.toPath()); }
    }

    @Test public void rejectsInvalidAndOversizedDocuments() throws Exception {
        try { ChatImageLoader.decode(new byte[]{1, 2, 3}); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("格式")); }
        try { ChatImageLoader.decode(new byte[0]); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("为空")); }
        try { ChatImageLoader.read(new ByteArrayInputStream(new byte[16 * 1024 * 1024 + 1])); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("16 MB")); }
    }

    private static byte[] image(int width, int height, Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(format, 100, bytes);
        bitmap.recycle();
        return bytes.toByteArray();
    }

    private static Bitmap decode(ChatImage image) {
        byte[] bytes = Base64.getDecoder().decode(image.base64Data);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }
}

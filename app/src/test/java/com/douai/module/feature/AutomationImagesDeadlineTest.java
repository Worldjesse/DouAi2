package com.douai.module.feature;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AutomationImagesDeadlineTest {
    @Test public void incomingImageLoadStartsItsDeadlineBeforeContinuouslyArrivingBytes() throws Exception {
        AtomicLong elapsed = new AtomicLong(1);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded); }
        finally { bitmap.recycle(); }
        byte[] bytes = encoded.toByteArray();
        boolean[] disconnected = {false};
        HttpURLConnection connection = new HttpURLConnection(new URL("https://p.byteimg.com/image.png")) {
            @Override public int getResponseCode() { return 200; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    int offset;
                    @Override public int read() {
                        if (offset == bytes.length) return -1;
                        // Every read succeeds within the socket's ten-second timeout.
                        elapsed.addAndGet(9_000_000_000L);
                        return bytes[offset++] & 0xff;
                    }
                    @Override public int read(byte[] output, int start, int length) {
                        int value = read();
                        if (value < 0) return -1;
                        output[start] = (byte) value;
                        return 1;
                    }
                };
            }
            @Override public void disconnect() { disconnected[0] = true; }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        MediaReadCancellation cancellation = new MediaReadCancellation(60_000, elapsed::get, uri -> connection);
        assertThrows(TimeoutException.class, () -> AutomationImages.load(
                Collections.singletonList("https://p.byteimg.com/image.png"), cancellation));
        assertTrue(disconnected[0]);
    }
}

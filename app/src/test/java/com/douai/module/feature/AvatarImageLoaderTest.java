package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AvatarImageLoaderTest {
    private static final String SOURCE = "https://p3.douyinpic.com/avatar.png";

    @Test public void directlyDecodesAtAvatarSizeAndPreservesWhiteBackground() throws Exception {
        Bitmap avatar = AvatarImageLoader.decode(image(1025, 513, Bitmap.CompressFormat.PNG), new MediaReadCancellation());
        try {
            assertTrue(avatar.getWidth() <= AvatarImageLoader.EDGE);
            assertTrue(avatar.getHeight() <= AvatarImageLoader.EDGE);
            assertEquals(Color.WHITE, avatar.getPixel(0, 0));
            assertTrue(Math.abs(2.0 - (double) avatar.getWidth() / avatar.getHeight()) < 0.05);
        } finally { avatar.recycle(); }
    }

    @Test public void appliesExifOrientationAtThumbnailSize() throws Exception {
        File file = File.createTempFile("douai-avatar", ".jpg");
        try {
            Files.write(file.toPath(), image(120, 60, Bitmap.CompressFormat.JPEG));
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
            exif.saveAttributes();
            Bitmap avatar = AvatarImageLoader.decode(Files.readAllBytes(file.toPath()), new MediaReadCancellation());
            try { assertEquals(60, avatar.getWidth()); assertEquals(120, avatar.getHeight()); }
            finally { avatar.recycle(); }
        } finally { Files.deleteIfExists(file.toPath()); }
    }

    @Test public void rejectsInvalidImagesAndByteLimits() throws Exception {
        expectInvalid(() -> AvatarImageLoader.decode(new byte[]{1, 2, 3}, new MediaReadCancellation()));
        expectInvalid(() -> AvatarImageLoader.decode(new byte[0], new MediaReadCancellation()));
        FakeConnection announced = new FakeConnection(200, new ByteArrayInputStream(new byte[0]));
        announced.length = AvatarImageLoader.MAX_SOURCE_BYTES + 1L;
        expectInvalid(() -> request(announced).load(SOURCE));
        assertFalse(announced.inputOpened);
        assertTrue(announced.disconnected);

        InputStream oversized = new InputStream() {
            int remaining = AvatarImageLoader.MAX_SOURCE_BYTES + 1;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = Math.min(length, remaining);
                remaining -= count;
                return count;
            }
        };
        FakeConnection unannounced = new FakeConnection(200, oversized);
        expectInvalid(() -> request(unannounced).load(SOURCE));
        assertTrue(unannounced.disconnected);
    }

    @Test public void validatesInitialAndRedirectedUrlsBeforeOpening() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        FakeConnection redirect = new FakeConnection(302, new ByteArrayInputStream(new byte[0]));
        redirect.location = "https://example.com/avatar.png";
        MediaReadCancellation cancellation = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            opened.incrementAndGet(); return redirect;
        });
        expectInvalid(() -> new AvatarImageLoader.Request(cancellation).load("http://p3.douyinpic.com/avatar.png"));
        assertEquals(0, opened.get());
        expectInvalid(() -> new AvatarImageLoader.Request(cancellation).load(SOURCE));
        assertEquals(1, opened.get());
        assertTrue(redirect.disconnected);
    }

    @Test public void followsRelativeRedirectAndDisconnectsBothConnections() throws Exception {
        FakeConnection redirect = new FakeConnection(302, new ByteArrayInputStream(new byte[0]));
        redirect.location = "/small.png";
        FakeConnection image = new FakeConnection(200,
                new ByteArrayInputStream(image(512, 512, Bitmap.CompressFormat.PNG)));
        AtomicInteger opened = new AtomicInteger();
        MediaReadCancellation cancellation = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            if (opened.getAndIncrement() == 0) return redirect;
            assertEquals("/small.png", uri.getPath());
            return image;
        });
        Bitmap avatar = new AvatarImageLoader.Request(cancellation).load(SOURCE);
        try { assertEquals(128, avatar.getWidth()); assertEquals(128, avatar.getHeight()); }
        finally { avatar.recycle(); }
        assertTrue(redirect.disconnected);
        assertTrue(image.disconnected);
        assertFalse(image.getInstanceFollowRedirects());
        assertTrue(image.getReadTimeout() > 0 && image.getReadTimeout() <= 10_000);
    }

    @Test public void cancellationDisconnectsActiveReadAndRejectsLateData() throws Exception {
        AtomicReference<AvatarImageLoader.Request> active = new AtomicReference<>();
        FakeConnection connection = new FakeConnection(200, new InputStream() {
            @Override public int read() { active.get().cancel(); return -1; }
        });
        AvatarImageLoader.Request request = request(connection);
        active.set(request);
        try { request.load(SOURCE); fail(); }
        catch (CancellationException expected) { assertTrue(connection.disconnected); }
        try { request.load(SOURCE); fail(); }
        catch (CancellationException expected) { }
    }

    private interface Attempt { void run() throws Exception; }

    private static void expectInvalid(Attempt attempt) throws Exception {
        try { attempt.run(); fail("Expected bounded avatar rejection"); }
        catch (IllegalArgumentException expected) { }
    }

    private static AvatarImageLoader.Request request(FakeConnection connection) {
        return new AvatarImageLoader.Request(new MediaReadCancellation(60_000, System::nanoTime, uri -> connection));
    }

    private static byte[] image(int width, int height, Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(format, 100, bytes));
        bitmap.recycle();
        return bytes.toByteArray();
    }

    private static final class FakeConnection extends HttpURLConnection {
        final int status;
        final InputStream input;
        long length = -1;
        String location;
        boolean disconnected, inputOpened;

        FakeConnection(int status, InputStream input) throws Exception {
            super(new URL(SOURCE)); this.status = status; this.input = input;
        }

        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return length; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public InputStream getInputStream() { inputOpened = true; return input; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}

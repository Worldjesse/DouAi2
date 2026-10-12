package com.douai.module.feature;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A blocked transport that requires disconnect, rather than just thread interruption. */
final class BlockingMediaConnection extends HttpURLConnection {
    final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final boolean blockHeaders;
    volatile boolean disconnected;

    BlockingMediaConnection(boolean blockHeaders) throws Exception {
        super(new URL("https://p.byteimg.com/media"));
        this.blockHeaders = blockHeaders;
    }

    @Override public int getResponseCode() throws IOException {
        if (blockHeaders) awaitDisconnect();
        return 200;
    }

    @Override public long getContentLengthLong() { return 1024; }

    @Override public InputStream getInputStream() {
        return new InputStream() {
            boolean started;
            @Override public int read() throws IOException {
                if (!started) { started = true; return 1; }
                awaitDisconnect();
                return -1;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int value = read();
                if (value < 0) return -1;
                bytes[offset] = (byte) value;
                return 1;
            }
        };
    }

    private void awaitDisconnect() throws IOException {
        blocked.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!disconnected) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Test transport was not disconnected");
            try { released.await(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException ignored) { }
        }
        throw new IOException("Transport disconnected");
    }

    @Override public void disconnect() { disconnected = true; released.countDown(); }
    @Override public boolean usingProxy() { return false; }
    @Override public void connect() { }
}

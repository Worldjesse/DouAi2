package com.douai.module.feature;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public final class MediaReadCancellationTest {
    @Test public void cancelReleasesConnectionAndRejectsLateRegistration() throws Exception {
        MediaReadCancellation read = new MediaReadCancellation();
        BlockingMediaConnection active = new BlockingMediaConnection(true);
        read.attach(active);
        read.cancel();
        assertTrue(active.disconnected);
        BlockingMediaConnection late = new BlockingMediaConnection(true);
        assertThrows(CancellationException.class, () -> read.attach(late));
        assertTrue(late.disconnected);
    }

    @Test public void oldConnectionCleanupDoesNotClearItsReplacement() throws Exception {
        MediaReadCancellation read = new MediaReadCancellation();
        BlockingMediaConnection old = new BlockingMediaConnection(true);
        BlockingMediaConnection current = new BlockingMediaConnection(true);
        read.attach(old);
        read.attach(current);
        read.detach(old);
        read.cancel();
        assertTrue(current.disconnected);
    }

    @Test public void deadlineUsesElapsedTimeAcrossReadsAndDisconnects() throws Exception {
        AtomicLong now = new AtomicLong(1);
        MediaReadCancellation read = new MediaReadCancellation(60_000, now::get);
        read.start();
        now.addAndGet(59_000_000_000L);
        assertEquals(1000, read.timeoutMillis(10_000));
        read.start();
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        read.attach(connection);
        now.addAndGet(1_000_000_000L);
        assertThrows(TimeoutException.class, read::check);
        assertTrue(connection.disconnected);
    }

    @Test public void cancelBeforeImageReadDoesNotTouchInput() throws Exception {
        MediaReadCancellation read = new MediaReadCancellation();
        read.cancel();
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{1});
        assertThrows(CancellationException.class, () -> ChatImageLoader.read(source, read));
        assertEquals(1, source.available());
    }
}

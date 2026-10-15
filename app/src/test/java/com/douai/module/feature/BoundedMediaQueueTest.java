package com.douai.module.feature;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class BoundedMediaQueueTest {
    @Test public void queuedCancellationImmediatelyReleasesCapacityAndIdentity() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        BoundedMediaQueue queue = new BoundedMediaQueue(executor);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            assertTrue(queue.submit("running", () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            BoundedMediaQueue.Handle queued = queue.submit("queued", () -> fail("Cancelled queued task ran"), () -> { });
            assertNotNull(queued);
            assertFalse(queue.submit("overflow", () -> { }));
            queued.cancel();
            assertEquals(0, executor.getQueue().size());
            assertTrue(queue.submit("queued", () -> { }));
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS); }
    }

    @Test public void runningCancellationKeepsIdentityUntilFileCleanupFinishes() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        BoundedMediaQueue queue = new BoundedMediaQueue(executor);
        CountDownLatch started = new CountDownLatch(1), cleaning = new CountDownLatch(1), cleanup = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        try {
            BoundedMediaQueue.Handle handle = queue.submit("file", () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException cancelled) {
                    cleaning.countDown();
                    try { cleanup.await(); } catch (InterruptedException ignored) { }
                }
            }, released::countDown);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            handle.cancel();
            assertTrue(cleaning.await(2, TimeUnit.SECONDS));
            assertFalse("A retry must wait until old cleanup is finished", queue.submit("file", () -> { }));
            cleanup.countDown();
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertTrue(queue.submit("file", () -> { }));
        } finally { cleanup.countDown(); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS); }
    }

    @Test public void rejectsDuplicatesAndOverflowThenReleasesKeysAfterCompletion() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        BoundedMediaQueue queue = new BoundedMediaQueue(executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch acceptedTasksFinished = new CountDownLatch(2);
        try {
            assertTrue(queue.submit("media-a", () -> {
                firstStarted.countDown();
                try { releaseFirst.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { acceptedTasksFinished.countDown(); }
            }));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertFalse(queue.submit("media-a", acceptedTasksFinished::countDown));
            assertTrue(queue.submit("media-b", acceptedTasksFinished::countDown));
            assertFalse(queue.submit("media-c", acceptedTasksFinished::countDown));

            releaseFirst.countDown();
            assertTrue(acceptedTasksFinished.await(5, TimeUnit.SECONDS));
            assertTrue(queue.submit("media-c", () -> { }));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

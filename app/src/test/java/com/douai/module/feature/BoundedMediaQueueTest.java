package com.douai.module.feature;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class BoundedMediaQueueTest {
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

package com.douai.module.feature;

import org.junit.Test;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class MediaMemoryBudgetTest {
    @Test public void admissionIsSharedBoundedAndReleaseIsIdempotent() {
        MediaMemoryBudget.Pool pool = new MediaMemoryBudget.Pool(100);
        MediaMemoryBudget.Reservation image = pool.tryAcquire(70);
        assertNotNull(image);
        assertNull(pool.tryAcquire(31));
        assertNull(pool.tryAcquire(Long.MAX_VALUE));
        assertNull(pool.tryAcquire(0));
        MediaMemoryBudget.Reservation frame = pool.tryAcquire(30);
        assertNotNull(frame);
        image.close(); image.close();
        assertEquals(30, pool.usedBytes());
        frame.close(); assertEquals(0, pool.usedBytes());
    }

    @Test public void waitingWorkerAcquiresAfterOwnerReleases() throws Exception {
        MediaMemoryBudget.Pool pool = new MediaMemoryBudget.Pool(10);
        MediaMemoryBudget.Reservation owner = pool.tryAcquire(10);
        CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            entered.countDown();
            try (MediaMemoryBudget.Reservation ignored = pool.acquire(10, null)) { assertEquals(10, pool.usedBytes()); }
            catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        });
        worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        owner.close(); assertTrue(done.await(2, TimeUnit.SECONDS));
        assertNull(failure.get()); assertEquals(0, pool.usedBytes());
    }

    @Test public void cancelledWorkerDoesNotConsumeOrReleaseAnotherOwnersBytes() throws Exception {
        MediaMemoryBudget.Pool pool = new MediaMemoryBudget.Pool(10);
        MediaMemoryBudget.Reservation owner = pool.tryAcquire(10);
        MediaReadCancellation cancellation = new MediaReadCancellation(); cancellation.cancel();
        try { pool.acquire(10, cancellation); fail("cancelled request admitted"); }
        catch (CancellationException expected) { }
        assertEquals(10, pool.usedBytes()); owner.close();
    }
}

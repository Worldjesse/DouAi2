package com.douai.module.feature;

import org.junit.Test;

import static org.junit.Assert.*;

public final class ReplySchedulerTest {
    @Test public void busyConversationYieldsToOtherReadyConversationsAndKeepsItsOwnOrder() {
        ReplyScheduler<String> queue = new ReplyScheduler<>(100);
        for (int i = 0; i < 90; i++) assertTrue(queue.offer("busy", "busy-" + i, 2000));
        assertTrue(queue.offer("other", "other-0", 2000));
        assertTrue(queue.offer("third", "third-0", 2000));
        assertEquals("busy-0", queue.poll(2000));
        assertEquals("other-0", queue.poll(2000));
        assertEquals("third-0", queue.poll(2000));
        for (int i = 1; i < 90; i++) assertEquals("busy-" + i, queue.poll(2000));
        assertEquals(0, queue.size());
    }

    @Test public void delayedConversationDoesNotBlockAnotherConversationOrOvertakeItsOwnHead() {
        ReplyScheduler<String> queue = new ReplyScheduler<>(3);
        assertTrue(queue.offer("first", "delayed", 10_000));
        assertTrue(queue.offer("first", "later", 1000));
        assertTrue(queue.offer("second", "ready", 1000));
        assertEquals("ready", queue.poll(1000));
        assertNull(queue.poll(1000));
        assertEquals(10_000, queue.nextReadyAt());
        assertEquals("delayed", queue.poll(10_000));
        assertEquals("later", queue.poll(10_000));
    }

    @Test public void queueCapacityAndCancellationStayBounded() {
        ReplyScheduler<Object> queue = new ReplyScheduler<>(100);
        Object[] tasks = new Object[100];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = new Object();
            assertTrue(queue.offer("chat-" + i % 5, tasks[i], 0));
        }
        assertFalse(queue.offer("overflow", new Object(), 0));
        for (Object task : tasks) assertTrue(queue.remove(task));
        assertEquals(0, queue.size());
        assertEquals(Long.MAX_VALUE, queue.nextReadyAt());
        assertTrue(queue.offer("replacement", new Object(), 0));
    }
}

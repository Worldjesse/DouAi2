package com.douai.module.feature;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public final class AutoCommentQueueTest {
    @Test public void threeFailedAttemptsFinishOriginalBeforeStartingNext() {
        Clock clock = new Clock();
        List<String> calls = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> {
            calls.add(work + ':' + attempt);
            result.accept("one".equals(work) ? "failed" : "sent");
        }, (work, status, attempts) -> outcomes.add(work + ':' + status + ':' + attempts));
        queue.offer("one", "one", 3000);
        queue.offer("two", "two", 3000);
        assertTrue(calls.isEmpty());
        clock.next();
        assertEquals(List.of("one:1"), calls);
        assertEquals(1000, clock.delays.peek().longValue());
        clock.next();
        assertEquals(List.of("one:1", "one:2"), calls);
        assertEquals(2000, clock.delays.peek().longValue());
        clock.next();
        assertEquals(List.of("one:failed:3"), outcomes);
        assertEquals(3000, clock.delays.peek().longValue());
        clock.next();
        assertEquals(List.of("one:1", "one:2", "one:3", "two:1"), calls);
        assertEquals(List.of("one:failed:3", "two:sent:1"), outcomes);
    }

    @Test public void cancellationAdvancesTheQueueAndDuplicateTargetsStaySuppressed() {
        Clock clock = new Clock();
        List<String> calls = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> {
            calls.add(work);
            result.accept("one".equals(work) ? "cancelled" : "sent");
        }, (work, status, attempts) -> outcomes.add(work + ':' + status));
        queue.offer("one", "one", 0);
        queue.offer("one", "duplicate", 0);
        queue.offer("two", "two", 0);
        clock.next(); clock.next();
        queue.offer("one", "again", 0);
        assertEquals(List.of("one", "two"), calls);
        assertEquals(List.of("one:cancelled", "two:sent"), outcomes);
        assertTrue(clock.tasks.isEmpty());
    }

    @Test public void callbackFromFailedAttemptCannotCompleteTheRetryOrNextTarget() {
        Clock clock = new Clock();
        List<Consumer<String>> callbacks = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> callbacks.add(result),
                (work, status, attempts) -> outcomes.add(work + ':' + status + ':' + attempts));
        queue.offer("one", "one", 0);
        queue.offer("two", "two", 0);
        clock.next();
        callbacks.get(0).accept("failed");
        callbacks.get(0).accept("sent");
        clock.next();
        callbacks.get(0).accept("sent");
        assertTrue(outcomes.isEmpty());
        callbacks.get(1).accept("sent");
        clock.next();
        callbacks.get(1).accept("failed");
        assertEquals(List.of("one:sent:2"), outcomes);
        callbacks.get(2).accept("sent");
        assertEquals(List.of("one:sent:2", "two:sent:1"), outcomes);
    }

    @Test public void resetCancelsWaitingAndActiveTargetsAndIgnoresOldCallbacks() {
        Clock clock = new Clock();
        List<Consumer<String>> callbacks = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> callbacks.add(result),
                (work, status, attempts) -> outcomes.add(work + ':' + status + ':' + attempts));
        queue.offer("one", "one", 0);
        queue.offer("two", "two", 0);
        clock.next();
        queue.reset();
        assertEquals(List.of("one:cancelled:1", "two:cancelled:0"), outcomes);
        callbacks.get(0).accept("sent");
        assertEquals(2, outcomes.size());
        queue.offer("one", "one", 0);
        clock.next();
        callbacks.get(1).accept("sent");
        assertEquals("one:sent:1", outcomes.get(2));
    }

    @Test public void synchronousProcessorFailureIsBoundedAndDoesNotBlockLaterTargets() {
        Clock clock = new Clock();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> {
            if ("one".equals(work)) throw new IllegalStateException("fixture");
            result.accept("sent");
        }, (work, status, attempts) -> outcomes.add(work + ':' + status + ':' + attempts));
        queue.offer("one", "one", 0); queue.offer("two", "two", 0);
        for (int i = 0; i < 4; i++) clock.next();
        assertEquals(List.of("one:failed:3", "two:sent:1"), outcomes);
    }

    @Test public void closeStopsDelayedAndActiveCallbacks() {
        Clock clock = new Clock();
        List<Consumer<String>> callbacks = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> callbacks.add(result),
                (work, status, attempts) -> outcomes.add(work));
        queue.offer("one", "one", 0); queue.offer("two", "two", 0);
        clock.next(); queue.close();
        callbacks.get(0).accept("sent");
        queue.offer("three", "three", 0);
        assertTrue(outcomes.isEmpty());
        assertEquals(1, callbacks.size());
    }

    @Test public void unknownSubmissionAdvancesWithoutRetryEvenWithALateCallback() {
        Clock clock = new Clock();
        List<Consumer<String>> callbacks = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> callbacks.add(result),
                (work, status, attempts) -> outcomes.add(work + ':' + status));
        queue.offer("one", "one", 0); queue.offer("two", "two", 0);
        clock.next(); callbacks.get(0).accept("submitted");
        clock.next(); callbacks.get(0).accept("sent");
        assertEquals(List.of("one:submitted"), outcomes);
        assertEquals(2, callbacks.size());
    }

    @Test public void queueBoundAndAccountKeysKeepTargetsDistinct() {
        Clock clock = new Clock();
        List<String> calls = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> { calls.add(work); result.accept("sent"); },
                (work, status, attempts) -> outcomes.add(work + ':' + status + ':' + attempts));
        queue.offer("100:work", "first account", 0);
        queue.offer("200:work", "second account", 0);
        for (int i = 0; i < 30; i++) queue.offer("id" + i, "target" + i, 0);
        queue.offer("overflow", "overflow", 0);
        assertEquals(List.of("overflow:failed:0"), outcomes);
        clock.next(); clock.next();
        assertEquals(List.of("first account", "second account"), calls);
    }

    @Test public void cancelLatestUsesAcceptedQueueTailAfterDuplicateOffer() {
        Clock clock = new Clock();
        List<String> outcomes = new ArrayList<>();
        List<String> cancellationHooks = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> { },
                (work, status, attempts) -> outcomes.add(work + ':' + status));
        queue.offer("work", "work", 0);
        queue.cancelLatest(cancellationHooks::add);
        assertEquals(List.of("work:cancelled"), outcomes);

        queue.offer("other", "other", 0);
        queue.offer("work", "duplicate", 0);
        queue.cancelLatest(cancellationHooks::add);

        assertEquals(List.of("work", "other"), cancellationHooks);
        assertEquals(List.of("work:cancelled", "other:cancelled"), outcomes);
        while (!clock.tasks.isEmpty()) clock.next();
        assertEquals(List.of("work:cancelled", "other:cancelled"), outcomes);
        assertTrue(clock.tasks.isEmpty());
    }

    @Test public void queueStateAppearsBeforeProcessingAndCancellationKeepsTheNextCountCorrect() {
        Clock clock = new Clock();
        List<String> states = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<Consumer<String>> callbacks = new ArrayList<>();
        String first = new String("first");
        String second = new String("second");
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(clock, (work, attempt, result) -> callbacks.add(result),
                (work, status, attempts) -> outcomes.add(work + ':' + status),
                (current, count, attempts, phase) -> states.add(current + ":" + count + ":" + attempts + ":" + phase));
        queue.offer("first", first, 3000);
        assertEquals(List.of("first:1:0:waiting"), states);
        assertTrue(callbacks.isEmpty());
        clock.next();
        queue.offer("second", second, 3000);
        assertEquals("first:2:1:running", states.get(states.size() - 1));
        queue.cancel(first);
        assertEquals("second:1:0:waiting", states.get(states.size() - 1));
        callbacks.get(0).accept("sent");
        assertEquals(List.of("first:cancelled"), outcomes);
        clock.next(); callbacks.get(1).accept("sent");
        assertEquals("null:0:0:empty", states.get(states.size() - 1));
        assertEquals(List.of("first:cancelled", "second:sent"), outcomes);
    }

    private static final class Clock implements AutoCommentQueue.Scheduler {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        final Queue<Long> delays = new ArrayDeque<>();
        @Override public void post(Runnable action) { action.run(); }
        @Override public void later(Runnable action, long delayMillis) { tasks.add(action); delays.add(delayMillis); }
        void next() { delays.remove(); tasks.remove().run(); }
    }
}

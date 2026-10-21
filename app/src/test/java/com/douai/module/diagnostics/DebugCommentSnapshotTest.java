package com.douai.module.diagnostics;

import com.ss.android.ugc.aweme.comment.model.Comment;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public final class DebugCommentSnapshotTest {
    @Test public void onlyExplicitCommentBoundariesReachTheSerializer() {
        List<Object> serialized = new ArrayList<>(); List<String> logs = new ArrayList<>();
        DebugCommentSnapshot snapshot = snapshot(logs, () -> 0, value -> { serialized.add(value); return "{\"field\":null}"; });
        Comment owner = new Comment("owner"), direct = new Comment("argument"), response = new Comment("response");
        Comment row = new Comment("row"), returned = new Comment("return");
        snapshot.capture("owner", "before", owner, List.of(), null);
        snapshot.capture("argument", "before", null, List.of(direct), null);
        snapshot.capture("response", "after", null, List.of(new Response(response)), null);
        snapshot.capture("row", "before", null, List.of(new Row(row)), null);
        snapshot.capture("return", "after", null, List.of(), returned);
        assertEquals(5, serialized.size());
        assertSame(owner, serialized.get(0)); assertSame(direct, serialized.get(1));
        assertSame(response, serialized.get(2)); assertSame(row, serialized.get(3)); assertSame(returned, serialized.get(4));
        assertEquals(5, logs.size());
        assertTrue(logs.get(2).startsWith("[DouAi.CommentJson] hook=response stage=after json="));
        assertTrue(logs.get(2).endsWith("{\"field\":null}"));
    }

    @Test public void aRepeatedIdentityWithinOnePhaseIsSerializedOnlyOnce() {
        AtomicInteger count = new AtomicInteger(); List<String> logs = new ArrayList<>(); Comment comment = new Comment("same");
        DebugCommentSnapshot snapshot = snapshot(logs, () -> 0, value -> { count.incrementAndGet(); return "{}"; });
        snapshot.capture("same", "before", comment, Arrays.asList(comment, new Response(comment), new Row(comment)), comment);
        assertEquals(1, count.get()); assertEquals(1, logs.size());
    }

    @Test public void oneKnownTypedFieldDoesNotBecomeAnArbitraryObjectGraphWalk() {
        AtomicInteger count = new AtomicInteger(); List<String> logs = new ArrayList<>(); Comment comment = new Comment("private");
        DebugCommentSnapshot snapshot = snapshot(logs, () -> 0, value -> { count.incrementAndGet(); return "{}"; });
        snapshot.capture("unrelated", "before", new Response(comment), Arrays.asList(
                new Untyped(comment), new Nested(new Response(comment)), new PublishModel(comment),
                new CommentFailure(comment), List.of(comment), new Comment[]{comment}, new Poison()), new Response(comment));
        assertEquals(0, count.get()); assertTrue(logs.isEmpty());
    }

    @Test public void disabledConfigurationNeverSerializesAndLaterEnablingWorks() {
        AtomicBoolean enabled = new AtomicBoolean(); AtomicInteger count = new AtomicInteger(); List<String> logs = new ArrayList<>();
        DebugCommentSnapshot snapshot = new DebugCommentSnapshot(enabled::get, logs::add, () -> 0,
                value -> { count.incrementAndGet(); return "{}"; });
        Comment comment = new Comment("payload");
        snapshot.capture("disabled", "before", comment, List.of(), null);
        assertEquals(0, count.get()); assertTrue(logs.isEmpty());
        enabled.set(true); snapshot.capture("enabled", "before", comment, List.of(), null);
        assertEquals(1, count.get()); assertEquals(1, logs.size());
    }

    @Test public void shaDeduplicationAndProbeLimitsDoNotSuppressTheOtherPhase() {
        AtomicLong time = new AtomicLong(); AtomicInteger count = new AtomicInteger(); List<String> logs = new ArrayList<>();
        DebugCommentSnapshot snapshot = snapshot(logs, time::get, value -> { count.incrementAndGet(); return "{\"same\":true}"; });
        Comment comment = new Comment("stable");
        snapshot.capture("row", "before", comment, List.of(), null);
        snapshot.capture("row", "before", comment, List.of(), null);
        assertEquals(1, count.get());
        time.set(600_000_000L);
        snapshot.capture("row", "before", comment, List.of(), null);
        assertEquals(2, count.get()); assertEquals(1, logs.size());
        snapshot.capture("row", "after", comment, List.of(), null);
        assertEquals(3, count.get()); assertEquals(2, logs.size());
        time.set(5_000_000_000L);
        snapshot.capture("row", "before", comment, List.of(), null);
        assertEquals(2, logs.stream().filter(line -> line.contains(" json=")).count());
        assertTrue(logs.stream().anyMatch(line -> line.contains("dropped=2")));
    }

    @Test public void publicationCallbacksHaveAnIndependentQuotaAndFullSnapshot() {
        List<String> logs = new ArrayList<>(); AtomicInteger count = new AtomicInteger();
        DebugCommentSnapshot snapshot = snapshot(logs, () -> 0, value -> {
            count.incrementAndGet(); return "{\"comment_audio\":{\"vid\":\"fixture\"}}";
        });
        for (int i = 0; i < 30; i++) snapshot.capture("ui." + i, "after", new Comment("ui"), List.of(), null);
        assertEquals(8, logs.size());
        snapshot.capture("DouAi.AutoComment.auto_comment_publish_success", "after", null,
                List.of(new Response(new Comment("published"))), null);
        snapshot.capture("DouAi.CommentReply.auto_comment_publish_failure", "after", null,
                List.of(new Row(new Comment("failed"))), null);
        assertEquals(10, logs.size());
        assertTrue(logs.get(8).contains("publish_success")); assertTrue(logs.get(9).contains("publish_failure"));
        assertTrue(logs.get(8).endsWith("{\"comment_audio\":{\"vid\":\"fixture\"}}"));
        assertEquals("Twenty UI probes plus independently admitted callbacks", 22, count.get());
    }

    @Test public void serializerAndSinkFailuresRemainDiagnosticAndExposeNoExceptionMessage() {
        List<String> logs = new ArrayList<>();
        DebugCommentSnapshot snapshot = snapshot(logs, () -> 0,
                value -> { throw new InvocationTargetException(new IllegalStateException("PRIVATE ERROR TOKEN")); });
        snapshot.capture("failed", "after", new Comment("text"), List.of(), null);
        assertEquals(1, logs.size()); assertTrue(logs.get(0).contains("unavailable=java.lang.IllegalStateException"));
        assertFalse(logs.get(0).contains("PRIVATE ERROR TOKEN"));
        new DebugCommentSnapshot(() -> true, line -> { throw new AssertionError("sink failed"); }, () -> 0,
                value -> "{}").capture("sink", "before", new Comment("text"), List.of(), null);
    }

    @Test public void oversizedSnapshotsAreReportedRatherThanSilentlyTruncatedJson() {
        List<String> logs = new ArrayList<>();
        snapshot(logs, () -> 0, value -> "x".repeat(256 * 1024 + 1))
                .capture("large", "before", new Comment("text"), List.of(), null);
        assertEquals(1, logs.size()); assertTrue(logs.get(0).contains("unavailable=oversize chars=262145"));
        assertFalse(logs.get(0).contains(" json="));
    }

    @Test public void resetAllowsTheIdenticalCommentJsonToBeRecordedAgainImmediately() {
        List<String> logs = new ArrayList<>(); Comment comment = new Comment("same");
        DebugCommentSnapshot snapshot = snapshot(logs, () -> 0, value -> "{\"same\":true}");
        snapshot.capture("row", "before", comment, List.of(), null);
        snapshot.capture("row", "before", comment, List.of(), null);
        assertEquals(1, logs.size());
        snapshot.reset();
        snapshot.capture("row", "before", comment, List.of(), null);
        assertEquals(2, logs.size()); assertEquals(logs.get(0), logs.get(1));
    }

    @Test public void aConcurrentResetCannotRepopulateTheNewDedupCacheWithAnOlderCapture() throws Exception {
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); Comment comment = new Comment("same");
        DebugCommentSnapshot snapshot = snapshot(logs, () -> 0, value -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await(1, TimeUnit.SECONDS); }
            return "{\"same\":true}";
        });
        Thread old = new Thread(() -> snapshot.capture("row", "before", comment, List.of(), null));
        old.start();
        try { assertTrue(entered.await(1, TimeUnit.SECONDS)); snapshot.reset(); }
        finally { release.countDown(); }
        old.join(1_000); assertFalse(old.isAlive()); assertTrue(logs.isEmpty());
        snapshot.capture("row", "before", comment, List.of(), null);
        assertEquals(1, logs.size()); assertEquals(2, calls.get());
    }

    private static DebugCommentSnapshot snapshot(List<String> logs, java.util.function.LongSupplier time,
                                                  DebugCommentSnapshot.Serializer serializer) {
        return new DebugCommentSnapshot(() -> true, logs::add, time, serializer);
    }
    private static class Response { private final Comment comment; Response(Comment value) { comment = value; } }
    private static final class Row { private final Comment a; Row(Comment value) { a = value; } }
    private static final class Untyped { private final Object comment; Untyped(Object value) { comment = value; } }
    private static final class Nested { private final Response comment; Nested(Response value) { comment = value; } }
    private static final class PublishModel { private final Comment p; PublishModel(Comment value) { p = value; } }
    private static final class CommentFailure extends Exception { private final Comment comment; CommentFailure(Comment value) { comment = value; } }
    private static final class Poison { @Override public String toString() { throw new AssertionError("DTO toString"); } }
}

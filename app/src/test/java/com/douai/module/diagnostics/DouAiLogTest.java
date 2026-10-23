package com.douai.module.diagnostics;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DouAiLogTest {
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private void capture() { DouAiLog.awaitIdle(3000); DebugLog.setEnabled(true); DouAiLog.setFrameworkSink((priority, json) -> lines.add(json)); }
    @After public void clean() { DouAiLog.awaitIdle(3000); DouAiLog.setFrameworkSink(null); DebugLog.setEnabled(false); }
    private List<JSONObject> events(String trace) throws Exception {
        assertTrue(DouAiLog.awaitIdle(5000));
        List<JSONObject> result = new ArrayList<>();
        synchronized (lines) { for (String line : lines) {
            JSONObject event = new JSONObject(line);
            if (trace.equals(event.optString("trace_id"))) result.add(event);
        } }
        return result;
    }
    @Test public void concurrentCallbacksProduceExactlyOneTerminalAndExplainLateCallbacks() throws Exception {
        capture(); DouAiLog.Operation op = DouAiLog.begin("test_race", "callback");
        CountDownLatch ready = new CountDownLatch(12), start = new CountDownLatch(1), done = new CountDownLatch(12);
        for (int i = 0; i < 12; i++) new Thread(() -> {
            ready.countDown(); try { start.await(); } catch (InterruptedException ignored) { }
            op.finish(DouAiLog.Outcome.SUCCESS, "callback_validated", "business_code=0;object_present=true"); done.countDown();
        }).start();
        assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS));
        long count = events(op.traceId()).stream().filter(e -> "operation_finished".equals(e.optString("event"))).count();
        assertEquals(1, count);
        assertTrue(lines.stream().anyMatch(s -> s.contains("duplicate_or_late_callback")));
    }
    @Test public void retryAndChildSpansStayOnOneTraceAndExecutorContextIsRestored() throws Exception {
        capture(); DouAiLog.Operation root = DouAiLog.begin("test_async", "selected");
        Thread thread = new Thread(root.wrap(() -> {
            assertSame(root, DouAiLog.current()); root.attempt(2);
            DouAiLog.Operation child = DouAiLog.begin("test_speech", "generate");
            assertEquals(root.traceId(), child.traceId()); child.stage("file_read", "bytes=32");
            child.finish(DouAiLog.Outcome.SUCCESS, "file_verified", "bytes=32;duration_ms=500");
            root.finish(DouAiLog.Outcome.UNCONFIRMED, "submitted", "native_result=unknown");
        })); thread.start(); thread.join(); assertNull(DouAiLog.current());
        List<JSONObject> result = events(root.traceId());
        saveExample("async-retry-unconfirmed", result);
        assertEquals(1, result.stream().filter(e -> "operation_finished".equals(e.optString("event"))).count());
        assertEquals(1, result.stream().filter(e -> "span_finished".equals(e.optString("event"))).count());
        assertTrue(result.stream().anyMatch(e -> "2".equals(e.optString("attempt"))));
    }
    @Test public void allSixOutcomesRemainDistinctEvenWithoutDebug() throws Exception {
        capture(); DebugLog.setEnabled(false);
        for (DouAiLog.Outcome outcome : DouAiLog.Outcome.values()) {
            DouAiLog.Operation op = DouAiLog.begin("test_outcomes", "trigger");
            op.finish(outcome, "test_reason", "observed=true");
            JSONObject end = events(op.traceId()).stream().filter(e -> "operation_finished".equals(e.optString("event"))).findFirst().orElseThrow();
            assertEquals(outcome.name().toLowerCase(java.util.Locale.ROOT), end.getString("outcome"));
            saveExample("outcome-" + outcome.name().toLowerCase(java.util.Locale.ROOT), events(op.traceId()));
        }
    }
    @Test public void secretsAndNewlinesCannotCreateAnotherPhysicalRecord() throws Exception {
        capture(); DouAiLog.Operation op = DouAiLog.begin("test_privacy", "user");
        op.stage("request", "api_key=synthetic-key Cookie=synthetic-cookie Authorization=Bearer synthetic-token\nforged event");
        op.error("network", new IllegalStateException("private message body; password=synthetic-password", new RuntimeException("Cookie=secret")));
        op.finish(DouAiLog.Outcome.FAILED, "network_error", "body_ref=" + DouAiLog.reference("private message body"));
        events(op.traceId()); String text = String.join("", lines);
        assertFalse(text.contains("synthetic-key")); assertFalse(text.contains("synthetic-cookie"));
        assertFalse(text.contains("synthetic-token")); assertFalse(text.contains("synthetic-password"));
        assertFalse(text.contains("private message body")); assertFalse(text.contains("\n"));
        assertTrue(text.contains("error_id")); assertTrue(text.contains("IllegalStateException"));
    }
    @Test public void queueFloodIsBoundedAndKeepsTerminalInPreferenceToNoise() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DouAiLog.setFrameworkSink((priority, line) -> {
            entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            lines.add(line);
        });
        DouAiLog.Operation op = DouAiLog.begin("test_flood", "flood"); assertTrue(entered.await(3, TimeUnit.SECONDS));
        try {
            for (int i = 0; i < 500; i++) DouAiLog.event("test_flood", "sample_" + i, "progress", "index=" + i);
            op.finish(DouAiLog.Outcome.SUCCESS, "verified", "count=500");
            JSONObject manifest = new JSONObject(DouAiLog.manifest());
            assertTrue(manifest.getInt("queue_size") <= manifest.getInt("queue_capacity"));
            assertTrue(manifest.getLong("lost_count") > 0);
        } finally { release.countDown(); }
        assertTrue(events(op.traceId()).stream().anyMatch(e -> "operation_finished".equals(e.optString("event"))));
    }
    @Test public void loggingSinkFailureDoesNotChangeBusinessResultOrThrow() {
        DouAiLog.setFrameworkSink((priority, line) -> { throw new IllegalStateException("sink unavailable"); });
        DouAiLog.Operation op = DouAiLog.begin("test_sink", "work");
        op.wrap(() -> { throw new IllegalArgumentException("business failure"); });
        try { op.wrap(() -> { throw new IllegalArgumentException("business failure"); }).run(); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("business failure", expected.getMessage()); }
        assertNull(DouAiLog.current()); op.finish(DouAiLog.Outcome.FAILED, "business_failure", "");
        assertTrue(DouAiLog.awaitIdle(3000)); assertTrue(DouAiLog.manifest().contains("sink_failures"));
    }
    @Test public void outstandingOperationsAreExplicitlyIncompleteInExport() throws Exception {
        capture(); DouAiLog.Operation op = DouAiLog.begin("test_pending", "queue"); op.stage("await_callback", "submitted=true");
        String snapshot = DouAiLog.manifest(); assertTrue(snapshot.contains(op.traceId()));
        assertTrue(snapshot.contains("incomplete_result_pending")); assertTrue(snapshot.contains("await_callback"));
        op.finish(DouAiLog.Outcome.TIMED_OUT, "local_wait_timeout", "remote_result=unknown");
        assertFalse(new JSONObject(DouAiLog.manifest()).getJSONArray("open_operations").toString().contains(op.traceId()));
    }
    @Test public void repeatedHighFrequencyObservationsAreCountedWithoutQueueGrowth() throws Exception {
        capture();
        long started = System.nanoTime();
        for (int i = 0; i < 10_000; i++) DouAiLog.event("test_progress", "eligibility", "paused", "foreground=false");
        assertTrue(DouAiLog.awaitIdle(3000));
        long count = lines.stream().filter(s -> s.contains("test_progress")).count(); assertEquals(1, count);
        JSONObject manifest = new JSONObject(DouAiLog.manifest()); assertTrue(manifest.getLong("suppressed_count") >= 9999);
        System.out.println("DouAiLog repeated_observations=10000 elapsed_ms=" + (System.nanoTime() - started) / 1_000_000L + " emitted=" + count);
    }
    @Test public void previousOpenJournalReportsInterruptionWithoutGuessingBusinessFailure() throws Exception {
        capture(); File dir = Files.createTempDirectory("douai-recovery").toFile();
        java.lang.reflect.Field journal = DouAiLog.class.getDeclaredField("journal"); journal.setAccessible(true); journal.set(null, null);
        java.lang.reflect.Field recovered = DouAiLog.class.getDeclaredField("recovered"); recovered.setAccessible(true); recovered.set(null, false);
        DouAiLog.environment("test", "1", "host", "recovery_test", 123);
        Files.writeString(new File(dir, "active-recovery_test.json").toPath(), "{\"open_operations\":[{\"trace_id\":\"previous-trace\",\"last_stage\":\"send\"}]}");
        DouAiLog.initialize(dir); assertTrue(DouAiLog.awaitIdle(5000));
        assertTrue(lines.toString(), lines.stream().anyMatch(s -> s.contains("previous_session_incomplete") && s.contains("previous-trace")));
        assertFalse(lines.stream().anyMatch(s -> s.contains("previous-trace") && s.contains("\"outcome\":\"failed\"")));
    }
    @Test public void fragmentedTransportCanBeReassembledAndCheckedWithoutMixingConcurrentEvents() throws Exception {
        capture(); DouAiLog.Operation op = DouAiLog.begin("test_framing", "export");
        op.stage("large_summary", "字段".repeat(500)); op.finish(DouAiLog.Outcome.UNCONFIRMED, "test", "");
        String original = events(op.traceId()).stream().filter(e -> "stage".equals(e.optString("event"))).findFirst().orElseThrow().toString();
        List<String> parts = new ArrayList<>(); DouAiLog.writeFramework((priority, part) -> parts.add(part), 4, original);
        assertTrue(parts.size() > 1); StringBuilder joined = new StringBuilder();
        String id = new JSONObject(original).getString("event_id");
        for (int i = 0; i < parts.size(); i++) {
            JSONObject frame = new JSONObject(parts.get(i));
            assertEquals(id, frame.getString("event_id")); assertEquals(op.traceId(), frame.getString("trace_id"));
            assertEquals(i, frame.getInt("part_index")); assertEquals(parts.size(), frame.getInt("part_count"));
            assertTrue(parts.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1200);
            joined.append(frame.getString("payload"));
        }
        assertEquals(original, joined.toString());
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
        assertEquals(hex.toString(), new JSONObject(parts.get(0)).getString("checksum_sha256"));
    }
    @Test public void clearFencesRecordsAlreadyWaitingInTheUpstreamQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DouAiLog.setFrameworkSink((priority, line) -> {
            entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            lines.add(line);
        });
        DouAiLog.event("test_clear", "first_in_sink", "test", ""); assertTrue(entered.await(3, TimeUnit.SECONDS));
        DouAiLog.event("test_clear", "must_not_reappear", "queued_before_clear", "");
        DouAiLog.clearPending(); release.countDown(); assertTrue(DouAiLog.awaitIdle(3000));
        assertFalse(lines.stream().anyMatch(s -> s.contains("must_not_reappear")));
        DouAiLog.event("test_clear", "after_clear", "test", ""); assertTrue(DouAiLog.awaitIdle(3000));
        assertTrue(lines.stream().anyMatch(s -> s.contains("after_clear")));
    }
    @Test public void equalObservationsFromDifferentTracesAreNeverCoalesced() throws Exception {
        capture(); DouAiLog.Operation first = DouAiLog.begin("test_isolation", "first");
        DouAiLog.Operation second = DouAiLog.begin("test_isolation", "second");
        first.wrap(() -> DouAiLog.event("test_isolation", "http_response", "received", "status=200")).run();
        second.wrap(() -> DouAiLog.event("test_isolation", "http_response", "received", "status=200")).run();
        first.finish(DouAiLog.Outcome.SUCCESS, "verified", ""); second.finish(DouAiLog.Outcome.SUCCESS, "verified", "");
        assertTrue(events(first.traceId()).stream().anyMatch(e -> "http_response".equals(e.optString("event"))));
        assertTrue(events(second.traceId()).stream().anyMatch(e -> "http_response".equals(e.optString("event"))));
    }
    @Test public void unavailableJournalDirectoryIsVisibleWithoutChangingBusiness() throws Exception {
        capture(); File notDirectory = File.createTempFile("douai-journal", ".file");
        java.lang.reflect.Field journal = DouAiLog.class.getDeclaredField("journal"); journal.setAccessible(true); journal.set(null, null);
        DouAiLog.initialize(notDirectory);
        DouAiLog.Operation op = DouAiLog.begin("test_disk_failure", "work"); op.finish(DouAiLog.Outcome.SUCCESS, "business_verified", "");
        events(op.traceId()); assertTrue(new JSONObject(DouAiLog.manifest()).getLong("journal_failures") > 0);
    }
    @Test public void simpleModeKeepsTheQuietActiveStageInThePrivateRecoveryJournal() throws Exception {
        capture(); DebugLog.setEnabled(false); File dir = Files.createTempDirectory("douai-simple-journal").toFile();
        java.lang.reflect.Field journal = DouAiLog.class.getDeclaredField("journal"); journal.setAccessible(true);
        Object old = journal.get(null); journal.set(null, null);
        DouAiLog.Operation operation = null;
        try {
            DouAiLog.environment("test", "1", "host", "simple_journal", 123); DouAiLog.initialize(dir);
            operation = DouAiLog.begin("simple_journal", "async"); assertTrue(DouAiLog.awaitIdle(5000));
            operation.stage("waiting_remote_receipt", "request_submitted=true");
            File file = new File(dir, "active-simple_journal.json"); boolean saved = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!saved && System.nanoTime() < deadline) {
                if (file.isFile()) saved = Files.readString(file.toPath()).contains("waiting_remote_receipt");
                if (!saved) Thread.sleep(20);
            }
            assertTrue("quiet stage must remain recoverable without detailed output", saved);
            assertFalse(lines.stream().anyMatch(line -> line.contains("\"event\":\"stage\"")));
        } finally {
            if (operation != null) operation.finish(DouAiLog.Outcome.UNCONFIRMED, "fixture_done", "");
            DouAiLog.awaitIdle(5000); journal.set(null, old);
        }
    }
    @Test public void legacyEnglishBodiesAndExceptionMessagesAreNeverPersisted() throws Exception {
        capture(); DouAiLog.legacy(5, "privacy_fixture", "PRIVATE_CHAT_CONTENT_SENTINEL", new IllegalStateException("PRIVATE_AI_BODY_SENTINEL"));
        assertTrue(DouAiLog.awaitIdle(3000)); String text = String.join("", lines);
        assertFalse(text.contains("PRIVATE_CHAT_CONTENT_SENTINEL")); assertFalse(text.contains("PRIVATE_AI_BODY_SENTINEL"));
        assertTrue(text.contains("legacy_diagnostic"));
    }
    private static void saveExample(String name, List<JSONObject> events) throws Exception {
        java.nio.file.Path directory = new File("build/reports/logging-examples").toPath();
        Files.createDirectories(directory);
        StringBuilder text = new StringBuilder(); for (JSONObject event : events) text.append(event).append('\n');
        Files.write(directory.resolve(name + ".jsonl"), text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void journalRetentionHasATotalProcessFileLimitAndReportsEvictions() throws Exception {
        capture(); File dir = Files.createTempDirectory("douai-journal-retention").toFile();
        for (int i = 0; i < 10; i++) Files.write(new File(dir, "active-old-" + i + ".json").toPath(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.lang.reflect.Field journal = DouAiLog.class.getDeclaredField("journal"); journal.setAccessible(true); journal.set(null, null);
        DouAiLog.environment("test", "1", "host", "retention", 456); DouAiLog.initialize(dir);
        DouAiLog.Operation op = DouAiLog.begin("retention_test", "work"); op.finish(DouAiLog.Outcome.SUCCESS, "verified", "");
        events(op.traceId()); File[] journals = dir.listFiles((directory, name) -> name.startsWith("active-") && name.endsWith(".json"));
        assertNotNull(journals); assertTrue(journals.length <= 8);
        assertTrue(new JSONObject(DouAiLog.manifest()).getLong("journal_files_evicted") >= 3);
        for (File file : journals) assertTrue(file.length() <= 524288);
    }
}

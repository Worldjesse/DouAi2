package com.douai.module.diagnostics;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadPoolExecutor;
import org.json.JSONObject;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DebugLogStoreTest {
    @Test public void aDeliveryBatchUsesOneOutputStreamAndNoSecondaryQueue() throws Exception {
        File directory = Files.createTempDirectory("douai-structured-batch").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            store.beginStructuredBatch();
            try {
                for (int seq = 1; seq <= 16; seq++) store.recordStructured(4, event(seq, "stage", "batch-trace"));
            } finally { store.endStructuredBatch(); }
            JSONObject integrity = new JSONObject(store.integrity());
            System.out.println("Structured log 16-record batch: records=" + integrity.getLong("structured_records")
                    + ", batches=" + integrity.getLong("structured_batches")
                    + ", outputOpens=" + integrity.getLong("structured_output_opens"));
            assertEquals(16, integrity.getLong("structured_records"));
            assertEquals(1, integrity.getLong("structured_batches"));
            assertEquals(1, integrity.getLong("structured_output_opens"));
            assertEquals(0, integrity.getLong("io_failures"));
            String saved = read(store, true);
            for (int seq = 1; seq <= 16; seq++) assertTrue(saved.contains(event(seq, "stage", "batch-trace")));
        }
    }

    @Test public void aFailedBatchAccountsEveryAffectedRecordIndividually() throws Exception {
        File unavailable = Files.createTempFile("douai-structured-batch-failure", ".not-directory").toFile();
        try (DebugLogStore store = new DebugLogStore(unavailable)) {
            store.beginStructuredBatch();
            try {
                for (int seq = 1; seq <= 4; seq++) store.recordStructured(4, event(seq, "stage", "batch-io-trace"));
            } finally { store.endStructuredBatch(); }
            JSONObject integrity = new JSONObject(store.integrity());
            assertEquals(4, integrity.getLong("io_failures"));
            assertEquals(4, integrity.getLong("lost_count"));
            assertTrue(integrity.getJSONObject("affected_records").toString().contains("batch-io-trace"));
            for (int seq = 1; seq <= 4; seq++) assertTrue(store.memoryTail(DebugLogStore.TAIL_BYTES)
                    .contains(event(seq, "stage", "batch-io-trace")));
        }
    }

    @Test public void clearingAnOpenBatchCannotReintroduceItsOlderGeneration() throws Exception {
        File directory = Files.createTempDirectory("douai-structured-batch-clear").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            store.beginStructuredBatch();
            store.recordStructured(4, event(1, "stage", "old-buffered-trace"));
            CountDownLatch cleared = new CountDownLatch(1);
            store.clear(ok -> { assertTrue(ok); cleared.countDown(); });
            assertTrue(cleared.await(5, TimeUnit.SECONDS));
            store.endStructuredBatch();
            store.recordStructured(4, event(2, "operation_finished", "new-after-clear-trace"));
            String saved = read(store, true);
            assertFalse(saved.contains("old-buffered-trace"));
            assertTrue(saved.contains("new-after-clear-trace"));
            assertTrue(store.integrity().contains("clear_or_close_generation"));
            assertEquals(0, new JSONObject(store.integrity()).getLong("io_failures"));
        }
    }

    @Test public void twoStoresKeepTheirBatchesSeparateAndCrossRotationsUnderTheSharedLock() throws Exception {
        File directory = Files.createTempDirectory("douai-structured-two-batches").toFile();
        try (DebugLogStore first = new DebugLogStore(directory); DebugLogStore second = new DebugLogStore(directory)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread one = new Thread(() -> appendBatchFixture(first, "batch-one", failure));
            Thread two = new Thread(() -> appendBatchFixture(second, "batch-two", failure));
            one.start(); two.start(); one.join(10000); two.join(10000);
            assertFalse(one.isAlive()); assertFalse(two.isAlive()); assertNull(failure.get());
            assertEquals(0, new JSONObject(first.integrity()).getLong("io_failures"));
            assertEquals(0, new JSONObject(second.integrity()).getLong("io_failures"));
            String saved = read(first, true);
            assertTrue(saved.contains("batch-one")); assertTrue(saved.contains("batch-two"));
            assertTrue(new File(directory, "debug.log").length() <= DebugLogStore.FILE_BYTES);
            assertTrue(new File(directory, "debug.1.log").length() <= DebugLogStore.FILE_BYTES);
            for (String line : saved.split("\n")) {
                int marker = line.indexOf("/DouAi.Event [basic] ");
                if (marker >= 0) new JSONObject(line.substring(marker + "/DouAi.Event [basic] ".length()));
            }
        }
    }

    private static void appendBatchFixture(DebugLogStore store, String trace, AtomicReference<Throwable> failure) {
        try {
            store.beginStructuredBatch();
            try {
                for (int seq = 1; seq <= 40; seq++) {
                    String json = event(seq, "operation_finished", trace);
                    store.recordStructured(4, json.substring(0, json.length() - 1) + ",\"evidence\":\"" + "x".repeat(15000) + "\"}");
                }
            } finally { store.endStructuredBatch(); }
        } catch (Throwable error) { failure.compareAndSet(null, error); }
    }

    @Test public void structuredFloodBypassesTheSaturatedLegacyQueueAndKeepsAllBusinessStages() throws Exception {
        File directory = Files.createTempDirectory("douai-structured-flood").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            CountDownLatch release = blockLegacyWorker(store);
            try {
                for (int i = 0; i < 600; i++) store.record(true, 3, "detail", "legacy-flood-" + i, null);
                // This is the same direct call made by the upstream evidence worker; there is no second queue.
                for (int trace = 0; trace < 300; trace++) {
                    store.recordStructured(4, event(trace * 3 + 1, "operation_started", "trace-" + trace));
                    store.recordStructured(4, event(trace * 3 + 2, "stage", "trace-" + trace));
                    store.recordStructured(4, event(trace * 3 + 3, "operation_finished", "trace-" + trace));
                }
            } finally { release.countDown(); }
            String snapshot = read(store, true);
            for (int trace = 0; trace < 300; trace++) {
                assertTrue("start " + trace, snapshot.contains(event(trace * 3 + 1, "operation_started", "trace-" + trace)));
                assertTrue("stage " + trace, snapshot.contains(event(trace * 3 + 2, "stage", "trace-" + trace)));
                assertTrue("terminal " + trace, snapshot.contains(event(trace * 3 + 3, "operation_finished", "trace-" + trace)));
            }
            JSONObject integrity = new JSONObject(store.integrity());
            assertTrue("legacy saturation is accounted", integrity.getLong("lost_count") > 0);
            assertEquals("evidence_worker_direct_no_secondary_queue", integrity.getString("structured_delivery"));
            assertFalse("no structured record was rejected by the legacy queue", integrity.getJSONObject("affected_records").toString().contains("trace-"));
            assertTrue(new File(directory, "debug.log").length() <= DebugLogStore.TOTAL_BYTES);
        }
    }

    @Test public void ioFailureIsExplicitAndIdentifiesTheMemoryOnlyBusinessEvidence() throws Exception {
        File unavailableDirectory = Files.createTempFile("douai-log-io-failure", ".not-directory").toFile();
        try (DebugLogStore store = new DebugLogStore(unavailableDirectory)) {
            String json = event(42, "operation_finished", "io-trace");
            store.recordStructured(4, json);
            String snapshot = read(store, true);
            assertTrue(snapshot.contains(json));
            assertTrue(snapshot.contains("memory_fallback")); assertTrue(snapshot.contains("disk_read_failure"));
            JSONObject integrity = new JSONObject(store.integrity());
            assertEquals(1, integrity.getLong("io_failures"));
            JSONObject affected = integrity.getJSONObject("affected_records").getJSONArray("affected").getJSONObject(0);
            assertEquals("disk_write_failure_memory_only", affected.getString("reason"));
            assertEquals("session-fixture", affected.getString("session_id"));
            assertEquals("io-trace", affected.getString("trace_id")); assertEquals(42, affected.getLong("first_seq"));
            assertEquals("operation_finished", affected.getString("event"));
        }
    }

    @Test public void clearSeparatesGenerationsIncludingStartupReplayAndLaterDirectDelivery() throws Exception {
        File directory = Files.createTempDirectory("douai-clear-generation").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            store.recordStructured(4, event(1, "operation_started", "old-trace"));
            CountDownLatch release = blockLegacyWorker(store);
            store.replayStartup(java.util.Collections.singletonList(new String[]{"4", "DouAi.Event", event(2, "stage", "old-startup"), "structured"}));
            CountDownLatch cleared = new CountDownLatch(1);
            store.clear(ok -> { assertTrue(ok); cleared.countDown(); });
            Thread evidence = new Thread(() -> store.recordStructured(4, event(3, "operation_finished", "new-trace")));
            evidence.start(); release.countDown();
            assertTrue(cleared.await(5, TimeUnit.SECONDS)); evidence.join(5000); assertFalse(evidence.isAlive());
            String snapshot = read(store, true);
            assertFalse(snapshot.contains("old-trace")); assertFalse(snapshot.contains("old-startup"));
            assertTrue(snapshot.contains("new-trace"));
            String integrity = store.integrity(); assertTrue(integrity.contains("user_clear_disk"));
            assertTrue(integrity.contains("old-trace")); assertTrue(integrity.contains("user_clear_startup"));
        }
    }

    @Test public void structuredJsonIsNeverCutAndUtf8TailStartsAtACompleteRecord() throws Exception {
        File directory = Files.createTempDirectory("douai-structured-utf8").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            String large = "{\"session_id\":\"s\",\"seq\":\"1\",\"event\":\"stage\",\"evidence\":\"" + "中😀".repeat(25_000) + "\"}";
            store.recordStructured(4, large);
            String snapshot = read(store, true);
            assertTrue(snapshot.contains(large)); assertFalse(snapshot.contains("\uFFFD"));
            assertFalse(store.memoryTail(DebugLogStore.TAIL_BYTES).contains("\"evidence\":"));
            assertTrue(store.integrity().contains("memory_entry_capacity"));
            String newest = event(2, "operation_finished", "utf8-trace");
            String tail = DebugLogStore.utf8Tail(large + "\n" + newest + "\n", 800);
            assertTrue(tail.contains(newest)); assertFalse(tail.contains("\uFFFD"));
            assertTrue(tail.startsWith("[tail truncated]\n{"));
        }
    }

    @Test public void rotationReportsDiscardedStructuredIdentitiesAndKeepsDiskBounded() throws Exception {
        File directory = Files.createTempDirectory("douai-structured-retention").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            for (int seq = 1; seq <= 36; seq++) {
                String record = event(seq, "operation_finished", "rotation-trace-" + seq);
                store.recordStructured(4, record.substring(0, record.length() - 1) + ",\"evidence\":\"" + "x".repeat(100_000) + "\"}");
            }
            assertTrue(new File(directory, "debug.log").length() + new File(directory, "debug.1.log").length() <= DebugLogStore.TOTAL_BYTES);
            String integrity = store.integrity(); assertTrue(integrity.contains("retention_rotation")); assertTrue(integrity.contains("rotation-trace-1"));
            assertFalse(integrity.contains("xxxx"));
            assertTrue(read(store, true).contains("rotation-trace-36"));
        }
    }

    @Test public void separateStoresShareAFileLockWithoutOverlappingLockFailures() throws Exception {
        File directory = Files.createTempDirectory("douai-shared-store").toFile();
        try (DebugLogStore first = new DebugLogStore(directory); DebugLogStore second = new DebugLogStore(directory)) {
            Thread one = new Thread(() -> { for (int i = 1; i <= 75; i++) first.recordStructured(4, event(i, "stage", "writer-one")); });
            Thread two = new Thread(() -> { for (int i = 76; i <= 150; i++) second.recordStructured(4, event(i, "operation_finished", "writer-two")); });
            one.start(); two.start(); one.join(10000); two.join(10000);
            assertFalse(one.isAlive()); assertFalse(two.isAlive());
            assertEquals(0, new JSONObject(first.integrity()).getLong("io_failures"));
            assertEquals(0, new JSONObject(second.integrity()).getLong("io_failures"));
            String snapshot = read(first, true);
            for (int seq = 1; seq <= 150; seq++) assertTrue(snapshot.contains("\"seq\":\"" + seq + "\""));
        }
    }

    private static String event(int seq, String event, String trace) {
        return "{\"session_id\":\"session-fixture\",\"seq\":\"" + seq + "\",\"feature_id\":\"download\",\"event\":\"" + event + "\",\"trace_id\":\"" + trace + "\"}";
    }

    private static CountDownLatch blockLegacyWorker(DebugLogStore store) throws Exception {
        java.lang.reflect.Field field = DebugLogStore.class.getDeclaredField("worker"); field.setAccessible(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ((ThreadPoolExecutor) field.get(store)).execute(() -> {
            entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS)); return release;
    }

    @Test public void writingIsAsyncBoundedRotatedAndRedactedBeforeDiskOrMemory() throws Exception {
        File directory = Files.createTempDirectory("douai-debug-store").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            for (int batch = 0; batch < 12; batch++) {
                store.record(false, 4, "test", "BATCH" + batch + " " + "x".repeat(200_000) + " api_key=must-not-persist", null);
                store.record(true, 3, "test", "SECOND" + batch + " " + "y".repeat(200_000), null);
                read(store, true);
                long bytes = new File(directory, "debug.log").length() + new File(directory, "debug.1.log").length();
                assertTrue("bounded disk bytes=" + bytes, bytes <= DebugLogStore.TOTAL_BYTES);
            }
            String snapshot = read(store, true);
            assertTrue(snapshot.contains("BATCH11"));
            assertFalse(snapshot.contains("BATCH0 "));
            assertFalse(snapshot.contains("must-not-persist"));
            assertFalse(store.memoryTail(DebugLogStore.TAIL_BYTES).contains("must-not-persist"));
            assertTrue(new File(directory, "debug.1.log").isFile());
            assertTrue(snapshot.getBytes(StandardCharsets.UTF_8).length <= DebugLogStore.TOTAL_BYTES);
        }
    }

    @Test public void clearRemovesPendingHistoryAndNewRecordsStillWork() throws Exception {
        File directory = Files.createTempDirectory("douai-debug-clear").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            store.record(false, 4, "before", "old history", null);
            CountDownLatch cleared = new CountDownLatch(1);
            AtomicReference<Boolean> result = new AtomicReference<>();
            store.clear(ok -> { result.set(ok); cleared.countDown(); });
            assertTrue(cleared.await(5, TimeUnit.SECONDS)); assertEquals(Boolean.TRUE, result.get());
            store.record(false, 4, "after", "new history", null);
            String text = read(store, true);
            assertFalse(text.contains("old history")); assertTrue(text.contains("new history"));
        }
    }

    @Test public void utf8EntryTruncationAndThrowableDoNotLeakSecrets() throws Exception {
        File directory = Files.createTempDirectory("douai-debug-utf8").toFile();
        try (DebugLogStore store = new DebugLogStore(directory)) {
            store.record(true, 6, "test", "中文".repeat(80_000), new IllegalStateException("access_token=exception-secret"));
            String text = read(store, true);
            assertTrue(text.contains("[record truncated]")); assertFalse(text.contains("exception-secret"));
            assertTrue(new File(directory, "debug.log").length() <= DebugLogStore.ENTRY_BYTES);
        }
    }

    private static String read(DebugLogStore store, boolean full) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        store.read(DebugLogStore.TOTAL_BYTES, full, value -> { result.set(value); done.countDown(); });
        assertTrue("asynchronous log callback", done.await(10, TimeUnit.SECONDS)); return result.get();
    }
}

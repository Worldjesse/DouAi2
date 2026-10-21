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

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DebugLogStoreTest {
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

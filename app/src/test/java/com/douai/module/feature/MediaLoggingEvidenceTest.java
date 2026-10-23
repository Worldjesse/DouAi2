package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.DebugLog;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Exercises real storage completion and the absence of save evidence, rather than logger plumbing. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class MediaLoggingEvidenceTest {
    private final List<String> records = new CopyOnWriteArrayList<>();
    private DownloadTransfer.Connections previous;

    @Before public void prepare() throws Exception {
        DownloadTaskTestIdle.beforeTest();
        previous = MediaStoreDownloader.connections;
        flush();
        DouAiLog.setFrameworkSink((level, json) -> records.add(json));
    }
    @After public void close() throws Exception {
        DownloadTaskTestIdle.afterTest();
        MediaStoreDownloader.connections = previous;
        flush(); DouAiLog.setFrameworkSink(null);
    }

    @Test public void acceptedWorkWithoutCommittedOutputRemainsUnconfirmedAndReleaseIsIdempotent() throws Exception {
        DownloadTasks.Task task = task("media\nprivate-work");
        task.run(transfer -> "submitted to a host API"); task.released(); task.released();
        flush();
        JSONObject terminal = terminal(task);
        assertEquals("unconfirmed", terminal.getString("outcome"));
        assertEquals("save_evidence_missing", terminal.getString("reason_code"));
        assertEquals(1, finished(task.operation.traceId()));
        assertFalse(String.join("\n", records).contains("private-work"));
        assertEquals("completed", task.status); // Existing business/UI behavior is unchanged.
    }

    @Test public void closedDocumentAndExactByteCountProduceSuccessInTheSameTrace() throws Exception {
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        try {
            DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
            MediaStoreDownloader.connections = uri -> response(200, "fixture-bytes");
            DownloadTasks.Task task = task("media\nprivate-file-name");
            task.run(transfer -> {
                transfer.file("private-file-name.mp4", 1);
                MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan(), DownloadDocumentTreeTest.TREE, transfer);
                return "saved";
            });
            task.released(); flush();
            JSONObject result = terminal(task);
            assertEquals("success", result.getString("outcome"));
            assertEquals(2, provider.files.size());
            assertTrue(provider.files.values().stream().anyMatch(file -> {
                try { return "fixture-bytes".equals(Files.readString(file.toPath())); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }));
            assertTrue(records.stream().anyMatch(line -> line.contains("output_committed")
                    && line.contains(task.operation.traceId()) && line.contains("read_back_verified=false")));
            String text = String.join("\n", records);
            assertFalse(text.contains("credential-secret")); assertFalse(text.contains("private-file-name"));
            assertFalse(text.contains("private-caption"));
        } finally { DouAiLog.awaitIdle(5000); DebugLog.setEnabled(previousDebug); }
    }

    @Test public void httpSuccessBeforeAnOutputFailureNeverCreatesSavedEvidence() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        MediaStoreDownloader.connections = uri -> response(200, "fixture-bytes");
        MediaStoreDownloader.OutputStreams output = MediaStoreDownloader.outputs;
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        try {
            MediaStoreDownloader.outputs = (resolver, target) -> { throw new java.io.IOException("credential-secret"); };
            DownloadTasks.Task task = task("third-party\nselected\nprivate-file-name");
            task.run(transfer -> {
                MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan(), DownloadDocumentTreeTest.TREE, transfer);
                return "must not complete";
            });
            task.released(); flush();
            assertEquals("failed", terminal(task).getString("outcome"));
            assertEquals(0, task.transfer.savedEvidenceCount);
            assertTrue(provider.names.isEmpty());
            assertTrue(records.stream().anyMatch(line -> line.contains("http_response") && line.contains("transport_only")));
            assertFalse(String.join("\n", records).contains("credential-secret"));
        } finally {
            MediaStoreDownloader.outputs = output;
            DouAiLog.awaitIdle(5000); DebugLog.setEnabled(previousDebug);
        }
    }

    @Test public void cancellationAndTimeoutRemainDifferentTerminalStates() throws Exception {
        DownloadTasks.Task cancelled = task("media\ncancelled");
        cancelled.transfer.requestCancel(false); cancelled.run(transfer -> "must not run"); cancelled.released();
        DownloadTasks.Task timedOut = task("media\ntimedout");
        timedOut.transfer.requestCancel(true); timedOut.run(transfer -> "must not run"); timedOut.released();
        flush();
        assertEquals("cancelled", terminal(cancelled).getString("outcome"));
        assertEquals("timed_out", terminal(timedOut).getString("outcome"));
    }

    private ThirdPartyDownloadPlan plan() {
        return ThirdPartyDownloadPlan.create(ThirdPartyResponse.parse("{\"data\":{\"id\":\"private-file-name\",\"text\":\"private-caption\",\"video\":[\"https://media.test/file?token=credential-secret\"]}}"),
                new com.douai.module.config.ThirdPartyDownloadSettings(new com.douai.module.config.LocalOptions(java.util.Collections.emptyMap())), 100);
    }
    private DownloadTasks.Task task(String key) {
        return new DownloadTasks.Task(RuntimeEnvironment.getApplication(), key, "private-file-name", null, "failed");
    }
    private JSONObject terminal(DownloadTasks.Task task) throws Exception {
        for (String record : records) {
            JSONObject json = new JSONObject(record);
            if (task.operation.traceId().equals(json.optString("trace_id")) && "media_download".equals(json.optString("feature_id"))
                    && json.has("outcome") && !json.optString("outcome").isEmpty()) return json;
            if (task.operation.traceId().equals(json.optString("trace_id")) && "third_party_download".equals(json.optString("feature_id"))
                    && json.has("outcome") && !json.optString("outcome").isEmpty()) return json;
        }
        throw new AssertionError("Missing terminal evidence: " + records);
    }
    private long finished(String trace) {
        return records.stream().filter(line -> line.contains(trace) && line.contains("operation_finished")).count();
    }
    private static void flush() throws Exception {
        CountDownLatch latch = new CountDownLatch(1); DouAiLog.flush(latch::countDown);
        assertTrue("evidence writer drained", latch.await(5, TimeUnit.SECONDS));
    }
    private static HttpURLConnection response(int status, String body) throws Exception {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new HttpURLConnection(new URL("https://media.test/file")) {
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
            @Override public int getResponseCode() { return status; }
            @Override public String getContentType() { return "video/mp4"; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        };
    }
}

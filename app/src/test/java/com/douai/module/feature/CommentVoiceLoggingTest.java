package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class CommentVoiceLoggingTest {
    @Test public void uploadAcknowledgmentCannotClaimThatTheCommentWasPublished() throws Exception {
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            DouAiLog.Operation operation = DouAiLog.begin("comment_voice_replace", "fixture_upload");
            CommentVoiceHook.UploadTrace trace = new CommentVoiceHook.UploadTrace(operation);
            trace.callback(0, "private-upload-video-id", true);
            trace.timeout();
            flush();
            List<String> terminal = terminals(records, operation.traceId());
            assertEquals(1, terminal.size());
            assertTrue(terminal.get(0).contains("audio_uploaded_comment_publish_unobserved"));
            assertTrue(terminal.get(0).contains("\"outcome\":\"unconfirmed\""));
            assertFalse(records.stream().anyMatch(line -> line.contains("private-upload-video-id")));
        } finally { DouAiLog.setFrameworkSink(null); }
    }

    @Test public void missingVideoIdNeedsTimeoutAndLateUploadCannotChangeTheFinalResult() throws Exception {
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            DouAiLog.Operation operation = DouAiLog.begin("comment_voice_replace", "fixture_upload");
            CommentVoiceHook.UploadTrace trace = new CommentVoiceHook.UploadTrace(operation);
            trace.callback(0, "", true);
            flush();
            assertTrue(terminals(records, operation.traceId()).isEmpty());
            trace.timeout();
            trace.callback(0, "late-video", true);
            flush();
            List<String> terminal = terminals(records, operation.traceId());
            assertEquals(1, terminal.size());
            assertTrue(terminal.get(0).contains("upload_callback_timeout_or_publish_unobserved"));
            assertTrue(records.stream().anyMatch(line -> line.contains(operation.traceId()) && line.contains("late_callback")));
        } finally { DouAiLog.setFrameworkSink(null); }
    }

    @Test public void nonzeroNativeUploadErrorIsADefiniteUploadFailure() throws Exception {
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            DouAiLog.Operation operation = DouAiLog.begin("comment_voice_replace", "fixture_upload");
            new CommentVoiceHook.UploadTrace(operation).callback(7, "", true);
            flush();
            List<String> terminal = terminals(records, operation.traceId());
            assertEquals(1, terminal.size());
            assertTrue(terminal.get(0).contains("native_audio_upload_failed"));
            assertTrue(terminal.get(0).contains("\"outcome\":\"failed\""));
        } finally { DouAiLog.setFrameworkSink(null); }
    }

    private static List<String> terminals(List<String> records, String trace) {
        List<String> result = new ArrayList<>();
        synchronized (records) {
            for (String record : records) if (record.contains(trace) && record.contains("\"event\":\"operation_finished\"")) result.add(record);
        }
        return result;
    }

    private static void flush() throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        DouAiLog.flush(flushed::countDown);
        assertTrue(flushed.await(5, TimeUnit.SECONDS));
    }
}

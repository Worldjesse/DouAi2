package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ResponsesAnalysisStreamTest {
    @Test public void utf8CrLfMultilineDataAndCommentsAreDecodedWithoutDuplicatingCompletedText() throws Exception {
        String text = "旅行与摄影 😀";
        String stream = "\uFEFF: heartbeat\r\nid: ignored\r\nretry: 1\r\n\r\n"
                + "event: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\r\n"
                + "data: \"delta\":\"旅行与摄影 😀\"}\r\n\r\n" + completed(text);
        InputStream oneByteChunks = new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(1, length));
            }
        };
        List<String> diagnostics = new ArrayList<>();
        assertEquals(text, ResponsesAnalysisStream.read(oneByteChunks, 4032, () -> { }, diagnostics::add));
        assertTrue(String.join("\n", diagnostics).contains("stage=stream_first_text"));
        assertFalse(String.join("\n", diagnostics).contains(text));
    }

    @Test public void completedSnapshotIsAuthoritativeAndCanSucceedWithoutDeltas() throws Exception {
        assertEquals("final", read(delta("partial") + completed("final")));
        assertEquals("final", read(completed("final")));
        String noSnapshot = event(new JSONObject().put("type", "response.completed")
                .put("response", new JSONObject().put("status", "completed")));
        assertEquals("from delta", read(delta("from delta") + noSnapshot));
    }

    @Test public void reasoningToolsAndUnknownEventsNeverBecomeBodyOrDiagnostics() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        String stream = event(new JSONObject().put("type", "response.reasoning_text.delta").put("delta", "PRIVATE_REASONING"))
                + event(new JSONObject().put("type", "PRIVATE_UNKNOWN_EVENT").put("delta", "PRIVATE_UNKNOWN_VALUE"))
                + event(new JSONObject().put("type", "response.function_call_arguments.delta").put("delta", "PRIVATE_TOOL"))
                + delta("visible") + completed("visible");
        assertEquals("visible", ResponsesAnalysisStream.read(bytes(stream), 4032, () -> { }, diagnostics::add));
        assertFalse(String.join("\n", diagnostics).contains("PRIVATE_"));
    }

    @Test public void sizeableReasoningEventOverheadDoesNotConsumeTheVisibleOutputBudget() throws Exception {
        String reasoning = event(new JSONObject().put("type", "response.reasoning_summary_text.delta")
                .put("delta", "synthetic ignored increment"));
        String stream = reasoning.repeat(20_000) + delta("final") + completed("final");
        assertTrue(stream.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024);
        assertTrue(stream.getBytes(StandardCharsets.UTF_8).length < ResponsesAnalysisStream.MAX_BYTES);
        assertEquals("final", read(stream));
    }

    @Test public void eofDoneFailedIncompleteAndErrorCannotCommitPartialText() throws Exception {
        String[] ends = {"", "data: [DONE]\n\n",
                event(new JSONObject().put("type", "response.failed").put("error", "PRIVATE_SERVER_ERROR")),
                event(new JSONObject().put("type", "response.incomplete")),
                event(new JSONObject().put("type", "error").put("message", "PRIVATE_SERVER_ERROR")),
                event(new JSONObject().put("type", "response.completed").put("response", new JSONObject().put("status", "incomplete")))};
        for (String end : ends) assertFailure(delta("partial") + end, ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }

    @Test public void byteLineEventFrameAndOutputBudgetsAreIndependent() throws Exception {
        assertFailure(":" + "x".repeat(ResponsesAnalysisStream.MAX_LINE_CHARS) + "\n", ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(("data: " + "x".repeat(50_000) + "\n").repeat(12), ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure((":" + "x".repeat(60_000) + "\n").repeat(ResponsesAnalysisStream.MAX_BYTES / 60_002 + 1),
                ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure("data: {}\n\n".repeat(ResponsesAnalysisStream.MAX_FRAMES + 1), ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(delta("x".repeat(4033)) + completed("ok"), ResponsesAnalysisStream.Failure.Kind.OVERLONG);
    }

    @Test public void malformedFramesAndEmptyCompletedResponsesHaveSafeKinds() throws Exception {
        assertFailure("data: PRIVATE_NOT_JSON\n\n", ResponsesAnalysisStream.Failure.Kind.FORMAT);
        assertFailure(event(new JSONObject().put("type", "response.output_text.delta").put("delta", 42)),
                ResponsesAnalysisStream.Failure.Kind.FORMAT);
        assertFailure(completed(" "), ResponsesAnalysisStream.Failure.Kind.EMPTY);
    }

    @Test public void cancellationAndDeadlineChecksEscapeParsingWithoutBeingReclassified() throws Exception {
        for (int index = 1; index <= 6; index++) {
            final int stop = index;
            AtomicInteger checks = new AtomicInteger();
            try {
                ResponsesAnalysisStream.read(bytes(completed("final")), 4032, () -> {
                    if (checks.incrementAndGet() == stop) throw new CancellationException("cancelled");
                }, ignored -> { });
                fail("cancel check must be observed: " + stop);
            } catch (CancellationException expected) { assertEquals("cancelled", expected.getMessage()); }
        }
        AtomicInteger checks = new AtomicInteger();
        try {
            ResponsesAnalysisStream.read(bytes(completed("final")), 4032, () -> {
                if (checks.incrementAndGet() == 6) throw new SocketTimeoutException("deadline");
            }, ignored -> { });
            fail("the absolute deadline must not become a format failure");
        } catch (SocketTimeoutException expected) { assertEquals("deadline", expected.getMessage()); }
    }

    private static String read(String stream) throws Exception {
        return ResponsesAnalysisStream.read(bytes(stream), 4032, () -> { }, ignored -> { });
    }
    private static InputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private static String delta(String value) throws Exception {
        return event(new JSONObject().put("type", "response.output_text.delta").put("delta", value));
    }
    private static String completed(String value) throws Exception {
        JSONObject block = new JSONObject().put("type", "output_text").put("text", value);
        JSONObject message = new JSONObject().put("type", "message").put("content", new JSONArray().put(block));
        JSONObject response = new JSONObject().put("status", "completed").put("output", new JSONArray().put(message));
        return event(new JSONObject().put("type", "response.completed").put("response", response));
    }
    private static String event(JSONObject value) { return "data: " + value + "\n\n"; }
    private static void assertFailure(String stream, ResponsesAnalysisStream.Failure.Kind kind) throws Exception {
        try { read(stream); fail("stream must fail with " + kind); }
        catch (ResponsesAnalysisStream.Failure expected) {
            assertEquals(kind, expected.kind);
            assertNull(expected.getCause());
            assertFalse(expected.getMessage().contains("PRIVATE_"));
        }
    }
}

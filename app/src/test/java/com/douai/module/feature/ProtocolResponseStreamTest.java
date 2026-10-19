package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;

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
public final class ProtocolResponseStreamTest {
    @Test public void chatHandlesSingleByteUtf8CrLfMultilineDataCommentsAndUsageAfterStop() throws Exception {
        String stream = "\uFEFF: heartbeat\r\nid: ignored\r\nretry: 1\r\n\r\n"
                + "data: {\"choices\":[{\"index\":0,\r\ndata: \"delta\":{\"role\":\"assistant\",\"content\":\"旅行 😀\"}}]}\r\n\r\n"
                + chat("摄影", "stop") + event(new JSONObject().put("choices", new JSONArray())
                .put("usage", new JSONObject().put("total_tokens", 30))) + "data: [DONE]\r\n\r\n";
        List<String> logs = new ArrayList<>();
        InputStream oneByte = new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] value, int offset, int length) {
                return super.read(value, offset, Math.min(1, length));
            }
        };
        assertEquals("旅行 😀摄影", ProtocolResponseStream.read(AiModelConfig.CHAT, oneByte, 4000, () -> { }, logs::add));
        assertTrue(String.join("\n", logs).contains("stream_complete"));
        assertFalse(String.join("\n", logs).contains("旅行"));
    }

    @Test public void chatUsesOnlyIndexZeroAndIgnoresReasoningMetadata() throws Exception {
        JSONArray choices = new JSONArray()
                .put(new JSONObject().put("index", 1).put("delta", new JSONObject().put("content", "PRIVATE_OTHER")))
                .put(new JSONObject().put("index", 0).put("delta", new JSONObject()
                        .put("reasoning_content", "PRIVATE_REASONING").put("content", "visible")));
        assertEquals("visible", read(AiModelConfig.CHAT, event(new JSONObject().put("choices", choices)) + chat("", "stop")));
        assertFailure(AiModelConfig.CHAT, event(new JSONObject().put("choices", new JSONArray()
                .put(new JSONObject().put("index", 1).put("finish_reason", "stop")))),
                ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }

    @Test public void chatAcceptsStopAtEofButNotDoneWithoutStopOrPartialEof() throws Exception {
        assertEquals("final", read(AiModelConfig.CHAT, chat("final", "stop").trim()));
        assertFailure(AiModelConfig.CHAT, chat("partial", null), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.CHAT, chat("partial", null) + "data: [DONE]\n\n", ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }

    @Test public void chatRejectsLengthFilterToolsAndUnexpectedRoleWithoutLeakingErrors() throws Exception {
        for (String reason : new String[]{"length", "content_filter", "tool_calls", "function_call", "unknown"}) {
            assertFailure(AiModelConfig.CHAT, chat("partial", reason), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        }
        JSONObject toolDelta = new JSONObject().put("content", "partial").put("tool_calls", new JSONArray()
                .put(new JSONObject().put("function", new JSONObject().put("arguments", "PRIVATE_TOOL"))));
        assertFailure(AiModelConfig.CHAT, event(new JSONObject().put("choices", new JSONArray()
                .put(new JSONObject().put("index", 0).put("delta", toolDelta)))) + chat("", "stop"), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.CHAT, event(new JSONObject().put("choices", new JSONArray()
                .put(new JSONObject().put("index", 0).put("delta", new JSONObject()
                        .put("role", "user").put("content", "PRIVATE_USER"))))) + chat("", "stop"), ResponsesAnalysisStream.Failure.Kind.FORMAT);
    }

    @Test public void chatDoesNotReturnAtStopBeforeLaterErrorOrBody() throws Exception {
        assertFailure(AiModelConfig.CHAT, chat("visible", "stop") + event(new JSONObject()
                .put("error", new JSONObject().put("message", "PRIVATE_SERVER_ERROR"))), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.CHAT, chat("visible", "stop") + chat("late", null), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.CHAT, chat("visible", "stop") + chat("", "stop"), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }

    @Test public void chatAllowsEmptyToolCallArrayButRejectsMistypedToolCalls() throws Exception {
        JSONObject delta = new JSONObject().put("content", "ordinary text").put("tool_calls", new JSONArray());
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta).put("finish_reason", "stop");
        assertEquals("ordinary text", read(AiModelConfig.CHAT,
                event(new JSONObject().put("choices", new JSONArray().put(choice))) + "data: [DONE]\n\n"));
        for (Object invalid : new Object[]{"PRIVATE_TOOL", 0, new JSONObject()}) {
            delta.put("tool_calls", invalid);
            assertFailure(AiModelConfig.CHAT, event(new JSONObject().put("choices", new JSONArray().put(choice))),
                    ResponsesAnalysisStream.Failure.Kind.FORMAT);
        }
        delta.put("tool_calls", JSONObject.NULL);
        assertEquals("ordinary text", read(AiModelConfig.CHAT, event(new JSONObject().put("choices", new JSONArray().put(choice)))));
    }

    @Test public void claudeCollectsOnlyTextBlocksAndRequiresMessageStopAfterEndTurn() throws Exception {
        String stream = claudeStart() + claudeBlockStart(0, "thinking", "PRIVATE_REASONING")
                + claudeDelta(0, "thinking_delta", "thinking", "PRIVATE_REASONING") + claudeBlockStop(0)
                + claudeBlockStart(1, "tool_use", "")
                + claudeDelta(1, "input_json_delta", "partial_json", "PRIVATE_TOOL") + claudeBlockStop(1)
                + claudeBlockStart(2, "text", "你好")
                + claudeDelta(2, "text_delta", "text", " 😀") + claudeBlockStop(2)
                + event(new JSONObject().put("type", "ping")) + claudeFinish("end_turn") + claudeStop();
        assertEquals("你好 😀", read(AiModelConfig.ANTHROPIC, stream));
        assertEquals("final", read(AiModelConfig.ANTHROPIC, claudeText("final") + claudeFinish("stop_sequence") + claudeStop()));
    }

    @Test public void claudeDoesNotTreatStopReasonOrMessageStopAloneAsComplete() throws Exception {
        assertFailure(AiModelConfig.ANTHROPIC, claudeText("partial") + claudeFinish("end_turn"), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.ANTHROPIC, claudeText("partial") + claudeStop(), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.ANTHROPIC, claudeText("partial") + claudeFinish("end_turn")
                + event(new JSONObject().put("type", "error").put("error", new JSONObject().put("message", "PRIVATE_SERVER_ERROR"))),
                ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        for (String reason : new String[]{"max_tokens", "tool_use", "pause_turn", "refusal", "unknown"}) {
            assertFailure(AiModelConfig.ANTHROPIC, claudeText("partial") + claudeFinish(reason) + claudeStop(),
                    ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        }
    }

    @Test public void claudeRequiresBlockBoundariesAndMatchingEventType() throws Exception {
        assertFailure(AiModelConfig.ANTHROPIC, claudeStart() + claudeDelta(0, "text_delta", "text", "orphan"),
                ResponsesAnalysisStream.Failure.Kind.FORMAT);
        assertFailure(AiModelConfig.ANTHROPIC, claudeStart() + claudeBlockStart(0, "text", "partial") + claudeFinish("end_turn"),
                ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.ANTHROPIC, "event: message_start\ndata: {\"type\":\"message_stop\"}\n\n",
                ResponsesAnalysisStream.Failure.Kind.FORMAT);
    }

    @Test public void geminiCollectsFirstCandidateNonThoughtTextAndWaitsForUsageEof() throws Exception {
        JSONArray parts = new JSONArray().put(new JSONObject().put("text", "PRIVATE_REASONING").put("thought", true))
                .put(new JSONObject().put("text", "visible"))
                .put(new JSONObject().put("inlineData", new JSONObject().put("data", "PRIVATE_MEDIA")));
        JSONArray candidates = new JSONArray().put(new JSONObject().put("index", 1)
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "PRIVATE_OTHER")))))
                .put(new JSONObject().put("index", 0).put("content", new JSONObject().put("role", "model").put("parts", parts)));
        String stream = event(new JSONObject().put("candidates", candidates)) + gemini(" finish", "STOP")
                + event(new JSONObject().put("usageMetadata", new JSONObject().put("totalTokenCount", 42)));
        assertEquals("visible finish", read(AiModelConfig.GEMINI, stream));
    }

    @Test public void geminiAllowsOmittedDefaultZeroIndexOnlyForSingleCandidate() throws Exception {
        JSONObject candidate = new JSONObject().put("finishReason", "STOP")
                .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "default zero"))));
        assertEquals("default zero", read(AiModelConfig.GEMINI, event(new JSONObject().put("candidates", new JSONArray().put(candidate)))));
        assertFailure(AiModelConfig.GEMINI, event(new JSONObject().put("candidates", new JSONArray().put(candidate).put(candidate))),
                ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }

    @Test public void geminiRejectsEofFilteringToolsErrorsAndTextAfterStop() throws Exception {
        assertFailure(AiModelConfig.GEMINI, gemini("partial", null), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        for (String reason : new String[]{"MAX_TOKENS", "SAFETY", "RECITATION", "OTHER", "MALFORMED_FUNCTION_CALL"}) {
            assertFailure(AiModelConfig.GEMINI, gemini("partial", reason), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        }
        assertFailure(AiModelConfig.GEMINI, gemini("partial", "STOP") + event(new JSONObject().put("error", "PRIVATE_SERVER_ERROR")),
                ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.GEMINI, gemini("partial", "STOP") + gemini("late", null), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        assertFailure(AiModelConfig.GEMINI, event(new JSONObject().put("promptFeedback", new JSONObject().put("blockReason", "SAFETY")))
                + gemini("partial", "STOP"), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        JSONObject call = new JSONObject().put("functionCall", new JSONObject().put("name", "PRIVATE_TOOL"));
        assertFailure(AiModelConfig.GEMINI, event(new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("index", 0).put("finishReason", "STOP").put("content", new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", "partial")).put(call)))))), ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }

    @Test public void geminiRejectsBlockedRatingsAndMistypedThoughtBeforeExposingText() throws Exception {
        JSONObject candidate = new JSONObject().put("index", 0).put("finishReason", "STOP")
                .put("safetyRatings", new JSONArray().put(new JSONObject().put("blocked", true)))
                .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "PRIVATE_BLOCKED"))));
        assertFailure(AiModelConfig.GEMINI, event(new JSONObject().put("candidates", new JSONArray().put(candidate))),
                ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        candidate.remove("safetyRatings");
        candidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).put("thought", "true");
        assertFailure(AiModelConfig.GEMINI, event(new JSONObject().put("candidates", new JSONArray().put(candidate))),
                ResponsesAnalysisStream.Failure.Kind.FORMAT);
    }

    @Test public void allProtocolsBoundVisibleOutputAndRequireNonemptyResult() throws Exception {
        assertFailure(AiModelConfig.CHAT, chat("x".repeat(4001), "stop"), ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(AiModelConfig.ANTHROPIC, claudeText("x".repeat(4001)) + claudeFinish("end_turn") + claudeStop(),
                ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(AiModelConfig.GEMINI, gemini("x".repeat(4001), "STOP"), ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(AiModelConfig.CHAT, chat(" ", "stop"), ResponsesAnalysisStream.Failure.Kind.EMPTY);
        assertFailure(AiModelConfig.ANTHROPIC, claudeText(" ") + claudeFinish("end_turn") + claudeStop(), ResponsesAnalysisStream.Failure.Kind.EMPTY);
        assertFailure(AiModelConfig.GEMINI, gemini(" ", "STOP"), ResponsesAnalysisStream.Failure.Kind.EMPTY);
    }

    @Test public void genericDecoderHasIndependentByteLineEventAndFrameBudgets() throws Exception {
        assertFailure(AiModelConfig.CHAT, ":" + "x".repeat(ResponsesAnalysisStream.MAX_LINE_CHARS) + "\n",
                ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(AiModelConfig.CHAT, ("data: " + "x".repeat(50_000) + "\n").repeat(12),
                ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(AiModelConfig.CHAT, (":" + "x".repeat(60_000) + "\n").repeat(ResponsesAnalysisStream.MAX_BYTES / 60_002 + 1),
                ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(AiModelConfig.CHAT, "data: {}\n\n".repeat(ResponsesAnalysisStream.MAX_FRAMES + 1),
                ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        assertFailure(AiModelConfig.CHAT, "data: PRIVATE_BAD_JSON\n\n", ResponsesAnalysisStream.Failure.Kind.FORMAT);
    }

    @Test public void invalidUtf8IsRejectedAndCancellationDeadlineKeepTheirKinds() throws Exception {
        byte[] invalid = new byte[]{'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, 0x28};
        try { ProtocolResponseStream.read(AiModelConfig.CHAT, new ByteArrayInputStream(invalid), 4000, () -> { }, ignored -> { }); fail(); }
        catch (ResponsesAnalysisStream.Failure expected) { assertEquals(ResponsesAnalysisStream.Failure.Kind.FORMAT, expected.kind); }
        AtomicInteger checks = new AtomicInteger();
        try {
            ProtocolResponseStream.read(AiModelConfig.CHAT, bytes(chat("text", "stop")), 4000, () -> {
                if (checks.incrementAndGet() == 5) throw new CancellationException("cancelled");
            }, ignored -> { });
            fail();
        } catch (CancellationException expected) { assertEquals("cancelled", expected.getMessage()); }
        checks.set(0);
        try {
            ProtocolResponseStream.read(AiModelConfig.GEMINI, bytes(gemini("text", "STOP")), 4000, () -> {
                if (checks.incrementAndGet() == 5) throw new SocketTimeoutException("deadline");
            }, ignored -> { });
            fail();
        } catch (SocketTimeoutException expected) { assertEquals("deadline", expected.getMessage()); }
    }

    @Test public void responsesStillRequiresCompletedAndRejectsNestedErrors() throws Exception {
        JSONObject response = new JSONObject().put("status", "completed").put("output_text", "visible");
        assertEquals("visible", read(AiModelConfig.RESPONSES, event(new JSONObject().put("type", "response.completed").put("response", response))));
        response.put("error", new JSONObject().put("message", "PRIVATE_SERVER_ERROR"));
        assertFailure(AiModelConfig.RESPONSES, event(new JSONObject().put("type", "response.completed").put("response", response)),
                ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }

    private static String read(String protocol, String stream) throws Exception {
        return ProtocolResponseStream.read(protocol, bytes(stream), 4000, () -> { }, ignored -> { });
    }
    private static InputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private static String event(JSONObject value) { return "data: " + value + "\n\n"; }
    private static String chat(String text, String reason) throws Exception {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", new JSONObject().put("content", text));
        if (reason != null) choice.put("finish_reason", reason);
        return event(new JSONObject().put("choices", new JSONArray().put(choice)));
    }
    private static String claudeStart() throws Exception {
        return event(new JSONObject().put("type", "message_start").put("message", new JSONObject().put("role", "assistant")));
    }
    private static String claudeBlockStart(int index, String type, String text) throws Exception {
        JSONObject block = new JSONObject().put("type", type);
        if ("text".equals(type)) block.put("text", text);
        return event(new JSONObject().put("type", "content_block_start").put("index", index).put("content_block", block));
    }
    private static String claudeDelta(int index, String type, String key, String value) throws Exception {
        return event(new JSONObject().put("type", "content_block_delta").put("index", index)
                .put("delta", new JSONObject().put("type", type).put(key, value)));
    }
    private static String claudeBlockStop(int index) throws Exception {
        return event(new JSONObject().put("type", "content_block_stop").put("index", index));
    }
    private static String claudeFinish(String reason) throws Exception {
        return event(new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", reason)));
    }
    private static String claudeStop() throws Exception { return event(new JSONObject().put("type", "message_stop")); }
    private static String claudeText(String value) throws Exception {
        return claudeStart() + claudeBlockStart(0, "text", "") + claudeDelta(0, "text_delta", "text", value) + claudeBlockStop(0);
    }
    private static String gemini(String value, String reason) throws Exception {
        JSONObject candidate = new JSONObject().put("index", 0).put("content", new JSONObject().put("role", "model")
                .put("parts", new JSONArray().put(new JSONObject().put("text", value))));
        if (reason != null) candidate.put("finishReason", reason);
        return event(new JSONObject().put("candidates", new JSONArray().put(candidate)));
    }
    private static void assertFailure(String protocol, String stream, ResponsesAnalysisStream.Failure.Kind kind) throws Exception {
        try { read(protocol, stream); fail("stream must fail with " + kind); }
        catch (ResponsesAnalysisStream.Failure expected) {
            assertEquals(kind, expected.kind);
            assertNull(expected.getCause());
            assertFalse(expected.getMessage().contains("PRIVATE_"));
        }
    }
}

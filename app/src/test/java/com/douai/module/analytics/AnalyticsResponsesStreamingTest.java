package com.douai.module.analytics;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsRepository;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises the public analysis request against synthetic HTTP fixtures only. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AnalyticsResponsesStreamingTest {
    @Test public void responsesAnalysisRequestsStreamingAndCombinesTextDeltasWithoutDuplicatingCompletedText() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String answer = answer();
            int split = answer.length() / 2;
            String events = event("response.created", new JSONObject().put("response",
                    new JSONObject().put("id", "synthetic-response").put("status", "in_progress")))
                    + event("response.reasoning_summary_text.delta", new JSONObject()
                            .put("delta", "SYNTHETIC_REASONING_MUST_NOT_ENTER_ANALYSIS"))
                    + event("response.output_text.delta", new JSONObject().put("item_id", "synthetic-message")
                            .put("output_index", 0).put("content_index", 0).put("delta", answer.substring(0, split)))
                    + event("response.output_text.delta", new JSONObject().put("item_id", "synthetic-message")
                            .put("output_index", 0).put("content_index", 0).put("delta", answer.substring(split)))
                    + event("response.completed", new JSONObject().put("response", response(answer)));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8")
                    .setBody(events));

            String normalized = AnalyticsAiAnalysis.prepare(settings(server), snapshot(), 7).run();
            assertEquals(AnalyticsAiAnalysis.parseResult(answer).toJson(), normalized);
            AnalyticsAiAnalysis.Result parsed = AnalyticsAiAnalysis.parseResult(normalized);
            assertTrue(parsed.hasPortrait());
            assertEquals("测试综合侧写", parsed.portrait);
            assertFalse(normalized.contains("SYNTHETIC_REASONING_MUST_NOT_ENTER_ANALYSIS"));
            assertEquals(11, new JSONObject(normalized).length());
            RecordedRequest sent = takeRequest(server);
            assertEquals("/v1/responses", sent.getPath());
            assertTrue("Responses analysis must request SSE on its actual HTTP request",
                    new JSONObject(sent.getBody().readUtf8()).getBoolean("stream"));
            assertEquals("Completion snapshots must not trigger another HTTP request", 1, server.getRequestCount());
        }
    }

    @Test public void responsesAnalysisStillAcceptsACompletedApplicationJsonResponseOnTheSameRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String answer = answer();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(response(answer).toString()));

            String normalized = AnalyticsAiAnalysis.prepare(settings(server), snapshot(), 7).run();
            assertEquals(AnalyticsAiAnalysis.parseResult(answer).toJson(), normalized);
            assertEquals("/v1/responses", takeRequest(server).getPath());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void sameResponsesProfileCanSwitchResponseModesAndPreparedAnalysisKeepsItsSelectedMode() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String answer = answer();
            SettingsRepository store = settings(server, AiModelConfig.RESPONSES, "non_streaming");
            AiModelConfig original = AiModelSettings.read(store).selected();
            AnalyticsAiAnalysis.AnalysisRequest preparedNonStreaming = AnalyticsAiAnalysis.prepare(store, snapshot(), 7);
            AiModelConfig streaming = AiModelConfig.fromJson(original.toJson().put("responseMode", "streaming"));
            new AiModelSettings(List.of(streaming), original.id).save(store);
            AnalyticsAiAnalysis.AnalysisRequest preparedStreaming = AnalyticsAiAnalysis.prepare(store, snapshot(), 7);
            new AiModelSettings(List.of(original), original.id).save(store);

            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(response(answer).toString()));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(event("response.output_text.delta", new JSONObject().put("delta", answer))
                            + event("response.completed", new JSONObject().put("response", response(answer)))));
            String expected = AnalyticsAiAnalysis.parseResult(answer).toJson();
            assertEquals(expected, preparedNonStreaming.run());
            RecordedRequest first = takeRequest(server);
            assertEquals("/v1/responses", first.getPath());
            assertFalse("A prepared non-streaming analysis must keep its model setting",
                    new JSONObject(first.getBody().readUtf8()).getBoolean("stream"));
            assertEquals(expected, preparedStreaming.run());
            RecordedRequest second = takeRequest(server);
            assertEquals("/v1/responses", second.getPath());
            assertTrue("A prepared streaming analysis must keep its model setting after it is changed again",
                    new JSONObject(second.getBody().readUtf8()).getBoolean("stream"));
            assertEquals(original.id, AiModelSettings.read(store).selected().id);
            assertEquals("Each requested analysis should make exactly one HTTP request", 2, server.getRequestCount());
        }
    }

    @Test public void chatCompletionsAnalysisUsesTheSelectedStreamingMode() throws Exception {
        String answer = answer();
        int split = answer.length() / 2;
        String events = chatDelta(answer.substring(0, split), null)
                + chatDelta(answer.substring(split), null) + chatDelta("", "stop") + "data: [DONE]\n\n";
        assertStreamingAnalysis(AiModelConfig.CHAT, events, "/v1/chat/completions");
    }

    @Test public void claudeAnalysisUsesTheSelectedStreamingMode() throws Exception {
        String answer = answer();
        int split = answer.length() / 2;
        String events = event("message_start", new JSONObject().put("message", new JSONObject()
                .put("id", "synthetic-message").put("type", "message").put("role", "assistant")
                .put("model", "synthetic-test-model").put("content", new JSONArray())))
                + event("content_block_start", new JSONObject().put("index", 0)
                        .put("content_block", new JSONObject().put("type", "text").put("text", "")))
                + event("content_block_delta", new JSONObject().put("index", 0)
                        .put("delta", new JSONObject().put("type", "text_delta").put("text", answer.substring(0, split))))
                + event("content_block_delta", new JSONObject().put("index", 0)
                        .put("delta", new JSONObject().put("type", "text_delta").put("text", answer.substring(split))))
                + event("content_block_stop", new JSONObject().put("index", 0))
                + event("message_delta", new JSONObject().put("delta", new JSONObject().put("stop_reason", "end_turn")))
                + event("message_stop", new JSONObject());
        assertStreamingAnalysis(AiModelConfig.ANTHROPIC, events, "/v1/messages");
    }

    @Test public void geminiAnalysisUsesTheSelectedStreamingModeAndStreamingEndpoint() throws Exception {
        String answer = answer();
        int split = answer.length() / 2;
        String events = geminiDelta(answer.substring(0, split), null) + geminiDelta(answer.substring(split), "STOP");
        assertStreamingAnalysis(AiModelConfig.GEMINI, events, "/v1/models/synthetic-test-model:streamGenerateContent");
    }

    @Test public void responsesGateway504RemainsASingleTypedHttpFailureWithoutAnAutomaticRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(504).setHeader("Content-Type", "text/plain")
                    .setBody("SYNTHETIC_GATEWAY_BODY_MUST_NOT_LEAK"));
            try {
                AnalyticsAiAnalysis.prepare(settings(server), snapshot(), 7).run();
                fail("HTTP 504 cannot be treated as a successful analysis");
            } catch (AnalyticsAiAnalysis.AnalysisFailure failure) {
                assertEquals(AnalyticsAiAnalysis.AnalysisFailure.Kind.HTTP, failure.kind());
                assertEquals(504, failure.httpStatus());
                assertFalse(failure.safeMessage().contains("SYNTHETIC_GATEWAY_BODY_MUST_NOT_LEAK"));
            }
            assertEquals("/v1/responses", takeRequest(server).getPath());
            assertEquals("An upstream timeout must not silently submit the analysis again", 1, server.getRequestCount());
        }
    }

    @Test public void incompleteJsonResponseCannotBecomeSuccessfulEvenWithAllElevenAnalysisFields() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            JSONObject incomplete = response(answer()).put("status", "incomplete")
                    .put("incomplete_details", new JSONObject().put("reason", "max_output_tokens"));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(incomplete.toString()));

            try {
                AnalyticsAiAnalysis.prepare(settings(server), snapshot(), 7).run();
                fail("Complete-looking JSON text cannot override an explicit incomplete response status");
            } catch (AnalyticsAiAnalysis.AnalysisFailure failure) {
                assertEquals(AnalyticsAiAnalysis.AnalysisFailure.Kind.INCOMPLETE, failure.kind());
                assertFalse(failure.safeMessage().contains("测试综合侧写"));
            }
            assertEquals("/v1/responses", takeRequest(server).getPath());
            assertEquals("An incomplete response must not silently retry", 1, server.getRequestCount());
        }
    }

    private static SettingsRepository settings(MockWebServer server) throws Exception {
        return settings(server, AiModelConfig.RESPONSES, "streaming");
    }

    private static SettingsRepository settings(MockWebServer server, String protocol, String responseMode) throws Exception {
        SettingsRepository store = new SettingsRepository(RuntimeEnvironment.getApplication()
                .getSharedPreferences("analytics-responses-streaming-" + UUID.randomUUID(), 0));
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "synthetic-responses")
                .put("name", "测试 Responses 模型").put("provider", "custom")
                .put("protocol", protocol).put("responseMode", responseMode).put("apiEndpoint", server.url("/v1").toString())
                .put("apiKey", "synthetic-test-key").put("modelName", "synthetic-test-model")
                .put("allowInsecureHttp", true).put("timeoutSeconds", 5));
        new AiModelSettings(List.of(config), config.id).save(store);
        return store;
    }

    private static void assertStreamingAnalysis(String protocol, String events, String expectedPath) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(events));
            String normalized = AnalyticsAiAnalysis.prepare(settings(server, protocol, "streaming"), snapshot(), 7).run();
            assertEquals(AnalyticsAiAnalysis.parseResult(answer()).toJson(), normalized);
            assertEquals(11, new JSONObject(normalized).length());
            RecordedRequest sent = takeRequest(server);
            assertNotNull(sent.getRequestUrl());
            assertEquals(expectedPath, sent.getRequestUrl().encodedPath());
            if (AiModelConfig.GEMINI.equals(protocol)) {
                assertEquals("sse", sent.getRequestUrl().queryParameter("alt"));
            } else {
                assertTrue("The actual analysis request must use the selected streaming mode",
                        new JSONObject(sent.getBody().readUtf8()).getBoolean("stream"));
            }
            assertEquals("Streaming must not make extra analysis requests", 1, server.getRequestCount());
        }
    }

    private static String chatDelta(String text, String finishReason) throws Exception {
        JSONObject choice = new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("content", text))
                .put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason);
        return "data: " + new JSONObject().put("id", "synthetic-chat").put("object", "chat.completion.chunk")
                .put("choices", new JSONArray().put(choice)) + "\n\n";
    }

    private static String geminiDelta(String text, String finishReason) throws Exception {
        JSONObject candidate = new JSONObject().put("index", 0).put("content", new JSONObject()
                .put("role", "model").put("parts", new JSONArray().put(new JSONObject().put("text", text))));
        if (finishReason != null) candidate.put("finishReason", finishReason);
        return "data: " + new JSONObject().put("candidates", new JSONArray().put(candidate)) + "\n\n";
    }

    private static AnalyticsSnapshot snapshot() {
        return new AnalyticsSnapshot(3, 1, 3, 0, 1, 1, 0,
                List.of(new AnalyticsSnapshot.Bucket("2026-10-18", 3)),
                List.of(new AnalyticsSnapshot.Rank("短视频", 3)),
                List.of(new AnalyticsSnapshot.Rank("测试作者", 3)),
                List.of(new AnalyticsSnapshot.Rank("测试话题", 3)),
                List.of(new AnalyticsSnapshot.Rank("测试操作", 1)), new long[24], List.of());
    }

    private static String answer() throws Exception {
        return new JSONObject().put("overview", "测试概览").put("trend", "测试趋势")
                .put("types", "测试类型").put("authors", "测试作者分布").put("topics", "测试话题分布")
                .put("features", "测试功能使用").put("hours", "测试时段分布")
                .put("portraitTitle", "测试侧写标题").put("portrait", "测试综合侧写")
                .put("contrasts", "测试反差描述").put("questions", "测试自我校准问题").toString();
    }

    private static JSONObject response(String answer) throws Exception {
        return new JSONObject().put("id", "synthetic-response").put("status", "completed")
                .put("output", new JSONArray().put(new JSONObject().put("id", "synthetic-message")
                        .put("type", "message").put("role", "assistant")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "output_text")
                                .put("text", answer)))));
    }

    private static String event(String type, JSONObject data) throws Exception {
        return "event: " + type + "\ndata: " + data.put("type", type) + "\n\n";
    }

    private static RecordedRequest takeRequest(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("The analysis must reach the synthetic HTTP server", request);
        return request;
    }
}

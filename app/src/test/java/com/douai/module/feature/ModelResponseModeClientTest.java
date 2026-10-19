package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ModelResponseModeClientTest {
    @Test public void everyProtocolUsesTheSelectedModeForOrdinaryAndAnalysisRequests() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            AiModelSettings sync = settings(server, protocol, false);
            AiModelSettings stream = settings(server, protocol, true);
            ChatCompletionClient client = new ChatCompletionClient();
            for (boolean structured : new boolean[]{false, true}) {
                server.enqueue(json(protocol, "完整正文"));
                assertEquals("完整正文", invoke(client, sync, structured));
                checkRequest(server.takeRequest(), protocol, false);
                server.enqueue(sse(protocol, "完整正文"));
                assertEquals("完整正文", invoke(client, stream, structured));
                checkRequest(server.takeRequest(), protocol, true);
            }
            assertEquals("Switching mode does not cause retries", 4, server.getRequestCount());
        }
    }

    @Test public void relayJsonOnStreamingConnectionUsesTheSelectedProtocolWithoutResending() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(protocol, "same connection"));
            assertEquals("same connection", new ChatCompletionClient().reply("test", "ping", settings(server, protocol, true)));
            checkRequest(server.takeRequest(), protocol, true);
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void explicitIncompleteJsonFailsInBothModesWithoutBecomingContext() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) for (boolean streaming : new boolean[]{false, true})
            try (MockWebServer server = new MockWebServer()) {
                JSONObject body = new JSONObject();
                if (AiModelConfig.RESPONSES.equals(protocol)) body.put("status", "incomplete").put("output_text", "PRIVATE partial");
                else if (AiModelConfig.CHAT.equals(protocol)) body.put("choices", new JSONArray().put(new JSONObject()
                        .put("finish_reason", "length").put("message", new JSONObject().put("content", "PRIVATE partial"))));
                else if (AiModelConfig.ANTHROPIC.equals(protocol)) body.put("stop_reason", "max_tokens")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "PRIVATE partial")));
                else body.put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "MAX_TOKENS")
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "PRIVATE partial"))))));
                server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()));
                AiModelSettings profile = settings(server, protocol, streaming);
                ChatCompletionClient client = new ChatCompletionClient();
                try { client.reply("same", "failed question", profile); fail("Explicit truncation must fail: " + protocol); }
                catch (ChatCompletionClient.ReplyFailure failure) {
                    assertEquals(ChatCompletionClient.ReplyFailure.Kind.INCOMPLETE, failure.kind());
                    assertFalse(failure.getMessage().contains("PRIVATE"));
                }
                assertEquals(1, server.getRequestCount());
                server.takeRequest();
                server.enqueue(json(protocol, "next"));
                assertEquals("next", client.reply("same", "next question", profile));
                String next = server.takeRequest().getBody().readUtf8();
                assertFalse(next.contains("failed question"));
                assertFalse(next.contains("PRIVATE partial"));
            }
    }

    @Test public void completedStreamPreservesPromptsAttachmentsAndHistoryAcrossModeSwitch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AiModelSettings stream = settings(server, AiModelConfig.CHAT, true);
            server.enqueue(sse(AiModelConfig.CHAT, "first answer"));
            server.enqueue(json(AiModelConfig.CHAT, "second answer"));
            ChatCompletionClient client = new ChatCompletionClient();
            ChatImage image = new ChatImage("image/png", new byte[]{1, 2, 3});
            assertEquals("first answer", client.reply("same", "first question", Collections.singletonList(image),
                    stream, "SECONDARY", true, "file evidence"));
            JSONObject first = new JSONObject(server.takeRequest().getBody().readUtf8());
            JSONArray firstMessages = first.getJSONArray("messages");
            assertEquals("PRIMARY", firstMessages.getJSONObject(0).getString("content"));
            assertTrue(firstMessages.toString().contains("SECONDARY"));
            assertTrue(firstMessages.toString().contains("file evidence"));
            assertEquals("data:image/png;base64,AQID", firstMessages.getJSONObject(1).getJSONArray("content")
                    .getJSONObject(1).getJSONObject("image_url").getString("url"));
            assertEquals("second answer", client.reply("same", "second question", settings(server, AiModelConfig.CHAT, false)));
            JSONObject second = new JSONObject(server.takeRequest().getBody().readUtf8());
            assertFalse(second.getBoolean("stream"));
            JSONArray messages = second.getJSONArray("messages");
            assertEquals(4, messages.length());
            assertTrue(messages.toString().contains("first question"));
            assertTrue(messages.toString().contains("file evidence"));
            assertEquals("first answer", messages.getJSONObject(2).getString("content"));
        }
    }

    @Test public void cancelledCompletionDoesNotEnterContextAndNextStreamStillWorks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse(AiModelConfig.CHAT, "PRIVATE cancelled answer"));
            server.enqueue(sse(AiModelConfig.CHAT, "next answer"));
            AtomicReference<ChatCompletionClient> owner = new AtomicReference<>();
            AtomicBoolean once = new AtomicBoolean(true);
            ChatCompletionClient client = new ChatCompletionClient(null, event -> {
                if (event.contains("stage=stream_complete") && once.getAndSet(false)) owner.get().cancelCurrentRequest();
            });
            owner.set(client);
            AiModelSettings profile = settings(server, AiModelConfig.CHAT, true);
            try { client.reply("same", "cancelled question", profile); fail("Cancellation must win before history commit"); }
            catch (CancellationException expected) { assertFalse(once.get()); }
            server.takeRequest();
            assertEquals("next answer", client.reply("same", "next question", profile));
            JSONArray messages = new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("messages");
            assertEquals(2, messages.length());
            assertFalse(messages.toString().contains("PRIVATE"));
            assertFalse(messages.toString().contains("cancelled question"));
        }
    }

    @Test public void ordinaryStreamFailureIsTypedSafeAndNeverRetriedOrRemembered() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"PRIVATE partial\"}}]}\n\n"));
            server.enqueue(sse(AiModelConfig.CHAT, "recovered"));
            AiModelSettings profile = settings(server, AiModelConfig.CHAT, true);
            ChatCompletionClient client = new ChatCompletionClient();
            try { client.reply("same", "failed question", profile); fail("EOF without finish is incomplete"); }
            catch (ChatCompletionClient.ReplyFailure failure) {
                assertEquals(ChatCompletionClient.ReplyFailure.Kind.INCOMPLETE, failure.kind());
                assertFalse(failure.getMessage().contains("PRIVATE"));
                assertNull(failure.getCause());
            }
            assertEquals(1, server.getRequestCount());
            server.takeRequest();
            assertEquals("recovered", client.reply("same", "next", profile));
            assertEquals(2, new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("messages").length());
        }
    }

    @Test public void fullGeminiOperationSwitchesInBothDirectionsWithoutDuplicatePathOrQuery() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            JSONObject base = settings(server, AiModelConfig.GEMINI, false).selected().toJson();
            for (String endpoint : new String[]{"/v1beta/models/model-a:generateContent", "/v1beta/models/model-a:streamGenerateContent"}) {
                for (boolean stream : new boolean[]{true, false}) {
                    AiModelConfig profile = AiModelConfig.fromJson(new JSONObject(base.toString())
                            .put("apiEndpoint", server.url(endpoint).toString())
                            .put("responseMode", stream ? "streaming" : "non_streaming"));
                    profile.validate(true);
                    assertEquals(server.url("/v1beta/models/model-a:" + (stream ? "streamGenerateContent?alt=sse" : "generateContent")).toString(),
                            ChatCompletionClient.requestEndpoint(profile, "model-a"));
                    assertEquals(server.url("/v1beta/models").toString(), ChatCompletionClient.modelsEndpoint(profile));
                    assertEquals(server.url("/v1beta/models/model-b:" + (stream ? "streamGenerateContent?alt=sse" : "generateContent")).toString(),
                            ChatCompletionClient.requestEndpoint(profile, "model-b"));
                }
            }
        }
    }

    private static String invoke(ChatCompletionClient client, AiModelSettings profile, boolean structured) throws Exception {
        return structured ? client.replyStructuredAnalysis("analysis", "ping", profile, "task", "{}")
                : client.reply("chat", "ping", profile);
    }

    private static void checkRequest(RecordedRequest request, String protocol, boolean streaming) throws Exception {
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        if (AiModelConfig.GEMINI.equals(protocol)) {
            assertEquals(streaming ? "/v1beta/models/model-a:streamGenerateContent?alt=sse"
                    : "/v1beta/models/model-a:generateContent", request.getPath());
            assertFalse("Gemini chooses the operation rather than a stream body parameter", body.has("stream"));
        } else assertEquals(streaming, body.getBoolean("stream"));
        if (streaming) assertEquals("text/event-stream", request.getHeader("Accept"));
        assertTrue(body.toString().contains("PRIMARY"));
    }

    private static AiModelSettings settings(MockWebServer server, String protocol, boolean streaming) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "same-model").put("name", "Fixture")
                .put("protocol", protocol).put("apiEndpoint", server.url(AiModelConfig.GEMINI.equals(protocol) ? "/v1beta" : "/v1").toString())
                .put("modelName", "model-a").put("apiKey", "fixture-key").put("allowInsecureHttp", true)
                .put("contextRounds", 2).put("systemPrompt", "PRIMARY")
                .put("responseMode", streaming ? "streaming" : "non_streaming"));
        return new AiModelSettings(Collections.singletonList(config), config.id);
    }

    private static MockResponse json(String protocol, String text) throws Exception {
        JSONObject body;
        if (AiModelConfig.CHAT.equals(protocol)) body = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "stop").put("message", new JSONObject().put("content", text))));
        else if (AiModelConfig.RESPONSES.equals(protocol)) body = new JSONObject().put("status", "completed").put("output_text", text);
        else if (AiModelConfig.ANTHROPIC.equals(protocol)) body = new JSONObject().put("stop_reason", "end_turn")
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
        else body = new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "STOP")
                .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", text))))));
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString());
    }

    private static MockResponse sse(String protocol, String text) throws Exception {
        String body;
        if (AiModelConfig.CHAT.equals(protocol)) body = frame(new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("index", 0).put("delta", new JSONObject().put("content", text)).put("finish_reason", "stop")))) + "data: [DONE]\n\n";
        else if (AiModelConfig.RESPONSES.equals(protocol)) body = frame(new JSONObject().put("type", "response.completed")
                .put("response", new JSONObject().put("status", "completed").put("output_text", text)));
        else if (AiModelConfig.ANTHROPIC.equals(protocol)) body = frame(new JSONObject().put("type", "message_start").put("message", new JSONObject().put("role", "assistant")))
                + frame(new JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", new JSONObject().put("type", "text").put("text", "")))
                + frame(new JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", new JSONObject().put("type", "text_delta").put("text", text)))
                + frame(new JSONObject().put("type", "content_block_stop").put("index", 0))
                + frame(new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", "end_turn")))
                + frame(new JSONObject().put("type", "message_stop"));
        else body = frame(new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("index", 0).put("finishReason", "STOP")
                .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", text)))))));
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    private static String frame(JSONObject event) { return "data: " + event + "\n\n"; }
}

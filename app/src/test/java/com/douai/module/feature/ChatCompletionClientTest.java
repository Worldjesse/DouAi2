package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ChatCompletionClientTest {
    @Test public void chatCompletionsProfileSendsBearerHeadersAndEnabledParameters() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}"));
            server.start();
            JSONObject config = config(server, AiModelConfig.CHAT, true)
                    .put("temperatureEnabled", true).put("temperature", 0.4)
                    .put("maxTokensEnabled", true).put("maxTokens", 96)
                    .put("customHeaders", "{\"X-Trace\":\"fixture\"}");
            ChatCompletionClient client = new ChatCompletionClient();
            assertEquals("pong", client.reply("thread", "ping", settings(config)));
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/v1/chat/completions", request.getPath());
            assertEquals("Bearer secret-key", request.getHeader("Authorization"));
            assertEquals("fixture", request.getHeader("X-Trace"));
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertEquals("model-a", body.getString("model"));
            assertEquals("Reply concisely", body.getJSONArray("messages").getJSONObject(0).getString("content"));
            assertEquals(0.4, body.getDouble("temperature"), 0.001);
            assertEquals(96, body.getInt("max_tokens"));
        }
    }

    @Test public void responsesProtocolUsesInputInstructionsAndResponsesTokenField() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"output_text\":\"ready\"}"));
            server.start();
            JSONObject config = config(server, AiModelConfig.RESPONSES, true)
                    .put("maxTokensEnabled", true).put("maxTokens", 73);
            assertEquals("ready", new ChatCompletionClient().reply("thread", "ping", settings(config)));
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/v1/responses", request.getPath());
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertEquals("Reply concisely", body.getString("instructions"));
            assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"));
            assertEquals(73, body.getInt("max_output_tokens"));
            assertFalse(body.has("messages"));
        }
    }

    @Test public void anthropicProtocolUsesMessagesAuthenticationAndRequiredMaxTokens() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}"));
            server.start();
            JSONObject config = config(server, AiModelConfig.ANTHROPIC, true);
            assertEquals("hello", new ChatCompletionClient().reply("thread", "ping", settings(config)));
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/v1/messages", request.getPath());
            assertEquals("secret-key", request.getHeader("x-api-key"));
            assertEquals("2023-06-01", request.getHeader("anthropic-version"));
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertEquals(1024, body.getInt("max_tokens"));
            assertEquals("Reply concisely", body.getString("system"));
        }
    }

    @Test public void geminiProtocolUsesGenerateContentShapeAndApiKeyHeader() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"gemini\"}]}}]}"));
            server.start();
            JSONObject config = config(server, AiModelConfig.GEMINI, true)
                    .put("temperatureEnabled", true).put("temperature", 0.5);
            assertEquals("gemini", new ChatCompletionClient().reply("thread", "ping", settings(config)));
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/v1beta/models/model-a:generateContent", request.getPath());
            assertEquals("secret-key", request.getHeader("x-goog-api-key"));
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertEquals("user", body.getJSONArray("contents").getJSONObject(0).getString("role"));
            assertEquals(0.5, body.getJSONObject("generationConfig").getDouble("temperature"), 0.001);
            assertTrue(body.has("systemInstruction"));
        }
    }

    @Test public void modelFetchParsesProviderCatalogsAndUsesProtocolAuthentication() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"}]}"));
            server.enqueue(json("{\"data\":[{\"id\":\"claude-sonnet\"}]}"));
            server.enqueue(json("{\"models\":[{\"name\":\"models/gemini-fast\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/embed\",\"supportedGenerationMethods\":[\"embedContent\"]}]}"));
            server.start();
            AiModelConfig chat = AiModelConfig.fromJson(config(server, AiModelConfig.CHAT, true));
            AiModelConfig anthropic = AiModelConfig.fromJson(config(server, AiModelConfig.ANTHROPIC, true));
            AiModelConfig gemini = AiModelConfig.fromJson(config(server, AiModelConfig.GEMINI, true));
            assertEquals(java.util.Arrays.asList("a-model", "z-model"), ChatCompletionClient.fetchModels(chat));
            assertEquals(Collections.singletonList("claude-sonnet"), ChatCompletionClient.fetchModels(anthropic));
            assertEquals(Collections.singletonList("gemini-fast"), ChatCompletionClient.fetchModels(gemini));
            assertEquals("Bearer secret-key", server.takeRequest().getHeader("Authorization"));
            assertEquals("secret-key", server.takeRequest().getHeader("x-api-key"));
            assertEquals("secret-key", server.takeRequest().getHeader("x-goog-api-key"));
        }
    }

    @Test public void failedConnectionDoesNotLeakApiKey() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("secret-key rejected"));
            server.start();
            try { new ChatCompletionClient().reply("fixture", "ping", settings(config(server, AiModelConfig.CHAT, true)));
                fail("Unauthorized request should fail"); }
            catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("HTTP 401"));
                assertFalse(expected.getMessage().contains("secret-key"));
            }
        }
    }

    @Test public void localHttpNeedsExplicitOptInAndRemoteHttpIsRejected() throws Exception {
        JSONObject local = config("http://127.0.0.1:11434/v1", AiModelConfig.CHAT, false)
                .toJson().put("allowInsecureHttp", false);
        try { AiModelConfig.fromJson(local).validate(true); fail("Local cleartext must require opt-in"); }
        catch (IllegalArgumentException expected) { }
        local.put("allowInsecureHttp", true);
        AiModelConfig.fromJson(local).validate(true);
        JSONObject remote = config("http://api.example.test/v1", AiModelConfig.CHAT, true)
                .toJson().put("allowInsecureHttp", false);
        try { AiModelConfig.fromJson(remote).validate(true); fail("Cleartext must require explicit opt-in"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void versionedProfilesDoNotReadRetiredLegacyConfiguration() throws Exception {
        JSONObject profile = config("https://api.example.test/v1", AiModelConfig.CHAT, false).toJson();
        AiModelSettings settings = new AiModelSettings(Collections.singletonList(AiModelConfig.fromJson(profile)),
                profile.getString("id"));
        String saved = settings.serialize();
        assertTrue(saved.contains("\"version\":2"));
        assertFalse(saved.contains("ai_models"));
        assertEquals(profile.getString("id"), AiModelSettings.parse(saved).selected().id);
        assertTrue(AiModelSettings.parse("").configs.isEmpty());
    }

    @Test public void successfulTurnsAreBoundedAndClearingStartsFreshContext() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(chatReply("first answer"));
            server.enqueue(chatReply("second answer"));
            server.enqueue(chatReply("third answer"));
            server.enqueue(chatReply("fresh answer"));
            server.start();
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true).put("contextRounds", 1));
            ChatCompletionClient client = new ChatCompletionClient();

            assertEquals("first answer", client.reply("test-chat", "first question", profile));
            assertEquals(2, requestMessages(server).length());
            assertEquals("second answer", client.reply("test-chat", "second question", profile));
            JSONArray second = requestMessages(server);
            assertEquals(4, second.length());
            assertEquals("first question", second.getJSONObject(1).getString("content"));
            assertEquals("assistant", second.getJSONObject(2).getString("role"));
            assertEquals("first answer", second.getJSONObject(2).getString("content"));
            assertEquals("third answer", client.reply("test-chat", "third question", profile));
            JSONArray third = requestMessages(server);
            assertEquals(4, third.length());
            assertEquals("second question", third.getJSONObject(1).getString("content"));

            client.clearHistory();
            assertEquals("fresh answer", client.reply("test-chat", "fresh question", profile));
            JSONArray fresh = requestMessages(server);
            assertEquals(2, fresh.length());
            assertEquals("fresh question", fresh.getJSONObject(1).getString("content"));
        }
    }

    @Test public void failedTurnIsExcludedFromNextRequestContext() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(chatReply("saved answer"));
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(chatReply("retried answer"));
            server.start();
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true));
            ChatCompletionClient client = new ChatCompletionClient();
            client.reply("test-chat", "saved question", profile);
            requestMessages(server);
            try { client.reply("test-chat", "failed question", profile); fail("503 must fail"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("HTTP 503")); }
            requestMessages(server);
            client.reply("test-chat", "retry question", profile);
            JSONArray retried = requestMessages(server);
            assertEquals(4, retried.length());
            assertEquals("saved question", retried.getJSONObject(1).getString("content"));
            assertEquals("retry question", retried.getJSONObject(3).getString("content"));
        }
    }

    @Test public void cancellationAbortsWaitingRequestAndPreservesOnlyCompletedTurns() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(chatReply("saved answer"));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(chatReply("after stop"));
            server.start();
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true).put("timeoutSeconds", 5));
            ChatCompletionClient client = new ChatCompletionClient();
            client.reply("test-chat", "saved question", profile);
            requestMessages(server);
            Future<String> pending = worker.submit(() -> client.reply("test-chat", "cancelled question", profile));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            client.cancelCurrentRequest();
            try { pending.get(2, TimeUnit.SECONDS); fail("Cancelled reply must fail"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }

            assertEquals("after stop", client.reply("test-chat", "next question", profile));
            JSONArray next = requestMessages(server);
            assertEquals(4, next.length());
            assertEquals("saved question", next.getJSONObject(1).getString("content"));
            assertEquals("next question", next.getJSONObject(3).getString("content"));
        } finally { worker.shutdownNow(); }
    }

    @Test public void preInterruptedRequestDoesNotReachProvider() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true));
            Future<String> pending = worker.submit(() -> {
                Thread.currentThread().interrupt();
                return new ChatCompletionClient().reply("test-chat", "cancelled before start", profile);
            });
            try { pending.get(2, TimeUnit.SECONDS); fail("Pre-interrupted reply must fail"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof CancellationException); }
            assertEquals(0, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void clearingDuringRequestPreventsLateHistoryRepopulation() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch responseReady = new CountDownLatch(1);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    responseReady.await(2, TimeUnit.SECONDS);
                    return json("{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}");
                }
            });
            server.start();
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true));
            ChatCompletionClient client = new ChatCompletionClient();
            Future<String> pending = worker.submit(() -> client.reply("test-chat", "old question", profile));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            client.clearHistory();
            responseReady.countDown();
            assertEquals("answer", pending.get(2, TimeUnit.SECONDS));

            client.reply("test-chat", "fresh question", profile);
            JSONArray fresh = requestMessages(server);
            assertEquals(2, fresh.length());
            assertEquals("fresh question", fresh.getJSONObject(1).getString("content"));
        } finally {
            responseReady.countDown();
            worker.shutdownNow();
        }
    }

    @Test public void chatCompletionsEncodesImageDataUrlAndKeepsItForFollowup() throws Exception {
        JSONArray content = firstImageMessage(AiModelConfig.CHAT).getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("Describe this", content.getJSONObject(0).getString("text"));
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertEquals("data:image/png;base64,AQID", content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test public void responsesEncodesInputImageAndKeepsItForFollowup() throws Exception {
        JSONArray content = firstImageMessage(AiModelConfig.RESPONSES).getJSONArray("content");
        assertEquals("input_text", content.getJSONObject(0).getString("type"));
        assertEquals("input_image", content.getJSONObject(1).getString("type"));
        assertEquals("data:image/png;base64,AQID", content.getJSONObject(1).getString("image_url"));
    }

    @Test public void anthropicEncodesBase64SourceAndKeepsItForFollowup() throws Exception {
        JSONArray content = firstImageMessage(AiModelConfig.ANTHROPIC).getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("image", content.getJSONObject(1).getString("type"));
        JSONObject source = content.getJSONObject(1).getJSONObject("source");
        assertEquals("base64", source.getString("type"));
        assertEquals("image/png", source.getString("media_type"));
        assertEquals("AQID", source.getString("data"));
    }

    @Test public void geminiEncodesInlineDataAndKeepsItForFollowup() throws Exception {
        JSONArray parts = firstImageMessage(AiModelConfig.GEMINI).getJSONArray("parts");
        assertEquals("Describe this", parts.getJSONObject(0).getString("text"));
        JSONObject inline = parts.getJSONObject(1).getJSONObject("inline_data");
        assertEquals("image/png", inline.getString("mime_type"));
        assertEquals("AQID", inline.getString("data"));
    }

    @Test public void imageOnlyMessageIsAllowedAndInvalidAttachmentsNeverReachProvider() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(chatReply("picture"));
            server.start();
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true));
            ChatCompletionClient client = new ChatCompletionClient();
            ChatImage image = new ChatImage("image/jpeg", new byte[]{1});
            assertEquals("picture", client.reply("test-chat", null, Collections.singletonList(image), profile));
            JSONArray content = requestMessages(server).getJSONObject(1).getJSONArray("content");
            assertEquals(1, content.length());
            assertEquals("image_url", content.getJSONObject(0).getString("type"));
            assertThrows(IllegalArgumentException.class,
                    () -> client.reply("test-chat", "  ", Collections.emptyList(), profile));
            assertThrows(IllegalArgumentException.class,
                    () -> client.reply("test-chat", "", Arrays.asList(image, image, image, image, image), profile));
            assertThrows(IllegalArgumentException.class,
                    () -> client.reply("test-chat", "message", Collections.singletonList(null), profile));
            assertThrows(IllegalArgumentException.class,
                    () -> client.reply("test-chat", String.join("", Collections.nCopies(4001, "a")),
                            Collections.singletonList(image), profile));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void globalImageBudgetEvictsWholeImageTurnsAndPreservesTextOnlyHistory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int i = 0; i < 7; i++) server.enqueue(chatReply("answer"));
            server.start();
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true));
            ChatCompletionClient client = new ChatCompletionClient();
            ChatImage large = new ChatImage("image/png", new byte[ChatImage.MAX_BYTES]);
            client.reply("text-only", "saved text", profile);
            server.takeRequest(2, TimeUnit.SECONDS);
            server.setBodyLimit(0);
            for (int i = 0; i < 4; i++) {
                client.reply("image-thread-" + i, "image " + i, Collections.singletonList(large), profile);
                RecordedRequest imageRequest = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(imageRequest);
                assertTrue(imageRequest.getBodySize() > large.base64Data.length());
            }
            server.setBodyLimit(Long.MAX_VALUE);

            client.reply("image-thread-0", "after eviction", profile);
            JSONArray evicted = requestMessages(server);
            assertEquals(2, evicted.length());
            assertEquals("after eviction", evicted.getJSONObject(1).getString("content"));
            client.reply("text-only", "followup text", profile);
            JSONArray text = requestMessages(server);
            assertEquals(4, text.length());
            assertEquals("saved text", text.getJSONObject(1).getString("content"));
            assertEquals("answer", text.getJSONObject(2).getString("content"));
        }
    }

    @Test public void currentImagesAreSentEvenWhenTheirTurnExceedsHistoryBudget() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(chatReply("pictures"));
            server.enqueue(chatReply("fresh"));
            server.start();
            server.setBodyLimit(0);
            AiModelSettings profile = settings(config(server, AiModelConfig.CHAT, true));
            ChatCompletionClient client = new ChatCompletionClient();
            ChatImage large = new ChatImage("image/png", new byte[ChatImage.MAX_BYTES]);
            client.reply("test-chat", "", Arrays.asList(large, large, large, large), profile);
            RecordedRequest current = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(current);
            assertTrue(current.getBodySize() >= 4L * large.base64Data.length());
            server.setBodyLimit(Long.MAX_VALUE);
            client.reply("test-chat", "fresh question", profile);
            assertEquals(2, requestMessages(server).length());
        }
    }

    private static JSONObject firstImageMessage(String protocol) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String response = AiModelConfig.RESPONSES.equals(protocol) ? "{\"output_text\":\"answer\"}"
                    : AiModelConfig.ANTHROPIC.equals(protocol) ? "{\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}"
                    : AiModelConfig.GEMINI.equals(protocol) ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"answer\"}]}}]}"
                    : "{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}";
            server.enqueue(json(response));
            server.enqueue(json(response));
            server.start();
            AiModelSettings profile = settings(config(server, protocol, true));
            ChatCompletionClient client = new ChatCompletionClient();
            client.reply("test-chat", "Describe this",
                    Collections.singletonList(new ChatImage("image/png", new byte[]{1, 2, 3})), profile);
            RecordedRequest firstRequest = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(firstRequest);
            JSONObject firstBody = new JSONObject(firstRequest.getBody().readUtf8());
            String key = AiModelConfig.RESPONSES.equals(protocol) ? "input"
                    : AiModelConfig.GEMINI.equals(protocol) ? "contents" : "messages";
            int userIndex = AiModelConfig.CHAT.equals(protocol) ? 1 : 0;
            JSONObject firstUser = firstBody.getJSONArray(key).getJSONObject(userIndex);
            client.reply("test-chat", "What color?", profile);
            RecordedRequest secondRequest = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(secondRequest);
            JSONArray followup = new JSONObject(secondRequest.getBody().readUtf8()).getJSONArray(key);
            assertEquals(userIndex + 3, followup.length());
            assertEquals(firstUser.toString(), followup.getJSONObject(userIndex).toString());
            assertEquals(AiModelConfig.GEMINI.equals(protocol) ? "model" : "assistant",
                    followup.getJSONObject(userIndex + 1).getString("role"));
            return firstUser;
        }
    }

    @Test public void primaryMemoryAndCurrentTaskAreSeparatedForEveryProtocol() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            try (MockWebServer server = new MockWebServer()) {
                String response = AiModelConfig.CHAT.equals(protocol) ? "{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}"
                        : AiModelConfig.RESPONSES.equals(protocol) ? "{\"output_text\":\"answer\"}"
                        : AiModelConfig.ANTHROPIC.equals(protocol) ? "{\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}"
                        : "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"answer\"}]}}]}";
                server.enqueue(json(response));
                server.start();
                ChatCompletionClient client = new ChatCompletionClient();
                client.reply("reply", "incoming", Collections.emptyList(),
                        settings(config(server, protocol, true).put("systemPrompt", "PRIMARY RULE")), "SECONDARY RULE");
                JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
                String instructions = AiModelConfig.CHAT.equals(protocol) ? body.getJSONArray("messages").getJSONObject(0).getString("content")
                        : AiModelConfig.RESPONSES.equals(protocol) ? body.getString("instructions")
                        : AiModelConfig.ANTHROPIC.equals(protocol) ? body.getString("system")
                        : body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
                assertEquals("PRIMARY RULE", instructions);
                JSONArray turns = body.getJSONArray(AiModelConfig.GEMINI.equals(protocol) ? "contents"
                        : AiModelConfig.RESPONSES.equals(protocol) ? "input" : "messages");
                String current = turns.getJSONObject(turns.length() - 1).toString();
                assertTrue(current.contains("SECONDARY RULE"));
                assertTrue(current.contains("incoming"));
                assertFalse(current.contains("PRIMARY RULE"));
            }
        }
    }

    @Test public void cancelledOrUnacknowledgedAutomaticReplyDoesNotEnterContext() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(chatReply("cancelled answer"));
            server.enqueue(chatReply("accepted answer"));
            server.enqueue(chatReply("next answer"));
            server.start();
            AiModelSettings models = settings(config(server, AiModelConfig.CHAT, true));
            ChatCompletionClient client = new ChatCompletionClient();
            client.reply("reply", "cancelled input", Collections.emptyList(), models, "task", false);
            requestMessages(server);
            client.reply("reply", "accepted input", Collections.emptyList(), models, "task", false);
            assertEquals(2, requestMessages(server).length());
            client.rememberReply("reply", "accepted input", Collections.emptyList(), models, "task", "accepted answer");
            client.reply("reply", "next input", Collections.emptyList(), models, "task", false);
            JSONArray messages = requestMessages(server);
            assertEquals(4, messages.length());
            assertEquals("accepted input", messages.getJSONObject(1).getString("content"));
            assertEquals("accepted answer", messages.getJSONObject(2).getString("content"));
            assertFalse(messages.toString().contains("cancelled"));
        }
    }

    @Test public void currentTaskChangesKeepMemoryContextAndDoNotReplayOldTaskInstructions() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(chatReply("first"));
            server.enqueue(chatReply("second"));
            server.start();
            AiModelSettings models = settings(config(server, AiModelConfig.CHAT, true));
            ChatCompletionClient client = new ChatCompletionClient();
            client.reply("reply", "first input", Collections.emptyList(), models, "first task");
            requestMessages(server);
            client.reply("reply", "second input", Collections.emptyList(), models, "second task");
            JSONArray messages = requestMessages(server);
            assertEquals(4, messages.length());
            assertEquals("first input", messages.getJSONObject(1).getString("content"));
            assertTrue(messages.getJSONObject(3).getString("content").contains("second task"));
            assertFalse(messages.toString().contains("first task"));
        }
    }

    @Test public void longMemoryAndTaskPromptsAreStoredAndSentWithoutLocalCharacterLimits() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String memory = " 记忆设定\n".repeat(50000);
            String task = " 本轮要求\n".repeat(3000);
            AiModelSettings models = settings(config(server, AiModelConfig.CHAT, true).put("systemPrompt", memory));
            models = AiModelSettings.parse(models.serialize());
            assertEquals(memory, models.selected().systemPrompt);
            server.enqueue(chatReply("answer"));
            new ChatCompletionClient().reply("thread", "incoming", Collections.emptyList(), models, task);
            JSONArray messages = requestMessages(server);
            assertEquals(memory, messages.getJSONObject(0).getString("content"));
            assertTrue(messages.getJSONObject(1).getString("content").contains(task));
        }
    }

    private static JSONArray requestMessages(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return new JSONObject(request.getBody().readUtf8()).getJSONArray("messages");
    }

    private static MockResponse chatReply(String text) throws Exception {
        return json(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message",
                new JSONObject().put("content", text)))).toString());
    }

    private static JSONObject config(MockWebServer server, String protocol, boolean key) throws Exception {
        String version = AiModelConfig.GEMINI.equals(protocol) ? "/v1beta"
                : AiModelConfig.ANTHROPIC.equals(protocol) ? "/v1"
                : AiModelConfig.CHAT.equals(protocol) || AiModelConfig.RESPONSES.equals(protocol) ? "/v1" : "";
        return config(server.url(version).toString(), protocol, key).toJson()
                .put("allowInsecureHttp", true).put("contextRounds", 2).put("systemPrompt", "Reply concisely");
    }

    private static AiModelConfig config(String endpoint, String protocol, boolean key) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", UUID.randomUUID().toString())
                .put("name", "Test profile").put("provider", "test").put("protocol", protocol)
                .put("apiEndpoint", endpoint).put("modelName", "model-a")
                .put("apiKey", key ? "secret-key" : "").put("allowInsecureHttp", endpoint.startsWith("http://")));
    }

    private static AiModelSettings settings(JSONObject config) throws Exception {
        AiModelConfig parsed = AiModelConfig.fromJson(config);
        return new AiModelSettings(java.util.Collections.singletonList(parsed), parsed.id);
    }

    private static MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }
}

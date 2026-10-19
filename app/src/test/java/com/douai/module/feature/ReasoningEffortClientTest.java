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
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ReasoningEffortClientTest {
    @Test public void ordinaryRepliesMapEveryEffortAndResponseModeForEveryProtocol() throws Exception {
        assertEffortMatrix(false);
    }

    @Test public void analysisUsesTheSameEffortAndResponseModeMappingForEveryProtocol() throws Exception {
        assertEffortMatrix(true);
    }

    private static void assertEffortMatrix(boolean analysis) throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            String[] levels = levels(protocol);
            for (boolean streaming : new boolean[]{false, true}) for (String level : levels) {
                String answer = analysis ? analysisResult("analysis result") : "ordinary result";
                server.enqueue(json(protocol, answer));
                AiModelSettings settings = settings(server, protocol, streaming, level, new JSONObject());
                ChatCompletionClient client = new ChatCompletionClient();
                assertEquals(answer, invoke(client, settings, analysis, "current question", "current evidence"));
                RecordedRequest request = take(server);
                JSONObject body = new JSONObject(request.getBody().readUtf8());
                assertEffort(body, protocol, "default".equals(level) ? null : level);
                assertResponseMode(request, body, protocol, streaming);
                assertPromptAndDocument(body, "current question", "current evidence");
            }
            assertEquals("There are no fallback retries", levels.length * 2, server.getRequestCount());
        }
    }

    @Test public void defaultEffortPreservesCustomReasoningParametersInBothResponseModes() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            for (boolean streaming : new boolean[]{false, true}) {
                JSONObject custom = customEffort(protocol, "high");
                custom.put("custom_fixture", "retained");
                server.enqueue(json(protocol, "custom result"));
                assertEquals("custom result", invoke(new ChatCompletionClient(),
                        settings(server, protocol, streaming, "default", custom), false, "custom question", "custom evidence"));
                JSONObject body = new JSONObject(take(server).getBody().readUtf8());
                assertEffort(body, protocol, "high");
                assertEquals("retained", body.getString("custom_fixture"));
            }
        }
    }

    @Test public void explicitEffortRejectsTheSameCustomLeafEvenWhenItsValueMatchesBeforeAnyRequest() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            for (boolean streaming : new boolean[]{false, true}) for (boolean analysis : new boolean[]{false, true}) {
                for (Object customLevel : new Object[]{"low", "high", JSONObject.NULL}) {
                    server.enqueue(json(protocol, "must not be requested"));
                    AiModelSettings profile = settings(server, protocol, streaming, "low", customEffort(protocol, customLevel));
                    try {
                        invoke(new ChatCompletionClient(), profile, analysis, "conflicting question", "conflicting evidence");
                        fail("Conflicting effort must be rejected before sending: " + protocol);
                    } catch (IllegalArgumentException expected) {
                        assertTrue(expected.getMessage().contains("推理强度"));
                    }
                    assertEquals("Validation must not contact the model", 0, server.getRequestCount());
                }
            }
        }
    }

    @Test public void explicitEffortRejectsMalformedNestedContainersBeforeAnyRequest() throws Exception {
        for (String protocol : new String[]{AiModelConfig.RESPONSES, AiModelConfig.ANTHROPIC, AiModelConfig.GEMINI}) {
            try (MockWebServer server = new MockWebServer()) {
                for (boolean streaming : new boolean[]{false, true}) {
                    String key = AiModelConfig.RESPONSES.equals(protocol) ? "reasoning"
                            : AiModelConfig.ANTHROPIC.equals(protocol) ? "output_config" : "generationConfig";
                    for (Object invalid : new Object[]{JSONObject.NULL, "not an object", new JSONArray()}) {
                        JSONObject custom = new JSONObject().put(key, invalid);
                        server.enqueue(json(protocol, "must not be requested"));
                        try {
                            invoke(new ChatCompletionClient(), settings(server, protocol, streaming, "medium", custom),
                                    false, "invalid question", "invalid evidence");
                            fail("Malformed reasoning container must be rejected");
                        } catch (IllegalArgumentException expected) {
                            assertTrue(expected.getMessage().contains("自定义参数"));
                        }
                        assertEquals(0, server.getRequestCount());
                    }
                }
            }
        }
    }

    @Test public void explicitEffortMergesNestedSiblingsInsteadOfDroppingCustomConfiguration() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            for (boolean streaming : new boolean[]{false, true}) for (boolean analysis : new boolean[]{false, true}) {
                JSONObject custom = customSiblings(protocol);
                String answer = analysis ? analysisResult("merged analysis") : "merged result";
                server.enqueue(json(protocol, answer));
                assertEquals(answer, invoke(new ChatCompletionClient(), settings(server, protocol, streaming, "medium", custom),
                        analysis, "merged question", "merged evidence"));
                JSONObject body = new JSONObject(take(server).getBody().readUtf8());
                assertEffort(body, protocol, "medium");
                assertEquals("retained", body.getString("custom_fixture"));
                if (AiModelConfig.RESPONSES.equals(protocol)) {
                    assertEquals("auto", body.getJSONObject("reasoning").getString("summary"));
                } else if (AiModelConfig.ANTHROPIC.equals(protocol)) {
                    assertEquals("json_schema", body.getJSONObject("output_config").getJSONObject("format").getString("type"));
                    assertEquals("object", body.getJSONObject("output_config").getJSONObject("format").getJSONObject("schema").getString("type"));
                } else if (AiModelConfig.GEMINI.equals(protocol)) {
                    JSONObject generation = body.getJSONObject("generationConfig");
                    assertEquals(0.26, generation.getDouble("temperature"), 0.0001);
                    assertEquals(18, generation.getInt("topK"));
                    assertTrue(generation.getJSONObject("thinkingConfig").getBoolean("includeThoughts"));
                } else assertEquals(0.26, body.getDouble("temperature"), 0.0001);
                assertPromptAndDocument(body, "merged question", "merged evidence");
            }
        }
    }

    @Test public void defaultDoesNotAddAnEffortLeafToCustomSiblingContainers() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            for (boolean streaming : new boolean[]{false, true}) {
                server.enqueue(json(protocol, "default result"));
                assertEquals("default result", invoke(new ChatCompletionClient(),
                        settings(server, protocol, streaming, "default", customSiblings(protocol)), false, "default question", "default evidence"));
                JSONObject body = new JSONObject(take(server).getBody().readUtf8());
                assertEffort(body, protocol, null);
                assertEquals("retained", body.getString("custom_fixture"));
            }
        }
    }

    @Test public void analysisIsNotRememberedWhenEffortAndResponseModeAreEnabled() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) for (boolean streaming : new boolean[]{false, true}) {
            try (MockWebServer server = new MockWebServer()) {
                AiModelSettings profile = settings(server, protocol, streaming, "high", new JSONObject());
                ChatCompletionClient client = new ChatCompletionClient();
                server.enqueue(json(protocol, analysisResult("PRIVATE first analysis result")));
                server.enqueue(json(protocol, analysisResult("second analysis result")));
                server.enqueue(json(protocol, "ordinary result"));
                invoke(client, profile, true, "PRIVATE first analysis question", "PRIVATE first evidence");
                take(server);
                invoke(client, profile, true, "second analysis question", "second evidence");
                JSONObject second = new JSONObject(take(server).getBody().readUtf8());
                assertFalse(second.toString().contains("PRIVATE"));
                invoke(client, profile, false, "ordinary question", "ordinary evidence");
                JSONObject ordinary = new JSONObject(take(server).getBody().readUtf8());
                assertFalse(ordinary.toString().contains("analysis question"));
                assertFalse(ordinary.toString().contains("analysis result"));
                assertPromptAndDocument(ordinary, "ordinary question", "ordinary evidence");
                assertEffort(ordinary, protocol, "high");
            }
        }
    }

    private static String invoke(ChatCompletionClient client, AiModelSettings profile, boolean analysis,
                                 String prompt, String document) throws Exception {
        return analysis ? client.replyStructuredAnalysis("same conversation", prompt, profile, "SECONDARY", document)
                : client.reply("same conversation", prompt, Collections.emptyList(), profile, "SECONDARY", true, document);
    }

    private static AiModelSettings settings(MockWebServer server, String protocol, boolean streaming, String effort,
                                            JSONObject custom) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "same model").put("name", "Fixture")
                .put("protocol", protocol).put("apiEndpoint", server.url(AiModelConfig.GEMINI.equals(protocol) ? "/v1beta" : "/v1").toString())
                .put("modelName", "fixture-model").put("apiKey", "fixture-key").put("allowInsecureHttp", true)
                .put("contextRounds", 2).put("systemPrompt", "PRIMARY").put("customParameters", custom.toString())
                .put("responseMode", streaming ? "streaming" : "non_streaming").put("reasoningEffort", effort));
        return new AiModelSettings(Collections.singletonList(config), config.id);
    }

    private static JSONObject customEffort(String protocol, Object level) throws Exception {
        switch (protocol) {
            case AiModelConfig.CHAT: return new JSONObject().put("reasoning_effort", level);
            case AiModelConfig.RESPONSES: return new JSONObject().put("reasoning", new JSONObject().put("effort", level));
            case AiModelConfig.ANTHROPIC: return new JSONObject().put("output_config", new JSONObject().put("effort", level));
            case AiModelConfig.GEMINI: return new JSONObject().put("generationConfig", new JSONObject()
                    .put("thinkingConfig", new JSONObject().put("thinkingLevel", level)));
            default: throw new AssertionError(protocol);
        }
    }

    private static String[] levels(String protocol) {
        if (AiModelConfig.CHAT.equals(protocol) || AiModelConfig.RESPONSES.equals(protocol))
            return new String[]{"default", "none", "minimal", "low", "medium", "high", "xhigh", "max"};
        if (AiModelConfig.ANTHROPIC.equals(protocol))
            return new String[]{"default", "low", "medium", "high", "xhigh", "max"};
        return new String[]{"default", "minimal", "low", "medium", "high"};
    }

    private static JSONObject customSiblings(String protocol) throws Exception {
        JSONObject custom = new JSONObject().put("custom_fixture", "retained");
        switch (protocol) {
            case AiModelConfig.CHAT: return custom.put("temperature", 0.26);
            case AiModelConfig.RESPONSES: return custom.put("reasoning", new JSONObject().put("summary", "auto"));
            case AiModelConfig.ANTHROPIC: return custom.put("output_config", new JSONObject().put("format", new JSONObject()
                    .put("type", "json_schema").put("schema", new JSONObject().put("type", "object"))));
            case AiModelConfig.GEMINI: return custom.put("generationConfig", new JSONObject().put("temperature", 0.26)
                    .put("topK", 18).put("thinkingConfig", new JSONObject().put("includeThoughts", true)));
            default: throw new AssertionError(protocol);
        }
    }

    private static void assertEffort(JSONObject body, String protocol, String level) throws Exception {
        JSONObject container;
        String leaf;
        switch (protocol) {
            case AiModelConfig.CHAT: container = body; leaf = "reasoning_effort"; break;
            case AiModelConfig.RESPONSES: container = body.optJSONObject("reasoning"); leaf = "effort"; break;
            case AiModelConfig.ANTHROPIC: container = body.optJSONObject("output_config"); leaf = "effort"; break;
            case AiModelConfig.GEMINI:
                JSONObject generation = body.optJSONObject("generationConfig");
                container = generation == null ? null : generation.optJSONObject("thinkingConfig"); leaf = "thinkingLevel"; break;
            default: throw new AssertionError(protocol);
        }
        if (level == null) assertTrue("Model default must not send a reasoning effort", container == null || !container.has(leaf));
        else { assertNotNull(container); assertEquals(level, container.getString(leaf)); }
        if (!AiModelConfig.CHAT.equals(protocol)) assertFalse(body.has("reasoning_effort"));
        if (!AiModelConfig.RESPONSES.equals(protocol)) assertFalse(body.has("reasoning"));
        if (!AiModelConfig.ANTHROPIC.equals(protocol)) assertFalse(body.has("output_config"));
        if (!AiModelConfig.GEMINI.equals(protocol)) assertFalse(body.has("generationConfig"));
    }

    private static void assertResponseMode(RecordedRequest request, JSONObject body, String protocol, boolean streaming) throws Exception {
        if (AiModelConfig.GEMINI.equals(protocol)) {
            assertEquals(streaming ? "/v1beta/models/fixture-model:streamGenerateContent?alt=sse"
                    : "/v1beta/models/fixture-model:generateContent", request.getPath());
            assertFalse(body.has("stream"));
        } else assertEquals(streaming, body.getBoolean("stream"));
        if (streaming) assertEquals("text/event-stream", request.getHeader("Accept"));
    }

    private static void assertPromptAndDocument(JSONObject body, String prompt, String document) {
        String serialized = body.toString();
        assertTrue(serialized.contains("PRIMARY"));
        assertTrue(serialized.contains("SECONDARY"));
        assertTrue(serialized.contains(prompt));
        assertTrue(serialized.contains(document));
    }

    private static RecordedRequest take(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("Expected one local request", request);
        return request;
    }

    private static String analysisResult(String text) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"overview", "trend", "types", "authors", "topics", "features", "hours",
                "portraitTitle", "portrait", "contrasts", "questions"}) result.put(key, text);
        return result.toString();
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
}

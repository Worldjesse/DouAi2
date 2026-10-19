package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.LocalOptions;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises the production generator against the parent automatic-reply configuration. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class CommentReplySharedSettingsTest {
    @Test public void mediaRequestsUseRealImagesAndRememberThemOnlyAfterNativeAcknowledgement() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "ai");
            LocalOptions options = new LocalOptions(values);
            ChatImage image = new ChatImage("image/png", new byte[]{1, 2, 3, 4});
            CommentReplyEvent first = event("first", "这张怎么样", "我的原评");
            String prompt = CommentReplyHook.input(first, "对方评论附图");
            AtomicInteger sent = new AtomicInteger(-1);
            server.enqueue(answer("针对附图的回复"));
            generator.generate(100, first, options, Collections.singletonList(image), prompt, sent::set);
            JSONArray firstMessages = messages(server);
            JSONArray content = firstMessages.getJSONObject(1).getJSONArray("content");
            assertEquals(2, content.length());
            String actualInput = content.getJSONObject(0).getString("text");
            assertTrue(actualInput.endsWith(prompt));
            assertTrue(content.getJSONObject(0).getString("text").contains("实际附带的视觉素材：对方评论附图"));
            assertEquals(image.dataUrl(), content.getJSONObject(1).getJSONObject("image_url").getString("url"));
            assertEquals(1, sent.get());

            CommentReplyEvent second = event("second", "下一条文字回复", "我的原评");
            server.enqueue(answer("还未确认发送"));
            generator.generate(100, second, options);
            JSONArray beforeAck = messages(server);
            assertEquals(2, beforeAck.length());
            assertFalse(beforeAck.toString().contains("\"image_url\""));

            generator.acknowledged(100, first, options, "针对附图的回复", Collections.singletonList(image), prompt);
            server.enqueue(answer("已确认的后续回复"));
            generator.generate(100, second, options);
            JSONArray afterAck = messages(server);
            assertEquals(4, afterAck.length());
            assertEquals(image.dataUrl(), afterAck.getJSONObject(1).getJSONArray("content")
                    .getJSONObject(1).getJSONObject("image_url").getString("url"));
            assertEquals(prompt, afterAck.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"));
            assertEquals("针对附图的回复", afterAck.getJSONObject(2).getString("content"));
            assertFalse(afterAck.toString().contains("还未确认发送"));

            // Settings changes call cancel(), which must discard earlier visual context as well.
            generator.cancel();
            values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, false);
            server.enqueue(answer("图片关闭后的回复"));
            generator.generate(100, second, new LocalOptions(values));
            JSONArray afterCancel = messages(server);
            assertEquals(2, afterCancel.length());
            assertFalse(afterCancel.toString().contains("\"image_url\""));
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void aiUsesParentPrimaryAndReplyPromptsWithBothCommentsAndIgnoresOldIndependentPrompt() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            server.enqueue(answer("共享模型生成的回复"));
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "ai");
            values.put("reply_prompt", "父自动回复任务提示");
            CommentReplyEvent incoming = event("incoming", "对方刚收到的回复正文", "我原来的评论内容");
            CommentReplyHook.Generated generated = generator.generate(100,
                    incoming, new LocalOptions(values));
            assertEquals("共享模型生成的回复", generated.text);
            assertEquals("parent-reply-model", generated.model);
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/v1/chat/completions", request.getPath());
            assertEquals("Bearer parent-key", request.getHeader("Authorization"));
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertEquals("parent-reply-model", body.getString("model"));
            JSONArray messages = body.getJSONArray("messages");
            assertEquals(2, messages.length());
            assertEquals("system", messages.getJSONObject(0).getString("role"));
            assertEquals("父模型一级提示词", messages.getJSONObject(0).getString("content"));
            assertEquals("user", messages.getJSONObject(1).getString("role"));
            String input = messages.getJSONObject(1).getString("content");
            assertTrue(input.contains("父自动回复任务提示"));
            assertFalse(input.contains("作品："));
            assertFalse(input.contains("作品说明"));
            assertEquals("The comment reply prompt must not read the original work description", 0,
                    ((Work) incoming.work).descriptionReads);
            assertTrue(input.contains("我的原评论：我原来的评论内容"));
            assertTrue(input.contains("对方的回复：对方刚收到的回复正文"));
            assertFalse(body.toString().contains("旧独立评论回复提示不得使用"));
            assertFalse(body.toString().contains("刷视频自动评论提示不得使用"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void sameThreadGenerationsDoNotRememberEarlierInputOrUnsentAiOutput() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            server.enqueue(answer("前一次尚未发送的回答"));
            server.enqueue(answer("本次回答"));
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "ai");
            values.put("reply_prompt", "共享任务提示");
            LocalOptions options = new LocalOptions(values);
            generator.generate(100, event("first", "上一条对方输入", "上一条自己的原评"), options);
            generator.generate(100, event("second", "本次对方输入", "本次自己的原评"), options);
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(second);
            JSONArray messages = new JSONObject(second.getBody().readUtf8()).getJSONArray("messages");
            assertEquals(2, messages.length());
            String requestText = messages.toString();
            assertTrue(requestText.contains("本次对方输入"));
            assertTrue(requestText.contains("本次自己的原评"));
            assertFalse(requestText.contains("上一条对方输入"));
            assertFalse(requestText.contains("上一条自己的原评"));
            assertFalse(requestText.contains("前一次尚未发送的回答"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void acknowledgedRepliesUseParentRoundLimitAndStayIsolatedByRootAccountAndPrivateChat() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            Map<String, Object> values = parentOptions(server, 1);
            values.put("reply_mode", "ai");
            values.put("reply_prompt", "共享回复提示");
            LocalOptions options = new LocalOptions(values);
            AiModelSettings models = AiModelSettings.read(options);
            ChatCompletionClient privateChat = new ChatCompletionClient();
            privateChat.rememberReply("reply:100:root", "私信历史输入", Collections.emptyList(), models,
                    "共享回复提示", "私信已发送回答");

            CommentReplyEvent first = event("first", "第一条评论来评", "自己的第一条原评");
            server.enqueue(answer("评论已发送回答一"));
            CommentReplyHook.Generated firstReply = generator.generate(100, first, options);
            assertEquals(2, messages(server).length());
            generator.acknowledged(100, first, options, firstReply.text);

            CommentReplyEvent second = event("second", "第二条评论来评", "自己的第二条原评");
            server.enqueue(answer("评论已发送回答二"));
            CommentReplyHook.Generated secondReply = generator.generate(100, second, options);
            JSONArray sameThread = messages(server);
            assertEquals(4, sameThread.length());
            assertEquals("assistant", sameThread.getJSONObject(2).getString("role"));
            assertEquals("评论已发送回答一", sameThread.getJSONObject(2).getString("content"));
            assertTrue(sameThread.toString().contains("第一条评论来评"));
            assertFalse(sameThread.toString().contains("私信已发送回答"));
            generator.acknowledged(100, second, options, secondReply.text);

            server.enqueue(answer("本轮生成但未发送"));
            generator.generate(100, event("third", "第三条评论来评", "自己的第三条原评"), options);
            JSONArray limited = messages(server);
            assertEquals(4, limited.length());
            assertEquals("评论已发送回答二", limited.getJSONObject(2).getString("content"));
            assertFalse(limited.toString().contains("评论已发送回答一"));
            assertFalse(limited.toString().contains("第一条评论来评"));

            CommentReplyEvent otherRoot = new CommentReplyEvent("notice-other", "work", "other", "different-root",
                    "200", "对方", "另一根评论来评", "另一根自己的原评", 1_780_000_000L, new Work(), new Object());
            server.enqueue(answer("另一根回复"));
            generator.generate(100, otherRoot, options);
            JSONArray rootIsolated = messages(server);
            assertEquals(2, rootIsolated.length());
            assertFalse(rootIsolated.toString().contains("评论已发送回答"));

            server.enqueue(answer("另一账号回复"));
            generator.generate(101, event("another-account", "另一账号来评", "另一账号原评"), options);
            JSONArray accountIsolated = messages(server);
            assertEquals(2, accountIsolated.length());
            assertFalse(accountIsolated.toString().contains("评论已发送回答"));

            server.enqueue(answer("私信本轮回答"));
            privateChat.reply("reply:100:root", "私信本轮输入", Collections.emptyList(), models, "共享回复提示", false);
            JSONArray privateMessages = messages(server);
            assertEquals(4, privateMessages.length());
            assertEquals("私信已发送回答", privateMessages.getJSONObject(2).getString("content"));
            assertFalse(privateMessages.toString().contains("评论已发送回答"));
            assertFalse(privateMessages.toString().contains("本轮生成但未发送"));
            assertEquals(6, server.getRequestCount());
        }
    }

    @Test public void defaultAndExplicitFixedModesUseParentAutoMessageTextWithoutCallingAi() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            Map<String, Object> values = parentOptions(server);
            values.put("auto_message_text", "  父自动回复固定正文  ");
            for (boolean explicit : new boolean[]{false, true}) {
                if (explicit) values.put("reply_mode", "fixed");
                CommentReplyHook.Generated generated = generator.generate(100,
                        event("incoming", "收到消息", "自己的原评"), new LocalOptions(values));
                assertEquals("父自动回复固定正文", generated.text);
                assertEquals("", generated.model);
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void keywordModeMatchesIncomingReplyThroughParentKeywordRules() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "keyword");
            values.put("reply_keywords", new JSONArray().put(new JSONObject()
                    .put("keyword", "THANKS").put("reply", "  父关键词回复  ")).toString());
            CommentReplyHook.Generated matched = generator.generate(100,
                    event("incoming", "thanks, 明白了", "我原来的评论"), new LocalOptions(values));
            assertEquals("父关键词回复", matched.text);
            assertEquals("", matched.model);
            CommentReplyHook.Generated unmatched = generator.generate(100,
                    event("other", "没有命中词", "THANKS 仅在自己的原评中"), new LocalOptions(values));
            assertEquals("", unmatched.text);
            assertEquals("", unmatched.model);
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void randomModeUsesParentReplyPoolAndEmptyPoolReturnsEmptyWithoutAiFallback() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "random");
            values.put("reply_random", new JSONArray().put("  父随机回复  ").toString());
            CommentReplyHook.Generated selected = generator.generate(100,
                    event("incoming", "收到回复", "自己的原评"), new LocalOptions(values));
            assertEquals("父随机回复", selected.text);
            assertEquals("", selected.model);
            values.put("reply_random", "[]");
            CommentReplyHook.Generated empty = generator.generate(100,
                    event("other", "另一条回复", "自己的原评"), new LocalOptions(values));
            assertEquals("", empty.text);
            assertEquals("", empty.model);
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void bothCommentsFitTheRealClientBudgetWithoutReadingOrIncludingTheOriginalWork() throws Exception {
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator()) {
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "ai");
            // The client guards the business input before wrapping a secondary task prompt.
            // Other tests cover parent prompt composition; here the captured user content is that input itself.
            values.put("reply_prompt", "");
            LocalOptions options = new LocalOptions(values);
            String description = "作".repeat(2000);
            Work work = new Work(description);
            String original = "原".repeat(1000);
            String incoming = "来".repeat(1000);
            CommentReplyEvent ordinary = new CommentReplyEvent("long-notice", "work", "long", "root", "200", "对方",
                    incoming, original, 1_780_000_000L, work, new Object());
            assertTrue(CommentReplyHook.input(ordinary).length() <= 4000);
            server.enqueue(answer("长上下文也能成功回复"));
            assertEquals("长上下文也能成功回复", generator.generate(100, ordinary, options).text);
            String captured = messages(server).getJSONObject(1).getString("content");
            assertTrue(captured.length() <= 4000);
            assertTrue(captured.contains("我的原评论：" + original));
            assertTrue(captured.contains("对方的回复：" + incoming));
            assertFalse(captured.contains("作品："));
            assertFalse(captured.contains(description));

            CommentReplyEvent oversized = new CommentReplyEvent("oversized-notice", "work", "oversized", "root", "200", "对方",
                    "来".repeat(10_000), "原".repeat(10_000), 1_780_000_000L, work, new Object());
            assertTrue(CommentReplyHook.input(oversized).length() <= 4000);
            server.enqueue(answer("超长上下文安全裁剪"));
            assertEquals("超长上下文安全裁剪", generator.generate(100, oversized, options).text);
            String bounded = messages(server).getJSONObject(1).getString("content");
            assertTrue(bounded.length() <= 4000);
            assertTrue(bounded.contains("我的原评论：" + "原".repeat(1000)));
            assertTrue(bounded.contains("对方的回复：" + "来".repeat(2400)));
            assertFalse(bounded.contains("作品："));
            assertEquals("Neither ordinary nor oversized comment inputs may read the original work", 0, work.descriptionReads);
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void sharedAiSuccessLogsTheRealRequestStagesWithoutModelOrPayload() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator(diagnostics::add)) {
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "ai");
            values.put("reply_prompt", "private-secondary-prompt");
            server.enqueue(answer("private-generated-result"));

            CommentReplyHook.Generated generated = generator.generate(100,
                    event("diagnostic-success", "private-incoming-text", "private-original-text"), new LocalOptions(values));

            assertEquals("private-generated-result", generated.text);
            assertEquals(1, server.getRequestCount());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            assertSharedAiDiagnostics(diagnostics, 200, server.url("/v1").toString());
        }
    }

    @Test public void sharedAiHttpFailureStillLogsSentAndResponseStatusWithoutServerBody() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        try (MockWebServer server = new MockWebServer();
             CommentReplyHook.SharedReplyGenerator generator = new CommentReplyHook.SharedReplyGenerator(diagnostics::add)) {
            Map<String, Object> values = parentOptions(server);
            values.put("reply_mode", "ai");
            values.put("reply_prompt", "private-secondary-prompt");
            server.enqueue(new MockResponse().setResponseCode(401).setBody("private-server-error-body"));

            ChatCompletionClient.ReplyFailure failure = assertThrows(ChatCompletionClient.ReplyFailure.class,
                    () -> generator.generate(100, event("diagnostic-http-failure", "private-incoming-text",
                            "private-original-text"), new LocalOptions(values)));

            assertEquals(401, failure.httpStatus());
            assertEquals(1, server.getRequestCount());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            assertSharedAiDiagnostics(diagnostics, 401, server.url("/v1").toString());
        }
    }

    @Test public void generationFailureDiagnosticMapsKnownMediaReasonAndIncludesOnlyASafeModuleSite() {
        IllegalArgumentException failure = new IllegalArgumentException("视频播放地址无法读取",
                new IllegalStateException("token=private-cause-token; https://private-cause.example/api"));
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("external.private.Text", "privateMethod", "private-file", 12),
                new StackTraceElement("com.douai.module.feature.AutomationReplyMedia", "load", "AutomationReplyMedia.java", 321)
        });

        String diagnostic = CommentReplyHook.generationFailureDiagnostic(failure);

        assertTrue(diagnostic, diagnostic.contains("IllegalArgumentException; reason=video_source_unavailable"));
        assertTrue(diagnostic, diagnostic.contains("site=com.douai.module.feature.AutomationReplyMedia.load:321"));
        assertFalse(diagnostic.contains("视频播放地址无法读取"));
        assertFalse(diagnostic.contains("private-cause-token"));
        assertFalse(diagnostic.contains("private-cause.example"));
        assertFalse(diagnostic.contains("external.private"));
    }

    @Test public void generationFailureDiagnosticDropsUnknownMessagesAndInvalidModuleSymbols() {
        IllegalArgumentException failure = new IllegalArgumentException("key=private-exception-key; private-request-text",
                new IllegalStateException("token=private-cause-token"));
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.douai.module.feature.Unsafe?key=private-site-key", "load", "ignored.java", 18),
                new StackTraceElement("com.douai.module.feature.AutomationReplyMedia", "load\nprivate-method-secret", "ignored.java", 19)
        });

        String diagnostic = CommentReplyHook.generationFailureDiagnostic(failure);

        assertEquals("IllegalArgumentException; reason=unexpected", diagnostic);
    }

    private static void assertSharedAiDiagnostics(List<String> diagnostics, int status, String endpoint) {
        assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(value -> value.contains("stage=prepare status=0")));
        assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(value -> value.contains("stage=sent status=0")));
        assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(value -> value.contains("stage=response status=" + status)));
        for (String value : diagnostics) {
            assertTrue(value, value.startsWith("Comment reply AI "));
            assertTrue(value, value.matches(".* status=\\d+"));
            for (String secret : List.of("parent-key", "Bearer", "parent-reply-model", "Parent reply model",
                    endpoint, "http://", "父模型一级提示词", "private-secondary-prompt", "private-incoming-text",
                    "private-original-text", "private-generated-result", "private-server-error-body")) {
                assertFalse("AI diagnostics must omit configuration and payload: " + value, value.contains(secret));
            }
        }
    }

    private static Map<String, Object> parentOptions(MockWebServer server) throws Exception {
        return parentOptions(server, 5);
    }

    private static Map<String, Object> parentOptions(MockWebServer server, int rounds) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "parent-model")
                .put("name", "Parent reply model").put("protocol", AiModelConfig.CHAT)
                .put("apiEndpoint", server.url("/v1").toString()).put("modelName", "parent-reply-model")
                .put("apiKey", "parent-key").put("allowInsecureHttp", true)
                .put("systemPrompt", "父模型一级提示词").put("contextRounds", rounds));
        Map<String, Object> values = new HashMap<>();
        values.put(AiModelSettings.KEY, new AiModelSettings(Collections.singletonList(config), config.id).serialize());
        values.put("comment_reply_prompt", "旧独立评论回复提示不得使用");
        values.put("comment_prompt", "刷视频自动评论提示不得使用");
        return values;
    }

    private static JSONArray messages(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return new JSONObject(request.getBody().readUtf8()).getJSONArray("messages");
    }

    private static MockResponse answer(String text) throws Exception {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(new JSONObject()
                .put("choices", new JSONArray().put(new JSONObject()
                        .put("message", new JSONObject().put("content", text)))).toString());
    }

    private static CommentReplyEvent event(String id, String text, String original) {
        return new CommentReplyEvent("notice-" + id, "work", id, "root", "200", "对方", text, original,
                1_780_000_000L, new Work(), new Object());
    }

    public static final class Work {
        private final String description;
        int descriptionReads;
        Work() { this("作品说明"); }
        Work(String description) { this.description = description; }
        public String getDesc() { descriptionReads++; return description; }
    }
}

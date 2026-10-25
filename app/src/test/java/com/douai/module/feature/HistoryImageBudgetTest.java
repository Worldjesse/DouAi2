package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HistoryImageBudgetTest {
    @Test public void crossClientEvictionRemovesTheOldestImageTurnTogetherWithItsAnswerAndKeepsText() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(answer()); server.enqueue(answer()); server.start();
            AiModelSettings settings = settings(server, 10);
            HistoryImageBudget budget = new HistoryImageBudget(16);
            ChatCompletionClient first = new ChatCompletionClient(null, null, budget);
            ChatCompletionClient second = new ChatCompletionClient(null, null, budget);
            first.rememberReply("thread", "retained-text", Collections.emptyList(), settings, "", "text-answer");
            rememberImage(first, settings, "old-image", "old-image-answer", 1);
            rememberImage(second, settings, "other-image", "other-image-answer", 2);
            rememberImage(first, settings, "new-image", "new-image-answer", 3);
            assertEquals(16, budget.usedBytes());
            assertEquals(2, budget.retainedTurns());

            first.reply("thread", "followup", Collections.emptyList(), settings, "", false);
            String firstBody = body(server).toString();
            assertTrue(firstBody.contains("retained-text")); assertTrue(firstBody.contains("text-answer"));
            assertTrue(firstBody.contains("new-image-answer"));
            assertFalse(firstBody.contains("old-image")); assertFalse(firstBody.contains("old-image-answer"));
            second.reply("thread", "followup", Collections.emptyList(), settings, "", false);
            assertTrue(body(server).toString().contains("other-image-answer"));
            first.clearHistory(); second.clearHistory();
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test public void clearAndRoundEvictionReleaseTheirReservationsExactlyOnce() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            AiModelSettings settings = settings(server, 1);
            HistoryImageBudget budget = new HistoryImageBudget(24);
            ChatCompletionClient first = new ChatCompletionClient(null, null, budget);
            ChatCompletionClient second = new ChatCompletionClient(null, null, budget);
            rememberImage(first, settings, "first", "first-answer", 1);
            rememberImage(second, settings, "second", "second-answer", 2);
            assertEquals(16, budget.usedBytes());
            first.clearHistory(); first.clearHistory();
            assertEquals(8, budget.usedBytes());
            second.rememberReply("thread", "text-replacement", Collections.emptyList(), settings, "", "text-answer");
            assertEquals(0, budget.usedBytes());
            assertEquals(0, budget.retainedTurns());
        }
    }

    @Test public void simultaneousClientsCanEvictEachOtherWithoutTakingEachOthersRequestLocks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            AiModelSettings settings = settings(server, 10);
            HistoryImageBudget budget = new HistoryImageBudget(16);
            ChatCompletionClient first = new ChatCompletionClient(null, null, budget);
            ChatCompletionClient second = new ChatCompletionClient(null, null, budget);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService writers = Executors.newFixedThreadPool(2);
            try {
                Future<?> one = writers.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) rememberImage(first, settings, "one-" + i, "answer", 1);
                    return null;
                });
                Future<?> two = writers.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) rememberImage(second, settings, "two-" + i, "answer", 2);
                    return null;
                });
                start.countDown(); one.get(5, TimeUnit.SECONDS); two.get(5, TimeUnit.SECONDS);
                assertEquals(16, budget.usedBytes());
                assertEquals(2, budget.retainedTurns());
            } finally { start.countDown(); writers.shutdownNow(); first.clearHistory(); second.clearHistory(); }
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test public void aTurnLargerThanTheGlobalBudgetIsStillSentButNeverRetained() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(answer()); server.enqueue(answer()); server.start();
            AiModelSettings settings = settings(server, 10);
            HistoryImageBudget budget = new HistoryImageBudget(4);
            ChatCompletionClient client = new ChatCompletionClient(null, null, budget);
            ChatImage image = image(1);
            client.reply("thread", "current-image", Collections.singletonList(image), settings);
            assertTrue(body(server).toString().contains(image.base64Data));
            assertEquals(0, budget.usedBytes());
            client.reply("thread", "fresh-question", Collections.emptyList(), settings, "", false);
            JSONArray messages = body(server).getJSONArray("messages");
            assertEquals("Evict the entire image-bearing pair", 2, messages.length());
            assertEquals("fresh-question", messages.getJSONObject(1).getString("content"));
        }
    }

    @Test public void bookkeepingDoesNotKeepAnAbandonedClientOrItsImagesAlive() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            HistoryImageBudget budget = new HistoryImageBudget(16);
            WeakReference<?>[] abandoned = abandonedClient(budget, settings(server, 10));
            assertEquals(8, budget.usedBytes());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ((abandoned[0].get() != null || abandoned[1].get() != null || budget.usedBytes() != 0)
                    && System.nanoTime() < until) {
                System.gc(); System.runFinalization(); Thread.sleep(10);
            }
            assertNull("Global bookkeeping must not pin the client", abandoned[0].get());
            assertNull("Global bookkeeping must not retain a dead owner's image", abandoned[1].get());
            assertEquals(0, budget.usedBytes());
            assertEquals(0, budget.retainedTurns());
        }
    }

    @Test public void anAlreadyCapturedRequestKeepsItsContextWhenAnotherClientEvictsTheStoredTurn() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(answer()); server.enqueue(answer()); server.start();
            AiModelSettings settings = settings(server, 10);
            HistoryImageBudget budget = new HistoryImageBudget(8);
            CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean firstPrepare = new AtomicBoolean(true);
            ChatCompletionClient first = new ChatCompletionClient(null, event -> {
                if (event.contains("stage=prepare") && firstPrepare.compareAndSet(true, false)) {
                    captured.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
            }, budget);
            ChatCompletionClient second = new ChatCompletionClient(null, null, budget);
            rememberImage(first, settings, "captured-image", "captured-answer", 1);
            ExecutorService requests = Executors.newSingleThreadExecutor();
            try {
                Future<String> pending = requests.submit(() -> first.reply("thread", "already-captured",
                        Collections.emptyList(), settings, "", false));
                assertTrue(captured.await(3, TimeUnit.SECONDS));
                rememberImage(second, settings, "other-client-image", "other-client-answer", 2);
                assertEquals(8, budget.usedBytes());
                release.countDown();
                assertEquals("answer", pending.get(5, TimeUnit.SECONDS));
                String sending = body(server).toString();
                assertTrue("The provider sees the frozen request context", sending.contains("captured-answer"));
                assertTrue(sending.contains("captured-image"));
                first.reply("thread", "after-eviction", Collections.emptyList(), settings, "", false);
                String later = body(server).toString();
                assertFalse(later.contains("captured-image")); assertFalse(later.contains("captured-answer"));
            } finally {
                release.countDown(); requests.shutdownNow(); first.clearHistory(); second.clearHistory();
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    private static WeakReference<?>[] abandonedClient(HistoryImageBudget budget, AiModelSettings settings) throws Exception {
        ChatCompletionClient client = new ChatCompletionClient(null, null, budget);
        ChatImage image = image(1);
        client.rememberReply("thread", "old", Collections.singletonList(image), settings, "", "old-answer");
        return new WeakReference<?>[]{new WeakReference<>(client), new WeakReference<>(image)};
    }

    private static void rememberImage(ChatCompletionClient client, AiModelSettings settings,
                                      String prompt, String answer, int value) throws Exception {
        client.rememberReply("thread", prompt, Collections.singletonList(image(value)), settings, "", answer);
    }

    private static ChatImage image(int value) { return new ChatImage("image/png", new byte[]{(byte) value}); }

    private static JSONObject body(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        return new JSONObject(request.getBody().readUtf8());
    }

    private static MockResponse answer() {
        return new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}");
    }

    private static AiModelSettings settings(MockWebServer server, int rounds) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "budget-test")
                .put("name", "Budget test").put("provider", "test").put("protocol", AiModelConfig.CHAT)
                .put("apiEndpoint", server.url("/v1").toString()).put("modelName", "model-a")
                .put("apiKey", "fixture-key").put("allowInsecureHttp", true)
                .put("contextRounds", rounds).put("systemPrompt", "Reply concisely"));
        return new AiModelSettings(Collections.singletonList(config), config.id);
    }
}

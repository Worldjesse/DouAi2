package com.douai.module.diagnostics;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises the real evidence worker, private-file store and framework sink together. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LogModeDeliveryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final List<String> framework = new CopyOnWriteArrayList<>();
    private Field storeField;
    private DebugLogStore previousStore, store;
    private DouAiLog.Sink previousSink;
    private boolean previousMode;
    private CountDownLatch release;
    private CountDownLatch inFlightCompleted;
    private Thread modeToggle;

    @Before public void connectBothRealDeliveryChannels() throws Exception {
        assertTrue(DouAiLog.awaitIdle(5000));
        previousMode = DebugLog.isEnabled();
        storeField = DebugLog.class.getDeclaredField("store"); storeField.setAccessible(true);
        previousStore = (DebugLogStore) storeField.get(null);
        Field sinkField = DouAiLog.class.getDeclaredField("framework"); sinkField.setAccessible(true);
        previousSink = (DouAiLog.Sink) sinkField.get(null);
        store = new DebugLogStore(temporary.newFolder("private-evidence"));
        storeField.set(null, store);
        DouAiLog.setFrameworkSink((priority, line) -> framework.add(line));
    }

    @After public void restoreProcessDiagnostics() throws Exception {
        if (release != null) release.countDown();
        try {
            if (modeToggle != null) modeToggle.join(5000);
            assertTrue("evidence worker must finish before restoring its sinks", DouAiLog.awaitIdle(5000));
        } finally {
            DouAiLog.setFrameworkSink(previousSink);
            if (storeField != null) storeField.set(null, previousStore);
            if (store != null) store.close();
            DebugLog.setEnabled(previousMode);
        }
    }

    @Test public void disabledModeWritesMatchingSimpleBusinessAndErrorEvidenceToBothChannels() throws Exception {
        DebugLog.setEnabled(false);
        DouAiLog.Operation root = DouAiLog.begin("test_delivery_simple", "user_request");
        root.wrap(() -> {
            root.stage("validate", "detail_stage_marker=true");
            root.configuration("cfg_selected", "test_live_read");
            DouAiLog.Operation child = DouAiLog.begin("test_delivery_simple_child", "nested_request");
            child.stage("nested", "child_detail_marker=true");
            child.finish(DouAiLog.Outcome.SUCCESS, "local_child_result", "child_returned=true");
            DouAiLog.hook("mode_delivery_simple_hook", "test_delivery_simple", "returned", "normal_hook_detail=true");
            DouAiLog.hookDetail("mode_delivery_simple_error", "test_delivery_simple", "threw",
                    "java.lang.String(len=100)", "unavailable", 123,
                    new IllegalStateException("PRIVATE SIMPLE ERROR BODY"));
            root.finish(DouAiLog.Outcome.FAILED, "callback_failed", "business_code=17;final_result=observed");
        }).run();

        List<JSONObject> events = matchingBothChannels(root.traceId());
        assertTrue(events.stream().anyMatch(e -> "operation_started".equals(e.optString("event"))));
        JSONObject terminal = event(events, "operation_finished");
        assertEquals("failed", terminal.getString("outcome"));
        assertEquals("callback_failed", terminal.getString("reason_code"));
        assertTrue(terminal.getString("evidence").contains("business_code=17"));
        assertTrue(terminal.has("error_ids"));
        JSONObject error = event(events, "hook_detail");
        assertEquals("java.lang.IllegalStateException", error.getString("exception_class"));
        assertEquals("mode_delivery_simple_error", error.getString("hook_id"));
        assertEquals("threw", error.getString("phase"));
        assertTrue(error.has("error_id")); assertTrue(error.has("exception_message"));
        for (JSONObject record : events) {
            assertEquals("simple", record.getString("log_mode"));
            for (String key : Arrays.asList("args", "result", "duration_us", "stack", "cause_chain", "configuration_selections")) {
                assertFalse(key + " must be absent from both simple channels", record.has(key));
            }
            assertFalse(record.getString("evidence").contains("stage_ms="));
        }
        assertFalse(events.stream().anyMatch(e -> Arrays.asList("stage", "stage_summary", "configuration_selected",
                "span_finished", "hook_returned").contains(e.optString("event"))));
        assertEquals(1, events.stream().filter(e -> "operation_started".equals(e.optString("event"))).count());
        assertFalse(String.join("\n", framework).contains("PRIVATE SIMPLE ERROR BODY"));
    }

    @Test public void enabledModeKeepsDetailedFieldsAndDisablingDoesNotDeleteHistoricalDetails() throws Exception {
        DebugLog.setEnabled(true);
        DouAiLog.Operation root = DouAiLog.begin("test_delivery_detailed", "user_request");
        root.wrap(() -> {
            root.stage("request", "bytes=100;business_result=pending");
            root.configuration("cfg_actual", "test_live_read");
            DouAiLog.hookDetail("mode_delivery_detailed_hook", "test_delivery_detailed", "returned",
                    "java.lang.String(len=100)", "java.lang.Boolean(false)", 750, null);
            root.error("request_failed", new IllegalStateException("PRIVATE DETAILED ERROR BODY",
                    new java.io.IOException("PRIVATE CAUSE BODY")));
            root.finish(DouAiLog.Outcome.FAILED, "network_failed", "business_result=failed");
        }).run();

        List<JSONObject> events = matchingBothChannels(root.traceId());
        for (JSONObject record : events) assertEquals("detail", record.getString("log_mode"));
        assertNotNull(event(events, "stage")); assertNotNull(event(events, "configuration_selected"));
        JSONObject hook = event(events, "hook_detail");
        assertEquals("java.lang.String(len=100)", hook.getString("args"));
        assertEquals("java.lang.Boolean(false)", hook.getString("result"));
        assertEquals("750", hook.getString("duration_us"));
        JSONObject error = event(events, "exception");
        assertTrue(error.getString("stack").length() > 0); assertTrue(error.getString("cause_chain").contains("IOException"));
        assertTrue(event(events, "operation_finished").has("configuration_selections"));
        String historicalId = error.getString("event_id");

        DebugLog.setEnabled(false);
        DouAiLog.Operation later = DouAiLog.begin("test_delivery_after_toggle", "user_request");
        later.finish(DouAiLog.Outcome.SUCCESS, "local_verified", "property_readback=true");
        matchingBothChannels(later.traceId());
        Map<String, JSONObject> disk = diskEvents();
        assertTrue("turning detail off must not delete already-persisted records", disk.containsKey(historicalId));
        assertEquals("detail", disk.get(historicalId).getString("log_mode"));
        assertTrue(disk.get(historicalId).has("stack"));
        assertFalse(readDisk().contains("PRIVATE DETAILED ERROR BODY")); assertFalse(readDisk().contains("PRIVATE CAUSE BODY"));
    }

    @Test public void recordsQueuedWhileEnabledAreRecheckedWhenDeliveredAfterDisabling() throws Exception {
        DebugLog.setEnabled(true);
        blockFrameworkDelivery();

        DouAiLog.Operation queued = DouAiLog.begin("test_delivery_queued", "user_request");
        queued.wrap(() -> {
            queued.stage("queued_stage", "queued_stage_detail_marker=true");
            queued.configuration("cfg_queued", "test_live_read");
            queued.error("queued_error", new IllegalArgumentException("PRIVATE QUEUED ERROR BODY"));
            queued.finish(DouAiLog.Outcome.FAILED, "queued_failure", "business_code=19;server_result=failed");
        }).run();
        disableAndConfirmDeliveryBarrier();

        List<JSONObject> events = matchingBothChannels(queued.traceId());
        assertEquals("expected root start, brief exception and root terminal", 3, events.size());
        for (JSONObject record : events) {
            assertEquals("simple", record.getString("log_mode"));
            assertFalse(record.has("stack")); assertFalse(record.has("cause_chain"));
            assertFalse(record.has("configuration_selections")); assertFalse(record.has("args"));
        }
        JSONObject terminal = event(events, "operation_finished");
        assertEquals("failed", terminal.getString("outcome")); assertEquals("queued_stage", terminal.getString("last_stage"));
        assertTrue(terminal.getString("evidence").contains("business_code=19"));
        assertFalse(terminal.getString("evidence").contains("stage_ms="));
        assertTrue(event(events, "exception").has("error_id"));
        assertFalse(events.stream().anyMatch(e -> "stage".equals(e.optString("event"))));
        assertFalse(readDisk().contains("queued_stage_detail_marker"));
        assertFalse(String.join("\n", framework).contains("PRIVATE QUEUED ERROR BODY"));
    }

    @Test public void queuedDetailProjectedToSimpleDoesNotConsumeTheSameThrowablesFirstDetailedContext() throws Exception {
        DebugLog.setEnabled(true);
        blockFrameworkDelivery();
        Throwable shared = new IllegalStateException("PRIVATE REUSED ERROR BODY",
                new java.io.IOException("PRIVATE REUSED CAUSE BODY"));
        DouAiLog.Operation queued = DouAiLog.begin("test_delivery_reused_before", "user_request");
        queued.error("queued_error", shared);
        queued.finish(DouAiLog.Outcome.FAILED, "queued_failure", "result_observed=true");
        disableAndConfirmDeliveryBarrier();

        JSONObject first = event(matchingBothChannels(queued.traceId()), "exception");
        assertEquals("simple", first.getString("log_mode"));
        assertFalse(first.has("stack")); assertFalse(first.has("cause_chain"));
        String identity = first.getString("error_id");

        DebugLog.setEnabled(true);
        DouAiLog.Operation retry = DouAiLog.begin("test_delivery_reused_after", "user_request");
        retry.error("same_error_observed_again", shared);
        retry.finish(DouAiLog.Outcome.FAILED, "confirmed_failure", "result_observed=true");
        JSONObject detailed = event(matchingBothChannels(retry.traceId()), "exception");
        assertEquals("detail", detailed.getString("log_mode"));
        assertEquals("same throwable identity must remain correlated", identity, detailed.getString("error_id"));
        assertEquals("true", detailed.getString("error_context_first"));
        assertTrue("first context was never sent as detail and must still be available", detailed.getString("stack").length() > 0);
        assertTrue(detailed.getString("cause_chain").contains("IOException"));
        assertFalse(readDisk().contains("PRIVATE REUSED ERROR BODY"));
        assertFalse(String.join("\n", framework).contains("PRIVATE REUSED CAUSE BODY"));
    }

    private void blockFrameworkDelivery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        release = new CountDownLatch(1); inFlightCompleted = new CountDownLatch(1);
        DouAiLog.setFrameworkSink((priority, line) -> {
            framework.add(line);
            if (line.contains("\"event\":\"delivery_mode_blocker\"")) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { inFlightCompleted.countDown(); }
            }
        });
        DouAiLog.event("test_delivery_blocker", "delivery_mode_blocker", "test_latch",
                "worker_paused=true;fixture=" + java.util.UUID.randomUUID());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private void disableAndConfirmDeliveryBarrier() throws Exception {
        CountDownLatch returned = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
        modeToggle = new Thread(() -> {
            try { DebugLog.setEnabled(false); }
            catch (Throwable error) { failure.set(error); }
            finally { returned.countDown(); }
        }, "test-log-mode-toggle");
        modeToggle.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (DebugLog.isEnabled() && System.nanoTime() < until) Thread.sleep(1);
        assertFalse("the new mode must publish before waiting for in-flight delivery", DebugLog.isEnabled());
        assertFalse("turn-off confirmation must wait for the in-flight detailed send", returned.await(25, TimeUnit.MILLISECONDS));
        assertEquals(1, inFlightCompleted.getCount());
        release.countDown(); modeToggle.join(5000);
        assertFalse("mode confirmation must finish after releasing delivery", modeToggle.isAlive());
        assertEquals(0, returned.getCount()); assertNull(failure.get());
        assertEquals("the in-flight send must finish before turn-off is confirmed", 0, inFlightCompleted.getCount());
    }

    private List<JSONObject> matchingBothChannels(String trace) throws Exception {
        assertTrue(DouAiLog.awaitIdle(5000));
        Map<String, JSONObject> disk = diskEvents();
        List<JSONObject> matched = new ArrayList<>();
        for (String json : framework) {
            JSONObject record = new JSONObject(json);
            if (!trace.equals(record.optString("trace_id"))) continue;
            JSONObject saved = disk.get(record.getString("event_id"));
            assertNotNull("framework event missing from private file: " + record.getString("event"), saved);
            assertEquals("sinks must get the identical JSON event", record.toString(), saved.toString());
            matched.add(record);
        }
        long savedCount = disk.values().stream().filter(e -> trace.equals(e.optString("trace_id"))).count();
        assertEquals("private file must not contain extra detailed events absent from the framework", savedCount, matched.size());
        assertFalse("test trace must have real delivered evidence", matched.isEmpty());
        return matched;
    }

    private Map<String, JSONObject> diskEvents() throws Exception {
        Map<String, JSONObject> events = new LinkedHashMap<>();
        for (String line : readDisk().split("\n")) {
            int first = line.indexOf('{');
            if (first < 0 || !line.contains("\"event_id\"")) continue;
            JSONObject event = new JSONObject(line.substring(first));
            events.put(event.getString("event_id"), event);
        }
        return events;
    }

    private String readDisk() throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        store.read(DebugLogStore.TOTAL_BYTES, true, text -> { result.set(text); done.countDown(); });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("the check must use actual disk evidence", store.integrity().contains("\"read_source\":\"disk\""));
        return result.get();
    }

    private static JSONObject event(List<JSONObject> events, String name) {
        return events.stream().filter(item -> name.equals(item.optString("event"))).findFirst()
                .orElseThrow(() -> new AssertionError("missing event " + name));
    }
}

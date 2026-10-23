package com.douai.module.diagnostics;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.EOFException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LogRepairContractsTest {
    private final List<String> sink = Collections.synchronizedList(new ArrayList<>());
    private void capture() { assertTrue(DouAiLog.awaitIdle(5000)); DebugLog.setEnabled(true); DouAiLog.setFrameworkSink((level, json) -> sink.add(json)); }
    @After public void reset() { DouAiLog.awaitIdle(5000); DouAiLog.setFrameworkSink(null); DebugLog.setEnabled(false); }
    private List<JSONObject> trace(String id) throws Exception {
        assertTrue(DouAiLog.awaitIdle(5000)); List<JSONObject> values = new ArrayList<>();
        synchronized (sink) { for (String line : sink) { JSONObject event = new JSONObject(line); if (id.equals(event.optString("trace_id"))) values.add(event); } }
        return values;
    }
    @Test public void revisionsCoverEqualLengthTailChangesAndAreIndependentOfMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>(); first.put("prompt", "x".repeat(6000) + "tail-a"); first.put("enabled", true);
        Map<String, Object> changed = new LinkedHashMap<>(); changed.put("prompt", "x".repeat(6000) + "tail-b"); changed.put("enabled", true);
        Map<String, Object> reordered = new LinkedHashMap<>(); reordered.put("enabled", true); reordered.put("prompt", first.get("prompt"));
        assertNotEquals(DouAiLog.configurationRevision(first), DouAiLog.configurationRevision(changed));
        assertEquals(DouAiLog.configurationRevision(first), DouAiLog.configurationRevision(reordered));
        changed.put("enabled", "true"); assertNotEquals(DouAiLog.configurationRevision(first), DouAiLog.configurationRevision(changed));
        assertEquals(DouAiLog.configurationRevision(Collections.singletonMap("set", new java.util.LinkedHashSet<>(List.of("a", "b")))),
                DouAiLog.configurationRevision(Collections.singletonMap("set", new java.util.LinkedHashSet<>(List.of("b", "a")))));
    }
    @Test public void asyncTaskAndItsChildrenRetainTheirEffectiveConfigurationAcrossHotUpdate() throws Exception {
        capture(); String before = DouAiLog.configurationRevision(Collections.singletonMap("prompt", "old"));
        String after = DouAiLog.configurationRevision(Collections.singletonMap("prompt", "new"));
        DouAiLog.configRevision(before); DouAiLog.Operation root = DouAiLog.begin("repair_config", "queue");
        DouAiLog.configRevision(after);
        Thread worker = new Thread(root.wrap(() -> {
            root.stage("old_snapshot_execute", "task_options=original");
            DouAiLog.Operation child = DouAiLog.begin("repair_config_child", "prepare");
            child.finish(DouAiLog.Outcome.SUCCESS, "local_prepared", ""); root.finish(DouAiLog.Outcome.UNCONFIRMED, "submitted", "");
        })); worker.start(); worker.join(5000); assertFalse(worker.isAlive());
        for (JSONObject event : trace(root.traceId())) assertEquals(before, event.getString("config_revision"));
        assertTrue(trace(root.traceId()).stream().anyMatch(e -> after.equals(e.optString("process_config_revision"))));
        DouAiLog.Operation next = DouAiLog.begin("repair_config", "new_user_operation"); next.finish(DouAiLog.Outcome.SKIPPED, "test", "");
        assertEquals(after, trace(next.traceId()).get(0).getString("config_revision"));
    }
    @Test public void installingAHookIsNotAnObservationAndCompletedUiAliasesHaveIndependentRuntimeFacts() throws Exception {
        String feature = "repair_state_ui", child = "repair_state_ui_save";
        DouAiLog.hook("fixture", feature, "installed", "handle_present=true");
        DouAiLog.featureState(feature, true, "unknown", "not_required", "support_unverified");
        JSONObject state = new JSONObject(DouAiLog.manifest()).getJSONObject("feature_states").getJSONObject(feature);
        assertFalse(state.getJSONObject("observation").getBoolean("observed"));
        DouAiLog.Operation operation = DouAiLog.begin(child, "save"); operation.finish(DouAiLog.Outcome.UNCONFIRMED, "write_returned", "disk_ack=unobserved");
        DouAiLog.registerFeatureAliases(feature, child);
        state = new JSONObject(DouAiLog.manifest()).getJSONObject("feature_states").getJSONObject(feature);
        assertTrue(state.getBoolean("enabled")); assertEquals("unknown", state.getString("availability"));
        assertEquals("not_required", state.getString("hook_state")); assertTrue(state.getJSONObject("observation").getBoolean("observed"));
        assertEquals("unconfirmed", state.getJSONObject("last_outcome").getString("outcome"));
        assertEquals(child, state.getJSONObject("last_outcome").getString("result_scope"));
    }
    @Test public void exceptionIdentityAndSafeCauseSummaryFollowTheOperationIntoItsFinalResult() throws Exception {
        capture(); DouAiLog.Operation root = DouAiLog.begin("repair_error", "network");
        EOFException failure = new EOFException("PRIVATE_PROVIDER_BODY api_key=SYNTHETIC_SECRET");
        root.error("network_read", failure); root.error("propagated", failure);
        root.finish(DouAiLog.Outcome.FAILED, "response_incomplete", "remote_result=unknown");
        List<JSONObject> events = trace(root.traceId()); List<JSONObject> errors = new ArrayList<>(); JSONObject terminal = null;
        for (JSONObject event : events) { if ("exception".equals(event.optString("event"))) errors.add(event); if ("operation_finished".equals(event.optString("event"))) terminal = event; }
        assertEquals(2, errors.size()); assertEquals(errors.get(0).getString("error_id"), errors.get(1).getString("error_id"));
        assertEquals("true", errors.get(0).getString("error_context_first")); assertEquals("false", errors.get(1).getString("error_context_first"));
        assertEquals("stream_ended_before_expected_completion", errors.get(0).getString("exception_message"));
        assertTrue(errors.get(0).getString("cause_chain").contains("EOFException")); assertFalse(errors.get(1).has("stack"));
        assertNotNull(terminal); assertEquals(errors.get(0).getString("error_id"), terminal.getJSONArray("error_ids").getString(0));
        assertFalse(events.toString().contains("PRIVATE_PROVIDER_BODY")); assertFalse(events.toString().contains("SYNTHETIC_SECRET"));
    }
    @Test public void repeatedProgressHasAWindowSummaryAndTheRootStillFinishesExactlyOnce() throws Exception {
        capture(); DouAiLog.Operation root = DouAiLog.begin("repair_progress", "stream");
        for (int i = 0; i < 2000; i++) root.stage("receiving", "characters=" + i);
        root.finish(DouAiLog.Outcome.SUCCESS, "response_validated", "characters=2000");
        List<JSONObject> events = trace(root.traceId());
        assertEquals(1, events.stream().filter(e -> "operation_finished".equals(e.optString("event"))).count());
        assertTrue(events.stream().anyMatch(e -> "stage_summary".equals(e.optString("event")))); assertTrue(events.size() < 15);
    }
    @Test public void knownLocalValidationMessagesAreReadableButAppendedPrivateContentIsNeverTrusted() throws Exception {
        capture(); DouAiLog.Operation root = DouAiLog.begin("repair_validation", "generate");
        root.error("validate", new IllegalArgumentException("请填写或获取模型名称"));
        root.error("validate_external", new IllegalArgumentException("请填写或获取模型名称 PRIVATE_REQUEST_SENTINEL"));
        root.finish(DouAiLog.Outcome.FAILED, "configuration_invalid", "model_name_present=false");
        List<JSONObject> events = trace(root.traceId());
        assertTrue(events.stream().anyMatch(e -> "请填写或获取模型名称".equals(e.optString("exception_message"))));
        assertFalse(events.toString().contains("PRIVATE_REQUEST_SENTINEL"));
    }
    @Test public void queuedEventsKeepProducerTimeAndEnvironmentWhenSerializationRunsLater() throws Exception {
        assertTrue(DouAiLog.awaitIdle(5000));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        DouAiLog.setFrameworkSink((level, json) -> { entered.countDown(); try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { } sink.add(json); });
        DouAiLog.event("repair_envelope", "barrier", "fixture", ""); assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        try {
            DouAiLog.environment("producer-version", "producer-build", "host", "producer", 1);
            DouAiLog.Operation root = DouAiLog.begin("repair_envelope", "captured");
            root.finish(DouAiLog.Outcome.SUCCESS, "verified", ""); long capturedBy = System.currentTimeMillis();
            DouAiLog.environment("later-version", "later-build", "host", "later", 2);
            Thread.sleep(30); release.countDown();
            for (JSONObject event : trace(root.traceId())) {
                assertEquals("producer-version", event.getString("module_version"));
                assertTrue(java.time.OffsetDateTime.parse(event.getString("wall_time")).toInstant().toEpochMilli() <= capturedBy);
            }
        } finally { release.countDown(); }
    }
    @Test public void limitedExceptionContextIsMarkedRatherThanAppearingComplete() throws Exception {
        capture(); IllegalArgumentException failure = new IllegalArgumentException("PRIVATE_ERROR_BODY");
        StackTraceElement[] frames = new StackTraceElement[20];
        for (int i = 0; i < frames.length; i++) frames[i] = new StackTraceElement("FixtureClass", "method" + i, "Fixture.java", i);
        failure.setStackTrace(frames);
        DouAiLog.Operation op = DouAiLog.begin("repair_error_limit", "test"); op.error("stage", failure);
        op.finish(DouAiLog.Outcome.FAILED, "fixture_failure", "");
        JSONObject event = trace(op.traceId()).stream().filter(e -> "exception".equals(e.optString("event"))).findFirst().orElseThrow();
        assertEquals("true", event.getString("error_context_truncated")); assertEquals("true", event.getString("truncated"));
        assertTrue(event.getString("stack").contains("method7")); assertFalse(event.getString("stack").contains("method8"));
        assertFalse(event.toString().contains("PRIVATE_ERROR_BODY"));
    }
}

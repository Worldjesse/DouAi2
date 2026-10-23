package com.douai.module.diagnostics;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class LogModePolicyTest {
    @Test public void defaultSimpleProjectionKeepsBusinessResultButRemovesAllDetailedFields() {
        Map<String, Object> source = terminal();
        source.put("stack", "PRIVATE STACK DETAIL"); source.put("cause_chain", "PRIVATE CAUSE DETAIL");
        source.put("args", "java.lang.String(len=4000)"); source.put("result", "java.lang.Boolean(true)");
        source.put("duration_us", "154"); source.put("stage_ms", "{request=45}");
        source.put("configuration_selections", List.of(Map.of("effective_config_revision", "cfg_after")));
        source.put("evidence", "business_code=0;object_present=true; stage_ms={request=45,callback=12}");
        Map<String, Object> simple = LogModePolicy.project(Collections.unmodifiableMap(source), false);
        assertEquals("simple", simple.get("log_mode"));
        for (String key : Arrays.asList("trace_id", "linked_trace_id", "outcome", "reason_code", "duration_ms",
                "last_stage", "config_revision", "event_id", "seq")) assertEquals(source.get(key), simple.get(key));
        assertEquals("business_code=0;object_present=true", simple.get("evidence"));
        for (String key : Arrays.asList("stack", "cause_chain", "args", "result", "duration_us", "stage_ms",
                "configuration_selections", "process_config_revision", "span_id", "monotonic_ns")) assertFalse(key, simple.containsKey(key));
        assertTrue(source.containsKey("stack"));
        try { simple.put("stack", "must not mutate"); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void privateAndFrameworkDeliveriesUseTheSameSimpleJsonProjection() {
        Map<String, Object> source = terminal(); source.put("stack", "PRIVATE DETAIL");
        source.put("cause_chain", "PRIVATE CAUSE"); source.put("args", "PRIVATE ARGS");
        Map<String, Object> projected = LogModePolicy.project(Collections.unmodifiableMap(source), false);
        String privateFile = new Gson().toJson(projected), framework = new Gson().toJson(projected);
        assertEquals(privateFile, framework);
        assertEquals("success", JsonParser.parseString(privateFile).getAsJsonObject().get("outcome").getAsString());
        assertTrue(privateFile.getBytes(StandardCharsets.UTF_8).length < 1200);
        assertFalse(privateFile.contains("PRIVATE"));
        assertFalse(framework.contains("stack")); assertFalse(framework.contains("cause_chain"));
    }

    @Test public void detailedModeKeepsOriginalFieldsAndMarksBothSinkPayloadsAsDetailed() {
        Map<String, Object> source = terminal(); source.put("stack", "safe_stack");
        source.put("cause_chain", "safe_cause"); source.put("args", "java.lang.String(len=4)");
        source.put("duration_us", "45");
        Map<String, Object> detail = LogModePolicy.project(Collections.unmodifiableMap(source), true);
        assertEquals("detail", detail.get("log_mode"));
        for (String name : source.keySet()) assertEquals(name, source.get(name), detail.get(name));
        assertFalse(source.containsKey("log_mode"));
    }

    @Test public void repeatedDetailOnlyEventsAreHiddenButDecisionsOutcomesAndProblemsRemain() {
        for (String event : Arrays.asList("stage", "stage_summary", "config_value", "config_removed",
                "configuration_selected", "registration", "registration_complete", "hook_installed",
                "hook_first_trigger", "hook_returned", "hook_detail", "legacy_diagnostic")) {
            assertTrue(event, LogModePolicy.detailOnly(event, true));
        }
        assertTrue(LogModePolicy.detailOnly("operation_started", false));
        assertTrue(LogModePolicy.detailOnly("span_finished", false));
        for (String event : Arrays.asList("operation_started", "operation_finished", "feature_state", "decision",
                "eligibility", "exception", "integrity_manifest", "storage_integrity", "startup_integrity",
                "window_summary", "hook_detail_suppression", "hook_threw", "hook_installation_failed")) {
            assertFalse(event, LogModePolicy.detailOnly(event, true));
        }
        assertFalse(LogModePolicy.detailOnly(null, true));
    }

    @Test public void simpleErrorRetainsIdentityAndSafeReasonWithBoundedMessagesAndReferences() {
        Map<String, Object> source = terminal(); source.put("level", "ERROR"); source.put("outcome", "failed");
        source.put("evidence", "a".repeat(300)); source.put("exception_class", "java.net.SocketTimeoutException");
        source.put("exception_message", "b".repeat(300)); source.put("error_id", "error_1");
        source.put("error_ids", Arrays.asList("error_1", "error_2", "error_3", "error_4", "error_5"));
        source.put("stack", "detail_not_for_simple");
        Map<String, Object> simple = LogModePolicy.project(Collections.unmodifiableMap(source), false);
        assertEquals("ERROR", simple.get("level")); assertEquals("failed", simple.get("outcome"));
        assertEquals("error_1", simple.get("error_id"));
        assertEquals(160, ((String) simple.get("evidence")).length());
        assertEquals(128, ((String) simple.get("exception_message")).length());
        assertEquals(Arrays.asList("error_1", "error_2", "error_3"), simple.get("error_ids"));
        assertEquals(2, simple.get("error_ids_omitted_count")); assertFalse(simple.containsKey("stack"));
    }

    @Test public void truncationDoesNotSplitAnEmojiAndEmptyRecordsDefaultToSimple() {
        Map<String, Object> source = terminal(); source.put("evidence", "a".repeat(159) + "😀" + "trailing");
        Map<String, Object> simple = LogModePolicy.project(source, false);
        assertEquals("a".repeat(159), simple.get("evidence"));
        assertEquals(Collections.singletonMap("log_mode", "simple"), LogModePolicy.project(Collections.emptyMap(), false));
    }

    private static Map<String, Object> terminal() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "2"); result.put("event_id", "event_1"); result.put("session_id", "session_1");
        result.put("seq", "23"); result.put("wall_time", "2026-10-23T12:00:00+08:00"); result.put("level", "INFO");
        result.put("feature_id", "auto_comment"); result.put("event", "operation_finished"); result.put("trace_id", "trace_1");
        result.put("linked_trace_id", "trace_previous"); result.put("outcome", "success"); result.put("reason_code", "callback_validated");
        result.put("evidence", "business_code=0;object_present=true"); result.put("duration_ms", "55");
        result.put("last_stage", "callback"); result.put("config_revision", "cfg_before");
        result.put("process_config_revision", "cfg_after"); result.put("span_id", "span_1"); result.put("monotonic_ns", "12345678");
        return result;
    }
}

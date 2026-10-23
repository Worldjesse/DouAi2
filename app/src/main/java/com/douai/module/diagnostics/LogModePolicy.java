package com.douai.module.diagnostics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One projection for private storage and framework delivery. Inputs must already be sanitized. */
final class LogModePolicy {
    private static final List<String> SIMPLE_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "schema_version", "event_id", "session_id", "seq", "wall_time", "level", "feature_id", "event",
            "trace_id", "linked_trace_id", "outcome", "reason_code", "evidence", "duration_ms", "last_stage",
            "config_revision", "exception_class", "exception_message", "error_id", "truncated", "context_missing",
            "source_feature_id", "result_scope", "root_operation", "module_version", "host_version", "process_name", "pid",
            "previous_session_id", "previous_trace_id", "hook_id", "phase"));

    private LogModePolicy() { }

    /** Event classification only. The caller must always retain WARN/ERROR with a simple projection. */
    static boolean detailOnly(String event, boolean rootOperation) {
        if (event == null) return false;
        switch (event) {
            case "operation_started": return !rootOperation;
            case "span_finished": case "stage": case "stage_summary":
            case "config_value": case "config_removed": case "configuration_selected":
            case "registration": case "legacy_diagnostic": return true;
            case "hook_threw": case "hook_installation_failed": case "hook_installation_empty":
            case "hook_detail_suppression": return false;
            default: return event.startsWith("hook_") || event.startsWith("registration_");
        }
    }

    static Map<String, Object> project(Map<String, Object> immutableFields, boolean detailed) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (detailed) output.putAll(immutableFields);
        else {
            for (String name : SIMPLE_FIELDS) {
                Object value = immutableFields.get(name);
                if (value != null) output.put(name, value);
            }
            Object evidence = output.get("evidence");
            if (evidence instanceof String) {
                String summary = withoutStageTimings((String)evidence);
                output.put("evidence", bound(summary, 160));
                if (summary.length() > 160) output.put("truncated", "true");
            }
            Object message = output.get("exception_message");
            if (message instanceof String) output.put("exception_message", bound((String) message, 128));
            Object ids = immutableFields.get("error_ids");
            if (ids instanceof List<?>) {
                List<?> source = (List<?>) ids;
                ArrayList<String> selected = new ArrayList<>();
                for (Object id : source) {
                    if (selected.size() >= 3) break;
                    if (id instanceof String) selected.add((String) id);
                }
                output.put("error_ids", Collections.unmodifiableList(selected));
                if (source.size() > selected.size()) output.put("error_ids_omitted_count", source.size() - selected.size());
            }
        }
        output.put("log_mode", detailed ? "detail" : "simple");
        return Collections.unmodifiableMap(output);
    }

    private static String withoutStageTimings(String evidence) {
        int spaced = evidence.indexOf("; stage_ms="), compact = evidence.indexOf(";stage_ms=");
        int marker = spaced < 0 ? compact : compact < 0 ? spaced : Math.min(spaced, compact);
        return marker < 0 ? evidence : evidence.substring(0, marker);
    }

    private static String bound(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
}

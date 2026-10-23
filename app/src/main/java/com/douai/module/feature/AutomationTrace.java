package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;

import java.util.LinkedHashMap;

/** Correlates the existing history identity across generation, preview and native callbacks. */
final class AutomationTrace {
    private static final LinkedHashMap<String, DouAiLog.Operation> OPERATIONS = new LinkedHashMap<>();

    private AutomationTrace() { }

    static synchronized DouAiLog.Operation operation(String feature, String id, String trigger) {
        DouAiLog.Operation operation = OPERATIONS.get(id);
        if (operation == null) {
            operation = DouAiLog.begin(feature, trigger);
            OPERATIONS.put(id, operation);
            while (OPERATIONS.size() > 512) {
                OPERATIONS.remove(OPERATIONS.keySet().iterator().next());
                DouAiLog.event("automation", "correlation_evicted", "registry_capacity", "capacity=512; explicit_task_trace_retained=true");
            }
        }
        return operation;
    }

    /** New task instance, even when a previously skipped receipt reuses its business identity. */
    static synchronized DouAiLog.Operation start(String feature, String id, String trigger) {
        return remember(id, DouAiLog.begin(feature, trigger));
    }

    static synchronized DouAiLog.Operation start(String feature, String id, String trigger, String effectiveRevision) {
        return remember(id, DouAiLog.begin(feature, trigger, effectiveRevision));
    }

    private static DouAiLog.Operation remember(String id, DouAiLog.Operation operation) {
        OPERATIONS.put(id, operation);
        while (OPERATIONS.size() > 512) {
            OPERATIONS.remove(OPERATIONS.keySet().iterator().next());
            DouAiLog.event("automation", "correlation_evicted", "registry_capacity", "capacity=512;explicit_task_trace_retained=true");
        }
        return operation;
    }

    static DouAiLog.Operation capture(String feature, String trigger) {
        DouAiLog.Operation current = DouAiLog.current();
        return current == null ? DouAiLog.begin(feature, trigger) : current;
    }

    static void outcome(DouAiLog.Operation operation, String status, String reason, String evidence) {
        DouAiLog.Outcome outcome = "sent".equals(status) ? DouAiLog.Outcome.SUCCESS
                : "submitted".equals(status) ? DouAiLog.Outcome.UNCONFIRMED
                : "cancelled".equals(status) ? DouAiLog.Outcome.CANCELLED
                : "deferred".equals(status) ? DouAiLog.Outcome.SKIPPED
                : reason != null && (reason.contains("timeout") || reason.contains("expired")) ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED;
        operation.finish(outcome, reason, evidence);
    }
}

package com.douai.module.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;

/** Bounded identity-only accounting. Sequence ranges are envelopes, never claims of contiguous loss. */
final class LogLossLedger {
    private static final int GROUP_LIMIT = 64;
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    private long count, overflow;
    private long overflowFirst = Long.MAX_VALUE, overflowLast = -1;

    synchronized void record(String reason, String json) {
        try {
            JSONObject item = new JSONObject(json);
            record(reason, item.optString("session_id", "unknown"), item.optLong("seq", -1),
                    item.optString("feature_id", "unknown"), item.optString("event", "unknown"),
                    item.optString("trace_id", "none"));
        } catch (Exception | LinkageError invalid) {
            record(reason, "unknown", -1, "legacy_or_unparseable", "unknown", "unknown");
        }
    }

    synchronized void record(String reason, String session, long seq, String feature, String event, String trace) {
        reason = identifier(reason); session = identifier(session); feature = identifier(feature);
        event = identifier(event); trace = identifier(trace);
        String key = reason + '\n' + session + '\n' + feature + '\n' + event + '\n' + trace;
        count++;
        Group group = groups.get(key);
        if (group == null) {
            if (groups.size() >= GROUP_LIMIT) {
                overflow++;
                if (seq >= 0) { overflowFirst = Math.min(overflowFirst, seq); overflowLast = Math.max(overflowLast, seq); }
                return;
            }
            group = new Group(reason, session, feature, event, trace); groups.put(key, group);
        }
        group.count++;
        if (seq >= 0) { group.first = Math.min(group.first, seq); group.last = Math.max(group.last, seq); }
    }

    synchronized String json() {
        try {
            JSONObject out = new JSONObject(); JSONArray affected = new JSONArray();
            out.put("count", count); out.put("group_capacity", GROUP_LIMIT);
            out.put("range_semantics", "min_max_envelope_not_contiguous");
            for (Group group : groups.values()) {
                JSONObject item = new JSONObject();
                item.put("reason", group.reason); item.put("session_id", group.session);
                item.put("feature_id", group.feature); item.put("event", group.event); item.put("trace_id", group.trace);
                item.put("count", group.count); item.put("first_seq", group.first == Long.MAX_VALUE ? -1 : group.first);
                item.put("last_seq", group.last); affected.put(item);
            }
            out.put("affected", affected); out.put("aggregate_overflow_count", overflow);
            out.put("overflow_first_seq", overflowFirst == Long.MAX_VALUE ? -1 : overflowFirst);
            out.put("overflow_last_seq", overflowLast);
            out.put("overflow_identity", overflow > 0 ? "omitted_by_capacity_multiple_sessions_possible" : "none");
            return out.toString();
        } catch (Exception | LinkageError unavailable) {
            return "{\"count\":" + count + ",\"aggregate_overflow_count\":" + overflow
                    + ",\"metadata_unavailable\":true}";
        }
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        // Identities only: never retain a message, URL, exception text or payload as loss evidence.
        return value.substring(0, Math.min(96, value.length())).replaceAll("[^a-zA-Z0-9_.:/@-]", "_");
    }
    private static final class Group {
        final String reason, session, feature, event, trace;
        long count, first = Long.MAX_VALUE, last = -1;
        Group(String reason, String session, String feature, String event, String trace) {
            this.reason = reason; this.session = session; this.feature = feature; this.event = event; this.trace = trace;
        }
    }
}

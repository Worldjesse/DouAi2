package com.douai.module.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Replays actual startup JSON into the private storage writer, including nested live-record fields. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class StartupReplayModeTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void detailedReplayRetainsErrorArraysAndNestedConfigurationSelections() throws Exception {
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        try (DebugLogStore store = new DebugLogStore(temporary.newFolder("detail"))) {
            JSONObject original = terminal("detailed-terminal", 3);
            store.replayStartup(Collections.singletonList(record(original)));
            JSONObject replayed = find(read(store), "detailed-terminal");
            assertEquals("detail", replayed.getString("log_mode"));
            assertEquals("err_1", replayed.getJSONArray("error_ids").getString(0));
            assertEquals(3, replayed.getJSONArray("error_ids").length());
            JSONObject selection = replayed.getJSONArray("configuration_selections").getJSONObject(0);
            assertEquals("cfg_generation", selection.getString("effective_config_revision"));
            assertEquals("live_at_generation", selection.getString("config_selection_policy"));
            assertTrue(selection.getJSONObject("checks").getBoolean("account_observed"));
            assertEquals(2, selection.getJSONObject("checks").getInt("attempt"));
            assertEquals("cfg_before", selection.getJSONArray("revisions").getString(0));
            assertTrue(selection.getJSONArray("revisions").isNull(2));
            assertEquals(0, new JSONObject(store.integrity()).getLong("lost_count"));
        } finally { DebugLog.setEnabled(previousDebug); }
    }

    @Test public void simpleReplayRetainsBoundedErrorIdsAndOmitsNestedDetailsAndStageEvents() throws Exception {
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(false);
        try (DebugLogStore store = new DebugLogStore(temporary.newFolder("simple"))) {
            JSONObject terminal = terminal("simple-terminal", 5);
            JSONObject stage = new JSONObject().put("event_id", "detail-stage").put("event", "stage")
                    .put("root_operation", true).put("session_id", "startup-fixture").put("seq", "2")
                    .put("evidence", "detail_only_configuration");
            store.replayStartup(Arrays.asList(record(terminal), new String[]{"4", "DouAi.Event", stage.toString(), "structured"}));
            String text = read(store);
            JSONObject replayed = find(text, "simple-terminal");
            assertEquals("simple", replayed.getString("log_mode"));
            assertEquals("failed", replayed.getString("outcome"));
            assertEquals("err_1", replayed.getString("error_id"));
            assertEquals(3, replayed.getJSONArray("error_ids").length());
            assertEquals(2, replayed.getInt("error_ids_omitted_count"));
            assertFalse(replayed.has("configuration_selections"));
            assertFalse(replayed.has("stack")); assertFalse(replayed.has("cause_chain"));
            assertFalse(text.contains("detail-stage"));
            assertEquals(0, new JSONObject(store.integrity()).getLong("lost_count"));
        } finally { DebugLog.setEnabled(previousDebug); }
    }

    @Test public void malformedStartupRecordCannotDiscardLaterTerminalsAndItsLossIsAccountedFor() throws Exception {
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        try (DebugLogStore store = new DebugLogStore(temporary.newFolder("mixed"))) {
            store.replayStartup(Arrays.asList(record(terminal("before-invalid", 0)),
                    new String[]{"6", "DouAi.Event", "{malformed-internal-json", "structured"},
                    record(terminal("after-invalid", 2))));
            String text = read(store);
            assertEquals(0, find(text, "before-invalid").getJSONArray("error_ids").length());
            assertEquals(2, find(text, "after-invalid").getJSONArray("error_ids").length());
            JSONObject integrity = new JSONObject(store.integrity());
            assertEquals(1, integrity.getLong("lost_count"));
            assertEquals(1, integrity.getLong("serialization_failures"));
            JSONObject affected = integrity.getJSONObject("affected_records").getJSONArray("affected").getJSONObject(0);
            assertEquals("startup_replay_invalid_record", affected.getString("reason"));
            assertEquals(1, affected.getLong("count"));
        } finally { DebugLog.setEnabled(previousDebug); }
    }

    private static JSONObject terminal(String id, int errorCount) throws Exception {
        JSONArray errors = new JSONArray(); for (int index = 1; index <= errorCount; index++) errors.put("err_" + index);
        JSONObject selection = new JSONObject().put("effective_config_revision", "cfg_generation")
                .put("config_selection_policy", "live_at_generation")
                .put("checks", new JSONObject().put("account_observed", true).put("attempt", 2))
                .put("revisions", new JSONArray().put("cfg_before").put("cfg_generation").put(JSONObject.NULL));
        return new JSONObject().put("event_id", id).put("event", "operation_finished").put("root_operation", true)
                .put("schema_version", "2").put("session_id", "startup-fixture").put("seq", "1")
                .put("feature_id", "comment_reply").put("trace_id", "startup-trace").put("level", "ERROR")
                .put("outcome", "failed").put("reason_code", "fixture_failure").put("error_id", "err_1")
                .put("error_ids", errors).put("configuration_selections", new JSONArray().put(selection))
                .put("stack", "fixture_stack_context").put("cause_chain", "fixture_cause_context");
    }
    private static String[] record(JSONObject fields) { return new String[]{"6", "DouAi.Event", fields.toString(), "structured"}; }
    private static String read(DebugLogStore store) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        store.read(DebugLog.MAX_EXPORT_BYTES, true, text -> { result.set(text); done.countDown(); });
        assertTrue("startup batch and private storage read completed", done.await(5, TimeUnit.SECONDS));
        return result.get();
    }
    private static JSONObject find(String text, String eventId) throws Exception {
        for (String line : text.split("\n")) {
            int start = line.indexOf('{'); if (start < 0) continue;
            JSONObject event = new JSONObject(line.substring(start));
            if (eventId.equals(event.optString("event_id"))) return event;
        }
        throw new AssertionError("Missing startup event " + eventId + " in " + text);
    }
}

package com.douai.module.diagnostics;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LogLossLedgerTest {
    @Test public void identitiesAggregateWithNonContiguousRangesAndNeverRetainBodies() throws Exception {
        LogLossLedger ledger = new LogLossLedger();
        ledger.record("queue_capacity", "{\"session_id\":\"s\",\"seq\":\"2\",\"feature_id\":\"download\",\"event\":\"stage\",\"trace_id\":\"t\",\"evidence\":\"do-not-retain-body\"}");
        ledger.record("queue_capacity", "s", 10, "download", "stage", "t");
        JSONObject result = new JSONObject(ledger.json()), group = result.getJSONArray("affected").getJSONObject(0);
        assertEquals(2, result.getLong("count")); assertEquals(2, group.getLong("first_seq")); assertEquals(10, group.getLong("last_seq"));
        assertEquals("min_max_envelope_not_contiguous", result.getString("range_semantics"));
        assertFalse(result.toString().contains("do-not-retain-body"));
    }
    @Test public void identityFloodIsBoundedAndOverflowIsExplicit() throws Exception {
        LogLossLedger ledger = new LogLossLedger();
        for (int seq = 0; seq < 2000; seq++) ledger.record("capacity", "s", seq, "feature-" + seq, "stage", "trace-" + seq);
        JSONObject result = new JSONObject(ledger.json());
        assertEquals(64, result.getJSONArray("affected").length()); assertEquals(1936, result.getLong("aggregate_overflow_count"));
        assertEquals(2000, result.getLong("count")); assertTrue(result.toString().length() < 32 * 1024);
        assertEquals("omitted_by_capacity_multiple_sessions_possible", result.getString("overflow_identity"));
    }
}

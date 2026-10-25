package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SubmissionLedgerTest {
    private SharedPreferences preferences;
    private SubmissionLedger ledger;
    private final String first = SubmissionLedger.identity("comment:1:work");
    private final String second = SubmissionLedger.identity("comment:2:work");

    @Before public void setUp() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
                "ledger-test-" + java.util.UUID.randomUUID(), Context.MODE_PRIVATE);
        ledger = new SubmissionLedger(preferences, 2);
    }

    @Test public void processRecreationKeepsReservationBeforeAnyHostCallback() {
        assertTrue(ledger.reserve(first, "attempt-1", 10));
        SubmissionLedger recreated = new SubmissionLedger(preferences, 2);
        assertTrue(recreated.contains(first));
        assertFalse(recreated.reserve(first, "attempt-2", 20));
        assertTrue(recreated.isReserved(first, "attempt-1"));
        assertFalse(recreated.isReserved(first, "attempt-2"));
        assertTrue(recreated.reserve(second, "account-2", 20));
    }

    @Test public void checkingAndCompletingOneReservationNeverCopiesTheWholeLedger() {
        assertTrue(ledger.reserve(first, "one", 10));
        java.util.concurrent.atomic.AtomicInteger snapshots = new java.util.concurrent.atomic.AtomicInteger();
        SharedPreferences counted = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) snapshots.incrementAndGet();
                    try { return method.invoke(preferences, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        SubmissionLedger observed = new SubmissionLedger(counted, 2);
        for (int i = 0; i < 100; i++) assertTrue(observed.isReserved(first, "one"));
        assertTrue(observed.complete(first, "one", "sent", 20));
        assertEquals(0, snapshots.get());
    }

    @Test public void malformedSingleRecordKeepsOtherReservationsUsable() {
        assertTrue(ledger.reserve(first, "one", 10));
        preferences.edit().putInt("send." + first, 7).commit();
        assertFalse(ledger.isReserved(first, "one"));
        assertTrue(ledger.contains(first));
        assertTrue(ledger.reserve(second, "two", 20));
    }

    @Test public void unknownCannotBecomeRetryableButLateSuccessIsRetained() {
        assertTrue(ledger.reserve(first, "one", 10));
        assertTrue(ledger.complete(first, "one", "unknown", 20));
        assertFalse(ledger.complete(first, "one", "failed", 30));
        assertFalse(ledger.abandon(first, "one"));
        assertFalse(ledger.reserve(first, "two", 30));
        assertTrue(ledger.complete(first, "one", "sent", 40));
        assertFalse(ledger.complete(first, "one", "failed", 50));
        assertTrue(ledger.contains(first));
    }

    @Test public void staleCallbackCannotReleaseNewAttempt() {
        assertTrue(ledger.reserve(first, "one", 10));
        assertTrue(ledger.complete(first, "one", "failed", 20));
        assertTrue(ledger.reserve(first, "two", 30));
        assertFalse(ledger.complete(first, "one", "failed", 40));
        assertFalse(ledger.complete(first, "one", "sent", 40));
        assertFalse(ledger.abandon(first, "one"));
        assertTrue(ledger.isReserved(first, "two"));
    }

    @Test public void confirmedPreInvocationCancellationReleasesOnlyItsOwnReservation() {
        assertTrue(ledger.reserve(first, "one", 10));
        assertFalse(ledger.abandon(first, "other"));
        assertTrue(ledger.abandon(first, "one"));
        assertFalse(ledger.contains(first));
        assertTrue(ledger.reserve(first, "two", 20));
    }

    @Test public void fullLedgerNeverEvictsAnUncertainOrCorruptSend() {
        assertTrue(ledger.reserve(first, "one", 10));
        assertTrue(ledger.reserve(second, "two", 20));
        String third = SubmissionLedger.identity("third");
        assertFalse(ledger.reserve(third, "three", 30));
        assertTrue(ledger.complete(first, "one", "sent", 40));
        assertTrue(ledger.reserve(third, "three", 50));
        assertTrue(ledger.contains(second));
        assertFalse(ledger.contains(first));
        preferences.edit().putString("send." + third, "corrupt").commit();
        assertFalse(ledger.reserve(SubmissionLedger.identity("fourth"), "four", 60));
        assertTrue(ledger.contains(third));
    }

    @Test public void commitFailureStopsAllInstancesEvenWhenMemoryWasMutated() {
        assertTrue(ledger.reserve(first, "one", 10));
        SubmissionLedger failing = new SubmissionLedger(preferences, 2, editor -> { editor.apply(); return false; });
        assertFalse(failing.complete(first, "one", "failed", 20));
        assertTrue(ledger.contains(first));
        assertFalse(ledger.reserve(second, "two", 30));
        assertFalse(new SubmissionLedger(preferences, 2).reserve(first, "again", 40));
    }

    @Test public void malformedIdsOrOutcomesAreRejectedAndRawIdentityIsNotStored() {
        assertFalse(ledger.reserve("raw-account-work", "one", 10));
        assertFalse(ledger.reserve(first, "bad token/credential", 10));
        assertFalse(ledger.reserve(first, "one", -1));
        assertTrue(ledger.reserve(first, "one", 10));
        assertFalse(ledger.complete(first, "one", "cancelled", 20));
        assertFalse(ledger.complete(first, "one", "sent", -1));
        assertFalse(preferences.getAll().toString().contains("comment:1:work"));
    }

    @Test public void reservationDistinguishesDuplicatesFullStorageAndInvalidInput() {
        String scope = SubmissionLedger.accountScope("1");
        assertEquals(SubmissionLedger.ReserveResult.INVALID, ledger.reserve("raw", "one", 10, scope));
        assertEquals(SubmissionLedger.ReserveResult.RESERVED, ledger.reserve(first, "one", 10, scope));
        assertEquals(SubmissionLedger.ReserveResult.DUPLICATE, ledger.reserve(first, "two", 20, scope));
        assertTrue(ledger.reserve(second, "two", 20));
        assertEquals(SubmissionLedger.ReserveResult.FULL, ledger.reserve(SubmissionLedger.identity("third"), "three", 30, scope));
        SubmissionLedger broken = new SubmissionLedger(preferences, 2, editor -> false);
        assertFalse(broken.complete(first, "one", "sent", 40));
        assertEquals(SubmissionLedger.ReserveResult.STORAGE_FAILED, ledger.reserve(first, "again", 50, scope));
    }

    @Test public void currentProcessCannotReleaseUnknownReservations() {
        String scope = SubmissionLedger.accountScope("1");
        assertEquals(SubmissionLedger.ReserveResult.RESERVED, ledger.reserve(first, "one", 10, scope));
        assertTrue(ledger.complete(first, "one", "unknown", 20));
        SubmissionLedger.Entry entry = ledger.inspect(scope, Collections.emptySet(), 300_000).entries.get(0);
        assertFalse(entry.recoverable);
        assertFalse(ledger.reconcile(entry, scope, false, 300_000));
        assertTrue(ledger.contains(first));
    }

    @Test public void explicitRecoveryIsAccountScopedAndCannotUseOtherLedger() throws Exception {
        String scope = SubmissionLedger.accountScope("1"), other = SubmissionLedger.accountScope("2");
        ledger.reserve(first, "one", 10, scope); previousProcess(first);
        assertTrue(ledger.inspect(other, Collections.singleton(first), 300_000).entries.isEmpty());
        SubmissionLedger.Entry entry = ledger.inspect(scope, Collections.emptySet(), 300_000).entries.get(0);
        assertTrue(entry.recoverable);
        assertFalse(ledger.reconcile(entry, other, false, 300_000));
        SharedPreferences copy = RuntimeEnvironment.getApplication().getSharedPreferences("other-ledger", 0);
        copy.edit().putString("send." + first, preferences.getString("send." + first, "")).commit();
        assertFalse(new SubmissionLedger(copy, 2).reconcile(entry, scope, false, 300_000));
        assertTrue(ledger.reconcile(entry, scope, false, 300_000));
        assertFalse(ledger.contains(first));
        assertTrue(ledger.reserve(first, "new", 300_001));
        assertFalse(ledger.complete(first, "one", "sent", 300_002));
    }

    @Test public void changedRecordOrRecentReservationCannotBeReconciled() throws Exception {
        String scope = SubmissionLedger.accountScope("1");
        ledger.reserve(first, "one", 10, scope); previousProcess(first);
        assertFalse(ledger.inspect(scope, Collections.emptySet(), 100).entries.get(0).recoverable);
        SubmissionLedger.Entry old = ledger.inspect(scope, Collections.emptySet(), 300_000).entries.get(0);
        assertTrue(ledger.complete(first, "one", "sent", 300_000));
        assertFalse(ledger.reconcile(old, scope, false, 300_001));
        assertTrue(ledger.contains(first));
    }

    @Test public void confirmSuccessRetainsDedupeAndLegacyNeedsAccountCheckedHistory() throws Exception {
        String scope = SubmissionLedger.accountScope("1");
        preferences.edit().putString("send." + first, new JSONObject().put("version", 1)
                .put("attempt", "old").put("status", "unknown").put("timestamp", 10).toString())
                .putString("send." + second, "corrupt").commit();
        SubmissionLedger.Snapshot unassigned = ledger.inspect(scope, Collections.emptySet(), 300_000);
        assertEquals(1, unassigned.unassigned); assertEquals(1, unassigned.corrupt); assertEquals(2, unassigned.total);
        assertTrue(unassigned.entries.isEmpty());
        SubmissionLedger.Entry entry = ledger.inspect(scope, Collections.singleton(first), 300_000).entries.get(0);
        assertTrue(ledger.reconcile(entry, scope, true, 300_000));
        assertTrue(ledger.contains(first));
        assertEquals("sent", ledger.inspect(scope, Collections.emptySet(), 300_001).entries.get(0).status);
        assertFalse(preferences.getAll().toString().contains("account:1"));
    }

    @Test public void malformedKeysAreCountedAsCorruptAndCannotCrashTheRecordList() throws Exception {
        String scope = SubmissionLedger.accountScope("1");
        ledger.reserve(first, "one", 10, scope);
        preferences.edit().putString("send.x", preferences.getString("send." + first, "")).commit();
        SubmissionLedger.Snapshot snapshot = ledger.inspect(scope, Collections.emptySet(), 300_000);
        assertEquals(2, snapshot.total); assertEquals(1, snapshot.corrupt); assertEquals(1, snapshot.entries.size());
    }

    @Test public void failedLegacyGuardReleaseKeepsTheDurableClaim() throws Exception {
        String scope = SubmissionLedger.accountScope("1");
        ledger.reserve(first, "one", 10, scope); previousProcess(first);
        SubmissionLedger.Entry entry = ledger.inspect(scope, Collections.emptySet(), 300_000).entries.get(0);
        assertFalse(ledger.reconcile(entry, scope, false, 300_000, () -> false));
        assertTrue(ledger.contains(first));
    }

    private void previousProcess(String identity) throws Exception {
        JSONObject record = new JSONObject(preferences.getString("send." + identity, ""));
        preferences.edit().putString("send." + identity, record.put("session", "previous-process").toString()).commit();
    }
}

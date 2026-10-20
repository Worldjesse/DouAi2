package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
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
}

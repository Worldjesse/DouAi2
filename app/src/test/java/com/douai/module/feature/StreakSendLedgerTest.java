package com.douai.module.feature;

import android.content.SharedPreferences;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class StreakSendLedgerTest {
    private SharedPreferences preferences;

    @Before public void reset() {
        preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("streak_ledger_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test public void legacyPendingIsMigratedToUnknownAndIsNotAutomaticallyRetried() {
        String key = "streak.100.2026-10-12.200";
        preferences.edit().putBoolean(key + ".pending", true).commit();

        StreakSendLedger ledger = new StreakSendLedger(preferences);

        StreakSendLedger.Record migrated = ledger.read(key);
        assertEquals(StreakSendLedger.Status.UNKNOWN, migrated.status);
        assertFalse(preferences.contains(key + ".pending"));
        assertEquals(1, ledger.unknownCount());
        assertNull(ledger.begin(key, 1000));
    }

    @Test public void sendingAttemptIsDurableAndCommitFailurePreventsBeginningIt() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger ledger = new StreakSendLedger(preferences);

        StreakSendLedger.Attempt attempt = ledger.begin(key, 1234);
        assertNotNull(attempt);
        assertEquals(StreakSendLedger.Status.SENDING, ledger.read(key).status);
        assertEquals(attempt.token, ledger.read(key).attemptToken);
        assertEquals(1234L, ledger.read(key).timestamp);

        StreakSendLedger failing = new StreakSendLedger(preferences, editor -> false);
        assertNull(failing.begin("streak.100.2026-10-12.201", 2345));
        assertNull(failing.read("streak.100.2026-10-12.201"));
    }

    @Test public void timeoutBecomesUnknownAndMatchingLateSuccessCanResolveIt() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger ledger = new StreakSendLedger(preferences);
        StreakSendLedger.Attempt attempt = ledger.begin(key, 1000);

        assertTrue(ledger.markUnknown(key, attempt.token, 31_000));
        assertEquals(StreakSendLedger.Status.UNKNOWN, ledger.read(key).status);
        assertTrue(ledger.complete(key, attempt.token, true, 32_000));
        assertTrue(preferences.getBoolean(key, false));
        assertEquals(StreakSendLedger.Status.SENT, ledger.read(key).status);
        assertEquals(0, ledger.unknownCount());
    }

    @Test public void timeoutCommitFailureStillBlocksAutomaticRetry() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger initial = new StreakSendLedger(preferences);
        StreakSendLedger.Attempt attempt = initial.begin(key, 1000);
        StreakSendLedger failing = new StreakSendLedger(preferences, editor -> false);

        assertFalse(failing.markUnknown(key, attempt.token, 31_000));
        assertEquals(StreakSendLedger.Status.UNKNOWN, failing.read(key).status);
        assertNull(failing.begin(key, 32_000));
        assertEquals(1, failing.unknownCount());
    }

    @Test public void sendingStateFromPriorProcessRecoversAsUnknown() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger firstProcess = new StreakSendLedger(preferences);
        StreakSendLedger.Attempt attempt = firstProcess.begin(key, 1000);
        assertNotNull(attempt);

        StreakSendLedger restarted = new StreakSendLedger(preferences);

        assertEquals(StreakSendLedger.Status.UNKNOWN, restarted.read(key).status);
        assertEquals(attempt.token, restarted.read(key).attemptToken);
        assertNull(restarted.begin(key, 2000));
    }

    @Test public void recoveryCommitFailureStillReportsUnknownAndBlocksAutomaticRetry() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger initial = new StreakSendLedger(preferences);
        initial.begin(key, 1000);

        StreakSendLedger restarted = new StreakSendLedger(preferences, editor -> false);

        assertEquals(StreakSendLedger.Status.UNKNOWN, restarted.read(key).status);
        assertEquals(1, restarted.unknownCount());
        assertNull(restarted.begin(key, 2000));
    }

    @Test public void migrationCommitFailureRetainsUnknownLegacyPendingInDiagnostics() {
        String key = "streak.100.2026-10-12.200";
        preferences.edit().putBoolean(key + ".pending", true).commit();

        StreakSendLedger ledger = new StreakSendLedger(preferences, editor -> false);

        assertEquals(StreakSendLedger.Status.UNKNOWN, ledger.read(key).status);
        assertEquals(1, ledger.unknownCount());
        assertTrue(preferences.getBoolean(key + ".pending", false));
        assertNull(ledger.begin(key, 2000));
    }

    @Test public void readOnlyDiagnosticCountsSendingUnknownAndLegacyPendingOnce() {
        String sending = "streak.100.2026-10-12.200";
        String legacy = "streak.100.2026-10-12.201";
        String failed = "streak.100.2026-10-12.202";
        StreakSendLedger ledger = new StreakSendLedger(preferences);
        ledger.begin(sending, 1000);
        preferences.edit().putBoolean(legacy + ".pending", true).commit();
        StreakSendLedger.Attempt failedAttempt = ledger.begin(failed, 1000);
        ledger.complete(failed, failedAttempt.token, false, 2000);

        assertEquals(2, StreakSendLedger.countUnknown(preferences));
    }

    @Test public void explicitUnknownRetryCreatesNewAttemptAndOldCallbackCannotOverwriteIt() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger ledger = new StreakSendLedger(preferences);
        StreakSendLedger.Attempt original = ledger.begin(key, 1000);
        assertTrue(ledger.markUnknown(key, original.token, 31_000));

        assertNull(ledger.begin(key, 32_000));
        assertTrue(ledger.updateRetryUnknownToken("retry-1"));
        StreakSendLedger.Attempt retry = ledger.begin(key, 33_000);
        assertNotNull(retry);
        assertNotEquals(original.token, retry.token);
        assertEquals(StreakSendLedger.Status.SENDING, ledger.read(key).status);

        assertFalse(ledger.complete(key, original.token, true, 34_000));
        assertEquals(StreakSendLedger.Status.SENDING, ledger.read(key).status);
        assertFalse(preferences.getBoolean(key, false));

        assertTrue(ledger.complete(key, retry.token, false, 35_000));
        assertEquals(StreakSendLedger.Status.FAILED, ledger.read(key).status);
        StreakSendLedger.Attempt nextScheduledCheck = ledger.begin(key, 65_000);
        assertNotNull(nextScheduledCheck);
        assertFalse(ledger.complete(key, original.token, true, 66_000));
        assertEquals(nextScheduledCheck.token, ledger.read(key).attemptToken);
    }

    @Test public void oneUnknownRetryTokenCanOnlyStartOneRetryForThatRecord() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger ledger = new StreakSendLedger(preferences);
        StreakSendLedger.Attempt first = ledger.begin(key, 1000);
        ledger.markUnknown(key, first.token, 31_000);

        assertTrue(ledger.updateRetryUnknownToken("retry-1"));
        StreakSendLedger.Attempt retry = ledger.begin(key, 32_000);
        assertNotNull(retry);
        assertTrue(ledger.markUnknown(key, retry.token, 63_000));
        assertNull(ledger.begin(key, 64_000));

        StreakSendLedger restarted = new StreakSendLedger(preferences);
        assertNull(restarted.begin(key, 64_500));
        assertTrue(restarted.updateRetryUnknownToken("retry-2"));
        assertNotNull(restarted.begin(key, 65_000));
    }

    @Test public void resetClearsDailySuccessAndRejectsCallbacksFromOldAttempt() {
        String key = "streak.100.2026-10-12.200";
        StreakSendLedger ledger = new StreakSendLedger(preferences);
        StreakSendLedger.Attempt original = ledger.begin(key, 1000);

        assertTrue(ledger.reset("reset-1"));
        assertFalse(ledger.complete(key, original.token, true, 2000));
        StreakSendLedger.Attempt retry = ledger.begin(key, 3000);
        assertNotNull(retry);
        assertTrue(ledger.complete(key, retry.token, true, 4000));
        assertNull(ledger.begin(key, 5000));

        assertTrue(ledger.reset("reset-1"));
        assertNull(ledger.begin(key, 6000));
        assertTrue(ledger.reset("reset-2"));
        assertNotNull(ledger.begin(key, 7000));
    }
}

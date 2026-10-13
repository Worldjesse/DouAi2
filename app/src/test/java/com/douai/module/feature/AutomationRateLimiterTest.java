package com.douai.module.feature;

import android.content.Context;
import android.content.SharedPreferences;

import com.douai.module.config.LocalOptions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AutomationRateLimiterTest {
    private SharedPreferences preferences;
    private MutableClock clock;
    private AutomationRateLimiter limiter;

    @Before public void reset() {
        preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("automation_rate_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        clock = new MutableClock("2026-10-13T15:59:00Z");
        limiter = new AutomationRateLimiter(preferences, clock, SharedPreferences.Editor::commit);
    }

    @Test public void defaultsAllowRepeatedSendsAndPersistForLaterLimits() {
        LocalOptions unlimited = new LocalOptions(Collections.emptyMap());
        assertNotNull(limiter.tryReserve("comment", 100, "200", unlimited));
        assertNotNull(limiter.tryReserve("comment", 100, "200", unlimited));
        assertTrue(limiter.canSend("comment", 100, "200", unlimited));
        assertFalse(limiter.canSend("comment", 100, "201", options("comment", 2, 0)));
    }

    @Test public void dailyLimitIsSeparateForAccountsAndKindsAndSurvivesRecreation() {
        LocalOptions options = options("comment", 1, 0);
        assertNotNull(limiter.tryReserve("comment", 100, "200", options));
        AutomationRateLimiter recreated = new AutomationRateLimiter(preferences, clock, SharedPreferences.Editor::commit);
        assertFalse(recreated.canSend("comment", 100, "201", options));
        assertNull(recreated.tryReserve("comment", 100, "201", options));
        assertNotNull(recreated.tryReserve("comment", 101, "201", options));
        assertNotNull(recreated.tryReserve("reply", 100, "200", options("reply", 1, 0)));
    }

    @Test public void localMidnightResetsCountButPreservesUserInterval() {
        LocalOptions options = options("reply", 1, 10);
        assertNotNull(limiter.tryReserve("reply", 100, "200", options));
        clock.advance(60_000);
        assertTrue(limiter.canSend("reply", 100, "201", options));
        assertFalse(limiter.canSend("reply", 100, "200", options));
        clock.advance(9 * 60_000);
        assertTrue(limiter.canSend("reply", 100, "200", options));
    }

    @Test public void changingTimeZoneUsesCurrentLocalDateWithoutResettingCooldown() {
        java.util.concurrent.atomic.AtomicReference<ZoneId> zone =
                new java.util.concurrent.atomic.AtomicReference<>(ZoneId.of("Asia/Shanghai"));
        AutomationRateLimiter changing = new AutomationRateLimiter(preferences, clock,
                SharedPreferences.Editor::commit, zone::get);
        LocalOptions options = options("reply", 1, 10);
        assertNotNull(changing.tryReserve("reply", 100, "200", options));
        clock.advance(60_000);
        assertTrue(changing.canSend("reply", 100, "201", options));
        zone.set(ZoneId.of("UTC"));
        assertFalse(changing.canSend("reply", 100, "201", options));
        zone.set(ZoneId.of("Asia/Shanghai"));
        assertTrue(changing.canSend("reply", 100, "201", options));
        assertFalse(changing.canSend("reply", 100, "200", options));
    }

    @Test public void failedReservationReleaseIsIdempotentAndDoesNotEraseLaterSends() {
        LocalOptions options = options("comment", 2, 0);
        AutomationRateLimiter.Reservation first = limiter.tryReserve("comment", 100, "200", options);
        AutomationRateLimiter.Reservation second = limiter.tryReserve("comment", 100, "200", options);
        assertNotNull(second);
        limiter.release(first);
        limiter.release(first);
        assertFalse(limiter.canSend("comment", 100, "200", options("comment", 0, 5)));
        assertNotNull(limiter.tryReserve("comment", 100, "201", options));
        assertFalse(limiter.canSend("comment", 100, "202", options));
    }

    @Test public void releasingLatestSendRestoresEarlierUserCooldown() {
        LocalOptions unlimited = options("reply", 0, 0);
        AutomationRateLimiter.Reservation first = limiter.tryReserve("reply", 100, "200", unlimited);
        clock.advance(60_000);
        AutomationRateLimiter.Reservation second = limiter.tryReserve("reply", 100, "200", unlimited);
        limiter.release(second);
        assertFalse(limiter.canSend("reply", 100, "200", options("reply", 0, 5)));
        limiter.release(first);
        assertTrue(limiter.canSend("reply", 100, "200", options("reply", 0, 5)));
    }

    @Test public void failedPersistenceNeverAuthorizesNativeSend() {
        AutomationRateLimiter failed = new AutomationRateLimiter(preferences, clock, editor -> false);
        assertNull(failed.tryReserve("reply", 100, "200", options("reply", 1, 5)));
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void sharedLockPreventsConcurrentInstancesExceedingDailyLimit() throws Exception {
        AutomationRateLimiter other = new AutomationRateLimiter(preferences, clock, SharedPreferences.Editor::commit);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AutomationRateLimiter.Reservation> first = executor.submit(() -> {
                start.await(); return limiter.tryReserve("reply", 100, "200", options("reply", 1, 0));
            });
            Future<AutomationRateLimiter.Reservation> second = executor.submit(() -> {
                start.await(); return other.tryReserve("reply", 100, "201", options("reply", 1, 0));
            });
            start.countDown();
            assertEquals(1, (first.get() == null ? 0 : 1) + (second.get() == null ? 0 : 1));
        } finally { executor.shutdownNow(); }
    }

    @Test public void cooldownSettingIncreaseStillSeesEarlierSends() {
        assertNotNull(limiter.tryReserve("reply", 100, "200", options("reply", 0, 1)));
        clock.advance(2 * 60_000);
        assertTrue(limiter.canSend("reply", 100, "200", options("reply", 0, 1)));
        assertFalse(limiter.canSend("reply", 100, "200", options("reply", 0, 60)));
    }

    @Test public void oldRecordsArePrunedOnlyAfterMaximumSupportedCooldown() {
        LocalOptions unlimited = options("reply", 0, 0);
        assertNotNull(limiter.tryReserve("reply", 100, "200", unlimited));
        clock.advance(525_599L * 60_000);
        assertNotNull(limiter.tryReserve("reply", 100, "201", unlimited));
        assertEquals(2, preferences.getAll().size());
        assertFalse(limiter.canSend("reply", 100, "200", options("reply", 0, 525_600)));
        clock.advance(60_000);
        assertNotNull(limiter.tryReserve("reply", 100, "202", unlimited));
        assertEquals(2, preferences.getAll().size());
        assertTrue(limiter.canSend("reply", 100, "200", options("reply", 0, 525_600)));
    }

    @Test public void unknownUserIsRejectedWhenCooldownRequiresIdentity() {
        assertFalse(limiter.canSend("reply", 100, "", options("reply", 0, 5)));
        assertNull(limiter.tryReserve("reply", 100, "", options("reply", 0, 5)));
        assertNotNull(limiter.tryReserve("reply", 100, "", options("reply", 5, 0)));
    }

    @Test public void corruptDurableStateCannotSilentlyResetLimits() {
        preferences.edit().putString("send.invalid", "invalid").commit();
        assertFalse(limiter.canSend("reply", 100, "200", options("reply", 1, 0)));
        assertNull(limiter.tryReserve("reply", 100, "200", options("reply", 1, 0)));
    }

    private static LocalOptions options(String kind, int daily, int interval) {
        Map<String, Object> values = new HashMap<>();
        values.put(kind + "_daily_limit", daily);
        values.put(kind + "_user_cooldown_minutes", interval);
        return new LocalOptions(values);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(String now) { this.now = Instant.parse(now); }
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}

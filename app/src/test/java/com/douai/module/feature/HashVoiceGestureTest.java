package com.douai.module.feature;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class HashVoiceGestureTest {
    @Test public void confirmationCanClaimLaterButNeverTwice() {
        HashVoiceGesture token = gesture(() -> true, () -> true);
        assertTrue(token.matches("#draft", "work", "service", "reply"));
        assertTrue(token.claim());
        assertFalse(token.claim());
        assertTrue(token.canSend());
    }

    @Test public void matchingNeverCrossesWorksRepliesServicesOrDrafts() {
        HashVoiceGesture token = gesture(() -> true, () -> true);
        assertFalse(token.matches("#changed", "work", "service", "reply"));
        assertFalse(token.matches("#draft", "other-work", "service", "reply"));
        assertFalse(token.matches("#draft", "work", "other-service", "reply"));
        assertFalse(token.matches("#draft", "work", "service", "other-reply"));
        assertFalse(token.matches("#draft", "work", "service", ""));
    }

    @Test public void contextOrDraftChangesInvalidateAClaimedAsynchronousResult() {
        AtomicBoolean current = new AtomicBoolean(true);
        HashVoiceGesture token = gesture(current::get, () -> true);
        assertTrue(token.claim());
        current.set(false);
        assertFalse(token.canSend());
        // Its identity is retained even after invalidation, so late native text stays consumed.
        assertTrue(token.matches("#draft", "work", "service", "reply"));
        assertFalse(token.claim());
    }

    @Test public void confirmingDialogCanTemporarilyLackFocusButCannotSendUntilItReturns() {
        AtomicBoolean focused = new AtomicBoolean(false);
        HashVoiceGesture token = gesture(() -> true, focused::get);
        assertTrue(token.claim());
        assertFalse(token.canSend());
        focused.set(true);
        assertTrue(token.canSend());
    }

    @Test public void cancellationDoesNotForgetTheGuardOrAllowASecondClaim() {
        HashVoiceGesture token = gesture(() -> true, () -> true);
        token.cancel();
        assertFalse(token.claim());
        assertFalse(token.canSend());
        assertTrue(token.matches("#draft", "work", "service", "reply"));
    }

    @Test public void failingLiveValidationIsFailClosed() {
        HashVoiceGesture token = gesture(() -> { throw new IllegalStateException(); }, () -> true);
        assertFalse(token.claim());
        assertFalse(token.canSend());
    }

    @Test public void nativeClickDraftRestorationIsExplicitAndNeverRunsDuringMatching() {
        AtomicInteger restored = new AtomicInteger();
        HashVoiceGesture token = new HashVoiceGesture("#draft", "work", "service", "reply",
                () -> true, () -> true, restored::incrementAndGet);
        token.matches("#draft", "work", "service", "reply");
        token.claim();
        assertEquals(0, restored.get());
        token.restoreAfterNativeClick();
        assertEquals(1, restored.get());
    }

    private static HashVoiceGesture gesture(java.util.function.BooleanSupplier current,
                                             java.util.function.BooleanSupplier sendable) {
        return new HashVoiceGesture("#draft", "work", "service", "reply", current, sendable, () -> { });
    }
}

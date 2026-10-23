package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class HashVoiceGestureTest {
    @Test public void deferredNativeConfirmationLinksToItsOriginAfterTheClickHasFinished() throws Exception {
        java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        assertTrue(DouAiLog.awaitIdle(5000));
        DouAiLog.setFrameworkSink((priority, line) -> lines.add(line));
        try {
            DouAiLog.Operation click = DouAiLog.begin("hash_long_press_voice", "hash_long_click");
            java.util.concurrent.atomic.AtomicReference<HashVoiceGesture> saved = new java.util.concurrent.atomic.AtomicReference<>();
            click.wrap(() -> saved.set(gesture(() -> true, () -> true))).run();
            click.finish(DouAiLog.Outcome.UNCONFIRMED, "native_click_handoff", "final_publication=unobserved");
            java.util.concurrent.atomic.AtomicReference<DouAiLog.Operation> confirmed = new java.util.concurrent.atomic.AtomicReference<>();
            AtomicBoolean contextRestored = new AtomicBoolean();
            Thread nativeConfirmation = new Thread(() -> {
                confirmed.set(saved.get().beginSpeechOperation());
                contextRestored.set(DouAiLog.current() == null);
            });
            nativeConfirmation.start(); nativeConfirmation.join(5000);
            assertFalse(nativeConfirmation.isAlive());
            assertNotNull(confirmed.get());
            assertTrue(contextRestored.get());
            assertNotEquals(click.traceId(), confirmed.get().traceId());
            confirmed.get().finish(DouAiLog.Outcome.UNCONFIRMED, "native_audio_comment_submitted", "remote_ack=false");
            assertTrue(DouAiLog.awaitIdle(5000));
            assertTrue(lines.stream().anyMatch(line -> line.contains("\"trace_id\":\"" + confirmed.get().traceId() + "\"")
                    && line.contains("\"linked_trace_id\":\"" + click.traceId() + "\"")));
            assertTrue(saved.get().claim());
            assertFalse(saved.get().claim());
        } finally {
            DouAiLog.awaitIdle(5000); DouAiLog.setFrameworkSink(null);
        }
    }

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

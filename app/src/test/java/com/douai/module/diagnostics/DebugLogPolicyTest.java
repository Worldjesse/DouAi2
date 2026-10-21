package com.douai.module.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DebugLogPolicyTest {
    @Test public void basicModeKeepsOutcomesAndErrorsWithoutStepOrCommentPayloadLogs() {
        assertTrue(DebugLogPolicy.isBasic(3, "DouAi 0.2.10 loaded"));
        assertTrue(DebugLogPolicy.isBasic(3, "Host scan complete: rules=229"));
        assertTrue(DebugLogPolicy.isBasic(3, "Automatic comment queue outcome=sent"));
        assertTrue(DebugLogPolicy.isBasic(3, "Automatic reply outcome: sent"));
        assertTrue(DebugLogPolicy.isBasic(3, "Automatic reply outcome: submitted"));
        assertTrue(DebugLogPolicy.isBasic(3, "Automatic comment_reply outcome: sent"));
        assertFalse(DebugLogPolicy.isBasic(3, "Diagnostic payload outcome: sent"));
        assertTrue(DebugLogPolicy.isBasic(5, "A hook is unavailable"));
        assertTrue(DebugLogPolicy.isBasic(3, "Could not read current input"));
        assertTrue(DebugLogPolicy.isBasic(3, "Comment reply ledger worker unavailable"));
        assertTrue(DebugLogPolicy.isBasic(3, "Native message callback timed out"));
        assertFalse(DebugLogPolicy.isBasic(3, "comment_model=UNIQUE"));
        assertFalse(DebugLogPolicy.isBasic(3, "Automatic comment model request started"));
        assertFalse(DebugLogPolicy.isBasic(3, "[DouAi.CommentJson] {\"status\":\"success\"}"));
        assertFalse(DebugLogPolicy.isBasic(3, "[DouAi.Trace] hook=Feed.bind"));
        assertEquals("Automatic comment native callback: sent; code=0", DebugLogPolicy.basicMessage(
                "Automatic comment native callback: sent; response=Reply; code=0; audio_receipt=present"));
        assertEquals("Automatic comment native callback: failed; code=5; audio_hint=duration; kind=root; media=voice",
                DebugLogPolicy.basicMessage("Automatic comment native callback: failed; response=Failure; code=5"
                        + "; audio_hint=duration; audio_permission=0; kind=root; media=voice"));
        assertFalse(DebugLogPolicy.isBasic(3, "Comment reply inbox scan complete: pages=1; diagnostic_errors=0"));
        assertTrue(DebugLogPolicy.isBasic(3, "Comment reply inbox scan complete: pages=1; diagnostic_errors=2"));
        assertFalse(DebugLogPolicy.isBasic(3, "Automatic comment selection skipped: target/account/foreground unavailable"));
        assertFalse(DebugLogPolicy.isBasic(3, "Comment reply notice skipped; unsupported or missing payload"));
    }
}

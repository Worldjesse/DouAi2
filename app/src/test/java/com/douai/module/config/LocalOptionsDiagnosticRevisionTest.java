package com.douai.module.config;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class LocalOptionsDiagnosticRevisionTest {
    @Test public void revisionUsesTheOwnedSnapshotAndCannotDriftWithTheSourceMapOrProcessRevision() {
        Map<String, Object> values = new HashMap<>();
        values.put("auto_comment_text", "captured-old-text");
        LocalOptions snapshot = new LocalOptions(values);
        values.put("auto_comment_text", "later-text");
        String oldRevision = snapshot.diagnosticRevision();
        assertEquals(com.douai.module.diagnostics.DouAiLog.configurationRevision(Map.of("auto_comment_text", "captured-old-text")), oldRevision);
        com.douai.module.diagnostics.DouAiLog.configRevision("process-revision-after-publication");
        assertSame("A snapshot computes the diagnostic digest once", oldRevision, snapshot.diagnosticRevision());
        assertNotEquals(new LocalOptions(values).diagnosticRevision(), oldRevision);
        assertEquals("captured-old-text", snapshot.text("auto_comment_text", ""));
    }

    @Test public void withDefaultGetsItsOwnRevisionOnlyWhenItsEffectiveValuesChange() {
        LocalOptions original = new LocalOptions(Map.of("reply_mode", "fixed"));
        String originalRevision = original.diagnosticRevision();
        assertSame(original, original.withDefault("reply_mode", "ai"));
        LocalOptions derived = original.withDefault("reply_daily_limit", "20");
        assertNotEquals(originalRevision, derived.diagnosticRevision());
        assertEquals(originalRevision, original.diagnosticRevision());
        assertEquals("20", derived.text("reply_daily_limit", ""));
        assertEquals("", original.text("reply_daily_limit", ""));
    }

    @Test public void revisionIncludesValuesPastTheOldSamplingBoundaryAndHasStableKeyOrdering() {
        Map<String, Object> before = new java.util.LinkedHashMap<>(), after = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 600; i++) before.put(String.format(java.util.Locale.ROOT, "key_%04d", i), "same-value");
        java.util.List<String> keys = new java.util.ArrayList<>(before.keySet());
        java.util.Collections.reverse(keys);
        for (String key : keys) after.put(key, before.get(key));
        assertEquals(new LocalOptions(before).diagnosticRevision(), new LocalOptions(after).diagnosticRevision());
        after.put("key_0599", "different-effective-value");
        assertNotEquals(new LocalOptions(before).diagnosticRevision(), new LocalOptions(after).diagnosticRevision());
    }
}

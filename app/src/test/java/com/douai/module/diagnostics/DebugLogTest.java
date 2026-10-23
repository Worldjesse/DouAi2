package com.douai.module.diagnostics;

import android.content.Context;
import android.content.ContextWrapper;
import com.douai.module.config.SettingsRepository;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DebugLogTest {
    @Test public void startupKeepsCompleteJsonAndProtectsStartsAndTerminalsUnderFlood() throws Exception {
        Field field = DebugLog.class.getDeclaredField("store"); field.setAccessible(true);
        DebugLogStore old = (DebugLogStore) field.get(null); if (old != null) old.close(); field.set(null, null);
        String start = "{\"session_id\":\"early\",\"seq\":\"1\",\"event\":\"operation_started\",\"trace_id\":\"early-trace\",\"evidence\":\"" + "safe".repeat(1500) + "\"}";
        String terminal = "{\"session_id\":\"early\",\"seq\":\"200\",\"event\":\"operation_finished\",\"trace_id\":\"early-trace\"}";
        DebugLog.recordStructured(4, start);
        for (int seq = 2; seq < 190; seq++) DebugLog.recordStructured(4, "{\"session_id\":\"early\",\"seq\":\"" + seq + "\",\"event\":\"stage\"}");
        Context context = host();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(DebugLog.KEY, true).commit();
        DebugLog.recordStructured(4, terminal); DebugLog.initialize(context);
        String text = read(); boolean sawStart = false, sawEnd = false;
        for (String line : text.split("\n")) {
            int json = line.indexOf('{'); if (json < 0) continue;
            org.json.JSONObject event = new org.json.JSONObject(line.substring(json));
            if (!"early".equals(event.optString("session_id"))) continue;
            if ("operation_started".equals(event.optString("event"))) {
                assertEquals("safe".repeat(1500), event.getString("evidence")); sawStart = true;
            }
            if ("operation_finished".equals(event.optString("event"))) sawEnd = true;
        }
        assertTrue(sawStart); assertTrue(sawEnd);
        assertFalse(text.contains("[record truncated]"));
    }

    @Test public void moduleProcessCanExportItsOwnPrivateLogsWithoutAHostConnection() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        Context moduleOwner = new ContextWrapper(application) {
            @Override public String getPackageName() { return com.douai.module.BuildConfig.APPLICATION_ID; }
            @Override public Context getApplicationContext() { return this; }
            @Override public java.io.File getFilesDir() { return new java.io.File(application.getFilesDir(), "module-process-export-fixture"); }
        };
        DebugLog.initialize(moduleOwner);
        DouAiLog.event("diagnostics", "module_only_evidence", "local_process", "host_connection=not_required");
        AtomicReference<String> result = new AtomicReference<>(); DebugLog.exportOwnProcess(moduleOwner, result::set);
        for (int index = 0; index < 1250 && result.get() == null; index++) { ShadowLooper.idleMainLooper(); Thread.sleep(4); }
        assertNotNull(result.get()); assertTrue(result.get().contains("module_only_evidence"));
        assertTrue(result.get().contains("storage_integrity")); assertFalse(DebugLog.UNAVAILABLE_TEXT.equals(result.get()));
    }
    @Test public void fullExportPreservesIntegrityPrefixWhenBodyExhaustsCapacity() {
        String prefix = "{\"event\":\"integrity_manifest\"}\n{\"event\":\"storage_integrity\"}\n{\"event\":\"startup_integrity\"}\n";
        String result = DebugLog.exportText(prefix, "body".repeat(DebugLog.MAX_EXPORT_BYTES / 4), DebugLog.MAX_EXPORT_BYTES);
        assertTrue(result.startsWith(prefix)); assertTrue(result.contains("[tail truncated]"));
        assertTrue(result.length() <= DebugLog.MAX_EXPORT_BYTES);
    }
    @After public void reset() {
        DebugLog.setEnabled(false); DebugLog.setClearListener(null);
        host().getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().remove(DebugLog.KEY).commit();
    }

    @Test public void disabledModeAddsOnlyBasicAndRetainsEarlierDetailedHistory() throws Exception {
        Context context = host();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().remove(DebugLog.KEY).commit();
        DebugLog.initialize(context); DebugLog.setEnabled(false);
        DebugLog.recordBasic(4, "test", "basic-in-disabled");
        DebugLog.record(3, "test", "must-not-record-disabled");
        String text = read();
        assertTrue(text.contains("basic-in-disabled")); assertFalse(text.contains("must-not-record-disabled"));
        DebugLog.setEnabled(true); DebugLog.record(3, "test", "retained-detailed-history"); read();
        DebugLog.setEnabled(false); DebugLog.recordBasic(4, "test", "new-simple-history");
        DebugLog.record(3, "test", "must-not-record-after-off"); text = read();
        assertTrue(text.contains("retained-detailed-history")); assertTrue(text.contains("new-simple-history"));
        assertFalse(text.contains("must-not-record-after-off"));
    }

    @Test public void successfulClearCallsListenerOnceAndListenerFailureCannotCancelCallback() throws Exception {
        Context context = host(); DebugLog.initialize(context);
        AtomicInteger calls = new AtomicInteger(); AtomicReference<Boolean> result = new AtomicReference<>();
        DebugLog.setClearListener(() -> { calls.incrementAndGet(); throw new IllegalStateException("listener failure"); });
        DebugLog.clear(context, result::set);
        for (int index = 0; index < 250 && result.get() == null; index++) { ShadowLooper.idleMainLooper(); Thread.sleep(4); }
        assertEquals(Boolean.TRUE, result.get()); assertEquals(1, calls.get());
        DebugLog.setClearListener(null);
    }

    private static Context host() {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() { return ScanDiagnosticsChannel.HOST_PACKAGE; }
            @Override public Context getApplicationContext() { return this; }
        };
    }
    private static String read() throws Exception {
        Field field = DebugLog.class.getDeclaredField("store"); field.setAccessible(true);
        DebugLogStore store = (DebugLogStore) field.get(null);
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> text = new AtomicReference<>();
        store.read(DebugLog.MAX_EXPORT_BYTES, true, value -> { text.set(value); done.countDown(); });
        assertTrue(done.await(5, TimeUnit.SECONDS)); return text.get();
    }
}

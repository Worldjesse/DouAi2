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
    @After public void reset() { DebugLog.setEnabled(false); DebugLog.setClearListener(null); }

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

package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.diagnostics.DouAiLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Observes real import actions and Android's Toast boundary, without replacing ConfigBackup.apply. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.ss.android.ugc.aweme")
@LooperMode(LooperMode.Mode.PAUSED)
public final class ConfigTransferToastTest {
    private Activity activity;
    private Store store;
    private Files files;
    private Picker picker;
    private Worker worker;
    private ConfigTransferDialog page;
    private int changes;
    private final java.util.List<String> evidence = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Before public void setup() {
        assertTrue(DouAiLog.awaitIdle(5000));
        DouAiLog.clearPending();
        DouAiLog.setFrameworkSink((priority, line) -> evidence.add(line));
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new Store(); files = new Files(); picker = new Picker(); worker = new Worker();
        show(() -> changes++);
        ShadowToast.reset();
    }

    @After public void close() {
        page.close(); page.dialog().dismiss();
        activity.finish(); ShadowLooper.idleMainLooper(); ShadowToast.reset();
        assertTrue(DouAiLog.awaitIdle(5000)); DouAiLog.setFrameworkSink(null);
    }

    @Test public void successfulApplyShowsOneHostToastOnMainBeforeChangedCanCloseThePage() throws Exception {
        page.close(); page.dialog().dismiss();
        show(() -> {
            changes++;
            assertSame("The completion callback belongs to main", Looper.getMainLooper(), Looper.myLooper());
            assertEquals("The successful import must notify before settings rebuild closes its page", 1, ShadowToast.shownToastCount());
            assertEquals("备份导入成功", ShadowToast.getTextOfLatestToast());
            Context toastContext = ReflectionHelpers.getField(ShadowToast.getLatestToast(), "mContext");
            assertSame("Use the host application rather than the dismissed dialog context", activity.getApplicationContext(), toastContext);
            assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().getDuration());
            page.close(); page.dialog().dismiss();
        });
        preview();
        assertEquals(0, ShadowToast.shownToastCount());
        view("confirm").performClick();
        view("confirm").performClick();
        assertEquals(0, store.applies);
        worker.runOffMain();
        assertEquals(1, store.applies);
        assertEquals("Worker completion must not display a Toast before the main callback", 0, ShadowToast.shownToastCount());
        ShadowLooper.idleMainLooper();
        assertEquals(1, changes);
        assertEquals(1, ShadowToast.shownToastCount());
        assertFalse(page.dialog().isShowing());
        ShadowLooper.idleMainLooper();
        assertEquals("Closing the rebuilt page cannot duplicate the notification", 1, ShadowToast.shownToastCount());
    }

    @Test public void previewCancellationAndExportNeverAnnounceAnImportSuccess() throws Exception {
        view("export").performClick(); picker.select(); finish();
        assertEquals(0, ShadowToast.shownToastCount());
        preview();
        assertTrue(view("confirm").isEnabled());
        assertEquals(0, ShadowToast.shownToastCount());
        view("cancel").performClick();
        view("confirm").performClick(); finish();
        assertEquals(0, store.applies);
        assertEquals(0, changes);
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void invalidDataAndFailedApplyNeverShowASuccessToast() throws Exception {
        files.input = "{invalid-fixture".getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        assertFalse(view("confirm").isEnabled());
        assertEquals(0, ShadowToast.shownToastCount());
        preview();
        store.failApply = true;
        view("confirm").performClick(); finish();
        assertEquals(1, store.applyAttempts);
        assertEquals(0, store.applies);
        assertEquals(0, changes);
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void closingAfterCommittedWorkButBeforeItsUiCallbackSuppressesTheStaleToast() throws Exception {
        preview();
        view("confirm").performClick(); worker.runOffMain();
        assertEquals(1, store.applies);
        page.close(); page.dialog().dismiss(); ShadowLooper.idleMainLooper();
        assertEquals("A later callback cannot notify through a closed page", 0, ShadowToast.shownToastCount());
        assertEquals(0, changes);
    }

    @Test public void closingBeforeQueuedApplyStartsDoesNotNotifyOrWrite() throws Exception {
        preview(); view("confirm").performClick();
        page.close(); page.dialog().dismiss(); finish();
        assertEquals(0, store.applyAttempts);
        assertEquals(0, store.applies);
        assertEquals(0, changes);
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test @Config(shadows = RejectingToast.class)
    public void platformToastFailureCannotUndoTheImportOrBlockItsChangedCallback() throws Exception {
        preview(); view("confirm").performClick(); finish();
        assertEquals(1, store.applies);
        assertEquals(1, changes);
        assertEquals(0, ShadowToast.shownToastCount());
        assertTrue(DouAiLog.awaitIdle(5000));
        java.util.List<org.json.JSONObject> events = new java.util.ArrayList<>();
        for (String line : evidence) events.add(new org.json.JSONObject(line));
        assertTrue(events.stream().anyMatch(log -> "config_transfer".equals(log.optString("feature_id"))
                && "operation_finished".equals(log.optString("event")) && "success".equals(log.optString("outcome"))
                && "backup_applied".equals(log.optString("reason_code"))));
        String diagnosticReference = DouAiLog.reference("ConfigTransfer import_success_toast_unavailable");
        assertTrue(events.stream().anyMatch(log -> "legacy_diagnostic".equals(log.optString("event"))
                && log.optString("evidence").contains("ref=" + diagnosticReference)
                && log.optString("evidence").contains("source_level=5")));
        for (org.json.JSONObject log : events) {
            assertFalse(log.toString().contains("private-toast-platform-detail"));
            assertFalse(log.has("exception_class"));
        }
    }

    @Implements(Toast.class)
    public static final class RejectingToast extends ShadowToast {
        @Implementation @Override protected void show() {
            throw new IllegalStateException("private-toast-platform-detail");
        }
    }

    private void show(Runnable changed) {
        page = new ConfigTransferDialog(activity, store, false, changed, files, picker, worker);
        page.dialog().show(); ShadowLooper.idleMainLooper();
    }

    private void preview() throws Exception {
        Store source = new Store(); source.values.put("native_auto_reply", true);
        files.input = ConfigTransfer.export(source, true).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        assertTrue("A valid file must reach preview before confirmation", view("confirm").isEnabled());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    private View view(String tag) {
        return page.dialog().getWindow().getDecorView().findViewWithTag("config.backup." + tag);
    }

    private void finish() throws Exception { worker.runOffMain(); ShadowLooper.idleMainLooper(); }

    private static final class Worker implements java.util.concurrent.Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runOffMain() throws Exception {
            FutureTask<Void> drain = new FutureTask<>(() -> {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper());
                while (!tasks.isEmpty()) tasks.remove().run();
                return null;
            });
            new Thread(drain, "backup-toast-test-worker").start();
            drain.get(5, TimeUnit.SECONDS);
        }
    }

    private static final class Files implements ConfigTransferDialog.Files {
        byte[] input;
        @Override public InputStream read(Uri uri) { return new ByteArrayInputStream(input); }
        @Override public OutputStream write(Uri uri) { return new ByteArrayOutputStream(); }
    }

    private static final class Picker implements ConfigTransferDialog.Picker {
        Consumer<Uri> selected;
        @Override public AutoCloseable launch(Activity owner, boolean export, String name, Consumer<Uri> selected) {
            this.selected = selected; return () -> { };
        }
        void select() { selected.accept(Uri.parse("content://backup-toast/fixture.json")); }
    }

    private static final class Store implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        int applies, applyAttempts; boolean failApply;
        @Override public FeatureSettings load() { return FeatureSettings.defaults(); }
        @Override public void save(FeatureSettings value) { }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.get(key) instanceof Boolean ? (Boolean) values.get(key) : fallback; }
        @Override public String getString(String key, String fallback) { return values.get(key) instanceof String ? (String) values.get(key) : fallback; }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); }
        @Override public void putString(String key, String value) { values.put(key, value); }
        @Override public Map<String, ?> snapshot() { return new LinkedHashMap<>(values); }
        @Override public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            applyAttempts++;
            if (failApply || !values.equals(expected)) throw new IllegalStateException("fixture-apply-failure");
            values.clear(); values.putAll(replacement); applies++;
        }
    }
}

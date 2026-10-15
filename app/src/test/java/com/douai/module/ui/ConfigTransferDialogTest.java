package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigTransferDialogTest {
    private Activity activity;
    private MemoryStore store;
    private Files files;
    private Picker picker;
    private ManualExecutor executor;
    private ConfigTransferDialog page;
    private int changes;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore(); files = new Files(); picker = new Picker(); executor = new ManualExecutor();
        page = new ConfigTransferDialog(activity, store, false, () -> changes++, files, picker, executor);
        page.dialog().show(); ShadowLooper.idleMainLooper();
    }
    @After public void tearDown() {
        page.close(); page.dialog().dismiss(); activity.finish(); ShadowLooper.idleMainLooper();
    }

    @Test public void exportExcludesSecretsUnlessExplicitlySelectedAndRunsOffTheUiCallback() throws Exception {
        store.values.put("native_auto_reply", true);
        store.values.put("third_party_app_secret", "private-export-key");
        assertFalse(((CheckBox) view("secrets")).isChecked());
        view("export").performClick(); picker.select();
        assertEquals(0, files.writes);
        finish();
        assertEquals(1, files.writes);
        assertFalse(files.output.toString("UTF-8").contains("private-export-key"));
        assertEquals(0, store.applies);
        ((CheckBox) view("secrets")).setChecked(true);
        view("export").performClick(); picker.select(); finish();
        assertTrue(files.output.toString("UTF-8").contains("private-export-key"));
        assertFalse(allText(page.dialog().getWindow().getDecorView()).contains("private-export-key"));
    }

    @Test public void previewIsReadOnlyAndExplicitConfirmationAppliesWithoutDisplayingValues() throws Exception {
        MemoryStore source = new MemoryStore();
        source.values.put("native_auto_reply", true);
        source.values.put("third_party_app_secret", "private-import-key");
        files.input = ConfigTransfer.export(source, true).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select();
        assertEquals(0, files.reads); finish();
        assertEquals(0, store.applies);
        assertTrue(view("confirm").isEnabled());
        String preview = ((TextView) view("preview")).getText().toString();
        assertTrue(preview.contains("格式版本"));
        assertTrue(preview.contains("native_auto_reply"));
        assertTrue(preview.contains("立即生效"));
        assertFalse(preview.contains("private-import-key"));
        view("confirm").performClick();
        assertEquals(0, store.applies); finish();
        assertEquals(1, store.applies);
        assertEquals(Boolean.TRUE, store.values.get("native_auto_reply"));
        assertEquals(1, changes);
    }

    @Test public void cancellingAndClosingRejectQueuedWorkLatePickerResultsAndOldConfirmation() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); view("cancel").performClick(); finish();
        assertEquals(0, files.reads); assertEquals(0, store.applies);
        view("import").performClick(); picker.select(); finish();
        View confirm = view("confirm");
        assertTrue(confirm.isEnabled());
        page.close(); confirm.performClick(); finish();
        assertEquals(0, store.applies);
        assertEquals(0, changes);
    }

    @Test public void closedFileChooserCannotStartAnImport() {
        view("import").performClick();
        Consumer<Uri> late = picker.callback;
        page.close();
        assertTrue(picker.closed);
        late.accept(Uri.parse("content://backup/late")); finish();
        assertEquals(0, files.reads); assertEquals(0, store.applies);
    }

    @Test public void cancellingBlockedReadClosesProviderOnBackgroundThreadAndIgnoresItsLateData() throws Exception {
        page.close(); page.dialog().dismiss();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch workerDone = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean uiClose = new java.util.concurrent.atomic.AtomicBoolean();
        InputStream blocked = new InputStream() {
            @Override public int read() {
                reading.countDown();
                while (released.getCount() != 0) {
                    try { released.await(); } catch (InterruptedException ignored) { }
                }
                return -1;
            }
            @Override public void close() {
                uiClose.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper());
                released.countDown();
            }
        };
        page = new ConfigTransferDialog(activity, store, false, () -> changes++, new ConfigTransferDialog.Files() {
            @Override public InputStream read(Uri uri) { return blocked; }
            @Override public OutputStream write(Uri uri) { throw new AssertionError(); }
        }, picker, task -> new Thread(() -> { try { task.run(); } finally { workerDone.countDown(); } }).start());
        page.dialog().show(); ShadowLooper.idleMainLooper();
        try {
            view("import").performClick(); picker.select();
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            view("cancel").performClick();
            assertTrue(workerDone.await(3, TimeUnit.SECONDS));
            ShadowLooper.idleMainLooper();
            assertFalse(uiClose.get());
            assertFalse(view("confirm").isEnabled());
            assertEquals(0, store.applies);
            assertTrue(((TextView) view("status")).getText().toString().startsWith("已取消"));
        } finally { released.countDown(); }
    }

    @Test public void rejectsOversizedMalformedAndProviderErrorsWithoutLeakingTheirContent() throws Exception {
        files.input = new byte[ConfigTransfer.MAX_BYTES + 1];
        view("import").performClick(); picker.select(); finish();
        assertFalse(view("confirm").isEnabled()); assertEquals(0, store.applies);
        files.input = "{private-malformed-key".getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        assertFalse(allText(page.dialog().getWindow().getDecorView()).contains("private-malformed-key"));
        files.fail = true;
        view("import").performClick(); picker.select(); finish();
        assertFalse(allText(page.dialog().getWindow().getDecorView()).contains("private-provider-key"));
        assertEquals(0, store.applies);
    }

    @Test public void settingChangedAfterPreviewRequiresAnewPreview() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        store.values.put("reply_mode", "random");
        view("confirm").performClick(); finish();
        assertEquals(0, store.applies);
        assertEquals("random", store.values.get("reply_mode"));
        assertFalse(view("confirm").isEnabled());
    }

    @Test public void switchingToReplaceReinspectsTheOriginalBackupAndRequiresNewConfirmation() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        store.values.put("reply_mode", "random");
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        assertFalse(((CheckBox) view("replace")).isChecked());
        view("import").performClick(); picker.select(); finish();
        assertTrue(((TextView) view("preview")).getText().toString().contains("将移除：0 项"));
        ((CheckBox) view("replace")).setChecked(true);
        assertFalse(view("confirm").isEnabled());
        assertEquals("", ((TextView) view("preview")).getText().toString());
        assertEquals(0, store.applies);
        finish();
        assertEquals("Mode changes use the original JSON without another file read", 1, files.reads);
        assertTrue(((TextView) view("preview")).getText().toString().contains("将移除：1 项"));
        assertTrue(((TextView) view("preview")).getText().toString().contains("导入方式：覆盖"));
        view("confirm").performClick(); finish();
        assertFalse(store.values.containsKey("reply_mode"));
        assertEquals(Boolean.TRUE, store.values.get("native_auto_reply"));
        assertEquals(1, store.applies);
    }

    @Test public void closingDuringModeReinspectionCannotPublishOrApplyItsPreview() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        ((CheckBox) view("replace")).setChecked(true);
        View oldConfirm = view("confirm");
        page.close(); finish(); oldConfirm.performClick(); finish();
        assertEquals(0, store.applies); assertEquals(0, changes);
        assertEquals("", ((TextView) view("preview")).getText().toString());
    }

    @Test public void backupNavigationClosesWithSettingsAndLeavesStorageUntouched() {
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        View home = screen.createView(); activity.setContentView(home);
        home.findViewWithTag("settings.category.BACKUP").performClick();
        Dialog backup = ShadowDialog.getLatestDialog();
        assertTrue(backup.isShowing());
        assertNotNull(backup.getWindow().getDecorView().findViewWithTag("config.backup.export"));
        screen.dismissChildPages(); ShadowLooper.idleMainLooper();
        assertFalse(backup.isShowing()); assertEquals(0, store.applies);
    }

    private View view(String tag) { return page.dialog().getWindow().getDecorView().findViewWithTag("config.backup." + tag); }
    private void finish() { executor.runAll(); ShadowLooper.idleMainLooper(); }
    private static String allText(View view) {
        StringBuilder value = new StringBuilder(view instanceof TextView ? ((TextView) view).getText() : "");
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            value.append(allText(((ViewGroup) view).getChildAt(index)));
        }
        return value.toString();
    }
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable task) { queued.add(task); }
        void runAll() { while (!queued.isEmpty()) queued.remove().run(); }
    }
    private static final class Files implements ConfigTransferDialog.Files {
        byte[] input = new byte[0]; ByteArrayOutputStream output; int reads, writes; boolean fail;
        @Override public InputStream read(Uri uri) {
            reads++; if (fail) throw new IllegalStateException("private-provider-key");
            return new ByteArrayInputStream(input);
        }
        @Override public OutputStream write(Uri uri) { writes++; output = new ByteArrayOutputStream(); return output; }
    }
    private static final class Picker implements ConfigTransferDialog.Picker {
        Consumer<Uri> callback; boolean closed;
        @Override public AutoCloseable launch(Activity activity, boolean export, String name, Consumer<Uri> callback) {
            this.callback = callback; closed = false; return () -> closed = true;
        }
        void select() { callback.accept(Uri.parse("content://backup/file.json")); }
    }
    static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>(); int applies;
        @Override public FeatureSettings load() { return FeatureSettings.defaults(); }
        @Override public void save(FeatureSettings settings) { }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.get(key) instanceof Boolean ? (Boolean) values.get(key) : fallback; }
        @Override public String getString(String key, String fallback) { return values.get(key) instanceof String ? (String) values.get(key) : fallback; }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); }
        @Override public void putString(String key, String value) { values.put(key, value); }
        @Override public boolean isInHost() { return true; }
        @Override public Map<String, ?> snapshot() { return new LinkedHashMap<>(values); }
        @Override public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            if (!values.equals(expected)) throw new IllegalStateException("changed");
            values.clear(); values.putAll(replacement); applies++;
        }
    }
}

package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DebugLogView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowDialog;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class DebugLogDialogTest {
    @Test public void usesHostSwitchStateAndCopiesOnlyTheBoundedDisplayedTail() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        FakeSource source = new FakeSource();
        source.enabled = true;
        source.tail = "较早日志".repeat(DebugLogDialog.DISPLAY_CHARACTERS / 3) + "\n最新日志😀";
        DebugLogDialog page = new DebugLogDialog(activity, store, false, source, new FakeExporter(), Runnable::run);
        try {
            page.dialog().show();
            ShadowLooper.idleMainLooper();
            CompoundButton toggle = view(page, "enabled");
            assertTrue(toggle.isChecked());
            assertTrue("Opening the viewer must not overwrite settings", store.values.isEmpty());
            TextView text = view(page, "text");
            String displayed = text.getText().toString();
            assertTrue(displayed.length() <= DebugLogDialog.DISPLAY_CHARACTERS);
            assertTrue(displayed.endsWith("最新日志😀"));
            assertTrue(displayed.startsWith("（较早内容已省略）"));
            view(page, "copy").performClick();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(displayed, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            toggle.setChecked(false);
            assertEquals(Boolean.FALSE, store.values.get(DebugLog.KEY));
            assertFalse(source.enabled);
            assertFalse(toggle.isChecked());
            assertTrue(text.getText().toString().contains("最新日志"));
            source.enableSuccess = false;
            toggle.setChecked(true);
            assertEquals("An unavailable host must not change persisted state", Boolean.FALSE, store.values.get(DebugLog.KEY));
            assertFalse(toggle.isChecked());
        } finally { page.dialog().dismiss(); activity.finish(); }
    }

    @Test public void exportRequestsTheFullSnapshotWithoutTruncatingOrChangingItsText() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeSource source = new FakeSource();
        source.tail = "仅显示末尾";
        source.full = "完整日志与评论 JSON\n" + "音频😀".repeat(DebugLogDialog.DISPLAY_CHARACTERS / 2);
        FakeExporter exporter = new FakeExporter();
        DebugLogDialog page = new DebugLogDialog(activity, new MemoryStore(), false, source, exporter, Runnable::run);
        try {
            page.dialog().show();
            ShadowLooper.idleMainLooper();
            view(page, "export").performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(1, source.exports);
            assertEquals(source.full, exporter.written);
            assertEquals("仅显示末尾", ((TextView) view(page, "text")).getText().toString());
            assertEquals("日志已导出。", ((TextView) view(page, "status")).getText().toString());
        } finally { page.dialog().dismiss(); activity.finish(); }
    }

    @Test public void failedClearKeepsVisibleDataAndUnavailableHostDisablesWrites() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeSource source = new FakeSource();
        source.tail = "保留这条日志";
        source.clearSuccess = false;
        DebugLogDialog page = new DebugLogDialog(activity, new MemoryStore(), false, source, new FakeExporter(), Runnable::run);
        try {
            page.dialog().show();
            ShadowLooper.idleMainLooper();
            view(page, "clear").performClick();
            assertEquals("保留这条日志", ((TextView) view(page, "text")).getText().toString());
            assertEquals("清空失败，请稍后重试。", ((TextView) view(page, "status")).getText().toString());
            source.available = false;
            view(page, "refresh").performClick();
            for (String action : new String[]{"enabled", "clear", "copy", "export"}) {
                assertFalse(action, view(page, action).isEnabled());
            }
            assertTrue(view(page, "refresh").isEnabled());
            assertEquals("", ((TextView) view(page, "text")).getText().toString());
        } finally { page.dialog().dismiss(); activity.finish(); }
    }

    @Test public void closingInvalidatesPendingReadAndQueuedExport() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeSource delayed = new FakeSource();
        delayed.delayRead = true;
        DebugLogDialog loading = new DebugLogDialog(activity, new MemoryStore(), false, delayed, new FakeExporter(), Runnable::run);
        loading.dialog().show();
        ShadowLooper.idleMainLooper();
        loading.dialog().dismiss();
        ShadowLooper.idleMainLooper();
        delayed.pendingRead.accept(new DebugLogDialog.Snapshot(true, true, "不应显示"));
        assertEquals("", ((TextView) view(loading, "text")).getText().toString());

        FakeSource source = new FakeSource();
        FakeExporter exporter = new FakeExporter();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        DebugLogDialog exporting = new DebugLogDialog(activity, new MemoryStore(), false, source, exporter, queued::set);
        try {
            exporting.dialog().show();
            ShadowLooper.idleMainLooper();
            view(exporting, "export").performClick();
            assertNotNull(queued.get());
            exporting.dialog().dismiss();
            ShadowLooper.idleMainLooper();
            queued.get().run();
            ShadowLooper.idleMainLooper();
            assertTrue(exporter.cancelled);
            assertNull(exporter.written);
        } finally { exporting.dialog().dismiss(); activity.finish(); }
    }

    @Test public void tailNeverSplitsASurrogateOrExceedsItsLimit() {
        assertEquals("", DebugLogDialog.tail(null, 4));
        assertEquals("", DebugLogDialog.tail("日志", 0));
        assertEquals("日志", DebugLogDialog.tail("日志", 2));
        String shortened = DebugLogDialog.tail("旧😀新", 2);
        assertEquals("新", shortened);
        assertTrue(shortened.length() <= 2);
    }

    @Test public void categoriesAndSearchCopyOnlyMatchesWhileExportKeepsTheFullSnapshot() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeSource source = new FakeSource();
        String chat = event(1, "[DouAi.MessageJson] hook=DouAi.AutoAudio.1 stage=after json={\"duration\":2117,\"url\":\"fixture\"}");
        String comment = event(2, "[DouAi.CommentJson] hook=row stage=after json={\"audio_duration\":16056}");
        String sent = event(3, "Automatic reply outcome: sent");
        source.tail = chat + comment + sent; source.full = "完整保留区\n" + source.tail;
        FakeExporter exporter = new FakeExporter();
        DebugLogDialog page = new DebugLogDialog(activity, new MemoryStore(), false, source, exporter, Runnable::run);
        try {
            page.dialog().show(); ShadowLooper.idleMainLooper();
            assertTrue(((TextView) view(page, "summary")).getText().toString().contains("成功 1"));
            Spinner category = view(page, "category"); category.setSelection(DebugLogView.Category.JSON.ordinal());
            ShadowLooper.idleMainLooper();
            EditText search = view(page, "search"); search.setText("DURATION\":2117");
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
            assertEquals(chat, ((TextView) view(page, "text")).getText().toString());
            assertTrue(((TextView) view(page, "summary")).getText().toString().startsWith("显示 1 / 3 条"));
            view(page, "copy").performClick();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(chat, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            view(page, "export").performClick(); ShadowLooper.idleMainLooper();
            assertEquals(source.full, exporter.written);
            assertEquals(chat, ((TextView) view(page, "text")).getText().toString());
        } finally { page.dialog().dismiss(); activity.finish(); }
    }

    @Test public void entryViewerFormatsJsonAndCopiesTheExactBoundEntry() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeSource source = new FakeSource();
        String first = event(1, "[DouAi.CommentJson] hook=row stage=before json={\"text\":\"first\"}");
        String latest = event(2, "[DouAi.MessageJson] hook=queue stage=after json={\"audio\":{\"duration\":2117}}");
        source.tail = first + latest;
        DebugLogDialog page = new DebugLogDialog(activity, new MemoryStore(), false, source, new FakeExporter(), Runnable::run);
        try {
            page.dialog().show(); ShadowLooper.idleMainLooper();
            view(page, "entries").performClick(); ShadowLooper.idleMainLooper();
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotSame(page.dialog(), picker);
            assertEquals(2, picker.getListView().getAdapter().getCount());
            assertTrue(picker.getListView().getAdapter().getItem(0).toString().contains("queue"));
            picker.getListView().performItemClick(null, 0, 0); ShadowLooper.idleMainLooper();
            AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
            TextView content = detail.getWindow().getDecorView().findViewWithTag("debug.logs.entry.text");
            assertNotNull(content); assertTrue(content.getText().toString().contains("json=\n{"));
            assertTrue(content.getText().toString().contains("\n  \"audio\""));
            assertFalse(content.getText().toString().contains("first"));
            detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(latest, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        } finally { page.dialog().dismiss(); activity.finish(); }
    }

    @Test public void noMatchDisablesEntryActionsAndClosingDismissesTheEntryViewer() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeSource source = new FakeSource(); source.tail = event(1, "Automatic reply outcome: sent");
        DebugLogDialog page = new DebugLogDialog(activity, new MemoryStore(), false, source, new FakeExporter(), Runnable::run);
        try {
            page.dialog().show(); ShadowLooper.idleMainLooper();
            EditText search = view(page, "search"); search.setText("missing-field");
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
            assertFalse(view(page, "copy").isEnabled()); assertFalse(view(page, "entries").isEnabled());
            assertEquals("没有符合条件的日志。", ((TextView) view(page, "text")).getText().toString());
            search.setText(""); ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
            view(page, "entries").performClick();
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            picker.getListView().performItemClick(null, 0, 0);
            AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog(); assertTrue(detail.isShowing());
            page.dialog().dismiss(); ShadowLooper.idleMainLooper();
            assertFalse(detail.isShowing());
        } finally { page.dialog().dismiss(); activity.finish(); }
    }

    @Test public void invalidJsonEntryRemainsReadableAndUnmodified() {
        String malformed = event(1, "[DouAi.MessageJson] hook=queue stage=after json={truncated");
        assertEquals(malformed, DebugLogDialog.formatEntry(malformed));
    }

    private static String event(int second, String text) {
        return String.format(java.util.Locale.ROOT, "2026-10-22 00:00:%02d.000 D/DouAi [detail] %s\n", second, text);
    }

    @SuppressWarnings("unchecked") private static <T extends View> T view(DebugLogDialog page, String suffix) {
        T result = (T) page.dialog().getWindow().getDecorView().findViewWithTag("debug.logs." + suffix);
        assertNotNull(suffix, result);
        return result;
    }

    private static final class FakeSource implements DebugLogDialog.Source {
        boolean enabled, available = true, clearSuccess = true, enableSuccess = true, delayRead;
        String tail = "日志", full = "完整日志";
        int exports;
        Consumer<DebugLogDialog.Snapshot> pendingRead;
        public void read(Consumer<DebugLogDialog.Snapshot> callback) {
            if (delayRead) pendingRead = callback;
            else callback.accept(new DebugLogDialog.Snapshot(enabled, available, tail));
        }
        public void enabled(boolean value, Consumer<Boolean> callback) {
            if (enableSuccess) enabled = value;
            callback.accept(enableSuccess);
        }
        public void clear(Consumer<Boolean> callback) {
            if (clearSuccess) tail = "暂无调试日志";
            callback.accept(clearSuccess);
        }
        public void export(Consumer<DebugLogDialog.Snapshot> callback) {
            exports++;
            callback.accept(new DebugLogDialog.Snapshot(enabled, available, full));
        }
    }
    private static final class FakeExporter implements DebugLogDialog.Exporter {
        String written;
        boolean cancelled;
        public AutoCloseable choose(Activity activity, Consumer<Uri> callback) {
            callback.accept(Uri.parse("content://test/log.txt"));
            return () -> { };
        }
        public void write(Uri uri, String text, BooleanSupplier valid) throws IOException {
            if (!valid.getAsBoolean()) { cancelled = true; throw new IOException("cancelled"); }
            written = text;
        }
    }
    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings settings = FeatureSettings.defaults();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }
}

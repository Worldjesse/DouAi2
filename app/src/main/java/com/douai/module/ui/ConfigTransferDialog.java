package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.ConfigBackup;
import com.douai.module.config.SettingsStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Files are read before preview; only an explicit confirmation can apply a validated backup. */
final class ConfigTransferDialog implements AutoCloseable {
    interface Files {
        InputStream read(Uri uri) throws Exception;
        OutputStream write(Uri uri) throws Exception;
    }
    interface Picker {
        AutoCloseable launch(Activity activity, boolean export, String name, Consumer<Uri> selected);
    }
    private static ThreadPoolExecutor executor(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        });
    }
    private static final ThreadPoolExecutor WORKER = executor("DouAi-config-files", 8);
    private static final ThreadPoolExecutor CLOSER = executor("DouAi-config-close", 8);
    private final SettingsStore store;
    private final Context application;
    private final Runnable changed;
    private final Files files;
    private final Picker picker;
    private final Executor worker;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AlertDialog dialog;
    private final TextView status;
    private final TextView previewText;
    private final CheckBox secrets;
    private final CheckBox includeAnalytics;
    private final CheckBox restoreAnalytics;
    private final CheckBox replace;
    private final Button exportButton;
    private final Button importButton;
    private final Button confirmButton;
    private final Button cancelButton;
    private volatile boolean closed;
    private AutoCloseable selection;
    private long selectionGeneration;
    private Operation operation;
    private ConfigBackup.Preview preview;
    private String sourceJson;

    ConfigTransferDialog(Context context, SettingsStore store, boolean miuix, Runnable changed) {
        this(context, store, miuix, changed, new Files() {
            @Override public InputStream read(Uri uri) throws Exception { return context.getContentResolver().openInputStream(uri); }
            @Override public OutputStream write(Uri uri) throws Exception { return context.getContentResolver().openOutputStream(uri, "wt"); }
        }, HostConfigFilePicker::launch, WORKER);
    }

    ConfigTransferDialog(Context baseContext, SettingsStore store, boolean miuix, Runnable changed,
                         Files files, Picker picker, Executor worker) {
        Context context = ModuleTheme.themedContext(baseContext);
        Context app = context.getApplicationContext();
        this.application = app == null ? context : app;
        this.store = store; this.changed = changed; this.files = files; this.picker = picker; this.worker = worker;
        this.activity = activity(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        body.setPadding(padding, 0, padding, padding);
        TextView note = text(context, "导出为 JSON 文件；导入先预览，确认后写入。默认只备份配置，可选择包含本地分析数据；不包含发送账本和缓存。", "config.backup.note");
        body.addView(note, matchWrap());
        secrets = miuix ? MiuixFormControls.checkbox(context, "导出包含 API 密钥", false) : new CheckBox(context);
        secrets.setText("导出包含 API 密钥"); secrets.setChecked(false); secrets.setTag("config.backup.secrets");
        body.addView(MiuixFormControls.viewFor(secrets), matchWrap());
        TextView secretNote = text(context, "默认省略密钥和可能包含凭据的自定义接口字段，导入后可能需要补填。回复正文、提示词等自由文本原样保存，请检查其中手填的敏感信息。勾选后密钥会以明文保存，请妥善保管备份文件。", "config.backup.secretNote");
        body.addView(secretNote, matchWrap());
        includeAnalytics = miuix ? MiuixFormControls.checkbox(context, "包含本地分析数据", false) : new CheckBox(context);
        includeAnalytics.setText("包含本地分析数据"); includeAnalytics.setChecked(false);
        includeAnalytics.setTag("config.backup.includeAnalytics");
        body.addView(MiuixFormControls.viewFor(includeAnalytics), matchWrap());
        body.addView(text(context, "默认不包含。勾选后，浏览记录、作者话题、模块功能使用、使用目的和侧写反馈等本地分析数据会存入你选择的备份文件。", "config.backup.analyticsNote"), matchWrap());
        exportButton = button(context, miuix, body, "导出配置", "config.backup.export", () -> choose(true));
        replace = miuix ? MiuixFormControls.checkbox(context, "覆盖当前配置", false) : new CheckBox(context);
        replace.setText("覆盖当前配置"); replace.setChecked(false); replace.setTag("config.backup.replace");
        body.addView(MiuixFormControls.viewFor(replace), matchWrap());
        body.addView(text(context, "默认合并。勾选覆盖会移除备份之外的功能配置；请核对预览中的移除数量。", "config.backup.modeNote"), matchWrap());
        replace.setOnCheckedChangeListener((button, checked) -> refreshMode());
        importButton = button(context, miuix, body, "选择配置文件", "config.backup.import", () -> choose(false));
        status = text(context, "请选择导出或导入。", "config.backup.status");
        body.addView(status, matchWrap());
        previewText = text(context, "", "config.backup.preview");
        previewText.setTextIsSelectable(true);
        body.addView(previewText, matchWrap());
        restoreAnalytics = miuix ? MiuixFormControls.checkbox(context, "恢复本地分析数据", false) : new CheckBox(context);
        restoreAnalytics.setText("恢复本地分析数据"); restoreAnalytics.setChecked(false);
        restoreAnalytics.setTag("config.backup.restoreAnalytics");
        restoreAnalytics.setVisibility(View.GONE);
        body.addView(MiuixFormControls.viewFor(restoreAnalytics), matchWrap());
        restoreAnalytics.setOnCheckedChangeListener((button, checked) -> updatePreview());
        confirmButton = button(context, miuix, body, "确认导入", "config.backup.confirm", this::confirm);
        confirmButton.setEnabled(false);
        cancelButton = button(context, miuix, body, "取消当前操作", "config.backup.cancel", () -> cancel("已取消，配置未更改。"));
        cancelButton.setEnabled(false);
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("config.backup.scroll");
        scroll.setFillViewport(false);
        scroll.addView(body);
        dialog = new MiuixDialogBuilder(context, miuix).setTitle("配置备份")
                .setView(scroll).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(ignored -> close());
    }

    AlertDialog dialog() { return dialog; }

    private void choose(boolean export) {
        if (!active() || operation != null || selection != null) return;
        preview = null; sourceJson = null; previewText.setText(""); confirmButton.setEnabled(false);
        resetAnalyticsRestore();
        boolean includeSecrets = secrets.isChecked();
        boolean includeLocalAnalytics = includeAnalytics.isChecked();
        ConfigTransfer.Mode mode = mode();
        long selectedGeneration = ++selectionGeneration;
        busy(true);
        status.setText(export ? "请选择备份文件保存位置。" : "请选择 DouAi 配置备份文件。");
        AutoCloseable pending = picker.launch(activity, export, "DouAi-config-" + System.currentTimeMillis() + ".json", uri -> {
            if (!active() || selectedGeneration != selectionGeneration) return;
            selectionGeneration++; // One selection can deliver at most one result.
            selection = null;
            if (uri == null) { busy(false); status.setText("已取消选择，配置未更改。"); return; }
            if (export) run(op -> {
                byte[] data = ConfigBackup.export(application, store, includeSecrets, includeLocalAnalytics).getBytes(StandardCharsets.UTF_8);
                if (data.length > ConfigBackup.MAX_BYTES) throw new IllegalArgumentException();
                op.check();
                try (OutputStream stream = op.attach(files.write(uri))) {
                    for (int offset = 0; offset < data.length; offset += 8192) {
                        op.check(); stream.write(data, offset, Math.min(8192, data.length - offset));
                    }
                    op.check(); stream.flush();
                } finally { op.detach(); }
                return null;
            }, "正在导出…", includeLocalAnalytics ? "配置和本地分析数据已导出。" : "配置已导出。");
            else run(op -> {
                byte[] data;
                try (InputStream stream = op.attach(files.read(uri))) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        op.check();
                        if (count > ConfigBackup.MAX_BYTES - output.size()) throw new IllegalArgumentException();
                        output.write(buffer, 0, count);
                    }
                    data = output.toByteArray();
                } finally { op.detach(); }
                op.check();
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
                op.importJson = json;
                return ConfigBackup.inspect(store, json, mode);
            }, "正在读取并校验…", "请核对以下变更，确认后才会写入配置。");
        });
        // Some adapters can deliver synchronously before launch returns its handle.
        if (active() && selectedGeneration == selectionGeneration) selection = pending;
        else closeSelection(pending);
        if (selectedGeneration == selectionGeneration && selection == null && operation == null) {
            selectionGeneration++;
            busy(false); status.setText("无法打开文件选择器，请从抖音内设置重试。");
        }
    }

    private ConfigTransfer.Mode mode() { return replace.isChecked() ? ConfigTransfer.Mode.REPLACE : ConfigTransfer.Mode.MERGE; }
    private void refreshMode() {
        if (!active() || operation != null || selection != null || sourceJson == null) return;
        String original = sourceJson;
        ConfigTransfer.Mode selected = mode();
        preview = null; previewText.setText("");
        run(op -> {
            op.check(); op.importJson = original;
            return ConfigBackup.inspect(store, original, selected);
        }, "正在重新核对导入方式…", "请核对以下变更，确认后才会写入配置。");
    }

    private void confirm() {
        if (!active() || operation != null || preview == null) return;
        ConfigBackup.Preview accepted = preview;
        boolean restoreLocalAnalytics = accepted.includesAnalytics && restoreAnalytics.isChecked();
        preview = null;
        run(op -> {
            op.check();
            // This is the commit boundary. A confirmed atomic settings write may finish after close.
            op.applying = true;
            op.check();
            ConfigBackup.apply(application, store, accepted, restoreLocalAnalytics);
            op.applied = true;
            return null;
        }, "正在应用备份…", restoreLocalAnalytics
                ? "配置已导入，本地分析记录已替换，采集开关保持原状态。需要重新安装 Hook 的项目将在重启抖音后生效。"
                : "配置已导入，本地分析数据保持原样。需要重新安装 Hook 的项目将在重启抖音后生效。");
        cancelButton.setEnabled(false);
    }

    private interface Work { ConfigBackup.Preview execute(Operation op) throws Exception; }
    private void run(Work work, String working, String success) {
        Operation op = new Operation();
        operation = op;
        busy(true); status.setText(working);
        op.future = new FutureTask<>(() -> {
            ConfigBackup.Preview result = null;
            boolean succeeded = false;
            try { op.check(); result = work.execute(op); op.check(); succeeded = true; }
            catch (Exception ignored) { /* Never display provider messages, JSON fragments, or secrets. */ }
            ConfigBackup.Preview ready = result;
            boolean done = succeeded;
            main.post(() -> {
                if (!active() || operation != op || op.cancelled) return;
                main.removeCallbacks(op.timeout);
                operation = null;
                busy(false);
                status.setText(done ? success : op.applying
                        ? "导入未完成，配置或本地分析数据可能已变化。请重新选择文件并核对预览。"
                        : "操作失败。请检查文件格式、文件权限和存储空间后重试。配置文件最大 2 MiB，含本地分析数据的备份最大 32 MiB。");
                if (done && ready != null) {
                    sourceJson = op.importJson; preview = ready;
                    restoreAnalytics.setVisibility(ready.includesAnalytics ? View.VISIBLE : View.GONE);
                    updatePreview();
                    cancelButton.setEnabled(true);
                }
                if (op.applied) {
                    sourceJson = null;
                    resetAnalyticsRestore();
                    if (changed != null) changed.run();
                }
            });
            return null;
        });
        try { worker.execute(op.future); main.postDelayed(op.timeout, 60_000); }
        catch (java.util.concurrent.RejectedExecutionException unavailable) {
            operation = null; busy(false); status.setText("配置文件任务繁忙，请稍后重试。");
        }
    }

    static String describe(ConfigTransfer.Preview value) {
        StringBuilder text = new StringBuilder("格式版本：").append(value.formatVersion)
                .append("\n备份来源版本：").append(value.moduleVersion)
                .append(value.includesSecrets ? "\n本备份包含密钥。" : "\n本备份已省略凭据字段。")
                .append("\n备份配置：").append(value.entryCount).append(" 项")
                .append("\n待变更：").append(value.changedCount).append(" 项")
                .append("\n将移除：").append(value.removedCount).append(" 项")
                .append("\n已省略的认证或非公开字段：").append(value.redactedCount).append(" 项")
                .append("\n需要补充认证或自定义字段：").append(value.missingSecretsCount).append(" 项")
                .append(value.mode == ConfigTransfer.Mode.REPLACE
                        ? "\n导入方式：覆盖，将移除备份之外的功能配置"
                        : "\n导入方式：合并，保留备份之外的设置")
                .append("\n确认后配置立即生效，可能改变自动回复、评论等功能开关。")
                .append("\n脱敏备份保留同服务现有密钥，不同服务需重新填写。")
                .append("\n目录和音频路径不包含原文件或系统授权，导入后可能需要重新选择。")
                .append("\n密钥值不在此处展示。");
        if (!value.changedKeys.isEmpty()) {
            text.append("\n\n待变更设置：");
            for (String key : value.changedKeys) text.append('\n').append(key);
        }
        return text.toString();
    }

    private boolean active() { return !closed && dialog.isShowing(); }
    private boolean hasChanges() {
        return preview != null && (preview.config.changedCount > 0 || preview.config.removedCount > 0
                || preview.includesAnalytics && restoreAnalytics.isChecked());
    }
    private void resetAnalyticsRestore() {
        restoreAnalytics.setChecked(false);
        restoreAnalytics.setVisibility(View.GONE);
    }
    private void updatePreview() {
        if (preview == null) { confirmButton.setEnabled(false); return; }
        String value = describe(preview.config);
        if (preview.includesAnalytics) value += "\n\n本备份包含本地分析数据：\n" + preview.analyticsSummary
                + (restoreAnalytics.isChecked()
                    ? "\n已选择恢复：将替换当前全部本地分析记录、自述和反馈；不改变当前采集开关，与配置合并或覆盖方式无关。"
                    : "\n默认不恢复本地分析数据；当前记录、自述、反馈和采集开关保持原样。勾选下方开关才会替换。");
        else value += "\n\n本备份不含本地分析数据；导入不会改变当前本地分析数据和采集开关。";
        previewText.setText(value);
        restoreAnalytics.setEnabled(operation == null && preview.includesAnalytics);
        confirmButton.setEnabled(operation == null && hasChanges());
    }
    private void busy(boolean value) {
        exportButton.setEnabled(!value); importButton.setEnabled(!value); secrets.setEnabled(!value);
        includeAnalytics.setEnabled(!value);
        replace.setEnabled(!value);
        restoreAnalytics.setEnabled(!value && preview != null && preview.includesAnalytics);
        confirmButton.setEnabled(!value && hasChanges());
        cancelButton.setEnabled(value);
    }
    private void cancel(String message) {
        if (!active() || operation != null && operation.applying) return;
        stop(); preview = null; sourceJson = null; resetAnalyticsRestore(); previewText.setText(""); busy(false); status.setText(message);
    }
    private void stop() {
        selectionGeneration++; // A cancelled chooser cannot restart an operation through a late callback.
        AutoCloseable pending = selection; selection = null;
        closeSelection(pending);
        Operation old = operation; operation = null;
        if (old != null) main.removeCallbacks(old.timeout);
        if (old != null && !old.applying) {
            old.cancelled = true;
            if (old.future != null) old.future.cancel(true);
            Closeable stream = old.stream;
            if (stream != null) try { CLOSER.execute(() -> closeQuietly(stream)); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }
    @Override public void close() { closed = true; stop(); preview = null; sourceJson = null; }

    private final class Operation {
        volatile boolean cancelled;
        volatile boolean applying;
        volatile boolean applied;
        volatile Closeable stream;
        String importJson;
        FutureTask<Void> future;
        final Runnable timeout = () -> { if (operation == this && !applying) cancel("操作超时，配置未更改。"); };
        void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException(); }
        <T extends Closeable> T attach(T value) {
            if (value == null) throw new IllegalStateException();
            stream = value;
            try { check(); return value; }
            catch (RuntimeException cancelled) { closeQuietly(value); stream = null; throw cancelled; }
        }
        void detach() { stream = null; }
    }

    private static void closeQuietly(Closeable value) { try { value.close(); } catch (Exception ignored) { } }
    private static void closeSelection(AutoCloseable value) {
        if (value != null) try { value.close(); } catch (Exception ignored) { }
    }
    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    private static TextView text(Context context, String value, String tag) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(14); view.setTag(tag);
        view.setPadding(0, 8, 0, 8); return view;
    }
    private static Button button(Context context, boolean miuix, LinearLayout parent, String label, String tag, Runnable action) {
        Button view = miuix ? MiuixFormControls.button(context) : new Button(context);
        view.setText(label); view.setTag(tag); view.setOnClickListener(ignored -> action.run());
        parent.addView(MiuixFormControls.viewFor(view), matchWrap()); return view;
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

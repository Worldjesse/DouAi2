package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One host-owned file per export; only URI handoff is performed on the UI thread. */
final class ReportImageShare {
    private static final ExecutorService CLEANUP = Executors.newSingleThreadExecutor(ReportImageShare::cleanupThread);
    private ReportImageShare() { }
    private static Thread cleanupThread(Runnable action) {
        Thread thread = new Thread(action, "DouAi-report-cleanup"); thread.setDaemon(true); return thread;
    }
    static void cleanup(Saved saved) { if (saved != null) saved.delete(); }

    static Intent destination(ReportImageExport.Format format) {
        if (format == null) throw new IllegalArgumentException("请选择报告格式");
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(format.mimeType).putExtra(Intent.EXTRA_TITLE, "DouAi-report." + format.extension)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    /** Worker-only encoding/provider I/O; cancellation and failure cleanup are always asynchronous. */
    static Saved save(Context context, ReportImageExport.Document document, ReportImageExport.Format format,
                      Uri destination, BooleanSupplier valid) throws IOException {
        document.requireFormat(format); ReportImageExport.check(valid);
        Context application = context.getApplicationContext();
        if (application == null) throw new IOException("报告存储上下文不可用");
        ContentResolver resolver = application.getContentResolver();
        boolean media = Build.VERSION.SDK_INT >= 29 && destination == null;
        Saved saved = null;
        DouAiLog.Operation trace = DouAiLog.begin("report_export", "save_report");
        trace.stage("encode_and_write", "format=" + format.name() + ";media_store=" + media);
        try {
            Uri uri = destination;
            if (media) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "DouAi-report-" + System.currentTimeMillis() + "-"
                        + UUID.randomUUID().toString().substring(0, 8) + "." + format.extension);
                values.put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (format == ReportImageExport.Format.PDF ? Environment.DIRECTORY_DOWNLOADS : Environment.DIRECTORY_PICTURES) + "/DouAi");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = resolver.insert(format == ReportImageExport.Format.PDF ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            }
            if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) throw new IOException("请选择报告文件保存位置");
            saved = new Saved(resolver, media, uri, format);
            ReportImageExport.check(valid);
            try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("无法写入报告文件");
                document.write(format, output, valid); ReportImageExport.check(valid); output.flush();
            }
            ReportImageExport.check(valid);
            if (media) {
                ContentValues complete = new ContentValues(); complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, complete, null, null) < 1) throw new IOException("无法完成报告文件保存");
            }
            ReportImageExport.check(valid);
            trace.finish(DouAiLog.Outcome.SUCCESS, "stream_closed_and_published", "output_stream_closed=true;provider_publish=" + media + ";file_ref=" + DouAiLog.reference(uri.toString()) + ";sharing_not_confirmed=true");
            return saved;
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            trace.error("encode_and_write", failure);
            trace.finish(failure instanceof java.util.concurrent.CancellationException ? DouAiLog.Outcome.CANCELLED : DouAiLog.Outcome.FAILED, "report_save_failed", "cleanup_requested=true");
            cleanup(saved); throw failure;
        }
    }

    static final class Saved {
        final Uri uri;
        private final ReportImageExport.Format format;
        private final ContentResolver resolver;
        private final boolean media;
        private final AtomicBoolean disposed = new AtomicBoolean();
        private boolean handedOff;
        Saved(ContentResolver resolver, boolean media, Uri uri, ReportImageExport.Format format) {
            this.resolver = resolver; this.media = media; this.uri = uri; this.format = format;
        }
        Intent chooser() {
            Intent send = new Intent(Intent.ACTION_SEND).setType(format.mimeType)
                    .putExtra(Intent.EXTRA_SUBJECT, "DouAi · 完整本地分析报告")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(new ClipData("DouAi report", new String[]{format.mimeType}, new ClipData.Item(uri)));
            return Intent.createChooser(send, "分享完整报告" + (format == ReportImageExport.Format.PDF ? " PDF" : "长图"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        synchronized void handOff() {
            if (disposed.get()) throw new IllegalStateException("报告导出已取消");
            handedOff = true;
            DouAiLog.event("report_export", "share_handoff", "unconfirmed", "chooser_requested=true;recipient_received=unobserved");
        }
        synchronized void failedHandOff() { handedOff = false; delete(); }
        void delete() {
            synchronized (this) {
                if (handedOff || !disposed.compareAndSet(false, true)) return;
            }
            Runnable action = () -> {
                try { if (media) resolver.delete(uri, null, null); else DocumentsContract.deleteDocument(resolver, uri); }
                catch (Exception ignored) { }
            };
            try { CLEANUP.execute(action); }
            catch (RejectedExecutionException unavailable) { cleanupThread(action).start(); }
        }
    }
}

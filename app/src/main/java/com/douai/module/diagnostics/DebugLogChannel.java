package com.douai.module.diagnostics;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.douai.module.BuildConfig;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit, signature-protected ordered requests; no provider or public filesystem paths. */
public final class DebugLogChannel {
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".action.REQUEST_DEBUG_LOG";
    public static final String PERMISSION = ScanDiagnosticsChannel.PERMISSION;
    public static final int CHUNK_CHARS = 32 * 1024;
    public static final long EXPORT_TTL_MILLIS = 30_000;
    private static final String RESULT = ScanDiagnosticsChannel.HOST_PACKAGE;
    private static ExportSession export;

    private DebugLogChannel() { }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static BroadcastReceiver registerHost(Context context) {
        if (context == null || !ScanDiagnosticsChannel.HOST_PACKAGE.equals(context.getPackageName())) throw new IllegalArgumentException("Host context required");
        DebugLog.initialize(context);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context current, Intent intent) {
                if (intent == null || !ACTION.equals(intent.getAction()) || !isOrderedBroadcast()
                        || !ScanDiagnosticsChannel.HOST_PACKAGE.equals(intent.getPackage()) || !authorized(current, intent)) return;
                String operation = intent.getStringExtra("operation");
                if ("export_next".equals(operation)) { result(this, chunk(intent.getStringExtra("session"), intent.getIntExtra("offset", -1))); return; }
                PendingResult pending = goAsync();
                Consumer<Bundle> complete = answer -> {
                    Bundle wrapped = new Bundle(); wrapped.putBundle(RESULT, answer);
                    pending.setResultExtras(wrapped); pending.finish();
                };
                try {
                    if ("read".equals(operation)) {
                        DebugLog.readLocal(current, intent.getIntExtra("limit", DebugLog.MAX_TAIL_CHARS), false,
                                snapshot -> complete.accept(snapshot(snapshot)));
                    } else if ("clear".equals(operation)) {
                        DebugLog.clearLocal(current, ok -> complete.accept(ok(ok)));
                    } else if ("enabled".equals(operation)) {
                        DebugLog.updateLocal(current, intent.getBooleanExtra("enabled", false), ok -> complete.accept(ok(ok)));
                    } else if ("export_start".equals(operation)) {
                        DebugLog.readLocal(current, DebugLog.MAX_EXPORT_BYTES, true, snapshot -> {
                            if (!snapshot.available) { complete.accept(ok(false)); return; }
                            complete.accept(startExport(snapshot.text));
                        });
                    } else complete.accept(ok(false));
                } catch (RuntimeException | LinkageError unavailable) { complete.accept(ok(false)); }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, PERMISSION, null);
        return receiver;
    }

    // Caller permission is enforced by registerReceiver; the system-issued token binds its package.
    static boolean authorized(Context context, Intent intent) {
        try {
            PendingIntent proof = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("caller", PendingIntent.class)
                    : intent.getParcelableExtra("caller");
            if (proof == null || !BuildConfig.APPLICATION_ID.equals(proof.getCreatorPackage()) || proof.getCreatorUid() < 0) return false;
            try { return proof.getCreatorUid() == context.getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid; }
            catch (PackageManager.NameNotFoundException hiddenByPackageVisibility) { return true; }
        } catch (RuntimeException unavailable) { return false; }
    }

    private static void result(BroadcastReceiver receiver, Bundle value) { Bundle result = new Bundle(); result.putBundle(RESULT, value); receiver.setResultExtras(result); }
    private static Bundle snapshot(DebugLog.Snapshot value) { Bundle result = ok(value.available); result.putBoolean("enabled", value.enabled); result.putString("text", value.text); return result; }
    private static Bundle ok(boolean value) { Bundle result = new Bundle(); result.putBoolean("ok", value); return result; }

    static void read(Context context, int maximum, Consumer<DebugLog.Snapshot> callback) {
        Bundle arguments = new Bundle(); arguments.putInt("limit", Math.max(1, Math.min(DebugLog.MAX_TAIL_CHARS, maximum)));
        request(context, "read", arguments, reply -> callback.accept(reply == null || !reply.getBoolean("ok")
                ? DebugLog.Snapshot.unavailable() : new DebugLog.Snapshot(true, reply.getBoolean("enabled"), reply.getString("text", DebugLog.EMPTY_TEXT))));
    }

    static void clear(Context context, Consumer<Boolean> callback) { request(context, "clear", new Bundle(), reply -> callback.accept(reply != null && reply.getBoolean("ok"))); }
    static void updateEnabled(Context context, boolean value, Consumer<Boolean> callback) {
        Bundle arguments = new Bundle(); arguments.putBoolean("enabled", value);
        request(context, "enabled", arguments, reply -> callback.accept(reply != null && reply.getBoolean("ok")));
    }

    static void export(Context context, Consumer<String> callback) {
        request(context, "export_start", new Bundle(), first -> {
            if (first == null || !first.getBoolean("ok")) { callback.accept(DebugLog.UNAVAILABLE_TEXT); return; }
            String session = first.getString("session"); int total = first.getInt("total", -1);
            if (session == null || total < 0 || total > DebugLog.MAX_EXPORT_BYTES) { callback.accept(DebugLog.UNAVAILABLE_TEXT); return; }
            collect(context, session, total, new StringBuilder(Math.min(total, DebugLog.MAX_EXPORT_BYTES)), first, callback);
        });
    }

    private static void collect(Context context, String session, int total, StringBuilder text, Bundle part, Consumer<String> callback) {
        String value = part.getString("text");
        if (!part.getBoolean("ok") || !session.equals(part.getString("session")) || part.getInt("offset", -1) != text.length()
                || value == null || value.length() > CHUNK_CHARS || text.length() + value.length() > total || value.isEmpty() && text.length() < total) {
            callback.accept(DebugLog.UNAVAILABLE_TEXT); return;
        }
        text.append(value);
        if (text.length() == total) { callback.accept(text.length() == 0 ? DebugLog.EMPTY_TEXT : text.toString()); return; }
        Bundle arguments = new Bundle(); arguments.putString("session", session); arguments.putInt("offset", text.length());
        request(context, "export_next", arguments, next -> {
            if (next == null) callback.accept(DebugLog.UNAVAILABLE_TEXT);
            else collect(context, session, total, text, next, callback);
        });
    }

    private static void request(Context context, String operation, Bundle extras, Consumer<Bundle> callback) {
        Handler handler = new Handler(Looper.getMainLooper()); AtomicBoolean done = new AtomicBoolean();
        Runnable timeout = () -> { if (done.compareAndSet(false, true)) callback.accept(null); };
        handler.postDelayed(timeout, ScanDiagnosticsChannel.REQUEST_TIMEOUT_MILLIS);
        BroadcastReceiver result = new BroadcastReceiver() {
            @Override public void onReceive(Context current, Intent intent) {
                if (!done.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeout);
                Bundle reply = getResultExtras(false);
                callback.accept(reply == null ? null : reply.getBundle(RESULT));
            }
        };
        try {
            if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName())) throw new SecurityException();
            PendingIntent proof = PendingIntent.getBroadcast(context, 0,
                    new Intent(BuildConfig.APPLICATION_ID + ".action.DEBUG_LOG_CALLER").setPackage(BuildConfig.APPLICATION_ID),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Intent intent = new Intent(ACTION).setPackage(ScanDiagnosticsChannel.HOST_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY).putExtras(extras).putExtra("operation", operation).putExtra("caller", proof);
            context.sendOrderedBroadcast(intent, null, result, handler, 0, null, null);
        } catch (RuntimeException unavailable) {
            if (done.compareAndSet(false, true)) { handler.removeCallbacks(timeout); DebugLog.deliver(() -> callback.accept(null)); }
        }
    }

    static synchronized Bundle startExport(String text) {
        export = new ExportSession(UUID.randomUUID().toString(), text, SystemClock.elapsedRealtime() + EXPORT_TTL_MILLIS);
        return chunk(export.id, 0);
    }
    static synchronized Bundle chunk(String id, int offset) {
        if (export == null || !export.id.equals(id) || SystemClock.elapsedRealtime() >= export.expires
                || offset < 0 || offset > export.text.length()) { if (export != null && SystemClock.elapsedRealtime() >= export.expires) export = null; return ok(false); }
        Bundle result = ok(true); result.putString("session", export.id); result.putInt("offset", offset); result.putInt("total", export.text.length());
        result.putString("text", export.text.substring(offset, Math.min(export.text.length(), offset + CHUNK_CHARS)));
        return result;
    }
    static synchronized void invalidateExport() { export = null; }
    private static final class ExportSession {
        final String id, text; final long expires;
        ExportSession(String id, String text, long expires) { this.id = id; this.text = text; this.expires = expires; }
    }
}

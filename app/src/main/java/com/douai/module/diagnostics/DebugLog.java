package com.douai.module.diagnostics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.douai.module.BuildConfig;
import com.douai.module.config.SettingsRepository;

import java.io.File;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Bounded diagnostics facade shared by the host settings, module UI and logging pipeline. */
public final class DebugLog {
    public static final String KEY = "debug_logging";
    public static final String UNAVAILABLE_TEXT = "宿主未运行或日志通道不可用";
    public static final String EMPTY_TEXT = "暂无调试日志";
    public static final int MAX_TAIL_CHARS = 128 * 1024;
    public static final int MAX_EXPORT_BYTES = 2 * 1024 * 1024;
    private static volatile DebugLogStore store;
    private static volatile boolean enabled;
    private static volatile String ownerPackage;
    private static volatile Runnable clearListener;
    private static final ArrayDeque<String[]> startup = new ArrayDeque<>();

    private DebugLog() { }

    public static synchronized void initialize(Context owner) {
        if (owner == null) return;
        Context application = owner.getApplicationContext();
        if (application == null) application = owner;
        String name = application.getPackageName();
        if (store != null && name.equals(ownerPackage)) return;
        if (store != null) store.close();
        ownerPackage = name;
        enabled = new SettingsRepository(application).getBoolean(KEY, false);
        store = new DebugLogStore(new File(application.getFilesDir(), "douai_debug_logs"));
        while (!startup.isEmpty()) {
            String[] early = startup.removeFirst();
            store.record(false, Integer.parseInt(early[0]), early[1], early[2], null);
        }
    }

    public static void setEnabled(boolean value) { enabled = value; }
    public static void setClearListener(Runnable listener) { clearListener = listener; }
    public static boolean isEnabled() { return enabled; }
    public static String redact(String value) { return DebugLogRedactor.text(value); }
    public static String redactJson(String value) { return DebugLogRedactor.json(value); }

    public static void record(int priority, String tag, String message) { record(priority, tag, message, null); }
    public static void record(int priority, String tag, String message, Throwable failure) {
        if (enabled) append(true, priority, tag, message, failure);
    }
    public static void record(String event, String message) { record(Log.DEBUG, event, message); }
    public static void recordBasic(int priority, String tag, String message) { recordBasic(priority, tag, message, null); }
    public static void recordBasic(int priority, String tag, String message, Throwable failure) { append(false, priority, tag, message, failure); }
    private static void append(boolean detail, int priority, String tag, String message, Throwable failure) {
        DebugLogStore current = store;
        if (current != null) current.record(detail, priority, tag, message, failure);
        else if (!detail) {
            synchronized (DebugLog.class) {
                if (store != null) { store.record(false, priority, tag, message, failure); return; }
                String text = message == null ? "" : message.substring(0, Math.min(4096, message.length()));
                if (startup.size() >= 8) startup.removeFirst();
                startup.addLast(new String[]{Integer.toString(priority), tag == null ? "DouAi" : tag.substring(0, Math.min(96, tag.length())), text});
            }
        }
    }

    /** No IO: only the already-redacted current-process memory ring. */
    public static String memoryTail(int maxChars) {
        DebugLogStore current = store;
        return current == null ? "" : current.memoryTail(Math.max(1, Math.min(MAX_TAIL_CHARS, maxChars)));
    }

    public static void read(Context context, int maxChars, Consumer<String> callback) {
        readSnapshot(context, maxChars, snapshot -> callback.accept(snapshot.text));
    }

    public static void readSnapshot(Context context, int maxChars, Consumer<Snapshot> callback) {
        if (host(context)) readLocal(context, maxChars, false, callback);
        else if (module(context)) DebugLogChannel.read(context, maxChars, callback);
        else deliver(() -> callback.accept(Snapshot.unavailable()));
    }

    public static void export(Context context, Consumer<String> callback) {
        if (host(context)) readLocal(context, MAX_EXPORT_BYTES, true, snapshot -> callback.accept(snapshot.text));
        else if (module(context)) DebugLogChannel.export(context, callback);
        else deliver(() -> callback.accept(UNAVAILABLE_TEXT));
    }

    public static void clear(Context context, Runnable done) { clear(context, ignored -> done.run()); }
    public static void clear(Context context, Consumer<Boolean> callback) {
        if (host(context)) clearLocal(context, callback);
        else if (module(context)) DebugLogChannel.clear(context, callback);
        else deliver(() -> callback.accept(false));
    }

    public static void updateEnabled(Context context, boolean value, Consumer<Boolean> callback) {
        if (host(context)) updateLocal(context, value, callback);
        else if (module(context)) DebugLogChannel.updateEnabled(context, value, callback);
        else deliver(() -> callback.accept(false));
    }

    static void readLocal(Context context, int maxChars, boolean full, Consumer<Snapshot> callback) {
        initialize(context);
        int limit = full ? MAX_EXPORT_BYTES : Math.max(1, Math.min(MAX_TAIL_CHARS, maxChars));
        store.read(limit, full, text -> deliver(() -> callback.accept(new Snapshot(true, enabled, text.isEmpty() ? EMPTY_TEXT : text))));
    }

    static void clearLocal(Context context, Consumer<Boolean> callback) {
        initialize(context);
        DebugLogChannel.invalidateExport();
        store.clear(result -> deliver(() -> {
            Runnable listener = clearListener;
            if (result && listener != null) {
                try { listener.run(); } catch (RuntimeException | LinkageError ignored) { }
            }
            callback.accept(result);
        }));
    }

    static void updateLocal(Context context, boolean value, Consumer<Boolean> callback) {
        try {
            initialize(context);
            new SettingsRepository(context).putBoolean(KEY, value);
            setEnabled(value);
            deliver(() -> callback.accept(true));
        } catch (RuntimeException unavailable) { deliver(() -> callback.accept(false)); }
    }

    static void deliver(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }
    private static boolean host(Context context) { return context != null && ScanDiagnosticsChannel.HOST_PACKAGE.equals(context.getPackageName()); }
    private static boolean module(Context context) { return context != null && BuildConfig.APPLICATION_ID.equals(context.getPackageName()); }

    public static final class Snapshot {
        public final boolean available, enabled;
        public final String text;
        public Snapshot(boolean available, boolean enabled, String text) { this.available = available; this.enabled = enabled; this.text = text; }
        static Snapshot unavailable() { return new Snapshot(false, false, UNAVAILABLE_TEXT); }
    }
}

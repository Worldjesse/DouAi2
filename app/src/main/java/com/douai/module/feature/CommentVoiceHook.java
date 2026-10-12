package com.douai.module.feature;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Replaces only comment audio input, retaining native authorization, upload and publish callbacks. */
public final class CommentVoiceHook implements AutoCloseable {
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;
    private static final Map<String, Long> DURATIONS = new LinkedHashMap<>();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, Long> uploads = new WeakHashMap<>();
    private final Map<Object, Long> listeners = new WeakHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private volatile Context context;
    private volatile Prepared prepared;
    private volatile boolean closed;
    private String requested = "";

    private CommentVoiceHook(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log;
    }

    public static CommentVoiceHook install(XposedInterface framework, HostScanReport report,
                                            ClassLoader loader, LocalOptions options,
                                            Consumer<String> log) throws Exception {
        CommentVoiceHook hook = new CommentVoiceHook(options, log == null ? ignored -> { } : log);
        try {
            Method input = HostTargets.method(report, "comment_audio_upload", loader);
            Method path = HostTargets.method(report, "comment_uploader_path", loader);
            Method listener = HostTargets.method(report, "comment_uploader_listener", loader);
            Method notify = HostTargets.method(report, "comment_audio_notify", loader);
            hook.add(framework, input, "Input", chain -> {
                Prepared ready = hook.prepared;
                if (hook.closed || !hook.options.enabled("comment_voice_replace") || ready == null
                        || !ready.file.isFile()) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                if (args[1] instanceof String && ((String) args[1]).contains("/douai-tts/")) return chain.proceed();
                args[1] = ready.file.getAbsolutePath();
                hook.safeLog("Using configured local audio in the native comment uploader");
                return chain.proceed(args);
            });
            hook.add(framework, path, "Path", chain -> {
                Prepared ready = hook.prepared;
                if (ready != null && ready.file.getAbsolutePath().equals(chain.getArg(0))) {
                    synchronized (hook.uploads) { hook.uploads.put(chain.getThisObject(), ready.durationMillis); }
                }
                return chain.proceed();
            });
            hook.add(framework, listener, "Listener", chain -> {
                Long duration;
                synchronized (hook.uploads) { duration = hook.uploads.remove(chain.getThisObject()); }
                Object value = chain.getArg(0);
                if (duration != null && value != null) {
                    synchronized (hook.listeners) { hook.listeners.put(value, duration); }
                }
                return chain.proceed();
            });
            hook.add(framework, notify, "Notify", chain -> {
                Long duration;
                synchronized (hook.listeners) { duration = hook.listeners.get(chain.getThisObject()); }
                Object info = chain.getArg(2);
                Object id = HostTargets.read(info, "mVideoId");
                Object error = HostTargets.read(info, "mErrorCode");
                if (duration != null && id instanceof String && !((String) id).isEmpty()
                        && error instanceof Number && ((Number) error).longValue() == 0) {
                    synchronized (DURATIONS) {
                        DURATIONS.put((String) id, duration);
                        while (DURATIONS.size() > 64) DURATIONS.remove(DURATIONS.keySet().iterator().next());
                    }
                }
                return chain.proceed();
            });
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    public void setContext(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        setOptions(options);
    }

    public synchronized void setOptions(LocalOptions options) {
        this.options = options;
        String source = options.enabled("comment_voice_replace") ? options.text("comment_voice_path", "").trim() : "";
        if (source.equals(requested) && (source.isEmpty() || prepared != null)) return;
        requested = source;
        long epoch = generation.incrementAndGet();
        prepared = null;
        Context currentContext = context;
        if (closed || source.isEmpty() || currentContext == null) return;
        worker.execute(() -> {
            Prepared value = null;
            try {
                value = prepare(currentContext, source, epoch);
                if (!closed && generation.get() == epoch) {
                    prepared = value;
                    safeLog("Prepared local comment audio with duration " + value.durationMillis + " ms");
                } else {
                    java.nio.file.Files.deleteIfExists(value.file.toPath());
                }
            } catch (Exception failure) {
                safeLog("Comment audio preparation failed: " + failure.getMessage());
            }
        });
    }

    static Long durationFor(String id) {
        synchronized (DURATIONS) { return DURATIONS.get(id); }
    }

    private static Prepared prepare(Context context, String source, long epoch) throws Exception {
        File directory = new File(context.getCacheDir(), "douai-comment-audio");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create audio cache");
        File[] stale = directory.listFiles();
        if (stale != null) {
            long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            for (File file : stale) {
                if (file.isFile() && file.getName().startsWith("replacement_")
                        && file.getName().endsWith(".audio") && file.lastModified() < cutoff) {
                    java.nio.file.Files.deleteIfExists(file.toPath());
                }
            }
        }
        File output = new File(directory, "replacement_" + java.util.UUID.randomUUID() + ".audio");
        try {
            Uri uri = Uri.parse(source);
            try (InputStream input = "content".equalsIgnoreCase(uri.getScheme())
                    ? context.getContentResolver().openInputStream(uri) : new FileInputStream(new File(source));
                 FileOutputStream target = new FileOutputStream(output)) {
                if (input == null) throw new IllegalStateException("Cannot read selected audio");
                byte[] bytes = new byte[16 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(bytes)) != -1) {
                    total += count;
                    if (total > MAX_FILE_BYTES) throw new IllegalArgumentException("Selected audio exceeds 20 MB");
                    target.write(bytes, 0, count);
                }
                target.getFD().sync();
                if (total == 0) throw new IllegalArgumentException("Selected audio is empty");
            }
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            long duration;
            try {
                metadata.setDataSource(output.getAbsolutePath());
                if (!"yes".equalsIgnoreCase(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) {
                    throw new IllegalArgumentException("Selected file has no audio track");
                }
                duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if (duration <= 0) throw new IllegalArgumentException("Selected audio duration is invalid");
                if (duration > 30_000) throw new IllegalArgumentException("评论语音不能超过 30 秒");
            } finally { metadata.release(); }
            return new Prepared(output, duration);
        } catch (Exception failure) {
            java.nio.file.Files.deleteIfExists(output.toPath());
            throw failure;
        }
    }

    private void add(XposedInterface framework, Method method, String id, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.CommentAudio." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(hooker);
        if (handle == null) throw new IllegalStateException("Empty comment audio handle: " + id);
        handles.add(handle);
    }

    private static final class Prepared {
        final File file;
        final long durationMillis;
        Prepared(File file, long durationMillis) { this.file = file; this.durationMillis = durationMillis; }
    }

    @Override public synchronized void close() {
        closed = true;
        generation.incrementAndGet();
        prepared = null;
        worker.shutdownNow();
        options = new LocalOptions(Collections.emptyMap());
        for (int i = handles.size() - 1; i >= 0; i--) {
            try { handles.get(i).unhook(); }
            catch (Throwable failure) { safeLog("Comment audio unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
        synchronized (uploads) { uploads.clear(); }
        synchronized (listeners) { listeners.clear(); }
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }
}

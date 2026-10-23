package com.douai.module.feature;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.douai.module.diagnostics.DouAiLog;
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
    private final Map<Object, UploadTrace> uploadTraces = new WeakHashMap<>();
    private final Map<Object, UploadTrace> listenerTraces = new WeakHashMap<>();
    private final ThreadLocal<UploadTrace> replacing = new ThreadLocal<>();
    private final Map<String, List<UploadTrace>> pendingInputs = new LinkedHashMap<>();
    private final Map<String, UploadTrace> activeUploadTraces = new LinkedHashMap<>();
    private final android.os.Handler traceTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile DouAiLog.Operation preparationTrace;
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
                        || !ready.file.isFile()) { DouAiLog.event("comment_voice_replace", "eligibility", "disabled_closed_or_audio_not_prepared", "closed=" + hook.closed + ";prepared=" + (ready != null)); return chain.proceed(); }
                Object[] args = chain.getArgs().toArray();
                if (isSynthesizedAudio(args[1])) { DouAiLog.event("comment_voice_replace", "eligibility", "synthesized_audio_excluded", "tts_audio=true"); return chain.proceed(); }
                args[1] = ready.file.getAbsolutePath();
                UploadTrace trace = hook.newUpload("native_comment_audio_input", ready);
                hook.rememberInput(ready.file.getAbsolutePath(), trace);
                trace.operation.stage("input_replaced", "duration_ms=" + ready.durationMillis + ";bytes=" + ready.file.length() + ";path_ref=" + DouAiLog.reference(ready.file.getAbsolutePath()) + ";publish_ack=false");
                hook.safeLog("Using configured local audio in the native comment uploader");
                UploadTrace previous = hook.replacing.get();
                hook.replacing.set(trace);
                try {
                    trace.invoked = true;
                    Object result = chain.proceed(args);
                    trace.operation.stage("native_audio_input_returned", "path_observed=" + trace.pathObserved + ";upload_ack=false;publish_ack=false");
                    return result;
                } catch (Throwable failure) {
                    trace.operation.error("native_audio_input", failure);
                    trace.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "native_input_threw", "remote_side_effect_unknown=true;publish_ack=false");
                    throw failure;
                } finally { if (previous == null) hook.replacing.remove(); else hook.replacing.set(previous); }
            });
            hook.add(framework, path, "Path", chain -> {
                Prepared ready = hook.prepared;
                if (ready != null && ready.file.getAbsolutePath().equals(chain.getArg(0))) {
                    synchronized (hook.uploads) { hook.uploads.put(chain.getThisObject(), ready.durationMillis); }
                    UploadTrace trace = hook.resolveInput(ready.file.getAbsolutePath());
                    if (trace == null) trace = hook.newUpload("configured_audio_uploader_path", ready);
                    trace.pathObserved = true;
                    trace.invoked = true;
                    trace.operation.stage("uploader_path_bound", "duration_ms=" + ready.durationMillis + ";configured_path_matches=true;upload_ack=false");
                    hook.track(hook.uploadTraces, chain.getThisObject(), trace, "uploader");
                }
                return chain.proceed();
            });
            hook.add(framework, listener, "Listener", chain -> {
                Long duration;
                synchronized (hook.uploads) { duration = hook.uploads.remove(chain.getThisObject()); }
                Object value = chain.getArg(0);
                UploadTrace trace;
                synchronized (hook.uploadTraces) { trace = hook.uploadTraces.remove(chain.getThisObject()); }
                if (trace != null) {
                    trace.operation.stage("upload_listener_bound", "listener_present=" + (value != null));
                    if (value != null) hook.track(hook.listenerTraces, value, trace, "listener");
                }
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
                UploadTrace trace;
                synchronized (hook.listenerTraces) { trace = hook.listenerTraces.get(chain.getThisObject()); }
                if (trace != null) trace.callback(error, id, duration != null);
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
        DouAiLog.Operation oldPreparation = preparationTrace;
        if (oldPreparation != null) oldPreparation.finish(DouAiLog.Outcome.CANCELLED, "audio_configuration_changed", "local_preparation_obsolete=true");
        preparationTrace = null;
        prepared = null;
        Context currentContext = context;
        if (closed || source.isEmpty() || currentContext == null) { DouAiLog.event("comment_voice_replace", "preparation_eligibility", "closed_source_missing_or_context_unavailable", "closed=" + closed + ";source_configured=" + !source.isEmpty() + ";context_ready=" + (currentContext != null)); return; }
        DouAiLog.Operation trace = DouAiLog.begin("comment_voice_replace", "configured_audio_prepare");
        preparationTrace = trace;
        trace.stage("audio_source_read", "source_ref=" + DouAiLog.reference(source) + ";max_bytes=" + MAX_FILE_BYTES);
        worker.execute(trace.wrap(() -> {
            Prepared value = null;
            try {
                value = prepare(currentContext, source, epoch);
                if (!closed && generation.get() == epoch) {
                    prepared = value;
                    trace.finish(DouAiLog.Outcome.SUCCESS, "configured_audio_validated", "bytes=" + value.file.length() + ";duration_ms=" + value.durationMillis + ";metadata_has_audio=true;duration_positive=true;duration_max_ms=30000;scope=local_preparation;comment_published=false");
                    safeLog("Prepared local comment audio with duration " + value.durationMillis + " ms");
                } else {
                    java.nio.file.Files.deleteIfExists(value.file.toPath());
                    trace.finish(DouAiLog.Outcome.CANCELLED, "audio_preparation_obsolete", "closed=" + closed + ";generation_current=" + (generation.get() == epoch) + ";prepared_file_deleted=true");
                }
            } catch (Exception failure) {
                trace.error("audio_preparation", failure);
                trace.finish(DouAiLog.Outcome.FAILED, "audio_preparation_failed", "scope=local_preparation;error_type=" + failure.getClass().getSimpleName());
                safeLog("Comment audio preparation failed: " + failure.getMessage());
            } finally { if (preparationTrace == trace) preparationTrace = null; }
        }));
    }

    static Long durationFor(String id) {
        synchronized (DURATIONS) { return DURATIONS.get(id); }
    }

    private static boolean isSynthesizedAudio(Object path) {
        if (!(path instanceof String)) return false;
        File parent = new File((String) path).getParentFile();
        return parent != null && ("douai-tts".equals(parent.getName())
                || "douai-private-voice".equals(parent.getName()));
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

    private UploadTrace newUpload(String trigger, Prepared ready) {
        UploadTrace trace = new UploadTrace(DouAiLog.begin("comment_voice_replace", trigger));
        trace.operation.stage("upload_wait", "duration_ms=" + ready.durationMillis + ";callback_deadline_ms=30000;publish_ack=false");
        trace.deadline = trace::timeout;
        trace.release = () -> { traceTimer.removeCallbacks(trace.deadline); synchronized (activeUploadTraces) { activeUploadTraces.remove(trace.observationId, trace); } };
        synchronized (activeUploadTraces) {
            activeUploadTraces.put(trace.observationId, trace);
            while (activeUploadTraces.size() > 128) {
                UploadTrace evicted = activeUploadTraces.remove(activeUploadTraces.keySet().iterator().next());
                traceTimer.removeCallbacks(evicted.deadline);
                evicted.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "upload_observer_capacity", "capacity=128;record_incomplete=true;publish_ack=false");
            }
        }
        traceTimer.postDelayed(trace.deadline, 30_000);
        return trace;
    }

    private void rememberInput(String path, UploadTrace trace) {
        synchronized (pendingInputs) {
            List<UploadTrace> candidates = pendingInputs.computeIfAbsent(path, ignored -> new ArrayList<>());
            candidates.add(trace);
            while (candidates.size() > 8) candidates.remove(0).operation.finish(DouAiLog.Outcome.UNCONFIRMED,
                    "input_path_correlation_capacity", "per_path_capacity=8;record_incomplete=true;publish_ack=false");
            while (pendingInputs.size() > 128) {
                String first = pendingInputs.keySet().iterator().next();
                for (UploadTrace old : pendingInputs.remove(first)) old.operation.finish(DouAiLog.Outcome.UNCONFIRMED,
                        "input_path_correlation_capacity", "path_capacity=128;record_incomplete=true;publish_ack=false");
            }
        }
    }

    private UploadTrace resolveInput(String path) {
        UploadTrace synchronous = replacing.get();
        synchronized (pendingInputs) {
            List<UploadTrace> candidates = pendingInputs.get(path);
            if (synchronous != null) {
                if (candidates != null) { candidates.remove(synchronous); if (candidates.isEmpty()) pendingInputs.remove(path); }
                return synchronous;
            }
            if (candidates == null || candidates.isEmpty()) return null;
            pendingInputs.remove(path);
            if (candidates.size() == 1) return candidates.get(0);
            // A shared configured file is insufficient proof of which concurrent input owns this uploader.
            for (UploadTrace candidate : candidates) candidate.operation.finish(DouAiLog.Outcome.UNCONFIRMED,
                    "asynchronous_input_correlation_ambiguous", "candidates=" + candidates.size() + ";record_incomplete=true;publish_ack=false");
            return null;
        }
    }

    private void track(Map<Object, UploadTrace> table, Object key, UploadTrace trace, String kind) {
        synchronized (table) {
            UploadTrace old = table.put(key, trace);
            if (old != null && old != trace) old.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "upload_correlation_replaced", "table=" + kind + ";publish_ack=false");
            while (table.size() > 128) {
                Object first = table.keySet().iterator().next();
                UploadTrace evicted = table.remove(first);
                if (evicted != null) evicted.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "upload_correlation_capacity", "capacity=128;table=" + kind + ";record_incomplete=true;publish_ack=false");
            }
        }
    }

    static final class UploadTrace {
        final DouAiLog.Operation operation;
        final String observationId = java.util.UUID.randomUUID().toString();
        volatile boolean invoked, pathObserved, uploadTerminalObserved;
        Runnable deadline;
        Runnable release = () -> { };
        UploadTrace(DouAiLog.Operation operation) { this.operation = operation; }
        void callback(Object error, Object videoId, boolean durationTracked) {
            boolean codeKnown = error instanceof Number;
            boolean idPresent = videoId instanceof String && !((String) videoId).isEmpty();
            String evidence = "error_code=" + (codeKnown ? ((Number) error).longValue() : "unknown")
                    + ";video_id_present=" + idPresent + ";duration_tracked=" + durationTracked
                    + ";video_ref=" + DouAiLog.reference(videoId instanceof String ? (String) videoId : null)
                    + ";scope=audio_upload;comment_publish_ack=false";
            operation.stage("upload_callback", evidence);
            if (durationTracked && codeKnown && ((Number) error).longValue() == 0 && idPresent) {
                uploadTerminalObserved = true;
                operation.finish(DouAiLog.Outcome.UNCONFIRMED, "audio_uploaded_comment_publish_unobserved", evidence);
                release.run();
            } else if (codeKnown && ((Number) error).longValue() != 0) {
                uploadTerminalObserved = true;
                operation.finish(DouAiLog.Outcome.FAILED, "native_audio_upload_failed", evidence);
                release.run();
            }
        }
        void timeout() {
            if (uploadTerminalObserved) return;
            operation.finish(DouAiLog.Outcome.UNCONFIRMED, "upload_callback_timeout_or_publish_unobserved",
                    "callback_deadline_ms=30000;native_invoked=" + invoked + ";path_observed=" + pathObserved + ";comment_publish_ack=false");
            release.run();
        }
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
        DouAiLog.Operation preparingTrace = preparationTrace;
        if (preparingTrace != null) preparingTrace.finish(DouAiLog.Outcome.CANCELLED, "hook_closed", "scope=local_preparation");
        preparationTrace = null;
        synchronized (activeUploadTraces) { for (UploadTrace trace : activeUploadTraces.values()) { traceTimer.removeCallbacks(trace.deadline); trace.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "hook_closed", "upload_observation_stopped=true;publish_ack=false"); } activeUploadTraces.clear(); }
        synchronized (pendingInputs) { for (List<UploadTrace> candidates : pendingInputs.values()) for (UploadTrace trace : candidates) trace.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "hook_closed", "input_path_observation_stopped=true;publish_ack=false"); pendingInputs.clear(); }
        synchronized (uploadTraces) { for (UploadTrace trace : uploadTraces.values()) trace.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "hook_closed", "upload_observation_stopped=true;publish_ack=false"); uploadTraces.clear(); }
        synchronized (listenerTraces) { for (UploadTrace trace : listenerTraces.values()) trace.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "hook_closed", "upload_observation_stopped=true;publish_ack=false"); listenerTraces.clear(); }
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

package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;

import android.os.Handler;
import android.os.Looper;

import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface;

/** Submits native audio attachments and observes remote acknowledgment separately from local insertion. */
final class NativeAudioMessageSender implements AutoCloseable {
    static final long RETENTION_MILLIS = 10 * 60_000;
    static final class Content {
        final int type;
        final Object value;
        Content(int type, Object value) { this.type = type; this.value = value; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, DouAiLog.Operation> completedTraces = Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private final Method type;
    private final Constructor<?> plainContent;
    private final Constructor<?> encryptedContent;
    private final Method encryptedType;
    private final Field duration;
    private final Field wave;
    private final Field digest;
    private final Constructor<?> attachment;
    private final Method attachmentUuid;
    private final Method attachmentPath;
    private final Method attachments;
    private final Method messageUuid;
    private final Method messageStatus;
    private final Method messageServerId;
    private final Method messageExt;
    private final Method setStatus;
    private final Method localPath;
    private final Method insert;
    private final Method enqueue;
    private final Object uploader;
    private final Class<?> listener;
    private volatile boolean closed;

    NativeAudioMessageSender(XposedInterface framework, HostScanReport report, ClassLoader loader) throws Exception {
        type = HostTargets.method(report, "dm_auto_audio_type", loader);
        Class<?> plain = HostTargets.type(report, "dm_auto_audio_content", loader);
        plainContent = plain.getConstructor();
        encryptedContent = HostTargets.type(report, "dm_auto_audio_encrypted_content", loader).getConstructor();
        encryptedType = HostTargets.method(report, "dm_auto_audio_content_type", loader);
        duration = HostTargets.field(plain, "duration");
        wave = HostTargets.field(plain, "waveList");
        digest = HostTargets.field(plain, "md5");
        Class<?> attachmentType = HostTargets.type(report, "dm_auto_audio_attachment", loader);
        attachment = attachmentType.getConstructor();
        attachmentUuid = attachmentType.getMethod("setMsgUuid", String.class);
        attachmentPath = attachmentType.getMethod("setLocalPath", String.class);
        setStatus = HostTargets.method(report, "dm_auto_audio_message_status", loader);
        Class<?> message = setStatus.getDeclaringClass();
        attachments = message.getMethod("setAttachments", List.class);
        messageUuid = message.getMethod("getUuid");
        messageStatus = message.getMethod("getMsgStatus");
        messageServerId = message.getMethod("getMsgId");
        messageExt = HostTargets.method(report, "dm_speech_message_local_ext", loader);
        localPath = HostTargets.method(report, "dm_auto_audio_local_path", loader);
        insert = HostTargets.method(report, "dm_auto_audio_insert", loader);
        listener = insert.getParameterTypes()[2];
        enqueue = HostTargets.method(report, "dm_speech_upload_enqueue", loader);
        Object singleton = HostTargets.field(type.getDeclaringClass(), "LJFF").get(null);
        uploader = HostTargets.method(report, "dm_auto_audio_uploader", loader).invoke(singleton);
        try {
            observe(framework, setStatus);
            observe(framework, HostTargets.method(report, "dm_auto_audio_message_server_id", loader));
        } catch (Throwable failure) {
            close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    private void observe(XposedInterface framework, Method method) {
        XposedInterface.HookHandle handle = framework.hook(method)
                .setId("DouAi.AutoAudio." + handles.size())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
            Object result = chain.proceed();
            try { acknowledge(chain.getThisObject()); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
            return result;
        });
        if (handle == null) throw new IllegalStateException("Native audio observer was not installed");
        handles.add(handle);
    }

    Content content(SpeechPreparation.Audio audio) throws Exception {
        if (closed || audio == null || !audio.file.isFile() || audio.file.length() == 0
                || audio.duration <= 0 || audio.duration > 120_000) {
            throw new IllegalArgumentException("Native audio file is unavailable or exceeds 120 seconds");
        }
        int kind = ((Number) type.invoke(null)).intValue();
        if (kind != 17 && kind != 109) throw new IllegalStateException("Unsupported native audio message type");
        Object content = (kind == 109 ? encryptedContent : plainContent).newInstance();
        if (kind == 109) encryptedType.invoke(content, 10900);
        duration.setLong(content, audio.duration);
        wave.set(content, Collections.emptyList());
        byte[] bytes = MessageDigest.getInstance("MD5").digest(audio.file.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        StringBuilder md5 = new StringBuilder(32);
        for (byte value : bytes) md5.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        digest.set(content, md5.toString());
        return new Content(kind, content);
    }

    void attach(Object message, SpeechPreparation.Audio audio) throws Exception {
        String uuid = (String) messageUuid.invoke(message);
        if (uuid == null || uuid.isEmpty()) throw new IllegalStateException("Native audio message has no UUID");
        Object item = attachment.newInstance();
        attachmentUuid.invoke(item, uuid);
        attachmentPath.invoke(item, audio.file.getAbsolutePath());
        attachments.invoke(message, Collections.singletonList(item));
        setStatus.invoke(message, 0);
        localPath.invoke(null, message, audio.file.getAbsolutePath());
    }

    void submit(Object message, Content content, SpeechPreparation.Audio audio, Consumer<Boolean> done,
                BooleanSupplier valid) throws Exception {
        if (closed) { audio.delete(); done.accept(false); return; }
        String uuid = (String) messageUuid.invoke(message);
        Pending item = new Pending(uuid, audio, done);
        item.trace.stage("audio_local_insert", "message_ref=" + DouAiLog.reference(uuid) + ";bytes=" + audio.file.length() + ";duration_ms=" + audio.duration + ";remote_ack=false");
        if (pending.putIfAbsent(uuid, item) != null) throw new IllegalStateException("Duplicate native audio UUID");
        main.postDelayed(() -> {
            item.trace.stage("audio_retention_expired", "retention_ms=" + RETENTION_MILLIS + ";remote_outcome=unknown;completed=" + item.completed.get());
            item.completed.set(true);
            pending.remove(uuid, item);
            audio.delete();
        }, RETENTION_MILLIS);
        Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "DouAiAudioInsertCallback";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    }
                    // This callback acknowledges local database insertion, not remote delivery.
                    if ("onFailure".equals(method.getName())) { item.trace.stage("audio_local_insert_failed", "remote_ack=false"); finish(item, false); }
                    if ("onSuccess".equals(method.getName()) && !item.completed.get() && (closed || !valid.getAsBoolean())) {
                        finish(item, false); return null;
                    }
                    if ("onSuccess".equals(method.getName()) && !item.completed.get()
                            && item.queued.compareAndSet(false, true)) {
                        item.trace.stage("audio_upload_enqueue", "local_insert_success=true;remote_ack=false");
                        try { enqueue.invoke(uploader, message, content.value, System.currentTimeMillis(), null); }
                        catch (ReflectiveOperationException | RuntimeException ignored) {
                            item.trace.error("audio_upload_enqueue", ignored);
                            item.trace.stage("audio_enqueue_unknown", "native_invocation_threw=true;remote_outcome=unknown");
                            // A throwing native queue may already have submitted an upload; retain its unknown outcome.
                        }
                    }
                    return null;
                });
        insert.invoke(null, message, null, callback, null);
    }

    private void acknowledge(Object message) throws ReflectiveOperationException {
        Object uuid = messageUuid.invoke(message);
        Pending item = pending.get(uuid);
        if (item == null) {
            DouAiLog.Operation completed = completedTraces.get(uuid);
            if (completed != null) completed.stage("audio_late_observation", "previously_completed=true;message_status=" + messageStatus.invoke(message));
            return;
        }
        int status = ((Number) messageStatus.invoke(message)).intValue();
        if (status == 3) { item.trace.stage("audio_native_failure", "message_status=3"); finish(item, false); return; }
        Object extension = messageExt.invoke(message);
        if (status == 2 && ((Number) messageServerId.invoke(message)).longValue() > 0
                && extension instanceof Map<?, ?> && "0".equals(((Map<?, ?>) extension).get("s:status_code"))) {
            item.trace.stage("audio_remote_ack", "message_status=2;server_id_positive=true;status_code=0");
            finish(item, true);
        }
    }

    private void finish(Pending item, boolean success) {
        if (!item.completed.compareAndSet(false, true)) { item.trace.stage("audio_callback_ignored", "duplicate=true;success=" + success); return; }
        item.trace.stage("audio_callback", "success=" + success + ";evidence=" + (success ? "status_2_positive_server_id_status_code_0" : "native_failure_or_cancelled_before_upload"));
        pending.remove(item.uuid, item);
        synchronized (completedTraces) {
            completedTraces.put(item.uuid, item.trace);
            while (completedTraces.size() > 128) completedTraces.remove(completedTraces.keySet().iterator().next());
        }
        item.audio.delete();
        item.done.accept(success);
    }

    @Override public void close() {
        closed = true;
        for (XposedInterface.HookHandle handle : handles) handle.unhook();
        handles.clear();
        // Submitted uploads retain their files until the bounded cleanup deadline.
        pending.clear();
        completedTraces.clear();
    }

    private static final class Pending {
        final String uuid;
        final DouAiLog.Operation trace;
        final SpeechPreparation.Audio audio;
        final Consumer<Boolean> done;
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean queued = new AtomicBoolean();
        Pending(String uuid, SpeechPreparation.Audio audio, Consumer<Boolean> done) {
            this.uuid = uuid; this.audio = audio;
            trace = AutomationTrace.capture("native_audio_message", "send");
            this.done = trace.wrapConsumer(done);
        }
    }
}

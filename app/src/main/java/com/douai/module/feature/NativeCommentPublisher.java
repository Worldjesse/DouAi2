package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;

import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Uses the host's permission-checked publisher with a captured work context. */
final class NativeCommentPublisher implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Constructor<?> requestConstructor;
    private final Constructor<?> modelConstructor;
    private final Method contextFactory;
    private final Method submit;
    private final Method resolveAweme;
    private final Field modelText;
    private final Field modelContext;
    private final Map<Object, Pending> requests = new IdentityHashMap<>();
    private final ThreadLocal<Pending> submitting = new ThreadLocal<>();
    private final Map<Object, String> ownedModels = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final Field attachment;
    private final Constructor<?> attachmentConstructor;
    private final Constructor<?> audioConstructor;
    private volatile boolean closed;

    private NativeCommentPublisher(HostScanReport report, ClassLoader loader, Consumer<String> log) throws Exception {
        submit = HostTargets.method(report, "auto_comment_checked_request", loader);
        requestConstructor = submit.getDeclaringClass().getConstructor();
        modelConstructor = submit.getParameterTypes()[0].getConstructor();
        contextFactory = HostTargets.method(report, "auto_comment_work_context", loader);
        resolveAweme = HostTargets.method(report, "portrait_comment_aweme_resolver", loader);
        modelText = HostTargets.field(modelConstructor.getDeclaringClass(), "a");
        modelContext = HostTargets.field(modelConstructor.getDeclaringClass(), "y");
        attachment = HostTargets.field(modelConstructor.getDeclaringClass(), "A");
        attachmentConstructor = attachment.getType().getConstructor();
        audioConstructor = report.getResults().containsKey("comment_audio_model")
                ? HostTargets.type(report, "comment_audio_model", loader).getConstructor(java.io.File.class, List.class, Long.class)
                : null;
        this.log = log;
        if (submit.getReturnType() != boolean.class || contextFactory.getReturnType() != modelContext.getType()
                || resolveAweme.getParameterTypes()[0] != modelContext.getType()) {
            throw new IllegalStateException("Automatic comment publisher contract changed");
        }
    }

    static NativeCommentPublisher install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Consumer<String> log) throws Exception {
        NativeCommentPublisher publisher = new NativeCommentPublisher(report, loader, log);
        try {
            Method upload = HostTargets.method(report, "auto_comment_audio_upload", loader);
            XposedInterface.HookHandle uploadHandle = framework.hook(upload).setId("DouAi.AutoComment.AudioUpload")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Pending pending = publisher.submitting.get();
                        Object callback = chain.getArg(2);
                        if (pending != null && pending.audioPath != null
                                && pending.model == HostTargets.read(callback, "l0")
                                && pending.owner == HostTargets.read(callback, "l1")
                                && pending.audioPath.equals(chain.getArg(1))) {
                            // The checked host method returns false after starting asynchronous audio upload.
                            pending.uploadStarted = true;
                            publisher.safeLog("Automatic comment native audio upload started for owned request");
                        }
                        return chain.proceed();
                    });
            if (uploadHandle == null) throw new IllegalStateException("Empty comment audio upload hook");
            publisher.handles.add(uploadHandle);
            for (String rule : new String[]{"auto_comment_publish_success", "auto_comment_publish_failure"}) {
                Method target = HostTargets.method(report, rule, loader);
                boolean success = rule.endsWith("success");
                XposedInterface.HookHandle handle = framework.hook(target).setId("DouAi.AutoComment." + rule)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object owner = chain.getThisObject();
                            Object response = chain.getArg(0);
                            Pending pending = publisher.submitting.get();
                            if (pending != null && pending.owner == owner) pending.callbackSeen = true;
                            try { return chain.proceed(); }
                            finally {
                                publisher.main.post(() -> {
                                    Pending tracked = publisher.requests.get(owner);
                                    String status = !success ? "failed" : tracked != null && validResponse(response, tracked.aid) ? "sent" : "submitted";
                                    publisher.received(owner, status, callbackDiagnostic(response));
                                });
                            }
                        });
                if (handle == null) throw new IllegalStateException("Empty comment callback hook");
                publisher.handles.add(handle);
            }
            return publisher;
        } catch (Exception failure) { publisher.close(); throw failure; }
    }

    boolean ownsPublish(Object model) {
        String text = ownedModels.get(model);
        return text != null && text.equals(HostTargets.read(model, "a"));
    }

    void send(Object work, String aid, String text, BooleanSupplier valid, BooleanSupplier beforeNativeSend,
              Runnable acceptedRequest, Consumer<String> result) {
        send(work, aid, text, null, valid, beforeNativeSend, acceptedRequest, result);
    }

    void send(Object work, String aid, String text, SpeechPreparation.Audio audio, BooleanSupplier valid,
              BooleanSupplier beforeNativeSend, Runnable acceptedRequest, Consumer<String> result) {
        main.post(() -> {
            if (closed || !valid.getAsBoolean()) { result.accept("cancelled"); return; }
            Object request = null;
            Pending pending = null;
            boolean accepted = false;
            try {
                Object state = contextFactory.invoke(null, work);
                Object origin = state == null ? null : resolveAweme.invoke(null, state);
                if (!aid.equals(HostTargets.invoke(origin, "getAid"))) throw new IllegalStateException("Origin work changed");
                Object model = modelConstructor.newInstance();
                String outputText = audio == null ? text : "";
                modelText.set(model, outputText);
                modelContext.set(model, state);
                if (audio != null) {
                    if (audioConstructor == null) throw new IllegalStateException("当前宿主未适配语音评论");
                    if (!audio.file.isFile() || audio.file.length() == 0 || audio.duration <= 0) {
                        throw new IllegalArgumentException("评论语音文件无效");
                    }
                    Object media = attachmentConstructor.newInstance();
                    HostTargets.field(media.getClass(), "audio").set(media,
                            audioConstructor.newInstance(audio.file, java.util.Collections.emptyList(), Long.valueOf(audio.duration)));
                    HostTargets.field(media.getClass(), "text").set(media, new android.text.SpannableStringBuilder());
                    attachment.set(model, media);
                    HostTargets.field(model.getClass(), "d").set(model, java.util.Collections.emptyList());
                }
                HostTargets.field(model.getClass(), "n").set(model, "douai_auto");
                ownedModels.put(model, outputText);
                request = requestConstructor.newInstance();
                pending = new Pending(aid, request, model, audio == null ? null : audio.file.getAbsolutePath(), result);
                requests.put(request, pending);
                if (!valid.getAsBoolean() || !beforeNativeSend.getAsBoolean()) {
                    received(request, "cancelled");
                    return;
                }
                safeLog("Automatic comment native submission; media=" + (audio == null ? "text" : "audio")
                        + "; audioMillis=" + (audio == null ? 0 : audio.duration)
                        + "; audioBytes=" + (audio == null ? 0 : audio.file.length()));
                boolean directSubmission;
                submitting.set(pending);
                try { directSubmission = Boolean.TRUE.equals(submit.invoke(request, model)); }
                finally { submitting.remove(); }
                accepted = directSubmission || pending.uploadStarted || pending.callbackSeen;
                if (!accepted) { received(request, "failed"); return; }
                acceptedRequest.run();
                safeLog("Automatic comment native request accepted for captured work; mode="
                        + (pending.uploadStarted ? "audio_upload" : "direct"));
                Object owner = request;
                Pending token = pending;
                main.postDelayed(() -> {
                    if (requests.get(owner) != token || !token.timedOut.compareAndSet(false, true)) return;
                    token.result.accept("submitted");
                }, 30_000);
                main.postDelayed(() -> requests.remove(owner, token), 120_000);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                // An invoked host method can throw after it has already sent the request.
                if (request != null && pending != null
                        && (accepted || failure instanceof java.lang.reflect.InvocationTargetException)) {
                    Object owner = request;
                    Pending token = pending;
                    main.postDelayed(() -> requests.remove(owner, token), 120_000);
                    safeLog("Automatic comment native request outcome unknown: " + failure.getClass().getSimpleName());
                    result.accept("submitted");
                    return;
                }
                if (request != null && pending != null) requests.remove(request);
                safeLog("Automatic comment native request failed: " + failure.getClass().getSimpleName());
                result.accept("failed");
            }
        });
    }

    private void received(Object request, String status) {
        received(request, status, "");
    }

    private void received(Object request, String status, String detail) {
        Pending pending = requests.remove(request);
        if (pending == null || closed) return;
        safeLog("Automatic comment native callback: " + status + detail);
        pending.result.accept(status);
    }

    static String callbackDiagnostic(Object response) {
        if (response == null) return "; response=null";
        Object code = HostTargets.read(response, "status_code");
        if (!(code instanceof Number)) code = HostTargets.invoke(response, "getErrorCode");
        return "; response=" + response.getClass().getSimpleName()
                + (code instanceof Number ? "; code=" + ((Number) code).longValue() : "");
    }

    static boolean validResponse(Object response, String aid) {
        Object status = HostTargets.read(response, "status_code");
        Object comment = HostTargets.read(response, "comment");
        Object cid = HostTargets.read(comment, "cid");
        Object origin = HostTargets.read(comment, "awemeId");
        return status instanceof Number && ((Number) status).intValue() == 0
                && cid instanceof String && !((String) cid).isEmpty()
                && (origin == null || "".equals(origin) || aid.equals(origin));
    }

    private void safeLog(String message) { try { log.accept(message); } catch (RuntimeException ignored) { } }

    @Override public void close() {
        closed = true;
        main.post(requests::clear);
        ownedModels.clear();
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (RuntimeException ignored) { }
        }
        handles.clear();
    }

    private static final class Pending {
        final String aid;
        final Object owner;
        final Object model;
        final String audioPath;
        final Consumer<String> result;
        final AtomicBoolean timedOut = new AtomicBoolean();
        boolean uploadStarted;
        boolean callbackSeen;
        Pending(String aid, Object owner, Object model, String audioPath, Consumer<String> result) {
            this.aid = aid; this.owner = owner; this.model = model; this.audioPath = audioPath; this.result = result;
        }
    }
}

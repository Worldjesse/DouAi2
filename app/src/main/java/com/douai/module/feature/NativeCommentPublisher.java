package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;

import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    private final HostScanReport report;
    private final ClassLoader loader;
    private ReplyBinding replyBinding;
    private volatile boolean closed;

    private NativeCommentPublisher(HostScanReport report, ClassLoader loader, Consumer<String> log) throws Exception {
        this.report = report;
        this.loader = loader;
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
        return install(framework, report, loader, log, "DouAi.AutoComment");
    }

    static NativeCommentPublisher install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Consumer<String> log, String namespace) throws Exception {
        if (namespace == null || namespace.trim().isEmpty()) throw new IllegalArgumentException("Hook namespace is required");
        NativeCommentPublisher publisher = new NativeCommentPublisher(report, loader, log);
        try {
            Method upload = HostTargets.method(report, "auto_comment_audio_upload", loader);
            XposedInterface.HookHandle uploadHandle = framework.hook(upload).setId(namespace + ".AudioUpload")
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
                XposedInterface.HookHandle handle = framework.hook(target).setId(namespace + "." + rule)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object owner = chain.getThisObject();
                            Object response = chain.getArg(0);
                            Pending pending = publisher.submitting.get();
                            if (pending != null && pending.owner == owner) pending.callbackSeen = true;
                            try { return chain.proceed(); }
                            finally {
                                publisher.main.post(() -> {
                                    Pending tracked = publisher.requests.get(owner);
                                    String status = !success ? "failed" : tracked == null ? "submitted"
                                            : responseStatus(response, tracked.aid, tracked.rootId, tracked.incomingId);
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
        send(work, aid, text, audio, null, valid, beforeNativeSend, acceptedRequest, result);
    }

    void sendReply(CommentReplyEvent event, String text, BooleanSupplier valid, BooleanSupplier beforeNativeSend,
                   Runnable acceptedRequest, Consumer<String> result) {
        sendReply(event, text, null, valid, beforeNativeSend, acceptedRequest, result);
    }

    void sendReply(CommentReplyEvent event, String text, SpeechPreparation.Audio audio,
                   BooleanSupplier valid, BooleanSupplier beforeNativeSend,
                   Runnable acceptedRequest, Consumer<String> result) {
        if (event == null) { main.post(() -> result.accept("failed")); return; }
        send(event.work, event.aid, text, audio, event, valid, beforeNativeSend, acceptedRequest, result);
    }

    private void send(Object work, String aid, String text, SpeechPreparation.Audio audio, CommentReplyEvent reply,
                      BooleanSupplier valid, BooleanSupplier beforeNativeSend, Runnable acceptedRequest, Consumer<String> result) {
        main.post(() -> {
            if (closed || !valid.getAsBoolean()) { result.accept("cancelled"); return; }
            Object request = null;
            Pending pending = null;
            boolean accepted = false;
            boolean nativeInvoked = false;
            try {
                Object state = contextFactory.invoke(null, work);
                Object origin = state == null ? null : resolveAweme.invoke(null, state);
                if (!aid.equals(HostTargets.invoke(origin, "getAid"))) throw new IllegalStateException("Origin work changed");
                Object model = modelConstructor.newInstance();
                ReplyBinding binding = reply == null ? null : replyBinding();
                if (reply != null) {
                    if ((audio == null && (text == null || text.trim().isEmpty())) || !validReplyTarget(reply, binding.target)) {
                        throw new IllegalStateException("Comment reply target unavailable or changed");
                    }
                    binding.target.set(model, reply.targetComment);
                    binding.root.set(model, reply.rootId);
                    binding.direct.set(model, reply.rootId.equals(reply.incomingId) ? "0" : reply.incomingId);
                }
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
                HostTargets.field(model.getClass(), "n").set(model, reply == null ? "douai_auto" : "douai_comment_reply");
                if (reply != null && !binding.matches(model, reply)) {
                    throw new IllegalStateException("Comment reply native target does not match");
                }
                ownedModels.put(model, outputText);
                request = requestConstructor.newInstance();
                pending = new Pending(aid, request, model, audio == null ? null : audio.file.getAbsolutePath(),
                        reply == null ? null : reply.rootId, reply == null ? null : reply.incomingId, result);
                requests.put(request, pending);
                if (!valid.getAsBoolean() || !beforeNativeSend.getAsBoolean()) {
                    received(request, "cancelled");
                    return;
                }
                if (reply != null && !binding.matches(model, reply)) {
                    throw new IllegalStateException("Comment reply target changed before submission");
                }
                if (reply != null) safeLog("Comment reply native target verified: root_match=1; direct_match=1; nested="
                        + (reply.rootId.equals(reply.incomingId) ? 0 : 1));
                safeLog("Automatic comment native submission; media=" + (audio == null ? "text" : "audio")
                        + "; audioMillis=" + (audio == null ? 0 : audio.duration)
                        + "; audioBytes=" + (audio == null ? 0 : audio.file.length()));
                boolean directSubmission;
                submitting.set(pending);
                try {
                    nativeInvoked = true;
                    directSubmission = Boolean.TRUE.equals(submit.invoke(request, model));
                }
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
            } catch (Exception failure) {
                // An invoked host method can throw after it has already sent the request.
                if (request != null && pending != null
                        && (accepted || nativeInvoked && failure instanceof java.lang.reflect.InvocationTargetException)) {
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

    /** Reply-only binding: ordinary work comments do not depend on these host helpers. */
    private ReplyBinding replyBinding() throws Exception {
        if (replyBinding == null) replyBinding = new ReplyBinding();
        return replyBinding;
    }

    private final class ReplyBinding {
        final Field target = HostTargets.field(modelConstructor.getDeclaringClass(), "p");
        final Field root = HostTargets.field(modelConstructor.getDeclaringClass(), "b");
        final Field direct = HostTargets.field(modelConstructor.getDeclaringClass(), "e");
        final Method rootResolver = HostTargets.method(report, "comment_reply_publish_root", loader);
        final Method directResolver = HostTargets.method(report, "comment_reply_publish_direct", loader);

        ReplyBinding() throws Exception {
            Class<?>[] parameters = {target.getType(), modelConstructor.getDeclaringClass()};
            if (root.getType() != String.class || direct.getType() != String.class
                    || Modifier.isStatic(target.getModifiers()) || Modifier.isStatic(root.getModifiers())
                    || Modifier.isStatic(direct.getModifiers())
                    || !Modifier.isStatic(rootResolver.getModifiers()) || !Modifier.isStatic(directResolver.getModifiers())
                    || rootResolver.getReturnType() != String.class || directResolver.getReturnType() != String.class
                    || !java.util.Arrays.equals(rootResolver.getParameterTypes(), parameters)
                    || !java.util.Arrays.equals(directResolver.getParameterTypes(), parameters)) {
                throw new IllegalStateException("Comment reply native target contract changed");
            }
        }

        boolean matches(Object model, CommentReplyEvent event) throws Exception {
            String expectedDirect = event.rootId.equals(event.incomingId) ? "0" : event.incomingId;
            if (target.get(model) != event.targetComment || !validReplyTarget(event, target)
                    || !event.rootId.equals(root.get(model)) || !expectedDirect.equals(direct.get(model))) return false;
            Object resolvedRoot = rootResolver.invoke(null, event.targetComment, model);
            Object resolvedDirect = directResolver.invoke(null, event.targetComment, model);
            return event.rootId.equals(resolvedRoot) && (event.rootId.equals(event.incomingId)
                    ? resolvedDirect == null || "".equals(resolvedDirect) || "0".equals(resolvedDirect)
                    : event.incomingId.equals(resolvedDirect));
        }
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

    private static boolean validReplyTarget(CommentReplyEvent event, Field targetField) {
        if (event.targetComment == null || !targetField.getType().isInstance(event.targetComment)
                || event.incomingId == null || event.incomingId.isEmpty() || event.rootId == null || event.rootId.isEmpty()
                || event.aid == null || event.aid.isEmpty()
                || !event.incomingId.equals(HostTargets.read(event.targetComment, "cid"))
                || !event.aid.equals(HostTargets.read(event.work, "aid"))) return false;
        Object targetAid = HostTargets.read(event.targetComment, "awemeId");
        // The unified native notice binds its work separately from current_comment.
        // Only that verified binding may omit the repeated ID; a conflicting ID always fails.
        if (!event.aid.equals(targetAid)
                && !(event.nativeWorkBound && (targetAid == null || "".equals(targetAid)))) return false;
        Object parent = HostTargets.read(event.targetComment, "replyId");
        String root = parent instanceof String && !((String) parent).isEmpty() && !"0".equals(parent)
                ? (String) parent : event.incomingId;
        return event.rootId.equals(root);
    }

    static boolean validReplyResponse(Object response, String aid, String rootId, String incomingId) {
        if (!validResponse(response, aid) || rootId == null || rootId.isEmpty() || incomingId == null || incomingId.isEmpty()) return false;
        Object comment = HostTargets.read(response, "comment");
        return aid.equals(HostTargets.read(comment, "awemeId"))
                && rootId.equals(HostTargets.read(comment, "replyId"))
                && (rootId.equals(incomingId) || incomingId.equals(HostTargets.read(comment, "replyToReplyId")));
    }

    static String responseStatus(Object response, String aid, String rootId, String incomingId) {
        Object status = HostTargets.read(response, "status_code");
        if (status instanceof Number && ((Number) status).longValue() != 0) return "failed";
        return (rootId == null ? validResponse(response, aid) : validReplyResponse(response, aid, rootId, incomingId))
                ? "sent" : "submitted";
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
        final String rootId;
        final String incomingId;
        final Consumer<String> result;
        final AtomicBoolean timedOut = new AtomicBoolean();
        boolean uploadStarted;
        boolean callbackSeen;
        Pending(String aid, Object owner, Object model, String audioPath, String rootId, String incomingId, Consumer<String> result) {
            this.aid = aid; this.owner = owner; this.model = model; this.audioPath = audioPath; this.result = result;
            this.rootId = rootId; this.incomingId = incomingId;
        }
    }
}

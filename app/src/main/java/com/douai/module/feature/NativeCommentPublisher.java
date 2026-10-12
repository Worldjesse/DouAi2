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
    private final Map<Object, String> ownedModels = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private volatile boolean closed;

    private NativeCommentPublisher(HostScanReport report, ClassLoader loader, Consumer<String> log) throws Exception {
        submit = HostTargets.method(report, "auto_comment_checked_request", loader);
        requestConstructor = submit.getDeclaringClass().getConstructor();
        modelConstructor = submit.getParameterTypes()[0].getConstructor();
        contextFactory = HostTargets.method(report, "auto_comment_work_context", loader);
        resolveAweme = HostTargets.method(report, "portrait_comment_aweme_resolver", loader);
        modelText = HostTargets.field(modelConstructor.getDeclaringClass(), "a");
        modelContext = HostTargets.field(modelConstructor.getDeclaringClass(), "y");
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
            for (String rule : new String[]{"auto_comment_publish_success", "auto_comment_publish_failure"}) {
                Method target = HostTargets.method(report, rule, loader);
                boolean success = rule.endsWith("success");
                XposedInterface.HookHandle handle = framework.hook(target).setId("DouAi.AutoComment." + rule)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object result = chain.proceed();
                            Object owner = chain.getThisObject();
                            Object response = success ? chain.getArg(0) : null;
                            publisher.main.post(() -> {
                                Pending pending = publisher.requests.get(owner);
                                String status = !success ? "failed" : pending != null && validResponse(response, pending.aid) ? "sent" : "submitted";
                                publisher.received(owner, status);
                            });
                            return result;
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

    void send(Object work, String aid, String text, BooleanSupplier valid, Runnable acceptedRequest, Consumer<String> result) {
        main.post(() -> {
            if (closed || !valid.getAsBoolean()) { result.accept("cancelled"); return; }
            Object request = null;
            Pending pending = null;
            try {
                Object state = contextFactory.invoke(null, work);
                Object origin = state == null ? null : resolveAweme.invoke(null, state);
                if (!aid.equals(HostTargets.invoke(origin, "getAid"))) throw new IllegalStateException("Origin work changed");
                Object model = modelConstructor.newInstance();
                modelText.set(model, text);
                modelContext.set(model, state);
                HostTargets.field(model.getClass(), "n").set(model, "douai_auto");
                ownedModels.put(model, text);
                request = requestConstructor.newInstance();
                pending = new Pending(aid, result);
                requests.put(request, pending);
                boolean accepted = Boolean.TRUE.equals(submit.invoke(request, model));
                if (!accepted) { received(request, "failed"); return; }
                acceptedRequest.run();
                safeLog("Automatic comment native request accepted for captured work");
                Object owner = request;
                Pending token = pending;
                main.postDelayed(() -> {
                    if (requests.get(owner) != token || !token.timedOut.compareAndSet(false, true)) return;
                    token.result.accept("submitted");
                }, 30_000);
                main.postDelayed(() -> requests.remove(owner, token), 120_000);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                if (request != null && pending != null) requests.remove(request);
                safeLog("Automatic comment native request failed: " + failure.getClass().getSimpleName());
                result.accept("failed");
            }
        });
    }

    private void received(Object request, String status) {
        Pending pending = requests.remove(request);
        if (pending == null || closed) return;
        safeLog("Automatic comment native callback: " + status);
        pending.result.accept(status);
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
        final Consumer<String> result;
        final AtomicBoolean timedOut = new AtomicBoolean();
        Pending(String aid, Consumer<String> result) { this.aid = aid; this.result = result; }
    }
}

package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Converts the text on the user's publish action into a native audio attachment. */
public final class NativeCommentSpeechHook implements AutoCloseable {
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Field attachmentField;
    private final Field textField;
    private final Field audioField;
    private final Method modelCopy;
    private final Method attachmentCopy;
    private final Constructor<?> audioConstructor;
    private final Consumer<String> log;
    private final ThreadLocal<Boolean> reentry = new ThreadLocal<>();
    private volatile Context context;
    private volatile LocalOptions options;
    private volatile boolean closed;
    private SpeechSynthesis pending;
    private long generation;
    private volatile java.util.function.Predicate<Object> automaticPublish = ignored -> false;

    private NativeCommentSpeechHook(Class<?> model, Class<?> attachment, Class<?> audio,
                                      Method modelCopy, Method attachmentCopy, LocalOptions options,
                                      Consumer<String> log) throws Exception {
        this.attachmentField = HostTargets.field(model, "A");
        this.textField = HostTargets.field(model, "a");
        this.audioField = HostTargets.field(attachment, "audio");
        this.modelCopy = modelCopy;
        this.attachmentCopy = attachmentCopy;
        this.audioConstructor = audio.getConstructor(java.io.File.class, List.class, Long.class);
        this.options = options;
        this.log = log;
    }

    public static NativeCommentSpeechHook install(XposedInterface framework, HostScanReport report,
                                                    ClassLoader loader, LocalOptions options,
                                                    Consumer<String> log) throws Exception {
        NativeCommentSpeechHook hook = new NativeCommentSpeechHook(
                HostTargets.type(report, "comment_publish_model", loader),
                HostTargets.type(report, "comment_attachment_model", loader),
                HostTargets.type(report, "comment_audio_model", loader),
                HostTargets.method(report, "comment_publish_model_copy", loader),
                HostTargets.method(report, "comment_attachment_copy", loader),
                options, log == null ? ignored -> { } : log);
        try {
            for (String id : new String[]{"comment_publish_submit", "comment_publish_submit_checked"}) {
                Method method = HostTargets.method(report, id, loader);
                XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.CommentSpeech." + id)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> hook.intercept(chain, method));
                if (handle == null) throw new IllegalStateException("Empty comment speech handle");
                hook.handles.add(handle);
            }
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    public void setContext(Context context) { this.context = context == null ? null : context.getApplicationContext(); }

    public void setAutomaticPublishGuard(java.util.function.Predicate<Object> guard) {
        automaticPublish = guard == null ? ignored -> false : guard;
    }

    public synchronized void setOptions(LocalOptions options) {
        boolean changed = !this.options.samePrefix(options, "tts_");
        this.options = options;
        if (!options.enabled("comment_tts") || changed) {
            generation++;
            if (pending != null) { pending.close(); pending = null; }
        }
    }

    private Object intercept(XposedInterface.Chain chain, Method submit) throws Throwable {
        LocalOptions current = options;
        if (closed || !current.enabled("comment_tts") || Boolean.TRUE.equals(reentry.get()) || context == null) {
            return chain.proceed();
        }
        Object original = chain.getArg(0);
        if (automaticPublish.test(original)) return chain.proceed();
        Object attachment = attachmentField.get(original);
        Object rawText = textField.get(original);
        String text = rawText instanceof String ? (String) rawText : "";
        if (!text.startsWith("#") || text.substring(1).trim().isEmpty()) return chain.proceed();
        if (!isTextOnly(attachment, text)) return chain.proceed();
        String speechText = text.substring(1).trim();
        Object model = modelCopy.invoke(original);
        Object copied = attachmentCopy.invoke(attachment);
        attachmentField.set(model, copied);
        Object receiver = chain.getThisObject();
        synchronized (this) {
            if (pending != null) {
                toast("语音正在生成，请稍候");
                return null;
            }
            toast("正在生成评论语音");
            long epoch = generation;
            pending = SpeechSynthesis.synthesize(context, speechText, current, (result, failure) -> {
                synchronized (NativeCommentSpeechHook.this) {
                    if (closed || epoch != generation || !options.enabled("comment_tts")) {
                        if (result != null) result.file.delete();
                        return;
                    }
                    pending = null;
                }
                if (result == null) { toast(failure); safeLog("Comment speech failed: " + failure); return; }
                try {
                    Object audio = audioConstructor.newInstance(result.file, Collections.emptyList(), result.durationMillis);
                    audioField.set(copied, audio);
                    HostTargets.field(copied.getClass(), "text").set(copied, new SpannableStringBuilder());
                    textField.set(model, "");
                    HostTargets.field(model.getClass(), "d").set(model, Collections.emptyList());
                    reentry.set(Boolean.TRUE);
                    submit.invoke(receiver, model);
                    safeLog("Submitted synthesized audio through the native comment publish workflow");
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getCause() : error;
                    safeLog("Native comment speech publish failed: " + cause);
                    toast("评论语音提交失败，请重试");
                } finally {
                    reentry.remove();
                    new Handler(Looper.getMainLooper()).postDelayed(result.file::delete, 600_000);
                }
            });
        }
        return null;
    }

    static boolean isTextOnly(Object attachment, String text) {
        if (attachment == null || text == null || text.trim().isEmpty()) return false;
        for (String field : new String[]{"audio", "emoji", "video", "cameraStruct", "groupChat"}) {
            if (HostTargets.read(attachment, field) != null) return false;
        }
        Object images = HostTargets.read(attachment, "imageList");
        return !(images instanceof List<?>) || ((List<?>) images).isEmpty();
    }

    @Override public synchronized void close() {
        closed = true;
        generation++;
        options = new LocalOptions(Collections.emptyMap());
        if (pending != null) { pending.close(); pending = null; }
        for (int index = handles.size() - 1; index >= 0; index--) {
            try { handles.get(index).unhook(); }
            catch (Throwable failure) { safeLog("Comment speech unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
    }

    private void toast(String message) {
        Context target = context;
        if (target == null || message == null) return;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(target, message, Toast.LENGTH_SHORT).show());
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }
}

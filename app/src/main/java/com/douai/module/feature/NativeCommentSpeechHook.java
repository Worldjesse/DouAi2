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
    private HashVoiceGesture pendingGesture;
    private final List<HashVoiceGesture> longPresses = new ArrayList<>();
    private long generation;
    private volatile java.util.function.Predicate<Object> automaticPublish = ignored -> false;

    NativeCommentSpeechHook(Class<?> model, Class<?> attachment, Class<?> audio,
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
        if (changed || pending != null && !speechEnabled(options, pendingGesture != null)) {
            generation++;
            if (pending != null) { pending.close(); pending = null; }
            if (pendingGesture != null) pendingGesture.cancel();
            pendingGesture = null;
        }
        if (!options.enabled(HashLongPressVoiceHook.OPTION)) {
            for (HashVoiceGesture gesture : longPresses) gesture.cancel();
        }
    }

    synchronized boolean armLongPress(HashVoiceGesture gesture) {
        if (closed || !options.enabled(HashLongPressVoiceHook.OPTION) || !gesture.canSend() || pending != null) return false;
        for (int index = 0; index < longPresses.size(); index++) {
            HashVoiceGesture previous = longPresses.get(index);
            if (!previous.matches(gesture.draft, gesture.group, gesture.service, gesture.reply)) continue;
            // A new deliberate gesture may retry a known failure. Any late old confirmation
            // can only claim this new voice operation once; it can never fall back to text.
            if (!previous.canRetry()) return false;
            longPresses.set(index, gesture);
            return true;
        }
        // Do not evict a live/tombstoned gesture: a delayed confirmation still needs its text guard.
        if (longPresses.size() >= 128) return false;
        longPresses.add(gesture);
        return true;
    }

    private synchronized HashVoiceGesture longPress(Object model, String text) throws ReflectiveOperationException {
        if (longPresses.isEmpty() || !text.startsWith("#")) return null;
        String[] key = HashLongPressVoiceHook.commentKey(HostTargets.field(model.getClass(), "y").get(model),
                HostTargets.field(model.getClass(), "p").get(model));
        for (HashVoiceGesture gesture : longPresses) {
            if (gesture.matches(text, key[0], key[1], key[2])) return gesture;
        }
        return null;
    }

    static boolean speechEnabled(LocalOptions options, boolean longPress) {
        return options.enabled(longPress ? HashLongPressVoiceHook.OPTION : "comment_tts");
    }

    Object intercept(XposedInterface.Chain chain, Method submit) throws Throwable {
        LocalOptions current = options;
        if (closed || Boolean.TRUE.equals(reentry.get())) {
            return chain.proceed();
        }
        synchronized (this) {
            if (longPresses.isEmpty() && (!current.enabled("comment_tts") || context == null)) return chain.proceed();
        }
        Object original = chain.getArg(0);
        if (original == null) return chain.proceed();
        if (automaticPublish.test(original)) return chain.proceed();
        Object rawText = textField.get(original);
        String text = rawText instanceof String ? (String) rawText : "";
        HashVoiceGesture gesture;
        try { gesture = longPress(original, text); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            synchronized (this) {
                for (HashVoiceGesture candidate : longPresses) {
                    if (!candidate.draft.equals(text)) continue;
                    candidate.cancel();
                    toast("无法核实评论目标，语音操作已取消，草稿已保留");
                    return null;
                }
            }
            throw failure;
        }
        if (gesture != null) {
            gesture.restoreAfterNativeClick();
            if (!speechEnabled(current, true) || !gesture.claim()) {
                toast("此语音操作已处理或输入目标已变化，草稿已保留");
                return null;
            }
        } else if (!current.enabled("comment_tts") || context == null) return chain.proceed();
        if (!text.startsWith("#") || text.substring(1).trim().isEmpty()) return gesture == null ? chain.proceed() : null;
        Object attachment = attachmentField.get(original);
        if (!isTextOnly(attachment, text)) {
            if (gesture == null) return chain.proceed();
            gesture.cancel();
            toast("请先清除图片、音频等附件，再长按发送语音；草稿已保留");
            return null;
        }
        if (context == null) {
            if (gesture != null) gesture.cancel();
            toast("评论语音入口未就绪，草稿已保留"); return null;
        }
        String speechText = text.substring(1).trim();
        Object model;
        Object copied;
        try {
            model = modelCopy.invoke(original);
            copied = attachmentCopy.invoke(attachment);
            attachmentField.set(model, copied);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (gesture == null) throw failure;
            gesture.cancel();
            safeLog("Long-press comment model rejected; exception=" + failure.getClass().getSimpleName());
            toast("评论语音入口异常，草稿已保留");
            return null;
        }
        Object receiver = chain.getThisObject();
        synchronized (this) {
            if (pending != null) {
                if (gesture != null) gesture.cancel();
                toast("语音正在生成，请稍候");
                return null;
            }
            toast("正在生成评论语音");
            long epoch = generation;
            pendingGesture = gesture;
            pending = SpeechSynthesis.synthesize(context, speechText, current, (result, failure) -> {
                synchronized (NativeCommentSpeechHook.this) {
                    if (closed || epoch != generation || !speechEnabled(options, gesture != null)) {
                        if (result != null) result.file.delete();
                        return;
                    }
                    pending = null;
                    pendingGesture = null;
                }
                if (gesture != null && !gesture.canSend()) {
                    if (result != null) result.file.delete();
                    gesture.cancel();
                    toast("评论或草稿已变化，已取消语音发送");
                    return;
                }
                if (result == null) {
                    if (gesture != null) gesture.cancel();
                    toast(failure); safeLog("Comment speech failed: " + failure); return;
                }
                try {
                    Object audio = audioConstructor.newInstance(result.file, CommentAudioWaveform.nativeFallback(), result.durationMillis);
                    audioField.set(copied, audio);
                    HostTargets.field(copied.getClass(), "text").set(copied, new SpannableStringBuilder());
                    textField.set(model, "");
                    HostTargets.field(model.getClass(), "d").set(model, Collections.emptyList());
                    reentry.set(Boolean.TRUE);
                    submit.invoke(receiver, model);
                    com.douai.module.analytics.LocalAnalytics.feature("comment_tts", "评论语音提交");
                    safeLog("Submitted synthesized audio through the native comment publish workflow");
                } catch (ReflectiveOperationException | RuntimeException error) {
                    if (gesture != null) gesture.cancel();
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
        for (HashVoiceGesture gesture : longPresses) gesture.cancel();
        longPresses.clear();
        pendingGesture = null;
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

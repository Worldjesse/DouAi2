package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Updates a user-initiated native comment request before Retrofit encodes its fields. */
public final class CommentEnhancementsHook implements AutoCloseable {
    private XposedInterface.HookHandle handle;
    private final Field urlField;
    private final Field apiMethodField;
    private final Method accountService;
    private final Method currentUser;
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private int logged;

    private CommentEnhancementsHook(Field urlField, Field apiMethodField,
                                      Method accountService, Method currentUser,
                                      LocalOptions options, Consumer<String> log) {
        this.urlField = urlField;
        this.apiMethodField = apiMethodField;
        this.accountService = accountService;
        this.currentUser = currentUser;
        this.options = options;
        this.log = log;
    }

    public static CommentEnhancementsHook install(XposedInterface framework, HostScanReport report,
                                                    ClassLoader loader, LocalOptions options,
                                                    Consumer<String> log) throws Exception {
        Method request = HostTargets.method(report, "comment_request_factory", loader);
        Class<?> owner = request.getDeclaringClass();
        Method account = null;
        Method user = null;
        try {
            account = HostTargets.method(report, "media_account_service", loader);
            user = HostTargets.method(report, "media_current_user", loader);
        } catch (Exception failure) {
            if (log != null) log.accept("Comment color account unavailable: " + failure.getMessage());
        }
        CommentEnhancementsHook hook = new CommentEnhancementsHook(
                HostTargets.field(owner, "relativeUrl"), HostTargets.field(owner, "method"),
                account, user, options, log == null ? ignored -> { } : log);
        try {
            hook.handle = framework.hook(request).setId("DouAi.Comment.RequestFields")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (hook.handle == null) throw new IllegalStateException("Empty native comment request handle");
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    public void setOptions(LocalOptions options) { this.options = options; }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        LocalOptions current = options;
        Object[] arguments = chain.getArgs().toArray();
        Object[] replacement;
        try {
            replacement = prepare(chain.getThisObject(), arguments, current);
        } catch (ReflectiveOperationException | RuntimeException | JSONException failure) {
            safeLog("Comment request fields unchanged: " + failure.getMessage());
            replacement = null;
        }
        if (replacement == null) return chain.proceed();
        arguments[1] = replacement;
        Object result = chain.proceed(arguments);
        if (logged++ < 5) safeLog("Applied configured fields to a native comment publish request");
        return result;
    }

    private Object[] prepare(Object receiver, Object[] arguments, LocalOptions current)
            throws ReflectiveOperationException, JSONException {
            Object relativeUrl = urlField.get(receiver);
            if (!("/aweme/v1/comment/publish/".equals(relativeUrl)
                    || "/aweme/v1/comment/publish_evaluate/".equals(relativeUrl))
                    || arguments.length != 2 || !(arguments[1] instanceof Object[])) return null;
            Method api = (Method) apiMethodField.get(receiver);
            Object[] original = (Object[]) arguments[1];
            int text = fieldIndex(api, "text");
            int extras = fieldIndex(api, "text_extra");
            if (text < 0 || extras < 0 || text >= original.length || extras >= original.length
                    || !(original[text] instanceof String)) return null;
            String uid = "";
            String secUid = "";
            if (current.enabled("colored_comment") && accountService != null && currentUser != null) {
                Object account = accountService.invoke(null);
                Object user = currentUser.invoke(account);
                Object rawUid = DownloadActionHook.jsonValue(user, "uid");
                Object rawSecUid = DownloadActionHook.jsonValue(user, "sec_uid");
                uid = rawUid == null ? "" : rawUid.toString();
                secUid = rawSecUid == null ? "" : rawSecUid.toString();
            }
            Object[] replacement = transform(original, text, extras, current, uid, secUid);
            int audioId = fieldIndex(api, "audio_vid");
            int audioDuration = fieldIndex(api, "audio_duration");
            if (audioId >= 0 && audioDuration >= 0 && audioId < original.length
                    && audioDuration < original.length && original[audioId] instanceof String) {
                Long duration = CommentVoiceHook.durationFor((String) original[audioId]);
                if (duration != null) {
                    if (replacement == null) replacement = original.clone();
                    replacement[audioDuration] = duration;
                }
            }
            return replacement;
    }

    static Object[] transform(Object[] source, int textIndex, int extraIndex, LocalOptions options,
                                String uid, String secUid) throws JSONException {
        String text = (String) source[textIndex];
        if (text.trim().isEmpty()) return null;
        String extras = source[extraIndex] instanceof String ? (String) source[extraIndex] : "[]";
        String suffix = options.text("comment_suffix", "");
        if (options.enabled("comment_append_suffix") && !suffix.isEmpty()
                && !text.endsWith("\n" + suffix)) text += "\n" + suffix;
        int type = options.integer("colored_comment_type", 0);
        boolean emptyExtras = extras.trim().isEmpty() || "[]".equals(extras.trim());
        if (options.enabled("colored_comment") && emptyExtras && !uid.isEmpty()
                && (type == 0 || type == 33)) {
            text = (type == 0 ? "@" : "____") + text + " ";
            JSONObject span = new JSONObject();
            span.put("start", 0);
            span.put("end", text.length());
            span.put("type", type);
            span.put("user_id", uid);
            span.put("sec_uid", secUid);
            span.put("at_user_type", "");
            span.put("user_follow_status", 1);
            span.put("isClickable", true);
            extras = new JSONArray().put(span).toString();
        }
        if (text.equals(source[textIndex]) && extras.equals(source[extraIndex])) return null;
        Object[] copy = source.clone();
        copy[textIndex] = text;
        copy[extraIndex] = extras;
        return copy;
    }

    static int fieldIndex(Method api, String name) throws ReflectiveOperationException {
        if (api == null) return -1;
        Annotation[][] parameters = api.getParameterAnnotations();
        int found = -1;
        for (int index = 0; index < parameters.length; index++) {
            for (Annotation annotation : parameters[index]) {
                String type = annotation.annotationType().getName();
                if (!("retrofit2.http.Field".equals(type)
                        || "com.bytedance.retrofit2.http.Field".equals(type))) continue;
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                if (!name.equals(value)) continue;
                if (found >= 0) throw new IllegalStateException("Duplicate comment field: " + name);
                found = index;
            }
        }
        return found;
    }

    @Override public void close() {
        options = new LocalOptions(Collections.emptyMap());
        if (handle != null) {
            try { handle.unhook(); }
            catch (Throwable failure) { safeLog("Comment request unhook failed: " + failure.getMessage()); }
            handle = null;
        }
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }
}

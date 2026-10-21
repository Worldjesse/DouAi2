package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Long presses only the native send controls whose ownership is verified at their bind sites. */
public final class HashLongPressVoiceHook implements AutoCloseable {
    public static final String OPTION = "hash_long_press_voice";
    private static final String PUBLISH_MODULE =
            "com.ss.android.ugc.aweme.comment.keyboard.refactor.inputbar.publishbtn.PublishBtnModule";
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, Binding> bindings = new WeakHashMap<>();
    private final List<EditorBinding> editors = new ArrayList<>();
    private final ThreadLocal<Boolean> changingClickable = new ThreadLocal<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DirectMessageSpeechHook messages;
    private final NativeCommentSpeechHook comments;
    private final Consumer<String> log;
    private final Method conversation;
    private final Field commentState;
    private volatile LocalOptions options;
    private volatile Context context;
    private volatile boolean closed;

    HashLongPressVoiceHook(LocalOptions options, DirectMessageSpeechHook messages,
                                  NativeCommentSpeechHook comments, Method conversation,
                                  Field commentState, Consumer<String> log) {
        this.options = options;
        this.messages = messages;
        this.comments = comments;
        this.conversation = conversation;
        this.commentState = commentState;
        this.log = log == null ? ignored -> { } : log;
    }

    public static HashLongPressVoiceHook install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, LocalOptions options,
                                                DirectMessageSpeechHook messages,
                                                NativeCommentSpeechHook comments,
                                                Consumer<String> log) throws Exception {
        Method drawerBind = HostTargets.method(report, "auto_message_editor_bind", loader);
        Field send = HostTargets.field(drawerBind.getDeclaringClass(), "s");
        Field editor = HostTargets.field(drawerBind.getDeclaringClass(), "m");
        Field session = HostTargets.field(drawerBind.getDeclaringClass(), "e");
        Method conversation = HostTargets.method(report, "dm_speech_conversation", loader);
        if (!View.class.isAssignableFrom(send.getType()) || !EditText.class.isAssignableFrom(editor.getType())
                || session.getType() != conversation.getDeclaringClass()) {
            throw new IllegalStateException("Native send drawer ownership changed");
        }
        Class<?> base = HostTargets.type(report, "portrait_comment_context_holder", loader);
        Field state = HostTargets.field(base, "e");
        Class<?> editorModule = HostTargets.type(report, "portrait_comment_editor_field", loader);
        Field commentEditor = HostTargets.field(editorModule, "n");
        Class<?> publish = Class.forName(PUBLISH_MODULE, false, loader);
        if (publish.getSuperclass() != base || !base.isAssignableFrom(editorModule)
                || !EditText.class.isAssignableFrom(commentEditor.getType())) {
            throw new IllegalStateException("Native comment send ownership changed");
        }
        Field styles = HostTargets.field(publish, "n");
        Field styleMap = HostTargets.field(styles.getType(), "LIZ");
        if (!Map.class.isAssignableFrom(styleMap.getType())) {
            throw new IllegalStateException("Native publish styles are not a map");
        }
        Method publishBind = publish.getDeclaredMethod("LJJIIZ", View.class);
        Method render = uniqueRender(publish);
        Method editorBind = editorModule.getDeclaredMethod("LJJIIZ", View.class);
        HashLongPressVoiceHook hook = new HashLongPressVoiceHook(options, messages, comments,
                conversation, state, log);
        try {
            hook.add(framework, drawerBind, "BindMessage", chain -> {
                Object result = chain.proceed();
                try {
                    Object owner = chain.getThisObject();
                    Object rawSend = send.get(owner);
                    Object rawEditor = editor.get(owner);
                    if (rawSend instanceof View && rawEditor instanceof EditText) {
                        hook.bindMessage(owner, (View) rawSend, (EditText) rawEditor);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) { hook.bindingFailed(failure); }
                return result;
            });
            hook.add(framework, editorBind, "BindCommentEditor", chain -> {
                Object result = chain.proceed();
                try {
                    Object owner = chain.getThisObject();
                    Object rawEditor = commentEditor.get(owner);
                    Object inputState = state.get(owner);
                    if (inputState != null && rawEditor instanceof EditText) {
                        hook.rememberEditor(inputState, (EditText) rawEditor);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) { hook.bindingFailed(failure); }
                return result;
            });
            for (Method method : new Method[]{publishBind, render}) {
                hook.add(framework, method, "BindCommentSend." + method.getName(), chain -> {
                    Object result = chain.proceed();
                    try {
                        Object owner = chain.getThisObject();
                        Object inputState = state.get(owner);
                        Object factory = styles.get(owner);
                        Object raw = factory == null ? null : styleMap.get(factory);
                        if (inputState != null && raw instanceof Map<?, ?>) {
                            for (Object style : ((Map<?, ?>) raw).values()) {
                                hook.bindCommentStyle(owner, inputState, style);
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException failure) { hook.bindingFailed(failure); }
                    return result;
                });
            }
            for (Method method : new Method[]{View.class.getDeclaredMethod("performLongClick"),
                    View.class.getDeclaredMethod("performLongClick", float.class, float.class)}) {
                hook.add(framework, method, "LongPress." + method.getParameterCount(), hook::longClick);
            }
            hook.add(framework, View.class.getDeclaredMethod("setLongClickable", boolean.class),
                    "PreserveLongClickable", chain -> {
                        Binding binding;
                        synchronized (hook.bindings) { binding = hook.bindings.get(chain.getThisObject()); }
                        if (binding == null || Boolean.TRUE.equals(hook.changingClickable.get())) return chain.proceed();
                        binding.nativeLongClickable = (Boolean) chain.getArg(0);
                        if (!hook.closed && hook.options.enabled(OPTION)) {
                            Object[] args = chain.getArgs().toArray();
                            args[0] = true;
                            return chain.proceed(args);
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

    static Method uniqueRender(Class<?> owner) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                    || method.getParameterCount() != 1 || method.getParameterTypes()[0].isPrimitive()
                    || method.getParameterTypes()[0] == View.class) continue;
            if (found != null) throw new IllegalStateException("Ambiguous native publish renderer");
            found = method;
        }
        if (found == null) throw new IllegalStateException("Missing native publish renderer");
        found.setAccessible(true);
        return found;
    }

    public void setContext(Context context) { this.context = context == null ? null : context.getApplicationContext(); }

    public void setOptions(LocalOptions options) {
        this.options = options;
        refreshClickable();
    }

    static boolean handles(LocalOptions options, String draft) {
        return options.enabled(OPTION) && draft != null && draft.startsWith("#");
    }

    Object longClick(XposedInterface.Chain chain) throws Throwable {
        View view = (View) chain.getThisObject();
        Binding binding;
        synchronized (bindings) { binding = bindings.get(view); }
        if (binding == null || closed || !options.enabled(OPTION)) return chain.proceed();
        EditText input = editor(binding);
        String draft = input == null ? null : input.getText().toString();
        if (!handles(options, draft)) return chain.proceed();
        // From this point the gesture is consumed even when validation or synthesis fails.
        try { start(view, binding, input, draft); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            bindingFailed(failure);
            toast("语音入口不可用，草稿已保留");
        }
        return true;
    }

    void bindMessage(Object owner, View send, EditText editor) {
        bind(send, new Binding(owner, null, editor, true));
    }

    private void bindCommentStyle(Object owner, Object state, Object style) throws ReflectiveOperationException {
        if (style == null) return;
        Field styleState = HostTargets.field(style.getClass(), "LIZ");
        Field label = HostTargets.field(style.getClass(), "LIZIZ");
        Field container = HostTargets.field(style.getClass(), "LIZJ");
        Field viewModel = HostTargets.field(style.getClass(), "LIZLLL");
        if (styleState.getType() != commentState.getType() || styleState.get(style) != state
                || label.getType() != TextView.class || container.getType() != ViewGroup.class
                || !"com.ss.android.ugc.aweme.arch.logic.StateViewModel".equals(viewModel.getType().getName())) {
            throw new IllegalStateException("Native comment publish style ownership changed");
        }
        for (Object raw : new Object[]{container.get(style), label.get(style)}) {
            if (raw instanceof View && ((View) raw).hasOnClickListeners()) {
                bind((View) raw, new Binding(owner, state, null, false));
            }
        }
    }

    private void bind(View view, Binding binding) {
        if (closed) return;
        synchronized (bindings) {
            Binding previous = bindings.get(view);
            if (previous != null && previous.owner.get() == binding.owner.get()
                    && previous.state.get() == binding.state.get() && previous.editor.get() == binding.editor.get()) return;
            binding.nativeLongClickable = previous == null ? view.isLongClickable() : previous.nativeLongClickable;
            bindings.put(view, binding);
        }
        applyClickable(view, binding);
    }

    private void rememberEditor(Object state, EditText editor) {
        synchronized (editors) {
            editors.removeIf(item -> item.state.get() == null || item.editor.get() == null || item.state.get() == state);
            editors.add(new EditorBinding(state, editor));
        }
    }

    private EditText editor(Binding binding) {
        if (binding.message) return binding.editor.get();
        Object state = binding.state.get();
        synchronized (editors) {
            for (EditorBinding item : editors) if (state != null && item.state.get() == state) return item.editor.get();
        }
        return null;
    }

    private void start(View view, Binding binding, EditText editor, String draft) throws ReflectiveOperationException {
        if (editor == null || !view.isShown() || !view.hasWindowFocus() || !editor.isShown()) {
            toast("当前输入框未就绪，草稿已保留");
            return;
        }
        if (draft.substring(1).trim().isEmpty()) { toast("语音内容为空，草稿已保留"); return; }
        String[] key = key(binding);
        WeakReference<View> target = new WeakReference<>(view);
        WeakReference<EditText> input = new WeakReference<>(editor);
        HashVoiceGesture gesture = new HashVoiceGesture(draft, key[0], key[1], key[2],
                () -> isCurrent(binding, target, input, draft, key),
                () -> target.get() != null && target.get().hasWindowFocus(),
                () -> restore(binding, target, input, draft, key));
        if (binding.message) {
            if (messages == null) { toast("聊天语音入口未就绪，草稿已保留"); return; }
            messages.startLongPress(binding.owner.get(), gesture);
        } else {
            if (comments == null || !comments.armLongPress(gesture)) {
                toast("评论语音正在处理或此草稿已接管，请修改草稿后重试");
                return;
            }
            // Native code builds the real attachment/model and may confirm asynchronously.
            try {
                if (!view.performClick()) toast("原生发送入口未就绪，草稿已保留");
            } finally { gesture.restoreAfterNativeClick(); }
        }
    }

    private boolean isCurrent(Binding binding, WeakReference<View> target, WeakReference<EditText> input,
                              String draft, String[] key) {
        View view = target.get();
        EditText editor = input.get();
        if (closed || !options.enabled(OPTION) || view == null || editor == null || !view.isShown()
                || !editor.isShown() || editor(binding) != editor || !draft.contentEquals(editor.getText())) return false;
        synchronized (bindings) { if (bindings.get(view) != binding) return false; }
        try { return java.util.Arrays.equals(key, key(binding)); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    private void restore(Binding binding, WeakReference<View> target, WeakReference<EditText> input,
                         String draft, String[] key) {
        Runnable restore = () -> {
            View view = target.get();
            EditText editor = input.get();
            if (closed || view == null || editor == null || !view.isShown() || !editor.isShown()
                    || editor(binding) != editor || editor.length() != 0) return;
            try {
                if (!java.util.Arrays.equals(key, key(binding))) return;
                editor.setText(draft);
                editor.setSelection(editor.length());
            } catch (ReflectiveOperationException | RuntimeException failure) { bindingFailed(failure); }
        };
        restore.run();
        main.post(restore);
    }

    private String[] key(Binding binding) throws ReflectiveOperationException {
        if (binding.message) {
            Object owner = binding.owner.get();
            Object session = owner == null ? null : HostTargets.field(owner.getClass(), "e").get(owner);
            Object id = session == null ? null : conversation.invoke(session);
            if (!(id instanceof String) || ((String) id).isEmpty()) throw new IllegalStateException("Missing conversation");
            return new String[]{(String) id, "", ""};
        }
        Object state = binding.state.get();
        if (state == null || binding.owner.get() == null) throw new IllegalStateException("Missing comment state");
        Object context = HostTargets.field(state.getClass(), "e").get(state);
        Object outer = HostTargets.field(state.getClass(), "E").get(state);
        Object pipeline = outer == null ? null : HostTargets.field(outer.getClass(), "LIZIZ").get(outer);
        if (pipeline == null) throw new IllegalStateException("Missing active comment pipeline");
        Object reply = pipeline.getClass().getMethod("LJIIJJI").invoke(pipeline);
        return commentKey(context, reply);
    }

    static String[] commentKey(Object context, Object reply) throws ReflectiveOperationException {
        if (context == null) throw new IllegalStateException("Missing comment context");
        Object group = HostTargets.field(context.getClass(), "groupId").get(context);
        Object service = HostTargets.field(context.getClass(), "serviceId").get(context);
        Object cid = reply == null ? "" : reply.getClass().getMethod("getCid").invoke(reply);
        if (!(group instanceof String) || ((String) group).isEmpty() || !(service instanceof String)
                || !(cid instanceof String) || reply != null && ((String) cid).isEmpty()) {
            throw new IllegalStateException("Missing comment target identity");
        }
        return new String[]{(String) group, (String) service, (String) cid};
    }

    private void refreshClickable() {
        List<Map.Entry<View, Binding>> snapshot;
        synchronized (bindings) { snapshot = new ArrayList<>(bindings.entrySet()); }
        for (Map.Entry<View, Binding> item : snapshot) {
            View view = item.getKey();
            if (view != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) applyClickable(view, item.getValue());
                else view.post(() -> applyClickable(view, item.getValue()));
            }
        }
    }

    private void applyClickable(View view, Binding binding) {
        changingClickable.set(Boolean.TRUE);
        try { view.setLongClickable(!closed && options.enabled(OPTION) || binding.nativeLongClickable); }
        finally { changingClickable.remove(); }
    }

    private void add(XposedInterface framework, Method method, String id, XposedInterface.Hooker interceptor) {
        XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.HashLongPress." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(interceptor);
        if (handle == null) throw new IllegalStateException("Empty long-press voice hook");
        handles.add(handle);
    }

    private void bindingFailed(Throwable failure) {
        try { log.accept("Long-press voice entry rejected; exception=" + failure.getClass().getSimpleName()); }
        catch (Throwable ignored) { }
    }

    private void toast(String message) {
        Context current = context;
        if (current != null) main.post(() -> Toast.makeText(current, message, Toast.LENGTH_SHORT).show());
    }

    @Override public void close() {
        closed = true;
        options = new LocalOptions(Collections.emptyMap());
        refreshClickable();
        for (int index = handles.size() - 1; index >= 0; index--) {
            try { handles.get(index).unhook(); } catch (Throwable failure) { bindingFailed(failure); }
        }
        handles.clear();
        synchronized (editors) { editors.clear(); }
        synchronized (bindings) { bindings.clear(); }
    }

    private static final class Binding {
        final WeakReference<Object> owner;
        final WeakReference<Object> state;
        final WeakReference<EditText> editor;
        final boolean message;
        volatile boolean nativeLongClickable;
        Binding(Object owner, Object state, EditText editor, boolean message) {
            this.owner = new WeakReference<>(owner);
            this.state = new WeakReference<>(state);
            this.editor = new WeakReference<>(editor);
            this.message = message;
        }
    }

    private static final class EditorBinding {
        final WeakReference<Object> state;
        final WeakReference<EditText> editor;
        EditorBinding(Object state, EditText editor) {
            this.state = new WeakReference<>(state);
            this.editor = new WeakReference<>(editor);
        }
    }
}

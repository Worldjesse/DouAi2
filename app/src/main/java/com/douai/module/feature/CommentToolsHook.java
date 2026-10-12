package com.douai.module.feature;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Adds copy and image tools to the already-open native comment long-press panel. */
public final class CommentToolsHook implements AutoCloseable {
    private static final String ITEM_VIEW_CLASS = "com.ss.android.ugc.aweme.comment.ui.longpress.CommentLongPressItemView";
    private static final String NATIVE_IMAGE_SAVE_ACTION_CLASS =
            "com.ss.android.ugc.aweme.comment.manager.longclickaction.actions.SaveImageActionItem";
    private final ClassLoader loader;
    private final Class<?> itemModelClass;
    private final Class<?> commentClass;
    private final Class<?> nativeImageSaveActionClass;
    private final Field itemParamsField;
    private final Field paramsCommentField;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, RowContext> contextsByRow = new WeakHashMap<>();
    private final Map<View, View.OnLongClickListener> listenersByRow = new WeakHashMap<>();
    private final Consumer<String> log;
    private volatile ExtendedSettings settings;

    private CommentToolsHook(ClassLoader loader, Class<?> itemModelClass, Class<?> commentClass,
                             Class<?> nativeImageSaveActionClass,
                             Field itemParamsField, Field paramsCommentField,
                             ExtendedSettings settings, Consumer<String> log) {
        this.loader = loader;
        this.itemModelClass = itemModelClass;
        this.commentClass = commentClass;
        this.nativeImageSaveActionClass = nativeImageSaveActionClass;
        this.itemParamsField = itemParamsField;
        this.paramsCommentField = paramsCommentField;
        this.settings = settings;
        this.log = log;
    }

    public static CommentToolsHook install(XposedInterface framework, HostScanReport report,
                                           ClassLoader loader, ExtendedSettings settings,
                                           Consumer<String> log) throws Exception {
        Method bind = resolveMethod(report, "comment_longpress_bind", loader);
        Class<?> modelClass = resolveClass(report, "comment_longpress_item_model", loader);
        Class<?> paramsClass = resolveClass(report, "comment_panel_params", loader);
        Class<?> commentClass = resolveClass(report, "comment_model", loader);
        Class<?> nativeImageSaveActionClass = optionalNativeImageSaveAction(loader, log);
        if (bind.getParameterCount() != 1 || bind.getReturnType() != void.class
                || !"com.ss.android.ugc.aweme.kiwi.model.QModel".equals(
                bind.getParameterTypes()[0].getName())) {
            throw new IllegalStateException("Comment long-press bind signature changed");
        }
        Field itemParams = modelClass.getField("k");
        Field paramsComment = paramsClass.getField("LJIIIIZZ");
        Field imageList = fieldForJsonName(commentClass, "image_list");
        Field commentText = fieldForJsonName(commentClass, "text");
        if (itemParams.getType() != paramsClass || paramsComment.getType() != commentClass
                || imageList == null || !List.class.isAssignableFrom(imageList.getType())
                || commentText == null || commentText.getType() != String.class) {
            throw new IllegalStateException("Comment data model changed");
        }

        itemParams.setAccessible(true);
        paramsComment.setAccessible(true);
        CommentToolsHook hook = new CommentToolsHook(loader, modelClass, commentClass,
                nativeImageSaveActionClass,
                itemParams, paramsComment, settings, log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(bind)
                    .setId("DouAi.Comment.Tools")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::afterBind);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        this.settings = settings;
    }

    private Object afterBind(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        ExtendedSettings current = settings;
        if (!current.copyCommentText && !current.commentMediaTools && !current.copySourceData) return result;
        Object presenter = chain.getThisObject();
        Object qModel = chain.getArg(0);
        try {
            Object itemModel = findModelForPresenter(qModel, presenter);
            if (itemModel == null || !itemModelClass.isInstance(itemModel)) return result;
            Object params = itemParamsField.get(itemModel);
            Object comment = params == null ? null : paramsCommentField.get(params);
            if (comment == null || !commentClass.isInstance(comment)) return result;
            Object nativeImageSaveAction = findNativeImageSaveAction(qModel, itemModel, comment);
            View row = findBoundRow(presenter, itemModel);
            if (row == null) return result;
            synchronized (contextsByRow) {
                View.OnLongClickListener listener = listenersByRow.get(row);
                if (listener == null) {
                    if (row.isLongClickable()) return result;
                    listener = view -> {
                        RowContext rowContext;
                        synchronized (contextsByRow) {
                            rowContext = contextsByRow.get(view);
                        }
                        Object currentComment = rowContext == null ? null : rowContext.comment.get();
                        Object saveAction = rowContext == null ? null : rowContext.nativeImageSaveAction.get();
                        return currentComment != null
                                && showMenu(view.getContext(), currentComment, saveAction);
                    };
                    listenersByRow.put(row, listener);
                    row.setOnLongClickListener(listener);
                }
                contextsByRow.put(row, new RowContext(comment, nativeImageSaveAction));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not attach comment tools: " + failure.getMessage());
        }
        return result;
    }

    private boolean showMenu(Context context, Object comment, Object nativeImageSaveAction) {
        ExtendedSettings current = settings;
        ArrayList<Action> actions = new ArrayList<>();
        String text = readString(comment, "text");
        if (current.copyCommentText && text != null) actions.add(new Action("复制评论内容", text, null));
        if (current.copySourceData) actions.add(new Action("复制评论源数据", null, comment));
        if (current.commentMediaTools && nativeImageSaveAction != null && hasCommentImages(comment)) {
            actions.add(new Action("使用抖音保存评论图片", nativeImageSaveAction, true));
        }
        if (actions.isEmpty()) return false;

        String[] labels = new String[actions.size()];
        for (int index = 0; index < actions.size(); index++) labels[index] = actions.get(index).title;
        try {
            new AlertDialog.Builder(context)
                    .setTitle("评论工具")
                    .setItems(labels, (dialog, which) -> runAction(context, actions.get(which)))
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        } catch (Throwable failure) {
            safeLog("Could not show comment tools: " + failure.getMessage());
            return false;
        }
    }

    private void runAction(Context context, Action action) {
        if (action.nativeImageSaveAction != null) {
            invokeNativeImageSaveAction(context, action.nativeImageSaveAction);
        } else if (action.text != null) {
            copyText(context, action.text, "评论");
        } else if (action.source != null) {
            String sourceJson = toJson(action.source);
            if (sourceJson == null || sourceJson.isEmpty()) {
                showToast(context, "复制评论源数据失败");
            } else {
                copyText(context, sourceJson, "评论源数据");
            }
        }
    }

    private boolean hasCommentImages(Object comment) {
        try {
            Object imageList = readMappedField(comment, "image_list");
            return imageList instanceof List && !((List<?>) imageList).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not inspect comment images: " + failure.getMessage());
        }
        return false;
    }

    private Object findNativeImageSaveAction(Object qModel, Object currentItemModel, Object comment) {
        if (nativeImageSaveActionClass == null) return null;
        if (isEligibleNativeImageSaveAction(currentItemModel, comment)) return currentItemModel;
        if (qModel == null) return null;
        for (Class<?> type = qModel.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(qModel);
                    if (!(value instanceof Map)) continue;
                    for (Object candidate : ((Map<?, ?>) value).values()) {
                        if (isEligibleNativeImageSaveAction(candidate, comment)) return candidate;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // An inaccessible panel map must not disable copy tools.
                }
            }
        }
        return null;
    }

    private boolean isEligibleNativeImageSaveAction(Object candidate, Object comment) {
        if (candidate == null || !nativeImageSaveActionClass.isInstance(candidate)) return false;
        try {
            Object params = itemParamsField.get(candidate);
            if (params == null || paramsCommentField.get(params) != comment) return false;
            return Boolean.TRUE.equals(invokeNoArg(candidate, "LJJIFFI"));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private void invokeNativeImageSaveAction(Context context, Object nativeAction) {
        try {
            Method click = nativeAction.getClass().getMethod("LJJI", int.class);
            if (click.getReturnType() != void.class) {
                throw new NoSuchMethodException("Unexpected native image-save action signature");
            }
            click.invoke(nativeAction, 0);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            safeLog("Native comment image save failed: " + cause.getMessage());
            showToast(context, "抖音原生图片保存入口调用失败");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Native comment image save is unavailable: " + failure.getMessage());
            showToast(context, "抖音原生图片保存入口不可用");
        }
    }

    private static Object findModelForPresenter(Object qModel, Object presenter)
            throws ReflectiveOperationException {
        if (qModel == null) return null;
        for (Class<?> type = qModel.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(qModel);
                if (value instanceof Map) {
                    Object model = ((Map<?, ?>) value).get(presenter);
                    if (model != null) return model;
                }
            }
        }
        return null;
    }

    private static View findBoundRow(Object presenter, Object itemModel) throws ReflectiveOperationException {
        Object view = invokeNoArg(presenter, "getView");
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = group.getChildCount() - 1; index >= 0; index--) {
            View child = group.getChildAt(index);
            if (!ITEM_VIEW_CLASS.equals(child.getClass().getName())) continue;
            Object model = invokeNoArg(child, "getModel");
            if (model == itemModel) return child;
        }
        return null;
    }

    private static Field fieldForJsonName(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (name.equals(serializedName(field))) return field;
        }
        return null;
    }

    private static Object readMappedField(Object target, String jsonName)
            throws ReflectiveOperationException {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!jsonName.equals(serializedName(field))) continue;
                field.setAccessible(true);
                return field.get(target);
            }
        }
        return null;
    }

    private static String serializedName(Field field) {
        for (Annotation annotation : field.getAnnotations()) {
            if (!"com.google.gson.annotations.SerializedName".equals(annotation.annotationType().getName())) continue;
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                return value instanceof String ? (String) value : null;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String readString(Object target, String name) {
        try {
            Object value = readMappedField(target, name);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Continue through presenter/view model hierarchy.
            }
        }
        return null;
    }

    private String toJson(Object source) {
        try {
            Class<?> gsonClass = Class.forName("com.google.gson.Gson", true, loader);
            Object gson = gsonClass.getConstructor().newInstance();
            return (String) gsonClass.getMethod("toJson", Object.class).invoke(gson, source);
        } catch (Throwable failure) {
            safeLog("Could not serialize comment source: " + failure.getMessage());
            return null;
        }
    }

    private void copyText(Context context, String value, String label) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("系统剪贴板不可用");
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            showToast(context, "已复制" + label);
        } catch (Throwable failure) {
            safeLog("Could not copy comment: " + failure.getMessage());
            showToast(context, "复制失败");
        }
    }

    private static final class Action {
        final String title;
        final String text;
        final Object source;
        final Object nativeImageSaveAction;

        Action(String title, String text, Object payload) {
            this.title = title;
            this.text = text;
            this.source = text == null && payload != null && !(payload instanceof String) ? payload : null;
            this.nativeImageSaveAction = null;
        }

        Action(String title, Object nativeImageSaveAction, boolean nativeSave) {
            this.title = title;
            this.text = null;
            this.source = null;
            this.nativeImageSaveAction = nativeSave ? nativeImageSaveAction : null;
        }

    }

    private static final class RowContext {
        final WeakReference<Object> comment;
        final WeakReference<Object> nativeImageSaveAction;

        RowContext(Object comment, Object nativeImageSaveAction) {
            this.comment = new WeakReference<>(comment);
            this.nativeImageSaveAction = new WeakReference<>(nativeImageSaveAction);
        }
    }

    private void showToast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable failure) {
            safeLog("Could not display comment tool result: " + failure.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (contextsByRow) {
            for (Map.Entry<View, View.OnLongClickListener> entry : listenersByRow.entrySet()) {
                View row = entry.getKey();
                if (row != null) {
                    row.setOnLongClickListener(null);
                    row.setLongClickable(false);
                }
            }
            contextsByRow.clear();
            listenersByRow.clear();
        }
        unhookAll(null);
    }

    private void unhookAll(Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable unhookFailure) {
                if (failure == null) failure = unhookFailure;
                else failure.addSuppressed(unhookFailure);
            }
        }
        handles.clear();
        if (originalFailure == null && failure != null) safeLog("Comment tool unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not alter the native comment menu.
        }
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        Class<?> owner = Class.forName(className(candidate.getClassDescriptor()), false, loader);
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(candidate.getMethodName())
                    && methodDescriptor(method).equals(candidate.getMethodDescriptor())) return method;
        }
        throw new NoSuchMethodException("Scanned host method is unavailable: " + ruleId);
    }

    private static Class<?> resolveClass(HostScanReport report, String ruleId,
                                         ClassLoader loader) throws ClassNotFoundException {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        if (!candidate.getMethodName().isEmpty()) throw new IllegalStateException("Expected class rule: " + ruleId);
        return Class.forName(className(candidate.getClassDescriptor()), false, loader);
    }

    private static Class<?> optionalNativeImageSaveAction(ClassLoader loader, Consumer<String> log) {
        try {
            Class<?> actionClass = Class.forName(NATIVE_IMAGE_SAVE_ACTION_CLASS, false, loader);
            if (!Class.forName("com.ss.android.ugc.aweme.comment.ui.longpress.CommentLongPressItemModel",
                    false, loader).isAssignableFrom(actionClass)) {
                throw new IllegalStateException("Native image-save action type changed");
            }
            actionClass.getMethod("LJJI", int.class);
            actionClass.getMethod("LJJIFFI");
            return actionClass;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            if (log != null) {
                try {
                    log.accept("Native comment image save unavailable: " + failure.getMessage());
                } catch (Throwable ignored) {
                    // Diagnostics must not prevent copy tools from installing.
                }
            }
            return null;
        }
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }

    private static String className(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String methodDescriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}

package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.app.Activity;
import android.net.Uri;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Host AndroidX document selection, without replacing the host's result callbacks. */
final class HostImagePicker implements AutoCloseable {
    private static final Map<Activity, HostImagePicker> PENDING = new WeakHashMap<>();
    private final Activity activity;
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;
    private boolean resultReceived;
    private final DouAiLog.Operation trace = DouAiLog.begin("media_picker", "HostImagePicker");

    private HostImagePicker(Activity activity) { this.activity = activity; }

    static HostImagePicker launch(Activity activity, Consumer<Uri> selected) {
        return launch(activity, new String[]{"image/*"}, selected);
    }

    static HostImagePicker launch(Activity activity, String[] mimeTypes, Consumer<Uri> selected) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        if (mimeTypes == null || mimeTypes.length == 0) return null;
        HostImagePicker picker = new HostImagePicker(activity);
        try {
            ClassLoader loader = activity.getClass().getClassLoader();
            Class<?> contract = Class.forName("androidx.activity.result.contract.ActivityResultContract", false, loader);
            Class<?> callback = Class.forName("androidx.activity.result.ActivityResultCallback", false, loader);
            Class<?> registryType = Class.forName("androidx.activity.result.ActivityResultRegistry", false, loader);
            picker.launcherType = Class.forName("androidx.activity.result.ActivityResultLauncher", false, loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object open = Class.forName("androidx.activity.result.contract.ActivityResultContracts$OpenDocument", false, loader)
                    .getConstructor().newInstance();
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    return "DouAiImageSelection";
                }
                if (!"onActivityResult".equals(method.getName()) || picker.closed) return null;
                picker.resultReceived = true;
                picker.trace.stage("selection_callback", "callback_received=true");
                picker.close();
                Object value = arguments == null || arguments.length == 0 ? null : arguments[0];
                picker.trace.finish(value instanceof Uri ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.CANCELLED,
                        value instanceof Uri ? "selection_returned" : "user_cancelled", "uri_present=" + (value instanceof Uri) + ";file_content_verified=false");
                picker.trace.wrapConsumer(selected).accept(value instanceof Uri ? (Uri) value : null);
                return null;
            });
            picker.launcher = registryType.getMethod("register", String.class, contract, callback)
                    .invoke(registry, "douai_image_" + UUID.randomUUID(), open, result);
            PENDING.put(activity, picker);
            picker.launcherType.getMethod("launch", Object.class).invoke(picker.launcher, (Object) mimeTypes.clone());
            return picker;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            picker.resultReceived = true;
            picker.trace.finish(DouAiLog.Outcome.FAILED, "picker_unavailable", "selection_not_started");
            picker.close();
            return null;
        }
    }

    static boolean isPending(Activity activity) { return PENDING.containsKey(activity); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (!resultReceived) trace.finish(DouAiLog.Outcome.CANCELLED, "picker_closed", "no_selection_consumed");
        if (PENDING.get(activity) == this) PENDING.remove(activity);
        if (launcher != null) {
            try { launcherType.getMethod("unregister").invoke(launcher); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
}

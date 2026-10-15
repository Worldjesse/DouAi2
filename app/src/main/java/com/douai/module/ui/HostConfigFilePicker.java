package com.douai.module.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Host-owned SAF document selection using the general contract retained by the host. */
final class HostConfigFilePicker implements AutoCloseable {
    private static final Map<Activity, HostConfigFilePicker> PENDING = new WeakHashMap<>();
    private final Activity activity;
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;

    private HostConfigFilePicker(Activity activity) { this.activity = activity; }

    static HostConfigFilePicker launch(Activity activity, boolean export, String name,
                                       Consumer<Uri> selected) {
        return launch(activity, export, name, selected,
                activity == null ? null : activity.getClass().getClassLoader());
    }

    static HostConfigFilePicker launch(Activity activity, boolean export, String name,
                                       Consumer<Uri> selected, ClassLoader loader) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostConfigFilePicker previous = PENDING.get(activity);
        if (previous != null) previous.close();
        HostConfigFilePicker picker = new HostConfigFilePicker(activity);
        try {
            Class<?> contract = Class.forName("androidx.activity.result.contract.ActivityResultContract", false, loader);
            Class<?> callback = Class.forName("androidx.activity.result.ActivityResultCallback", false, loader);
            Class<?> registryType = Class.forName("androidx.activity.result.ActivityResultRegistry", false, loader);
            picker.launcherType = Class.forName("androidx.activity.result.ActivityResultLauncher", false, loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object start = Class.forName("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult", false, loader)
                    .getConstructor().newInstance();
            Class<?> resultType = Class.forName("androidx.activity.result.ActivityResult", false, loader);
            Method resultCode = resultType.getMethod("getResultCode");
            Method resultData = resultType.getMethod("getData");
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                    return "DouAiConfigSelection";
                }
                if (!"onActivityResult".equals(method.getName()) || picker.closed) return null;
                picker.close();
                Uri uri = null;
                try {
                    Object value = args == null || args.length == 0 ? null : args[0];
                    if (resultType.isInstance(value) && Integer.valueOf(Activity.RESULT_OK).equals(resultCode.invoke(value))) {
                        Object data = resultData.invoke(value);
                        if (data instanceof Intent) uri = ((Intent) data).getData();
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
                // No persistent grant is needed: the operation consumes this document immediately.
                selected.accept(uri != null && "content".equalsIgnoreCase(uri.getScheme()) ? uri : null);
                return null;
            });
            picker.launcher = registryType.getMethod("register", String.class, contract, callback)
                    .invoke(registry, "douai_config_" + UUID.randomUUID(), start, result);
            PENDING.put(activity, picker);
            Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(export ? "application/json" : "*/*")
                    .addFlags(export ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (export) intent.putExtra(Intent.EXTRA_TITLE, name);
            else intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            picker.launcherType.getMethod("launch", Object.class).invoke(picker.launcher, intent);
            return picker;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            picker.close();
            return null;
        }
    }

    static boolean isPending(Activity activity) { return PENDING.containsKey(activity); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (PENDING.get(activity) == this) PENDING.remove(activity);
        if (launcher != null) {
            try { launcherType.getMethod("unregister").invoke(launcher); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
}

package com.douai.module.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Requests a persistent host-owned grant to a writable document tree. */
final class HostDownloadDirectoryPicker implements AutoCloseable {
    static final String SETTINGS_KEY = com.douai.module.config.LocalOptions.DOWNLOAD_SAVE_TREE;
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final Map<Activity, HostDownloadDirectoryPicker> PENDING = new WeakHashMap<>();
    private final Activity activity;
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;

    private HostDownloadDirectoryPicker(Activity activity) { this.activity = activity; }

    static HostDownloadDirectoryPicker launch(Activity activity, Uri initial,
                                               Consumer<Uri> selected, Consumer<String> failed) {
        return launch(activity, initial, selected, failed,
                activity == null ? null : activity.getClass().getClassLoader());
    }

    static HostDownloadDirectoryPicker launch(Activity activity, Uri initial,
                                               Consumer<Uri> selected, Consumer<String> failed,
                                               ClassLoader loader) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostDownloadDirectoryPicker previous = PENDING.get(activity);
        if (previous != null) previous.close();
        HostDownloadDirectoryPicker picker = new HostDownloadDirectoryPicker(activity);
        try {
            Class<?> contract = Class.forName("androidx.activity.result.contract.ActivityResultContract", false, loader);
            Class<?> callback = Class.forName("androidx.activity.result.ActivityResultCallback", false, loader);
            Class<?> registryType = Class.forName("androidx.activity.result.ActivityResultRegistry", false, loader);
            picker.launcherType = Class.forName("androidx.activity.result.ActivityResultLauncher", false, loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            // The host strips OpenDocumentTree, but keeps its general Intent contract.
            Object open = Class.forName("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult", false, loader)
                    .getConstructor().newInstance();
            Class<?> resultType = Class.forName("androidx.activity.result.ActivityResult", false, loader);
            Method resultCode = resultType.getMethod("getResultCode");
            Method resultData = resultType.getMethod("getData");
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    return "DouAiDownloadDirectorySelection";
                }
                if (!"onActivityResult".equals(method.getName()) || picker.closed) return null;
                picker.close();
                Object value = arguments == null || arguments.length == 0 ? null : arguments[0];
                try {
                    if (!resultType.isInstance(value)
                            || !Integer.valueOf(Activity.RESULT_OK).equals(resultCode.invoke(value))) {
                        selected.accept(null);
                        return null;
                    }
                    Object data = resultData.invoke(value);
                    if (!(data instanceof Intent) || ((Intent) data).getData() == null) {
                        selected.accept(null);
                        return null;
                    }
                    Intent intent = (Intent) data;
                    Uri uri = intent.getData();
                    int granted = intent.getFlags();
                    if ((granted & GRANT_FLAGS) != GRANT_FLAGS
                            || (granted & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
                        throw new IllegalArgumentException("Persistent read/write permission is required");
                    }
                    requireWritableDirectory(activity, uri);
                    activity.getContentResolver().takePersistableUriPermission(uri, GRANT_FLAGS);
                    requirePersistedPermission(activity, uri);
                    selected.accept(uri);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    if (failed != null) failed.accept("无法使用所选文件夹，请选择可写且支持持久授权的文件夹");
                }
                return null;
            });
            picker.launcher = registryType.getMethod("register", String.class, contract, callback)
                    .invoke(registry, "douai_download_directory_" + UUID.randomUUID(), open, result);
            PENDING.put(activity, picker);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(GRANT_FLAGS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            if (initial != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
            picker.launcherType.getMethod("launch", Object.class).invoke(picker.launcher, intent);
            return picker;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            picker.close();
            return null;
        }
    }

    private static void requirePersistedPermission(Activity activity, Uri uri) {
        for (UriPermission permission : activity.getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri()) && permission.isReadPermission()
                    && permission.isWritePermission()) return;
        }
        throw new IllegalStateException("Persistent read/write permission was not retained");
    }

    private static void requireWritableDirectory(Activity activity, Uri uri) {
        if (!"content".equalsIgnoreCase(uri.getScheme()) || !DocumentsContract.isTreeUri(uri)) {
            throw new IllegalArgumentException("Document tree is required");
        }
        Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
        String[] columns = {DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS};
        try (Cursor cursor = activity.getContentResolver().query(document, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()
                    || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))
                    || (cursor.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                throw new IllegalArgumentException("Writable document directory is required");
            }
        }
    }

    static boolean isPending(Activity activity) { return PENDING.containsKey(activity); }

    static String displayName(String configured) {
        if (configured == null || configured.isEmpty()) return "默认位置";
        try {
            Uri uri = Uri.parse(configured);
            return DocumentsContract.getTreeDocumentId(uri);
        } catch (RuntimeException ignored) { return "自定义文件夹"; }
    }

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

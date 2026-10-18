package com.douai.module.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Host album selection only. No conversation, direct-send config, upload or media decoding is created. */
final class HostMediaPicker implements AutoCloseable {
    private static final String HOST_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String ACTIVITY = HOST_PACKAGE + ".im.business.mediaselectpage.choose.MediaChooseActivity";
    private static final String RESULT = HOST_PACKAGE + ".im.business.mediaselectpage.choose.model.MediaChooseResult";
    private static final String MODEL = HOST_PACKAGE + ".im.service.model.MediaModel";
    private static final String PARAMETERS = "extra_choose_parameters";
    private static final String CHOOSE_RESULT = "extra_choose_result";
    private static final int RESULT_CHOSEN = 2012;
    private static final int MAX_SELECTIONS = 4;
    private static final Map<Activity, HostMediaPicker> PENDING = new WeakHashMap<>();

    static final class Selection {
        final Uri uri;
        final boolean video;
        final String name;
        final String mimeType;
        final long durationMillis;
        final long sizeBytes;
        final int width;
        final int height;

        private Selection(Uri uri, boolean video, String name, String mimeType,
                          long durationMillis, long sizeBytes, int width, int height) {
            this.uri = uri;
            this.video = video;
            this.name = name;
            this.mimeType = mimeType;
            this.durationMillis = durationMillis;
            this.sizeBytes = sizeBytes;
            this.width = width;
            this.height = height;
        }
    }

    private final Activity activity;
    private final boolean videoOnly;
    private final Consumer<List<Selection>> selected;
    private final ClassLoader loader;
    private Object launcher;
    private Class<?> launcherType;
    private Class<?> resultType;
    private Class<?> modelType;
    private boolean closed;

    private HostMediaPicker(Activity activity, boolean videoOnly, Consumer<List<Selection>> selected) {
        this.activity = activity;
        this.videoOnly = videoOnly;
        this.selected = selected;
        loader = activity.getClass().getClassLoader();
    }

    /** Null means unavailable; a null callback result means cancellation or an unusable result. */
    static HostMediaPicker launch(Activity activity, boolean videoOnly, Consumer<List<Selection>> selected) {
        if (activity == null || selected == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostMediaPicker picker = new HostMediaPicker(activity, videoOnly, selected);
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(HOST_PACKAGE, 0);
            if (!HOST_PACKAGE.equals(activity.getPackageName()) || !"40.6.0".equals(info.versionName)
                    || info.getLongVersionCode() != 400601L) {
                throw new ReflectiveOperationException("Unsupported host album version");
            }
            Intent intent = picker.albumIntent();
            Class<?> contract = picker.hostClass("androidx.activity.result.contract.ActivityResultContract");
            Class<?> callback = picker.hostClass("androidx.activity.result.ActivityResultCallback");
            Class<?> registryType = picker.hostClass("androidx.activity.result.ActivityResultRegistry");
            picker.launcherType = picker.hostClass("androidx.activity.result.ActivityResultLauncher");
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object start = picker.hostClass("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult")
                    .getConstructor().newInstance();
            Object resultCallback = Proxy.newProxyInstance(picker.loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                    return "DouAiHostAlbumSelection";
                }
                if (!"onActivityResult".equals(method.getName()) || picker.closed) return null;
                picker.close();
                List<Selection> result = null;
                try {
                    if (args != null && args.length != 0 && args[0] != null) result = picker.readResult(args[0]);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
                    Log.w("DouAi.HostAlbum", "Host album returned an unavailable selection", invalid);
                }
                picker.selected.accept(result);
                return null;
            });
            picker.launcher = registryType.getMethod("register", String.class, contract, callback)
                    .invoke(registry, "douai_host_album_" + UUID.randomUUID(), start, resultCallback);
            HostMediaPicker previous = PENDING.put(activity, picker);
            if (previous != null) previous.close();
            picker.launcherType.getMethod("launch", Object.class).invoke(picker.launcher, intent);
            return picker;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            picker.close();
            Log.w("DouAi.HostAlbum", "Host album is unavailable", unavailable);
            return null;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            picker.close();
            Log.w("DouAi.HostAlbum", "Host album package is unavailable", unavailable);
            return null;
        }
    }

    static boolean isPending(Activity activity) { return PENDING.containsKey(activity); }

    private Intent albumIntent() throws ReflectiveOperationException {
        Class<?> nativeActivity = hostClass(ACTIVITY);
        if (!Activity.class.isAssignableFrom(nativeActivity)) throw new ReflectiveOperationException("Host album activity type");
        Class<?> parametersClass = hostClass("X.131D");
        if (!Serializable.class.isAssignableFrom(parametersClass)) throw new ReflectiveOperationException("Host album parameters type");
        Object parameters = parametersClass.getConstructor(int.class).newInstance(videoOnly ? 0x10 : 0x1);
        set(parameters, "b", boolean.class, true); // Native multiple-selection controls.
        set(parameters, "e", int.class, 3); // Ordinary album, not the emoji-upload scene.
        set(parameters, "f", int.class, MAX_SELECTIONS);
        set(parameters, "h", boolean.class, true); // Keep video selection enabled.
        set(parameters, "g", String.class, null);
        // A null direct-send config keeps the native completion path at setResult(2012).
        // In particular no SessionInfo/user/conversation model is manufactured here.
        parametersClass.getField("l").set(parameters, null);
        set(parameters, "m", boolean.class, false);
        set(parameters, "n", long.class, System.currentTimeMillis());
        resultType = hostClass(RESULT);
        resultType.getMethod("getSelectedList");
        modelType = hostClass(MODEL);
        modelType.getMethod("getFilePath");
        modelType.getMethod("LJIILJJIL");
        for (String field : new String[]{"type", "edited", "mimeType", "duration", "fileSize", "width", "height"}) {
            modelType.getField(field);
        }
        return new Intent(activity, nativeActivity)
                .putExtra(PARAMETERS, (Serializable) parameters)
                .putExtra("picure_choose_origin", 1);
    }

    private List<Selection> readResult(Object activityResult) throws ReflectiveOperationException {
        Class<?> activityResultType = hostClass("androidx.activity.result.ActivityResult");
        Object code = activityResultType.getMethod("getResultCode").invoke(activityResult);
        // Native album results use 2012/2011, rather than Activity.RESULT_OK/-1.
        if (!Integer.valueOf(RESULT_CHOSEN).equals(code)) return null;
        Intent data = (Intent) activityResultType.getMethod("getData").invoke(activityResult);
        if (data == null) return null;
        data.setExtrasClassLoader(loader);
        Serializable result = data.getSerializableExtra(CHOOSE_RESULT);
        if (!resultType.isInstance(result)) throw new ReflectiveOperationException("Host album result type");
        Object selectedList = resultType.getMethod("getSelectedList").invoke(result);
        if (!(selectedList instanceof List<?>)) throw new ReflectiveOperationException("Host album selected list type");
        List<Selection> selections = new ArrayList<>();
        LinkedHashSet<Uri> unique = new LinkedHashSet<>();
        for (Object model : (List<?>) selectedList) {
            if (!modelType.isInstance(model)) continue;
            int type = modelType.getField("type").getInt(model);
            boolean video = type == 1;
            if (video != videoOnly || (!video && type != 0 && type != 2)) continue;
            String filePath = (String) modelType.getMethod("getFilePath").invoke(model);
            String original = (String) modelType.getMethod("LJIILJJIL").invoke(model);
            boolean edited = modelType.getField("edited").getBoolean(model);
            Uri file = localUri(filePath);
            Uri source = localUri(original);
            // Under scoped storage the host may assign filePath a future cache filename
            // while relativePath still contains the selected source content URI.
            Uri uri = edited && file != null ? file : source != null ? source : file;
            if (uri == null || !unique.add(uri)) continue;
            String name = fileName(original);
            if (TextUtils.isEmpty(name)) name = fileName(filePath);
            if (TextUtils.isEmpty(name)) name = video ? "视频" : "图片";
            selections.add(new Selection(uri, video, name,
                    (String) modelType.getField("mimeType").get(model),
                    modelType.getField("duration").getLong(model),
                    modelType.getField("fileSize").getLong(model),
                    modelType.getField("width").getInt(model), modelType.getField("height").getInt(model)));
            if (selections.size() == MAX_SELECTIONS) break;
        }
        return Collections.unmodifiableList(selections);
    }

    private static Uri localUri(String value) {
        if (TextUtils.isEmpty(value)) return null;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme) && !TextUtils.isEmpty(uri.getAuthority())) return uri;
        if ("file".equalsIgnoreCase(scheme) && TextUtils.isEmpty(uri.getAuthority())
                && uri.getPath() != null && uri.getPath().startsWith("/")) return fileUri(uri.getPath());
        if (scheme == null && value.startsWith("/")) return fileUri(value);
        return null;
    }

    private static Uri fileUri(String androidAbsolutePath) {
        // Resolve Android path syntax without touching storage or applying the desktop
        // JVM's Windows drive rules during the same host-side contract tests.
        return new Uri.Builder().scheme("file").authority("").path(androidAbsolutePath).build();
    }

    private static String fileName(String value) {
        Uri uri = localUri(value);
        return uri != null && "file".equalsIgnoreCase(uri.getScheme()) ? uri.getLastPathSegment() : null;
    }

    private static void set(Object target, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        if (field.getType() != type) throw new ReflectiveOperationException("Host album parameter " + name);
        field.set(target, value);
    }

    private Class<?> hostClass(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (PENDING.get(activity) == this) PENDING.remove(activity);
        if (launcher != null) {
            try { launcherType.getMethod("unregister").invoke(launcher); }
            catch (ReflectiveOperationException | RuntimeException unavailable) {
                Log.w("DouAi.HostAlbum", "Host album callback could not be unregistered", unavailable);
            }
        }
    }
}

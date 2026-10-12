package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.douai.module.config.SettingsStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** Uses the host's AndroidX result registry without taking over its Activity callbacks. */
public final class HostAudioPicker {
    private HostAudioPicker() { }

    public static boolean launch(Activity activity, SettingsStore settings, String key,
                                  java.util.function.Consumer<String> onImported) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        Object[] launcher = new Object[1];
        try {
            ClassLoader loader = activity.getClass().getClassLoader();
            Class<?> contract = Class.forName("androidx.activity.result.contract.ActivityResultContract", false, loader);
            Class<?> callback = Class.forName("androidx.activity.result.ActivityResultCallback", false, loader);
            Class<?> registryType = Class.forName("androidx.activity.result.ActivityResultRegistry", false, loader);
            Class<?> launcherType = Class.forName("androidx.activity.result.ActivityResultLauncher", false, loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object open = Class.forName("androidx.activity.result.contract.ActivityResultContracts$OpenDocument", false, loader)
                    .getConstructor().newInstance();
            Context context = activity.getApplicationContext();
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    return "DouAiAudioSelection";
                }
                if (!"onActivityResult".equals(method.getName())) return null;
                if (launcher[0] != null) {
                    try { launcherType.getMethod("unregister").invoke(launcher[0]); }
                    catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
                Object value = arguments == null || arguments.length == 0 ? null : arguments[0];
                if (!(value instanceof Uri)) return null;
                Uri source = (Uri) value;
                Thread copy = new Thread(() -> importFile(context, source, settings, key, onImported), "DouAi-AudioImport");
                copy.setDaemon(true);
                copy.start();
                return null;
            });
            Method register = registryType.getMethod("register", String.class, contract, callback);
            launcher[0] = register.invoke(registry, "douai_audio_" + UUID.randomUUID(), open, result);
            launcherType.getMethod("launch", Object.class).invoke(launcher[0], (Object) new String[]{"audio/*"});
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (launcher[0] != null) {
                try { launcher[0].getClass().getMethod("unregister").invoke(launcher[0]); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            return false;
        }
    }

    private static void importFile(Context context, Uri source, SettingsStore settings, String key,
                                    java.util.function.Consumer<String> onImported) {
        File target = null;
        try {
            File directory = new File(context.getFilesDir(), "douai-voice-source");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建语音目录");
            String mime = context.getContentResolver().getType(source);
            String extension = mime != null && mime.contains("wav") ? ".wav"
                    : mime != null && mime.contains("mpeg") ? ".mp3"
                    : mime != null && mime.contains("ogg") ? ".ogg" : ".m4a";
            target = new File(directory, UUID.randomUUID() + extension);
            try (InputStream input = context.getContentResolver().openInputStream(source);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IllegalStateException("无法读取所选音频");
                byte[] buffer = new byte[8192];
                long count = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > 20L * 1024 * 1024) throw new IllegalStateException("语音文件不能超过 20 MB");
                    output.write(buffer, 0, read);
                }
                if (count == 0) throw new IllegalStateException("语音文件为空");
            }
            settings.putString(key, target.getAbsolutePath());
            String imported = target.getAbsolutePath();
            if (onImported != null) new Handler(Looper.getMainLooper()).post(() -> onImported.accept(imported));
            toast(context, "语音文件已选定");
            target = null;
        } catch (Exception failure) {
            toast(context, failure.getMessage() == null ? "语音文件读取失败" : failure.getMessage());
        } finally {
            if (target != null) {
                try { java.nio.file.Files.deleteIfExists(target.toPath()); }
                catch (Exception ignored) { }
            }
        }
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}

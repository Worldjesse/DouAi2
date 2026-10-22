package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/** Imports a draft file; the main-thread callback receives null on cancellation or failure. */
public final class HostAudioPicker {
    private HostAudioPicker() { }

    static HostImagePicker launch(Activity activity, java.util.function.Consumer<String> onImported) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || onImported == null) return null;
        Context context = activity.getApplicationContext();
        // The shared document picker keeps host settings alive while the system picker is open.
        return HostImagePicker.launch(activity, new String[]{"audio/*"}, source -> {
            if (source == null) {
                deliver(onImported, null);
                return;
            }
            Thread copy = new Thread(() -> importFile(context, source, onImported), "DouAi-AudioImport");
            copy.setDaemon(true);
            copy.start();
        });
    }

    private static void importFile(Context context, Uri source, java.util.function.Consumer<String> onImported) {
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
            String imported = target.getAbsolutePath();
            deliver(onImported, imported);
            target = null;
        } catch (Exception failure) {
            deliver(onImported, null);
            toast(context, failure.getMessage() == null ? "语音文件读取失败" : failure.getMessage());
        } finally {
            if (target != null) {
                try { java.nio.file.Files.deleteIfExists(target.toPath()); }
                catch (Exception ignored) { }
            }
        }
    }

    private static void deliver(java.util.function.Consumer<String> callback, String path) {
        if (callback != null) new Handler(Looper.getMainLooper()).post(() -> callback.accept(path));
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}

package com.douai.module.feature;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** User-initiated media saving through MediaStore, with DownloadManager fallback before API 29. */
public final class MediaStoreDownloader {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;

    private MediaStoreDownloader() { }

    public static boolean isHttpUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean download(Context sourceContext, String url, String fileName,
                                   boolean audio, Consumer<String> log) {
        return download(sourceContext, url, fileName,
                audio ? "audio/mpeg" : "video/mp4",
                audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES,
                audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                log);
    }

    public static boolean downloadImage(Context sourceContext, String url, String fileName,
                                        Consumer<String> log) {
        String mimeType = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                ? "image/png" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".webp")
                ? "image/webp" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")
                ? "image/gif" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".heic")
                ? "image/heic" : "image/jpeg";
        return download(sourceContext, url, fileName, mimeType, Environment.DIRECTORY_PICTURES,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, log);
    }

    private static boolean download(Context sourceContext, String url, String fileName,
                                    String mimeType, String directory, Uri collection,
                                    Consumer<String> log) {
        if (sourceContext == null || fileName == null || fileName.isEmpty()
                || fileName.contains("/") || fileName.contains("\\") || !isHttpUrl(url)) return false;
        Context context = sourceContext.getApplicationContext();
        if (context == null) context = sourceContext;
        Context targetContext = context;
        EXECUTOR.execute(() -> save(targetContext, url, fileName, mimeType, directory, collection, log));
        toast(sourceContext, "正在保存媒体");
        return true;
    }

    private static void save(Context context, String mediaUrl, String fileName,
                             String mimeType, String directory, Uri collection,
                             Consumer<String> log) {
        Uri target = null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(mediaUrl).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DouAi");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            String responseType = connection.getContentType();
            if (responseType != null && (responseType.contains("text/html")
                    || responseType.contains("application/json"))) {
                throw new IllegalStateException("下载地址返回了非媒体内容");
            }
            if (connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException("媒体文件过大");
            }

            ContentResolver resolver = context.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/DouAi");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                target = resolver.insert(collection, values);
                if (target == null) throw new IllegalStateException("MediaStore 无法创建记录");
                copyResponse(connection, resolver, target);
                ContentValues completed = new ContentValues();
                completed.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(target, completed, null, null);
                target = null;
                toast(context, "媒体已保存到 " + directory + "/DouAi");
            } else {
                DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("系统下载服务不可用");
                manager.enqueue(new DownloadManager.Request(Uri.parse(mediaUrl))
                        .setTitle(fileName)
                        .setMimeType(mimeType)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(
                                directory,
                                "DouAi/" + fileName));
                toast(context, "已加入系统下载队列");
            }
        } catch (Throwable failure) {
            if (target != null) {
                try {
                    context.getContentResolver().delete(target, null, null);
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup of an incomplete MediaStore row.
                }
            }
            safeLog(log, "Could not save media: " + failure.getMessage());
            toast(context, "媒体保存失败：" + safeMessage(failure));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void copyResponse(HttpURLConnection connection, ContentResolver resolver,
                                     Uri target) throws Exception {
        long copied = 0;
        try (InputStream input = connection.getInputStream();
             OutputStream output = resolver.openOutputStream(target, "w")) {
            if (output == null) throw new IllegalStateException("MediaStore 输出流不可用");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("媒体文件过大");
                output.write(buffer, 0, count);
            }
            if (copied == 0) throw new IllegalStateException("下载内容为空");
        }
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // UI feedback is best-effort; the file operation remains authoritative.
            }
        });
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? "请稍后重试" : message;
    }

    private static void safeLog(Consumer<String> log, String message) {
        if (log == null) return;
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Logging must not change the download result.
        }
    }
}

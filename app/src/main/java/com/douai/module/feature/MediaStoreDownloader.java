package com.douai.module.feature;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** User-initiated media saving through MediaStore, with DownloadManager fallback before API 29. */
public final class MediaStoreDownloader {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
    private static final BoundedMediaQueue QUEUE = new BoundedMediaQueue(EXECUTOR);
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
        return download(sourceContext, url, fileName, audio, "", log);
    }

    public static boolean download(Context sourceContext, String url, String fileName,
                                   boolean audio, String tree, Consumer<String> log) {
        return download(sourceContext, url, fileName,
                audio ? "audio/mpeg" : "video/mp4",
                audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES,
                audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                tree, log);
    }

    public static boolean downloadImage(Context sourceContext, String url, String fileName,
                                        Consumer<String> log) {
        return downloadImage(sourceContext, url, fileName, "", log);
    }

    public static boolean downloadImage(Context sourceContext, String url, String fileName,
                                        String tree, Consumer<String> log) {
        String mimeType = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                ? "image/png" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".webp")
                ? "image/webp" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")
                ? "image/gif" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".heic")
                ? "image/heic" : "image/jpeg";
        return download(sourceContext, url, fileName, mimeType, Environment.DIRECTORY_PICTURES,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, tree, log);
    }

    private static boolean download(Context sourceContext, String url, String fileName,
                                    String mimeType, String directory, Uri collection,
                                    String tree, Consumer<String> log) {
        if (sourceContext == null || fileName == null || fileName.isEmpty()
                || fileName.contains("/") || fileName.contains("\\") || !isHttpUrl(url)) return false;
        Context context = sourceContext.getApplicationContext();
        if (context == null) context = sourceContext;
        Context targetContext = context;
        String key = url + '\n' + fileName + '\n' + collection + '\n' + tree;
        if (!QUEUE.submit(key, () -> {
            if (tree == null || tree.isEmpty()) save(targetContext, url, fileName, mimeType, directory, collection, log);
            else saveDocument(targetContext, url, fileName, mimeType, tree, log);
        })) {
            safeLog(log, "Media save request was deduplicated or the queue is full");
            toast(sourceContext, "媒体正在保存或下载队列已满");
            return false;
        }
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
        try (InputStream input = connection.getInputStream();
             OutputStream output = resolver.openOutputStream(target, "w")) {
            if (output == null) throw new IllegalStateException("MediaStore 输出流不可用");
            copyMedia(input, output, connection.getContentLengthLong());
        }
    }

    /** Saves one parser result together; failures remove all rows created for this bundle. */
    static void saveThirdPartyBundle(Context context, ThirdPartyDownloadPlan plan) throws Exception {
        saveThirdPartyBundle(context, plan, "");
    }

    static void saveThirdPartyBundle(Context context, ThirdPartyDownloadPlan plan, String tree) throws Exception {
        String folder = plan.baseName + "_" + System.currentTimeMillis() + "_"
                + Long.toHexString(System.nanoTime());
        if (tree != null && !tree.isEmpty()) {
            saveDocumentBundle(context, plan, tree, folder);
            return;
        }
        if (Build.VERSION.SDK_INT < 29) {
            saveLegacyBundle(context, plan, folder);
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        List<Uri> created = new ArrayList<>();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/DouAi/" + folder;
        try {
            for (ThirdPartyDownloadPlan.Asset asset : plan.assets) {
                HttpURLConnection connection = openBundleMedia(asset.url);
                try {
                    Uri target = createBundleRow(resolver, relativePath, asset.name, asset.mimeType);
                    created.add(target);
                    copyResponse(connection, resolver, target);
                } finally { connection.disconnect(); }
            }
            Uri metadata = createBundleRow(resolver, relativePath, plan.baseName + ".txt", "text/plain");
            created.add(metadata);
            try (OutputStream output = resolver.openOutputStream(metadata, "w")) {
                if (output == null) throw new IllegalStateException("作品信息输出流不可用");
                output.write(plan.metadata.getBytes(StandardCharsets.UTF_8));
            }
            ContentValues completed = new ContentValues();
            completed.put(MediaStore.MediaColumns.IS_PENDING, 0);
            for (Uri target : created) {
                if (resolver.update(target, completed, null, null) < 1) {
                    throw new IllegalStateException("无法完成文件保存");
                }
            }
        } catch (Exception failure) {
            for (Uri target : created) {
                try { resolver.delete(target, null, null); }
                catch (RuntimeException ignored) { }
            }
            throw failure;
        }
    }

    private static void saveDocument(Context context, String url, String name, String mime,
                                      String tree, Consumer<String> log) {
        Uri target = null;
        HttpURLConnection connection = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri root = DownloadDocumentTree.root(tree);
            connection = openBundleMedia(url);
            target = DownloadDocumentTree.create(resolver, root, mime, name);
            copyResponse(connection, resolver, target);
            target = null;
            toast(context, "媒体已保存到所选目录");
        } catch (Exception failure) {
            DownloadDocumentTree.delete(context.getContentResolver(), target);
            safeLog(log, "Custom media save failed: " + failure.getClass().getSimpleName());
            toast(context, "所选目录保存失败，请检查目录权限或重新选择");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void saveDocumentBundle(Context context, ThirdPartyDownloadPlan plan,
                                           String tree, String folder) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri root = DownloadDocumentTree.root(tree);
        Uri directory = DownloadDocumentTree.create(resolver, root,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR, folder);
        List<Uri> created = new ArrayList<>();
        try {
            for (ThirdPartyDownloadPlan.Asset asset : plan.assets) {
                HttpURLConnection connection = openBundleMedia(asset.url);
                try {
                    Uri target = DownloadDocumentTree.create(resolver, directory, asset.mimeType, asset.name);
                    created.add(target);
                    copyResponse(connection, resolver, target);
                } finally { connection.disconnect(); }
            }
            Uri metadata = DownloadDocumentTree.create(resolver, directory, "text/plain", plan.baseName + ".txt");
            created.add(metadata);
            try (OutputStream output = resolver.openOutputStream(metadata, "w")) {
                if (output == null) throw new IllegalStateException("无法写入作品信息");
                output.write(plan.metadata.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception failure) {
            for (Uri target : created) DownloadDocumentTree.delete(resolver, target);
            DownloadDocumentTree.delete(resolver, directory);
            throw failure;
        }
    }

    @android.annotation.TargetApi(29)
    private static Uri createBundleRow(ContentResolver resolver, String path, String name, String mime) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri result = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (result == null) throw new IllegalStateException("无法创建下载文件");
        return result;
    }

    private static void saveLegacyBundle(Context context, ThirdPartyDownloadPlan plan, String folder) throws Exception {
        File parent = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DouAi");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("无法创建下载目录");
        }
        File directory = new File(parent, folder);
        Files.createDirectory(directory.toPath());
        List<File> created = new ArrayList<>();
        try {
            for (ThirdPartyDownloadPlan.Asset asset : plan.assets) {
                HttpURLConnection connection = openBundleMedia(asset.url);
                try {
                    File target = new File(directory, asset.name);
                    created.add(target);
                    try (InputStream input = connection.getInputStream();
                         OutputStream output = new FileOutputStream(target)) {
                        copyMedia(input, output, connection.getContentLengthLong());
                    }
                } finally { connection.disconnect(); }
            }
            File metadata = new File(directory, plan.baseName + ".txt");
            created.add(metadata);
            try (OutputStream output = new FileOutputStream(metadata)) {
                output.write(plan.metadata.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception failure) {
            for (File file : created) file.delete();
            directory.delete();
            throw failure;
        }
        String[] paths = new String[created.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = created.get(i).getAbsolutePath();
        MediaScannerConnection.scanFile(context, paths, null, null);
    }

    private static HttpURLConnection openBundleMedia(String url) throws Exception {
        if (!isHttpUrl(url)) throw new IllegalArgumentException("下载地址无效");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DouAi");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("媒体请求失败：HTTP " + status);
            String type = connection.getContentType();
            if (type != null) {
                type = type.toLowerCase(java.util.Locale.ROOT);
                if (type.startsWith("text/") || type.contains("json") || type.contains("xml")
                        || type.contains("mpegurl") || type.contains("dash+xml")) {
                    throw new IllegalStateException("下载字段返回了非媒体内容");
                }
            }
            if (connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("媒体文件过大");
            return connection;
        } catch (Exception failure) {
            connection.disconnect();
            throw failure;
        }
    }

    private static void copyMedia(InputStream input, OutputStream output, long expected) throws Exception {
        long copied = 0;
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            copied += count;
            if (copied > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("媒体文件过大");
            output.write(buffer, 0, count);
        }
        if (copied == 0) throw new IllegalStateException("下载内容为空");
        if (expected >= 0 && copied != expected) throw new IllegalStateException("媒体文件未下载完整");
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

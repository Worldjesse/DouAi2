package com.douai.module.feature;

import android.content.Context;

import com.douai.module.config.LocalOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loads images and sampled visual frames only from the captured comment work. */
public final class AutomationCommentMedia {
    public static final String RECOGNIZE_IMAGES = "comment_recognize_images";
    public static final String RECOGNIZE_VIDEO = "comment_recognize_video";

    private AutomationCommentMedia() { }

    public static List<ChatImage> load(Context context, Object work, LocalOptions options) throws Exception {
        ChatMediaLoader.checkCancelled();
        boolean imagesEnabled = options.enabled(RECOGNIZE_IMAGES, true);
        boolean videoEnabled = options.enabled(RECOGNIZE_VIDEO, false);
        if (!imagesEnabled && !videoEnabled) return Collections.emptyList();
        String video = videoEnabled ? videoUrl(work) : null;
        List<String> sources = imagesEnabled ? imageUrls(work) : Collections.emptyList();
        int imageLimit = video == null ? ChatCompletionClient.MAX_IMAGES_PER_MESSAGE : 1;
        List<ChatImage> images = new ArrayList<>(AutomationImages.load(
                sources.subList(0, Math.min(imageLimit, sources.size()))));
        if (video != null) {
            File local = downloadVideo(context, video);
            try { images.addAll(ChatMediaLoader.loadVideoFile(local,
                    ChatCompletionClient.MAX_IMAGES_PER_MESSAGE - images.size())); }
            finally { ChatMediaLoader.deleteTemporary(local); }
        } else if (imagesEnabled && images.isEmpty()) {
            String snapshotSource = videoUrl(work);
            if (snapshotSource != null) {
                File local = downloadVideo(context, snapshotSource);
                try { images.addAll(ChatMediaLoader.loadVideoFile(local, 1)); }
                finally { ChatMediaLoader.deleteTemporary(local); }
            }
        }
        ChatMediaLoader.checkCancelled();
        return Collections.unmodifiableList(images);
    }

    static List<String> imageUrls(Object work) {
        List<String> sources = new ArrayList<>();
        Object gallery = DownloadActionHook.jsonValue(work, "images");
        if (gallery instanceof Iterable<?>) {
            for (Object image : (Iterable<?>) gallery) {
                String source = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "url_list"));
                if (source == null) source = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "download_url_list"));
                if (source == null || !AutomationImages.isMediaUrl(source)) {
                    throw new IllegalArgumentException("图文图片无法读取");
                }
                sources.add(source);
                if (sources.size() == ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) break;
            }
        }
        if (sources.isEmpty()) {
            Object video = DownloadActionHook.jsonValue(work, "video");
            for (String field : new String[]{"cover", "origin_cover", "dynamic_cover"}) {
                String source = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(video, field));
                if (source != null) {
                    if (!AutomationImages.isMediaUrl(source)) throw new IllegalArgumentException("视频封面地址无法读取");
                    sources.add(source);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(sources);
    }

    static String videoUrl(Object work) {
        Object video = DownloadActionHook.jsonValue(work, "video");
        if (video == null) return null;
        String url = DownloadActionHook.findPlaybackVideoUrl(work);
        if (url == null || !isVideoUrl(url)) throw new IllegalArgumentException("视频播放地址无法读取");
        return url;
    }

    static boolean isVideoUrl(String value) {
        if (AutomationImages.isMediaUrl(value)) return true;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            for (String domain : new String[]{"douyinvod.com", "iesdouyin.com"}) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static File downloadVideo(Context context, String source) throws Exception {
        String current = source;
        for (int redirect = 0; redirect < 4; redirect++) {
            ChatMediaLoader.checkCancelled();
            if (!isVideoUrl(current)) throw new IllegalArgumentException("视频地址不属于受支持的抖音媒体域名");
            HttpURLConnection connection = (HttpURLConnection) URI.create(current).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(false);
            File local = null;
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IllegalStateException("视频地址跳转无效");
                    current = URI.create(current).resolve(location).toString();
                    continue;
                }
                if (status != 200) throw new IllegalStateException("视频下载失败");
                if (connection.getContentLengthLong() > ChatMediaLoader.MAX_VIDEO_BYTES) {
                    throw new IllegalArgumentException("视频超过 64 MB");
                }
                local = File.createTempFile("douai-comment-video-", ".video", context.getCacheDir());
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(local)) {
                    ChatMediaLoader.copyBounded(input, output, ChatMediaLoader.MAX_VIDEO_BYTES);
                }
                return local;
            } catch (Exception failure) { ChatMediaLoader.deleteTemporary(local); throw failure; }
            finally { connection.disconnect(); }
        }
        throw new IllegalStateException("视频地址跳转次数过多");
    }
}

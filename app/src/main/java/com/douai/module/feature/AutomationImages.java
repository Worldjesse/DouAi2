package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fetches explicit media URLs from host image models without forwarding model credentials. */
public final class AutomationImages {
    private AutomationImages() { }

    public static List<String> incoming(JSONObject content) {
        JSONObject resource = content.optJSONObject("resource_url");
        if (resource == null) resource = content.optJSONObject("url");
        if (resource == null) return Collections.emptyList();
        JSONArray urls = resource.optJSONArray("url_list");
        if (urls == null) return Collections.emptyList();
        for (int i = 0; i < urls.length(); i++) {
            String value = urls.optString(i, "");
            if (isMediaUrl(value)) return Collections.singletonList(value);
        }
        return Collections.emptyList();
    }

    public static List<ChatImage> load(List<String> urls) throws Exception {
        if (urls.size() > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) throw new IllegalArgumentException("图片最多 4 张");
        List<ChatImage> images = new ArrayList<>();
        for (String source : urls) images.add(fetch(source));
        return images;
    }

    static ChatImage fetch(String source) throws Exception {
        String current = source;
        for (int redirect = 0; redirect < 4; redirect++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (!isMediaUrl(current)) throw new IllegalArgumentException("图片地址不属于受支持的抖音媒体域名");
            HttpURLConnection connection = (HttpURLConnection) URI.create(current).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IllegalStateException("图片地址跳转无效");
                    current = URI.create(current).resolve(location).toString();
                    continue;
                }
                if (status != 200) throw new IllegalStateException("图片下载失败");
                if (connection.getContentLengthLong() > 16L * 1024 * 1024) throw new IllegalArgumentException("图片超过 16 MB");
                try (InputStream input = connection.getInputStream()) {
                    return ChatImageLoader.decode(ChatImageLoader.read(input));
                }
            } finally { connection.disconnect(); }
        }
        throw new IllegalStateException("图片地址跳转次数过多");
    }

    public static boolean isMediaUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            for (String domain : new String[]{"douyinpic.com", "byteimg.com", "ibyteimg.com", "pstatp.com",
                    "douyincdn.com", "snssdk.com", "bytedance.com", "bytecdn.cn", "douyinstatic.com"}) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }
}

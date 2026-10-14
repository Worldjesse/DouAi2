package com.douai.module.feature;

import com.douai.module.config.ThirdPartyDownloadSettings;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves configured response paths into a bounded media bundle. */
final class ThirdPartyDownloadPlan {
    static final class Asset {
        final String url;
        final String name;
        final String mimeType;

        Asset(String url, String name, String mimeType) {
            this.url = url;
            this.name = name;
            this.mimeType = mimeType;
        }
    }

    final String baseName;
    final List<Asset> assets;
    final String metadata;

    private ThirdPartyDownloadPlan(String baseName, List<Asset> assets, String metadata) {
        this.baseName = baseName;
        this.assets = Collections.unmodifiableList(assets);
        this.metadata = metadata;
    }

    static ThirdPartyDownloadPlan create(ThirdPartyResponse response,
                                         ThirdPartyDownloadSettings settings, long now) {
        Map<String, String> values = response.values();
        List<String> parts = new ArrayList<>();
        for (String path : settings.namingFields) {
            String value = values.get(path);
            if (value != null && !value.trim().isEmpty() && !"null".equals(value)
                    && !"[]".equals(value) && !"{}".equals(value)) parts.add(value);
        }
        String base = MediaNaming.sanitize(String.join("_", parts), "");
        if (base.isEmpty()) base = "DouAi_" + now;
        while (base.getBytes(StandardCharsets.UTF_8).length > 110) {
            base = base.substring(0, base.offsetByCodePoints(base.length(), -1));
        }
        Map<String, List<String>> fields = response.downloadFields();
        Set<String> includedFields = new LinkedHashSet<>();
        Set<String> seenUrls = new HashSet<>();
        List<Asset> assets = new ArrayList<>();
        int sequence = 0;
        for (String path : settings.downloadFields) {
            List<String> urls = fields.get(path);
            if (urls == null || urls.isEmpty()) continue;
            includedFields.add(path);
            for (String url : urls) {
                if (!seenUrls.add(url)) continue;
                if (assets.size() >= 64) throw new IllegalArgumentException("单次最多下载 64 个文件");
                String extension = extension(path, url);
                String name = MediaNaming.sanitize(base + "_" + (++sequence) + "_"
                        + path.replaceAll("[^A-Za-z0-9_-]", "_"), extension);
                assets.add(new Asset(url, name, mimeType(extension)));
            }
        }
        if (assets.isEmpty()) throw new IllegalArgumentException("所选下载字段没有链接，请在第三方设置中测试接口并选择字段");
        return new ThirdPartyDownloadPlan(base, assets, response.metadataText(includedFields));
    }

    private static String extension(String field, String url) {
        String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            throw new IllegalArgumentException("暂不支持下载播放列表，请选择媒体文件字段");
        }
        for (String ext : new String[]{"mp4", "webm", "mov", "mkv", "mp3", "m4a", "aac",
                "wav", "ogg", "flac", "jpg", "jpeg", "png", "webp", "gif", "heic"}) {
            if (path.endsWith("." + ext) || path.endsWith("~" + ext)) return ext;
        }
        String key = field.toLowerCase(Locale.ROOT);
        if (key.contains("video")) return "mp4";
        if (key.contains("audio") || key.contains("music")) return "mp3";
        if (key.contains("image") || key.contains("cover") || key.contains("avatar")
                || key.contains("picture")) return "jpg";
        return "bin";
    }

    private static String mimeType(String extension) {
        switch (extension) {
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "mkv": return "video/x-matroska";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "flac": return "audio/flac";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "heic": return "image/heic";
            default: return "application/octet-stream";
        }
    }
}

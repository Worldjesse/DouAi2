package com.douai.module.feature;

import java.util.Base64;
import java.util.Locale;

/** Immutable inline image shared by the supported model protocols. */
public final class ChatImage {
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    public final String mimeType;
    public final String base64Data;
    private final int sizeBytes;

    public ChatImage(String mimeType, byte[] data) {
        String type = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!("image/jpeg".equals(type) || "image/png".equals(type)
                || "image/webp".equals(type) || "image/gif".equals(type))) {
            throw new IllegalArgumentException("仅支持 JPEG、PNG、WebP 和 GIF 图片");
        }
        if (data == null || data.length == 0) throw new IllegalArgumentException("图片内容为空");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("单张图片不能超过 4 MB");
        this.mimeType = type;
        this.sizeBytes = data.length;
        this.base64Data = Base64.getEncoder().encodeToString(data);
    }

    public int sizeBytes() { return sizeBytes; }

    public String dataUrl() { return "data:" + mimeType + ";base64," + base64Data; }
}

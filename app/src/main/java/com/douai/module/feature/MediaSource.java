package com.douai.module.feature;

/** One URL candidate obtained from the host's media model. */
public final class MediaSource {
    public final String url;
    public final boolean watermarked;
    public final boolean defaultSource;
    public final int qualityRank;

    public MediaSource(String url, boolean watermarked, boolean defaultSource, int qualityRank) {
        this.url = url == null ? "" : url.trim();
        this.watermarked = watermarked;
        this.defaultSource = defaultSource;
        this.qualityRank = qualityRank;
    }

    public boolean isUsable() {
        return url.startsWith("https://") || url.startsWith("http://");
    }
}

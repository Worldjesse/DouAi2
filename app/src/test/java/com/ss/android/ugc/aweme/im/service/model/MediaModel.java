package com.ss.android.ugc.aweme.im.service.model;

import java.io.Serializable;

/** Native result metadata contract; intentionally has no loader, uploader or sender. */
public final class MediaModel implements Serializable {
    public int type;
    public boolean edited;
    public String filePath;
    public String relativePath;
    public String mimeType;
    public long duration;
    public long fileSize;
    public int width;
    public int height;

    public String getFilePath() { return filePath; }
    public String LJIILJJIL() { return relativePath; }
}

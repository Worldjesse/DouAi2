package com.douai.module.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.douai.module.config.AiModelConfig;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** Small self-contained contact avatars survive restarts and configuration transfers. */
public final class AiContactAvatar {
    private static final int EDGE = 128;
    private AiContactAvatar() { }

    /** Call on a worker. The result does not retain the document permission or source image. */
    public static String fromSelection(Context context, Uri uri) throws Exception {
        ChatImage source = ChatImageLoader.load(context, uri);
        Bitmap thumbnail = ChatImageLoader.thumbnail(source);
        if (thumbnail == null) throw new IllegalArgumentException("无法读取联系人头像");
        Bitmap scaled = null;
        try {
            float scale = Math.min(1f, EDGE / (float) Math.max(thumbnail.getWidth(), thumbnail.getHeight()));
            scaled = Bitmap.createScaledBitmap(thumbnail, Math.max(1, Math.round(thumbnail.getWidth() * scale)),
                    Math.max(1, Math.round(thumbnail.getHeight() * scale)), true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    || output.size() > AiModelConfig.MAX_CONTACT_AVATAR_BYTES) {
                throw new IllegalArgumentException("头像转换失败，请选择较小图片");
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } finally {
            if (scaled != null && scaled != thumbnail) scaled.recycle();
            thumbnail.recycle();
        }
    }

    /** Caller owns the returned bitmap. Invalid or absent avatars use the UI's default icon. */
    public static Bitmap load(String value) {
        try {
            byte[] bytes = AiModelConfig.decodeContactAvatar(value);
            if (bytes.length == 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0
                    || options.outWidth > 512 || options.outHeight > 512) return null;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > EDGE || options.outHeight / options.inSampleSize > EDGE) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (RuntimeException ignored) { return null; }
    }
}

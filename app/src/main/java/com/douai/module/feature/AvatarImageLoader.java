package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/** Bounded avatar requests that decode directly to display size without AI-image serialization. */
public final class AvatarImageLoader {
    static final int EDGE = 128;
    static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;

    private AvatarImageLoader() { }

    public static final class Request {
        private final MediaReadCancellation cancellation;

        public Request() { this(new MediaReadCancellation()); }

        Request(MediaReadCancellation cancellation) { this.cancellation = cancellation; }

        public Bitmap load(String source) throws Exception {
            cancellation.start();
            String current = source;
            for (int redirect = 0; redirect < 4; redirect++) {
                cancellation.check();
                if (!AutomationImages.isMediaUrl(current))
                    throw new IllegalArgumentException("头像地址不属于受支持的抖音媒体域名");
                HttpURLConnection connection = cancellation.open(URI.create(current));
                try {
                    int status = connection.getResponseCode();
                    cancellation.check();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IllegalArgumentException("头像地址跳转无效");
                        current = URI.create(current).resolve(location).toString();
                        continue;
                    }
                    if (status != 200) throw new IllegalStateException("头像下载失败");
                    if (connection.getContentLengthLong() > MAX_SOURCE_BYTES)
                        throw new IllegalArgumentException("头像超过 16 MB");
                    try (InputStream input = connection.getInputStream()) {
                        return decode(read(input, cancellation), cancellation);
                    }
                } catch (Exception failure) {
                    cancellation.check();
                    throw failure;
                } finally { cancellation.close(connection); }
            }
            throw new IllegalStateException("头像地址跳转次数过多");
        }

        public void cancel() { cancellation.cancel(); }
    }

    private static byte[] read(InputStream input, MediaReadCancellation cancellation) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            cancellation.check();
            if (output.size() + count > MAX_SOURCE_BYTES) throw new IllegalArgumentException("头像超过 16 MB");
            output.write(buffer, 0, count);
        }
        cancellation.check();
        return output.toByteArray();
    }

    static Bitmap decode(byte[] source, MediaReadCancellation cancellation) throws Exception {
        cancellation.check();
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("头像为空或超过 16 MB");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, options);
        cancellation.check();
        if (options.outWidth <= 0 || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > 100_000_000L)
            throw new IllegalArgumentException("头像格式或尺寸不支持");
        int edge = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while ((long) edge > (long) EDGE * options.inSampleSize) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (decoded == null) throw new IllegalArgumentException("无法解码头像");
        Bitmap oriented = null;
        Bitmap avatar = null;
        try {
            cancellation.check();
            Matrix orientation = ChatImageLoader.orientation(source);
            cancellation.check();
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), orientation, true);
            // Preserve the former white background, with all compositing limited to thumbnail dimensions.
            avatar = Bitmap.createBitmap(oriented.getWidth(), oriented.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(avatar);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(oriented, 0, 0, null);
            cancellation.check();
            return avatar;
        } catch (Exception failure) {
            if (avatar != null) avatar.recycle();
            throw failure;
        } finally {
            if (oriented != null && oriented != decoded) oriented.recycle();
            decoded.recycle();
        }
    }
}

package com.douai.module.feature;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runs only after a user invokes a third-party download. */
public final class ThirdPartyDownload {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4));
    private static final BoundedMediaQueue QUEUE = new BoundedMediaQueue(EXECUTOR);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThirdPartyDownload() { }

    public static void start(Context source, Object aweme, LocalOptions options, Consumer<String> log) {
        if (source == null) return;
        if (options.downloadSaveTree().isEmpty() && Build.VERSION.SDK_INT < 29
                && source.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            toast(source, "请先授予抖音存储权限");
            return;
        }
        ThirdPartyDownloadSettings config = new ThirdPartyDownloadSettings(options);
        String tree = options.downloadSaveTree();
        String capturedLink;
        try { capturedLink = shareUrl(aweme); }
        catch (IllegalArgumentException failure) {
            if (config.getTemplate.contains("{url}")) { toast(source, failure.getMessage()); return; }
            capturedLink = "";
        }
        final String link = capturedLink;
        Context application = source.getApplicationContext();
        Context context = application == null ? source : application;
        String key = config.getTemplate + '\n' + link + '\n' + tree;
        boolean accepted = QUEUE.submit(key, () -> {
            try {
                String body = ThirdPartyGetClient.fetch(config, link);
                ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(
                        ThirdPartyResponse.parse(body), config, System.currentTimeMillis());
                MediaStoreDownloader.saveThirdPartyBundle(context, plan, tree);
                toast(context, "已保存 " + plan.assets.size() + " 个文件和作品信息到 "
                        + (tree.isEmpty() ? "Download/DouAi" : "所选目录"));
            } catch (Exception failure) {
                if (log != null) {
                    try { log.accept("Third-party download failed: " + failure.getClass().getSimpleName()); }
                    catch (RuntimeException ignored) { }
                }
                String message = failure instanceof IllegalArgumentException
                        || failure instanceof IllegalStateException ? failure.getMessage() : "请求或文件保存失败";
                toast(context, "第三方下载失败：" + message);
            }
        });
        toast(context, accepted ? "正在请求第三方解析" : "该作品正在下载或队列已满");
    }

    static String shareUrl(Object aweme) {
        if (aweme instanceof String) return ThirdPartyGetClient.extractShareUrl((String) aweme);
        Object value = DownloadActionHook.jsonValue(aweme, "share_url");
        if (value == null) value = DownloadActionHook.jsonValue(
                DownloadActionHook.jsonValue(aweme, "share_info"), "share_url");
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try { return ThirdPartyGetClient.extractShareUrl((String) value); }
            catch (IllegalArgumentException ignored) { }
        }
        Object id = DownloadActionHook.jsonValue(aweme, "aweme_id");
        if (id != null && id.toString().matches("[0-9]{5,30}")) {
            Object type = DownloadActionHook.jsonValue(aweme, "aweme_type");
            return "https://www.douyin.com/" + ("68".equals(String.valueOf(type)) ? "note/" : "video/") + id;
        }
        throw new IllegalArgumentException("当前作品没有可读取的分享链接");
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> {
            try { Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }
            catch (RuntimeException ignored) { }
        });
    }
}

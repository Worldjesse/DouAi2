package com.douai.module.feature;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import java.util.function.Consumer;

/** Runs only after a user invokes a third-party download. */
public final class ThirdPartyDownload {
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
        boolean accepted = DownloadTasks.submit(context, "third-party\n" + key, "第三方作品", transfer -> {
                transfer.phase("正在解析作品");
                String body = new ThirdPartyGetClient.Request(transfer, MediaStoreDownloader.connections).fetch(config, link);
                transfer.check();
                ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(
                        ThirdPartyResponse.parse(body), config, System.currentTimeMillis());
                MediaStoreDownloader.saveThirdPartyBundle(context, plan, tree, transfer);
                return "已保存 " + plan.assets.size() + " 个文件和作品信息到 "
                        + (tree.isEmpty() ? "Download/DouAi" : "所选目录");
        }, log) != null;
        toast(context, accepted ? "已加入下载任务，可在设置中查看进度或取消" : "该作品正在下载或队列已满");
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

package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.util.Linkify;
import android.text.method.LinkMovementMethod;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.douai.module.BuildConfig;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

public final class AboutDialog {
    public static final String SOURCE_URL = "https://github.com/yudigaga/DouAi";
    public static final String RELEASES_URL = SOURCE_URL + "/releases";
    public static final String LSP_URL = "https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai";
    public static final String MIUIX_URL = "https://github.com/compose-miuix-ui/miuix";
    private AboutDialog() { }
    public static AlertDialog create(Context context, boolean miuix) {
        TextView content = new TextView(context);
        content.setText("DouAi " + BuildConfig.VERSION_NAME + "\n作者：yudigaga"
                + "\n\n源码仓库\n" + SOURCE_URL
                + "\n\n正式版下载\n" + RELEASES_URL
                + "\n\nLSPosed 模块仓库\n" + LSP_URL
                + "\n\n感谢 Miuix 提供 UI\n" + MIUIX_URL);
        content.setTextSize(14);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        Linkify.addLinks(content, Linkify.WEB_URLS);
        content.setMovementMethod(LinkMovementMethod.getInstance());
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        return new MiuixDialogBuilder(context, miuix).setTitle("关于 DouAi")
                .setView(scroll)
                .setNegativeButton("返回", null)
                .setNeutralButton("LSP 仓库", (dialog, which) -> open(context, LSP_URL))
                .setPositiveButton("源码仓库", (dialog, which) -> open(context, SOURCE_URL))
                .create();
    }
    private static void open(Context context, String url) {
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException | SecurityException failure) {
            Toast.makeText(context, "无法打开项目链接", Toast.LENGTH_SHORT).show();
        }
    }
}

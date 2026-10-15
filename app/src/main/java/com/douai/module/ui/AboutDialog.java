package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.util.Linkify;
import android.text.method.LinkMovementMethod;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;
import com.douai.module.BuildConfig;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixSettingsViews;
import java.util.function.BiConsumer;
import android.app.Dialog;

public final class AboutDialog {
    public static final String SOURCE_URL = "https://github.com/yudigaga/DouAi";
    public static final String RELEASES_URL = SOURCE_URL + "/releases";
    public static final String LSP_URL = "https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai";
    public static final String MIUIX_URL = "https://github.com/compose-miuix-ui/miuix";
    public static final String DISCLAIMER_TEXT = "本项目仅用于学习与技术研究，按现状提供。"
            + "用户的应用、配置、二次开发，以及使用本项目生成、下载、发送或发布内容的行为和结果，"
            + "均由用户自行决定并承担责任，与本项目及作者无关，不代表本项目或作者的立场、授权或背书。\n\n"
            + "用户应遵守适用法律法规、平台规则及相关服务协议，尊重他人的隐私、知识产权和其他合法权益，"
            + "不得将本项目用于违法违规或侵权活动。\n\n"
            + "本项目不保证功能、兼容性、服务可用性或使用结果。"
            + "在适用法律允许的范围内，作者及贡献者不对使用或无法使用本项目造成的损失承担责任。"
            + "本说明不排除法律规定不能排除的责任，也不授予任何第三方平台、内容或服务的使用权。";
    private AboutDialog() { }
    public static AlertDialog create(Context context, boolean miuix) {
        return create(context, miuix, (dialog, cleanup) -> {
            if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
            dialog.show();
        });
    }
    static AlertDialog create(Context baseContext, boolean miuix, BiConsumer<Dialog, Runnable> presenter) {
        Context context = ModuleTheme.themedContext(baseContext);
        TextView content = new TextView(context);
        content.setTag("about.details");
        content.setText("DouAi " + BuildConfig.VERSION_NAME + "\n作者：yudigaga"
                + "\n\n源码仓库\n" + SOURCE_URL
                + "\n\n正式版下载\n" + RELEASES_URL
                + "\n\nLSPosed 模块仓库\n" + LSP_URL
                + "\n\n感谢 Miuix 提供 UI\n" + MIUIX_URL
                + "\n\n免责协议\n" + DISCLAIMER_TEXT);
        content.setTextSize(14);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        Linkify.addLinks(content, Linkify.WEB_URLS);
        content.setMovementMethod(LinkMovementMethod.getInstance());
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        HelpDialog[] help = new HelpDialog[1];
        Runnable openHelp = () -> {
            if (help[0] != null && help[0].dialog().isShowing()) return;
            help[0] = new HelpDialog(context, miuix);
            presenter.accept(help[0].dialog(), null);
        };
        View helpRow;
        if (miuix) {
            helpRow = new MiuixSettingsViews.Option(context, "帮助", "", null, true, openHelp).getView();
        } else {
            TextView label = new TextView(context);
            label.setText("帮助");
            label.setTextSize(16);
            label.setPadding(padding, padding / 2, padding, padding / 2);
            label.setMinimumHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
            label.setFocusable(true);
            label.setOnClickListener(ignored -> openHelp.run());
            helpRow = label;
        }
        helpRow.setTag("about.help");
        helpRow.setContentDescription("帮助");
        body.addView(helpRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(content);
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("about.page");
        scroll.addView(body);
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                if (help[0] != null) help[0].dialog().dismiss();
            }
        });
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

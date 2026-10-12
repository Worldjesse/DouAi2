package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.ViewGroup;

import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AutomationHistoryStore.Entry;

import java.text.DateFormat;
import java.util.Date;
import com.douai.module.ui.miuix.HostComposeContext;
import com.douai.module.ui.miuix.MiuixSettingsViews;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

/** Read-only records independent of the model's in-memory conversation context. */
final class AutomationHistoryDialog {
    private final Context context;
    private final Context rowContext;
    private final AutomationHistoryStore store;
    private final String kind;
    private final boolean miuix;
    private final LinearLayout rows;
    private final AlertDialog dialog;
    private AlertDialog detail;

    AutomationHistoryDialog(Context context, String kind) {
        this(context, kind, false);
    }

    AutomationHistoryDialog(Context context, String kind, boolean miuix) {
        this.context = context;
        this.kind = kind;
        Activity owner = ownerActivity(context);
        Context styledContext = context;
        boolean styled = false;
        if (miuix && owner != null && !owner.isFinishing() && !owner.isDestroyed()) {
            try {
                styledContext = HostComposeContext.create(owner);
                styled = true;
            } catch (RuntimeException unavailable) {
                styledContext = context;
            }
        }
        rowContext = styledContext;
        this.miuix = styled;
        store = new AutomationHistoryStore(context);
        rows = new LinearLayout(context);
        rows.setTag("automation.history");
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(this.miuix ? 0 : dp(16), dp(8), this.miuix ? 0 : dp(16), dp(8));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(rows);
        dialog = new MiuixDialogBuilder(context, this.miuix)
                .setTitle(AutomationHistoryStore.REPLY.equals(kind) ? "回复历史记录" : "评论历史记录")
                .setView(scroll).setNegativeButton("关闭", null).setNeutralButton("清空记录", null).create();
        if (owner != null) dialog.setOwnerActivity(owner);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            store.clear(kind);
            refresh();
        }));
        dialog.setOnDismissListener(ignored -> closeDetails());
        refresh();
    }

    AlertDialog dialog() { return dialog; }

    private void refresh() {
        rows.removeAllViews();
        for (Entry entry : store.read(kind)) {
            if (miuix) {
                MiuixSettingsViews.Option option = new MiuixSettingsViews.Option(rowContext,
                        entry.targetName + " · " + status(entry.status),
                        DateFormat.getDateTimeInstance().format(new Date(entry.createdAt)),
                        entry.output, true, () -> showDetail(entry));
                View row = option.getView();
                row.setTag("automation.history." + entry.id);
                rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                continue;
            }
            TextView row = label(entry.targetName + " · " + status(entry.status) + "\n"
                    + DateFormat.getDateTimeInstance().format(new Date(entry.createdAt)) + "\n" + entry.output);
            row.setTag("automation.history." + entry.id);
            row.setMaxLines(5);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(0, dp(12), 0, dp(12));
            row.setOnClickListener(ignored -> showDetail(entry));
            rows.addView(row);
            android.view.View line = new android.view.View(context);
            line.setBackgroundColor(Color.rgb(226, 231, 232));
            rows.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        if (rows.getChildCount() == 0) rows.addView(label("暂无记录"));
    }

    private void showDetail(Entry entry) {
        if (detail != null) detail.dismiss();
        TextView text = label("对象：" + entry.targetName + "\n标识：" + entry.targetId + "\n状态："
                + status(entry.status) + "\n模型：" + entry.modelName + "\n时间："
                + DateFormat.getDateTimeInstance().format(new Date(entry.createdAt))
                + "\n\n原内容\n" + entry.input + "\n\n生成内容\n" + entry.output);
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        AlertDialog next = new MiuixDialogBuilder(context, miuix).setTitle("历史详情").setView(scroll)
                .setNegativeButton("关闭", null).create();
        if (dialog.getOwnerActivity() != null) next.setOwnerActivity(dialog.getOwnerActivity());
        next.setOnDismissListener(ignored -> { if (detail == next) detail = null; });
        detail = next;
        next.show();
    }

    /** Also use as the parent's dismiss callback when its owner replaces the listener. */
    void closeDetails() {
        AlertDialog child = detail;
        detail = null;
        if (child != null) child.dismiss();
    }

    static String status(String status) {
        switch (status) {
            case "sent": return "发送成功";
            case "submitted": return "已提交";
            case "failed": return "失败";
            case "cancelled": return "已取消";
            case "sending": return "结果未确认";
            default: return "尚未提交";
        }
    }

    private TextView label(String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(31, 39, 45));
        return view;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static Activity ownerActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) return null;
            current = next;
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}

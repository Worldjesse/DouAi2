package com.douai.module.feature;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import com.douai.module.ui.ModuleTheme;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Lets a user choose among an already resolved, configured third-party media plan. */
final class ThirdPartyDownloadDialog implements AutoCloseable {
    private Context context;
    private ThirdPartyDownloadPlan plan;
    private Consumer<ThirdPartyDownloadPlan> onSave;
    private Runnable onCancel;
    private BooleanSupplier valid;
    private AlertDialog dialog;
    private boolean closed;
    private long navigation;

    ThirdPartyDownloadDialog(Context context, ThirdPartyDownloadPlan plan,
                             Consumer<ThirdPartyDownloadPlan> onSave, Runnable onCancel,
                             BooleanSupplier valid) {
        this.context = ModuleTheme.themedContext(context);
        this.plan = plan;
        this.onSave = onSave == null ? ignored -> { } : onSave;
        this.onCancel = onCancel == null ? () -> { } : onCancel;
        this.valid = valid == null ? () -> true : valid;
    }

    void show() {
        if (!available()) return;
        List<Choice> choices = new ArrayList<>();
        for (ThirdPartyDownloadPlan.Category category : ThirdPartyDownloadPlan.Category.values()) {
            List<Integer> indexes = indexes(category);
            if (!indexes.isEmpty()) {
                choices.add(new Choice(label(category) + "（" + indexes.size() + "）",
                        () -> showCategory(category, indexes)));
            }
        }
        if (choices.isEmpty()) { cancelUser(); return; }
        showChoices("第三方下载", choices, null);
    }

    private List<Integer> indexes(ThirdPartyDownloadPlan.Category category) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < plan.assets.size(); index++) {
            if (plan.assets.get(index).category() == category) result.add(index);
        }
        return Collections.unmodifiableList(result);
    }

    private void showCategory(ThirdPartyDownloadPlan.Category category, List<Integer> indexes) {
        if (!available()) return;
        String name = label(category);
        List<Choice> choices = new ArrayList<>();
        if (indexes.size() == 1) {
            ThirdPartyDownloadPlan.Asset asset = plan.assets.get(indexes.get(0));
            String source = asset.fieldPath.isEmpty() ? "" : " · " + sourceLabel(asset);
            choices.add(new Choice("保存" + name + source, () -> save(indexes)));
        } else {
            choices.add(new Choice("选择" + name + "保存", () -> showSelection(category, indexes)));
            choices.add(new Choice("保存全部" + name, () -> save(indexes)));
        }
        showChoices(name, choices, this::show);
    }

    private void showChoices(String title, List<Choice> choices, Runnable back) {
        if (!available()) return;
        long page = ++navigation;
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i).label;
        AlertDialog.Builder builder = new MiuixDialogBuilder(context, true).setTitle(title)
                .setItems(labels, (ignored, which) -> {
                    if (navigation == page && available() && which >= 0 && which < choices.size()) {
                        choices.get(which).action.run();
                    }
                })
                .setNegativeButton("取消", (ignored, which) -> {
                    if (navigation == page) cancelUser();
                });
        if (back != null) builder.setNeutralButton("返回", (ignored, which) -> {
            if (navigation == page && available()) back.run();
        });
        present(builder.create(), page);
    }

    private void showSelection(ThirdPartyDownloadPlan.Category category, List<Integer> indexes) {
        if (!available()) return;
        long page = ++navigation;
        String[] labels = new String[indexes.size()];
        boolean[] selected = new boolean[indexes.size()];
        for (int i = 0; i < labels.length; i++) {
            ThirdPartyDownloadPlan.Asset asset = plan.assets.get(indexes.get(i));
            labels[i] = sourceLabel(asset);
        }
        AlertDialog next = new MiuixDialogBuilder(context, true).setTitle("选择" + label(category))
                .setMultiChoiceItems(labels, selected, (ignored, which, checked) -> {
                    if (navigation == page && available() && which >= 0 && which < selected.length) {
                        selected[which] = checked;
                    }
                })
                .setNegativeButton("取消", (ignored, which) -> {
                    if (navigation == page) cancelUser();
                })
                .setNeutralButton("返回", (ignored, which) -> {
                    if (navigation == page && available()) showCategory(category, indexes);
                })
                .setPositiveButton("保存", null).create();
        next.setOnShowListener(ignored -> next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (navigation != page || !available()) return;
            List<Integer> chosen = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(indexes.get(i));
            if (chosen.isEmpty()) {
                Toast.makeText(context, "请先选择要保存的媒体", Toast.LENGTH_SHORT).show();
                return;
            }
            save(chosen);
        }));
        present(next, page);
    }

    private void present(AlertDialog next, long page) {
        AlertDialog previous = dialog;
        dialog = next;
        next.setOnCancelListener(ignored -> {
            if (dialog == next && navigation == page) cancelUser();
        });
        next.setOnDismissListener(ignored -> {
            if (dialog == next && navigation == page && !closed) cancelUser();
        });
        if (previous != null) previous.dismiss();
        next.show();
    }

    private void save(List<Integer> indexes) {
        if (!available()) return;
        ThirdPartyDownloadPlan selected = plan.selectAssets(indexes);
        Consumer<ThirdPartyDownloadPlan> callback = onSave;
        close();
        callback.accept(selected);
    }

    private boolean available() {
        if (closed || context == null || plan == null) return false;
        boolean active;
        try { active = valid.getAsBoolean(); }
        catch (RuntimeException ignored) { active = false; }
        if (!active) { cancelUser(); return false; }
        return true;
    }

    private void cancelUser() {
        if (closed) return;
        Runnable callback = onCancel;
        close();
        if (callback != null) callback.run();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        navigation++;
        AlertDialog previous = dialog;
        dialog = null;
        if (previous != null) previous.dismiss();
        context = null;
        plan = null;
        onSave = null;
        onCancel = null;
        valid = null;
    }

    private static String label(ThirdPartyDownloadPlan.Category category) {
        switch (category) {
            case IMAGE: return "图片";
            case VIDEO: return "视频";
            case MOTION: return "动态视频";
            case AUDIO: return "音频";
            default: return "文件";
        }
    }

    private static String sourceLabel(ThirdPartyDownloadPlan.Asset asset) {
        String position = "第 " + (asset.sourceIndex + 1) + " 项";
        return asset.fieldPath.isEmpty() ? position : asset.fieldPath + " · " + position;
    }

    private static final class Choice {
        final String label;
        final Runnable action;
        Choice(String label, Runnable action) { this.label = label; this.action = action; }
    }
}

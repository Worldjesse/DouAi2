package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.util.List;

/** Model profile switcher surfaced by the feed gesture menu. */
public final class GestureQuickModelDialog {
    private GestureQuickModelDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable openSettings) {
        AiModelSettings settings;
        try {
            settings = AiModelSettings.read(store);
        } catch (RuntimeException invalid) {
            return new MiuixDialogBuilder(activity, true).setTitle("切换模型")
                    .setMessage("模型配置无法读取，请到模块设置中检查。")
                    .setNegativeButton("关闭", null).create();
        }
        List<AiModelConfig> configs = settings.configs;
        if (configs.isEmpty()) {
            return new MiuixDialogBuilder(activity, true).setTitle("切换模型")
                    .setMessage("尚未添加 AI 模型配置。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("打开模块设置", (dialog, which) -> openSettings.run())
                    .create();
        }

        String[] labels = new String[configs.size()];
        int selected = -1;
        for (int index = 0; index < configs.size(); index++) {
            AiModelConfig config = configs.get(index);
            labels[index] = config.name + " · " + config.modelName;
            if (config.id.equals(settings.activeId)) selected = index;
        }
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle("切换模型");
        builder.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    try {
                        activate(store, configs.get(index).id);
                        Toast.makeText(activity, "已切换到 " + configs.get(index).name, Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException failure) {
                        Toast.makeText(activity, failure.getMessage() == null ? "模型切换失败" : failure.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                    dialog.dismiss();
                });
        builder.setNegativeButton("关闭", null);
        builder.setPositiveButton("模块设置", (dialog, which) -> openSettings.run());
        return builder.create();
    }

    static void activate(SettingsStore store, String modelId) {
        AiModelSettings.read(store).activate(modelId).save(store);
    }
}

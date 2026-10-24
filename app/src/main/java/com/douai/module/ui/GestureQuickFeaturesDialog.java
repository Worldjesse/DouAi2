package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.douai.module.config.AutomationMessageSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixSettingsViews;

import java.util.Map;
import java.util.function.Consumer;

/** Compact gesture panel for frequently used comment, reply, and speech settings. */
public final class GestureQuickFeaturesDialog {
    private GestureQuickFeaturesDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable switchVoice,
                                     Consumer<String> onError) {
        AutomationMessageSettings updates = new AutomationMessageSettings(store, null);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        MiuixSettingsViews.Toggle autoComment = new MiuixSettingsViews.Toggle(activity, "自动评论",
                "对匹配的推荐内容自动评论。", store.getBoolean("native_auto_comment", false), true,
                enabled -> setEnabled(updates, "comment", enabled, onError));
        content.addView(autoComment.getView(), row);
        MiuixSettingsViews.Toggle autoReply = new MiuixSettingsViews.Toggle(activity, "自动回复",
                "对符合当前接收范围的私信自动回复。", store.getBoolean("native_auto_reply", false), true,
                enabled -> setEnabled(updates, "reply", enabled, onError));
        content.addView(autoReply.getView(), row);

        SpeechSettings speech = speechSettings(store);
        String voice = speech.minimax ? speech.minimaxVoiceName.isEmpty() ? speech.minimaxVoiceId
                : speech.minimaxVoiceName + " · " + speech.minimaxVoiceId
                : speech.thirdParty ? speech.selectedVoiceName.isEmpty() ? speech.selectedVoice
                : speech.selectedVoiceName + " · " + speech.selectedVoice : "系统语音引擎";
        MiuixSettingsViews.Option voiceOption = new MiuixSettingsViews.Option(activity, "切换音色",
                speech.minimax ? "MiniMax" : speech.thirdParty ? "第三方语音" : "系统语音引擎",
                voice, true, switchVoice);
        content.addView(voiceOption.getView(), row);

        AlertDialog dialog = new MiuixDialogBuilder(activity, true).setTitle("功能快捷")
                .setView(content).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(ignored -> updates.close());
        return dialog;
    }

    private static void setEnabled(AutomationMessageSettings settings, String kind,
                                   boolean enabled, Consumer<String> onError) {
        try {
            settings.setEnabled(kind, enabled);
        } catch (RuntimeException failure) {
            if (onError != null) onError.accept(failure.getMessage() == null ? "设置保存失败" : failure.getMessage());
        }
    }

    private static SpeechSettings speechSettings(SettingsStore store) {
        try {
            Map<String, ?> snapshot = store.snapshot();
            return new SpeechSettings(new LocalOptions(snapshot));
        } catch (RuntimeException unavailable) {
            return new SpeechSettings(new LocalOptions(java.util.Collections.emptyMap()));
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

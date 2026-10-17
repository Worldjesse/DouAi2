package com.douai.module.ui;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.os.Bundle;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;

/** Opens a module-owned conversation for a saved model on the host message page. */
public final class AiConversationDialog {
    private AiConversationDialog() { }

    public static Dialog show(Activity activity, SettingsStore store, AiModelConfig requested) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        AiModelConfig config = null;
        for (AiModelConfig saved : AiModelSettings.read(store).configs) {
            if (saved.id.equals(requested.id) && saved.messageContactEnabled) { config = saved; break; }
        }
        if (config == null) return null;
        AiModelConfig snapshot = config;
        AiTestChatDialog dialog = new AiTestChatDialog(activity, snapshot, true, true);
        dialog.setOwnerActivity(activity);
        dialog.setContactEditAction(() -> {
            dialog.dismiss();
            edit(activity, store, snapshot);
        });
        dialog.show();
        return dialog;
    }

    static AiModelEditor contactEditor(Activity activity, SettingsStore store, AiModelConfig config) {
        return new AiModelEditor(activity, store, config, true, () -> { }, false, () -> {
            AiModelSettings current = AiModelSettings.read(store);
            for (AiModelConfig candidate : current.configs) {
                if (candidate.id.equals(config.id) && !candidate.toJson().toString().equals(config.toJson().toString())) {
                    throw new IllegalStateException("模型配置已在其他位置修改，请关闭后重新打开联系人再删除");
                }
            }
            current.delete(config.id).save(store);
        });
    }

    private static void edit(Activity activity, SettingsStore store, AiModelConfig config) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        AiModelEditor editor = contactEditor(activity, store, config);
        Application application = activity.getApplication();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity owner) {
                if (owner == activity && !HostImagePicker.isPending(owner)) editor.close();
            }
            @Override public void onActivityDestroyed(Activity owner) { if (owner == activity) editor.close(); }
            @Override public void onActivityCreated(Activity owner, Bundle state) { }
            @Override public void onActivityStarted(Activity owner) { }
            @Override public void onActivityResumed(Activity owner) { }
            @Override public void onActivityStopped(Activity owner) { }
            @Override public void onActivitySaveInstanceState(Activity owner, Bundle state) { }
        };
        editor.show((child, close) -> {
            child.setOwnerActivity(activity);
            child.setOnDismissListener(ignored -> {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                close.run();
            });
            application.registerActivityLifecycleCallbacks(lifecycle);
            try {
                child.show();
                HostDialogBounds.install(activity, child, false);
            } catch (RuntimeException failure) {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                throw failure;
            }
        });
    }
}

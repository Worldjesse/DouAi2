package com.douai.module;

import android.content.pm.ApplicationInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.app.Application;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.douai.module.scan.HostRuleSet;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanCache;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostScanner;
import com.douai.module.scan.StartupHostCache;
import com.douai.module.scan.HostInstallation;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.ConfigurationChanges;
import com.douai.module.config.ConfigurationSnapshot;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.FrameworkConfigSync;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.feature.HostBehaviorHooks;
import com.douai.module.feature.PlaybackBehaviorHooks;
import com.douai.module.feature.ProfileSpoofHook;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.feature.RecallHighlightHook;
import com.douai.module.feature.FeedGestureToolsHook;
import com.douai.module.feature.DirectMessageSpeechHook;
import com.douai.module.feature.MediaModelHook;
import com.douai.module.feature.CommentEnhancementsHook;
import com.douai.module.feature.CommentVoiceHook;
import com.douai.module.feature.NativeCommentSpeechHook;
import com.douai.module.config.SettingsRepository;
import com.douai.module.config.HostSettingsMigration;
import com.douai.module.diagnostics.ScanDiagnosticsChannel;
import com.douai.module.diagnostics.HostAdaptationDiagnostics;
import com.douai.module.feature.FeedAdFilterHook;
import com.douai.module.feature.DownloadActionHook;
import com.douai.module.feature.VersionInfoHook;
import com.douai.module.feature.TopTabFilterHook;
import com.douai.module.feature.FeedUiHideHook;
import com.douai.module.feature.RefreshConfirmHook;
import com.douai.module.feature.AutoLikeHook;
import com.douai.module.feature.AutoFollowHook;
import com.douai.module.feature.AutoPlayHook;
import com.douai.module.feature.AutoCommentHook;
import com.douai.module.feature.AutomationDispatcher;
import com.douai.module.feature.AutoMessageDraftHook;
import com.douai.module.feature.CommentToolsHook;
import com.douai.module.feature.ImmersiveModeController;
import com.douai.module.feature.HomeNavigationHideController;
import com.douai.module.ui.HostUiController;
import io.github.libxposed.api.XposedInterface;

public final class DouAiModule extends XposedModule {
    private static final String TARGET_PACKAGE = HostAdaptation.TARGET_PACKAGE;
    private static final String TAG = "DouAi";
    private volatile boolean isMainProcess;
    private volatile boolean scanScheduled;
    private volatile boolean configurationReady;
    private volatile BroadcastReceiver diagnosticsReceiver;
    private volatile Bundle scanDiagnostics = new Bundle();
    private volatile Bundle hostVersionEvidence = new Bundle();
    private volatile String hostRulesFingerprint;
    private volatile SharedPreferences hostPreferences;
    private volatile Context targetContext;
    private volatile ApplicationInfo targetApplicationInfo;
    private volatile SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private volatile FeatureSettings currentSettings = FeatureSettings.defaults();
    private volatile ExtendedSettings extendedSettings;
    private volatile LocalOptions localOptions = new LocalOptions(java.util.Collections.emptyMap());
    private Map<String, ?> lastConfigurationValues;
    private boolean configurationUpdatePending;
    private volatile HostBehaviorHooks hostBehaviorHooks;
    private volatile PlaybackBehaviorHooks playbackBehaviorHooks;
    private volatile ProfileSpoofHook profileSpoofHook;
    private volatile NativeMessagingHooks nativeMessagingHooks;
    private volatile NativeFollowLists nativeFollowLists;
    private volatile AutomationDispatcher automationDispatcher;
    private volatile RecallHighlightHook recallHighlightHook;
    private volatile FeedGestureToolsHook feedGestureToolsHook;
    private volatile DirectMessageSpeechHook directMessageSpeechHook;
    private volatile boolean additionalInitialized;
    private volatile MediaModelHook mediaModelHook;
    private volatile CommentEnhancementsHook commentEnhancementsHook;
    private volatile CommentVoiceHook commentVoiceHook;
    private volatile NativeCommentSpeechHook nativeCommentSpeechHook;
    private volatile HostScanReport hostScanReport;
    private volatile ClassLoader hostClassLoader;
    private volatile FeedAdFilterHook feedAdFilterHook;
    private volatile DownloadActionHook downloadActionHook;
    private volatile VersionInfoHook versionInfoHook;
    private volatile TopTabFilterHook topTabFilterHook;
    private volatile FeedUiHideHook feedUiHideHook;
    private volatile RefreshConfirmHook refreshConfirmHook;
    private volatile AutoLikeHook autoLikeHook;
    private volatile AutoFollowHook autoFollowHook;
    private volatile AutoPlayHook autoPlayHook;
    private volatile AutoCommentHook autoCommentDraftHook;
    private volatile AutoMessageDraftHook autoMessageDraftHook;
    private volatile CommentToolsHook commentToolsHook;
    private volatile ImmersiveModeController immersiveModeController;
    private volatile HomeNavigationHideController homeNavigationHideController;
    private volatile HostUiController hostUiController;
    private volatile XposedInterface.HookHandle applicationCreateHandle;
    private volatile XposedInterface.HookHandle activityResumeHandle;
    private volatile String feedHookFailure;
    private volatile String downloadHookFailure;
    private volatile String versionHookFailure;
    private volatile String topTabHookFailure;
    private volatile String feedUiHookFailure;
    private volatile String refreshHookFailure;
    private volatile String autoLikeHookFailure;
    private volatile String autoFollowHookFailure;
    private volatile String autoPlayHookFailure;
    private volatile String autoCommentDraftHookFailure;
    private volatile String autoMessageDraftHookFailure;
    private volatile String commentToolsHookFailure;
    private volatile String immersiveModeFailure;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        isMainProcess = TARGET_PACKAGE.equals(param.getProcessName());
        log(3, TAG, "DouAi " + BuildConfig.VERSION_NAME + " / API " + getApiVersion()
                + " loaded in " + param.getProcessName());
        if (isMainProcess) installHostApplicationHook();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!isMainProcess || !TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()
                || scanScheduled) {
            return;
        }

        ApplicationInfo appInfo = param.getApplicationInfo();
        if (appInfo == null || !"com.ss.android.ugc.aweme".equals(appInfo.packageName)) {
            log(6, TAG, "Target package metadata is unavailable");
            return;
        }

        scanScheduled = true;
        targetApplicationInfo = appInfo;
        installHostApplicationHook();
        List<File> archives = getApkArchives(appInfo);
        Thread thread = new Thread(() -> scanHost(archives), "DouAi-DexScanner");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!isMainProcess || !TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        hostClassLoader = param.getClassLoader();
        if (hostClassLoader == null) {
            feedHookFailure = "抖音 ClassLoader 不可用";
            downloadHookFailure = "抖音 ClassLoader 不可用";
            return;
        }
        installEarlyCachedHooks();
        installFeatureHooksIfReady();
    }

    private synchronized void installEarlyCachedHooks() {
        ApplicationInfo info = targetApplicationInfo;
        if (info == null || info.dataDir == null || hostBehaviorHooks != null) return;
        try {
            File cache = new File(info.dataDir, "no_backup/douai/host-scan-v2.cache");
            List<File> archives = getApkArchives(info);
            HostScanReport report = StartupHostCache.load(cache, archives, loadRulesFromModuleApk(),
                    installationIdentity(archives));
            if (report == null) return;
            hostScanReport = report;
            hostBehaviorHooks = HostBehaviorHooks.install(this, report, hostClassLoader, null,
                    localOptions, message -> log(3, TAG, message));
            log(3, TAG, "Validated startup cache; basic host gates registered before Application.onCreate");
        } catch (Exception failure) {
            log(5, TAG, "Startup cache unavailable; asynchronous scan retained: " + failure.getClass().getSimpleName());
        }
    }

    private void scanHost(List<File> archives) {
        try {
            Context hostContext = awaitHostContext();
            targetContext = hostContext;
            hostVersionEvidence = HostAdaptationDiagnostics.readInstalledVersion(hostContext);
            HostUiController ui = ensureHostUiController(hostContext);
            registerDiagnosticsReceiver(hostContext);
            HostRuleSet rules = loadRulesFromModuleApk();
            File cacheFile = new File(new File(hostContext.getNoBackupFilesDir(), "douai"),
                    "host-scan-v2.cache");
            SharedPreferences modulePreferences = getRemotePreferences(FrameworkConfigSync.PREFERENCES_GROUP);
            SharedPreferences preferences = hostContext.getSharedPreferences(
                    SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (HostSettingsMigration.copyOnce(modulePreferences, preferences)) {
                log(3, TAG, "Copied existing module settings to independent Douyin-local storage");
            }
            registerSettingsListener(preferences);
            updateConfiguration(preferences);
            configurationReady = true;
            HostScanReport report = new HostScanner().scanCached(
                    archives, rules, new HostScanCache(cacheFile), new HostScanner.ProgressListener() {
                        @Override
                        public void onCacheHit(int dexCount) {
                            HostUiController controller = hostUiController;
                            if (controller != null) controller.onCacheHit(dexCount);
                        }

                        @Override
                        public void onCacheSaveFailed(Exception failure) {
                            log(5, TAG, "Host scan cache could not be saved: "
                                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                        }

                        @Override
                        public void onScanStarted(int dexCount) {
                            HostUiController controller = hostUiController;
                            if (controller != null) controller.onScanStarted(dexCount);
                        }

                        @Override
                        public void onDexScanned(int completed, int total, String dexName) {
                            HostUiController controller = hostUiController;
                            if (controller != null) controller.onDexScanned(completed, total, dexName);
                        }
                    }, installationIdentity(archives));
            hostScanReport = report;
            log(3, TAG, "Host scan complete: " + report.summary());
            installFeatureHooksIfReady();
            publishDiagnostics(report, null);
            HostUiController completedUi = hostUiController;
            if (completedUi != null) completedUi.complete(report.isCacheHit(), report.summary(),
                    report.getHostIdentity());
            if (rules.getRules().isEmpty()) {
                log(5, TAG, "No verified host rules are packaged; no host methods were selected");
            } else if (!report.isCacheHit()) {
                for (com.douai.module.scan.RuleScanResult result : report.getRuleResults()) {
                    log(3, TAG, result.getRuleId() + "=" + result.getStatus()
                            + " candidates=" + result.getCandidates());
                }
            }
        } catch (Exception exception) {
            log(6, TAG, "Host scan failed", exception);
            publishDiagnostics(null, exception.getMessage());
            HostUiController ui = hostUiController;
            if (ui != null) ui.fail(exception.getMessage());
        }
    }

    private synchronized void installHostApplicationHook() {
        if (applicationCreateHandle == null) {
            try {
                java.lang.reflect.Method onCreate = Instrumentation.class.getDeclaredMethod(
                        "callApplicationOnCreate", Application.class);
                applicationCreateHandle = hook(onCreate)
                        .setId("DouAi.Host.ApplicationUi")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object application = chain.getArg(0);
                            if (hostBehaviorHooks != null && application instanceof Application) {
                                try {
                                    SharedPreferences preferences = ((Application) application).getSharedPreferences(
                                            SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
                                    localOptions = LocalOptions.readFrom(preferences);
                                    hostBehaviorHooks.setOptions(localOptions);
                                } catch (RuntimeException failure) {
                                    log(5, TAG, "Could not read startup options: " + failure.getClass().getSimpleName());
                                }
                            }
                            Object result = chain.proceed();
                            if (application instanceof Application) {
                                try {
                                    ensureHostUiController((Application) application);
                                } catch (Throwable failure) {
                                    log(5, TAG, "Could not initialize in-app controls: "
                                            + failure.getMessage());
                                }
                            }
                            return result;
                        });
                if (applicationCreateHandle == null) {
                    throw new IllegalStateException("Framework returned an empty Application hook handle");
                }
            } catch (Throwable failure) {
                applicationCreateHandle = null;
                log(5, TAG, "Could not hook target Application startup", failure);
            }
        }
        if (activityResumeHandle == null) {
            try {
                java.lang.reflect.Method onResume = Activity.class.getDeclaredMethod("onResume");
                activityResumeHandle = hook(onResume)
                        .setId("DouAi.Host.ActivityUi")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object activity = chain.getThisObject();
                            if (activity instanceof Activity) {
                                try {
                                    HostUiController controller = ensureHostUiController(
                                            ((Activity) activity).getApplication());
                                    if (controller != null) controller.onActivityResumed((Activity) activity);
                                } catch (Throwable failure) {
                                    log(5, TAG, "Could not attach controls to resumed Activity: "
                                            + failure.getMessage());
                                }
                            }
                            return result;
                        });
                if (activityResumeHandle == null) {
                    throw new IllegalStateException("Framework returned an empty Activity hook handle");
                }
            } catch (Throwable failure) {
                activityResumeHandle = null;
                log(5, TAG, "Could not hook Activity resume", failure);
            }
        }
    }

    private synchronized HostUiController ensureHostUiController(Context context) {
        if (hostUiController != null) return hostUiController;
        Context appContext = context == null ? null : context.getApplicationContext();
        if (!(appContext instanceof Application)
                || !TARGET_PACKAGE.equals(appContext.getPackageName())) return null;
        return ensureHostUiController((Application) appContext);
    }

    private synchronized HostUiController ensureHostUiController(Application application) {
        if (hostUiController != null) return hostUiController;
        if (application == null || !TARGET_PACKAGE.equals(application.getPackageName())) return null;
        hostUiController = new HostUiController(application, message -> log(3, TAG, message));
        return hostUiController;
    }

    private Context awaitHostContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method currentApplication = activityThread.getMethod("currentApplication");
        for (int attempt = 0; attempt < 60; attempt++) {
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                Context context = (Context) application;
                if (TARGET_PACKAGE.equals(context.getPackageName())) return context;
            }
            Thread.sleep(100L);
        }
        throw new IOException("Douyin Application context was not available");
    }

    private HostRuleSet loadRulesFromModuleApk() throws IOException {
        ApplicationInfo moduleInfo = getModuleApplicationInfo();
        if (moduleInfo == null || moduleInfo.sourceDir == null) {
            throw new IOException("Module APK path is unavailable");
        }
        try (ZipFile moduleApk = new ZipFile(moduleInfo.sourceDir)) {
            ZipEntry entry = moduleApk.getEntry("assets/" + HostAdaptation.RULES_ASSET);
            if (entry == null) throw new IOException("Rule file is missing from module APK");
            try (InputStream input = moduleApk.getInputStream(entry)) {
                HostRuleSet rules = HostRuleSet.parse(input);
                hostRulesFingerprint = rules.getFingerprint();
                return rules;
            }
        }
    }

    private String installationIdentity(List<File> archives) throws IOException {
        ApplicationInfo module = getModuleApplicationInfo();
        return HostInstallation.identity(BuildConfig.VERSION_NAME + ":" + BuildConfig.VERSION_CODE,
                module == null || module.sourceDir == null ? null : new File(module.sourceDir), archives);
    }

    private void publishDiagnostics(HostScanReport report, String failure) {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("complete", report != null);
        snapshot.putLong("completed_at", System.currentTimeMillis());
        if (failure != null) snapshot.putString("failure", failure);
        if (report != null) {
            snapshot.putString("identity", report.getHostIdentity());
            snapshot.putString("summary", report.summary());
            snapshot.putBoolean("cache_hit", report.isCacheHit());
            int unique = 0;
            int ambiguous = 0;
            int notFound = 0;
            for (com.douai.module.scan.RuleScanResult result : report.getRuleResults()) {
                String prefix = "rule." + result.getRuleId();
                snapshot.putString(prefix + ".status", result.getStatus().name());
                if (result.getStatus() == com.douai.module.scan.RuleScanResult.Status.UNIQUE) unique++;
                else if (result.getStatus() == com.douai.module.scan.RuleScanResult.Status.AMBIGUOUS) ambiguous++;
                else notFound++;
                StringBuilder candidates = new StringBuilder();
                for (com.douai.module.scan.HostCandidate candidate : result.getCandidates()) {
                    if (candidates.length() > 0) candidates.append('\n');
                    candidates.append(candidate);
                }
                snapshot.putString(prefix + ".candidates", candidates.toString());
            }
            snapshot.putInt("unique_count", unique);
            snapshot.putInt("ambiguous_count", ambiguous);
            snapshot.putInt("not_found_count", notFound);
            snapshot.putBoolean("all_rules_unique",
                    !report.getRuleResults().isEmpty() && unique == report.getRuleResults().size());
        }
        scanDiagnostics = snapshot;
        log(3, TAG, "Scan diagnostics snapshot updated: complete=" + (report != null));
    }

    private synchronized void registerDiagnosticsReceiver(Context hostContext) {
        if (diagnosticsReceiver != null) return;
        diagnosticsReceiver = ScanDiagnosticsChannel.registerHost(hostContext,
                this::diagnosticsSnapshot);
    }

    private synchronized void registerSettingsListener(SharedPreferences preferences) {
        if (hostPreferences == preferences && settingsListener != null) return;
        hostPreferences = preferences;
        SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, key) ->
                scheduleConfigurationUpdate();
        try {
            preferences.registerOnSharedPreferenceChangeListener(listener);
            settingsListener = listener;
        } catch (RuntimeException exception) {
            settingsListener = null;
            log(5, TAG, "Live configuration listener unavailable: " + exception.getMessage());
        }
    }

    private synchronized void scheduleConfigurationUpdate() {
        if (configurationUpdatePending) return;
        configurationUpdatePending = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (DouAiModule.this) {
                configurationUpdatePending = false;
                if (hostPreferences != null) updateConfiguration(hostPreferences);
            }
        });
    }

    private synchronized void updateConfiguration(SharedPreferences preferences) {
        try {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.readFrom(preferences);
            Map<String, ?> values = snapshot.values;
            FeatureSettings settings = snapshot.features;
            ExtendedSettings extended = snapshot.extended;
            LocalOptions options = snapshot.options;

            boolean initial = lastConfigurationValues == null;
            ConfigurationChanges changes = ConfigurationChanges.between(lastConfigurationValues, values);
            if (!initial && changes.isEmpty()) return;

            currentSettings = settings;
            extendedSettings = extended;
            localOptions = options;
            lastConfigurationValues = values;

            boolean feedFilterChanged = initial || changes.any("feed_filter_enabled", "hide_ads", "hide_lives",
                    "hide_image_posts", "hide_recommendation_cards", "hide_recommendation_merchants",
                    "keyword_filter_enabled", "keyword", "minimum_duration_enabled", "minimum_duration_seconds",
                    "minimum_likes_enabled", "minimum_like_count", "filter_interrupt", "hide_mini_games",
                    "hide_long_videos", "maximum_duration_seconds");
            boolean downloadChanged = initial || changes.any("download_enabled", "remove_watermark",
                    "download_custom_name", "download_name_template", "remove_download_limits");
            boolean replyChanged = initial || changes.any("automation_enabled", "native_auto_reply", "reply_global",
                    "reply_targets", "reply_mode", "reply_prompt", "reply_delay_seconds", "auto_message_text",
                    "reply_keywords", "reply_scope_mode", "reply_scope_relations", "reply_scope_fields",
                    "reply_scope_keywords", "reply_scope_users", "reply_filter_mode", "reply_filter_keywords",
                    "reply_filter_fields", "reply_private_chats", "reply_group_chats", "reply_group_scope_mode",
                    "reply_group_targets", "reply_group_mode", "reply_group_members", "reply_random",
                    "ai_model_settings_v2", "streak_enabled", "streak_targets", "streak_content",
                    "streak_content_api", "streak_start", "streak_end", "streak_tip", "streak_reset_token",
                    "streak_retry_unknown_token");
            boolean commentAutomationChanged = initial || changes.any("automation_enabled", "native_auto_comment",
                    "comment_mode", "comment_prompt", "comment_delay_seconds", "auto_comment_text",
                    "comment_recognize_images", "comment_recognize_video", "comment_scope_mode",
                    "comment_scope_relations", "comment_scope_fields", "comment_scope_keywords",
                    "comment_scope_users", "comment_filter_mode", "comment_filter_keywords",
                    "comment_filter_fields", "ai_model_settings_v2");
            boolean feedUiChanged = initial || changes.any("hide_interface", "video_right_alpha",
                    "component_hide_enabled", "component_hide_uniform_enabled", "component_hide_uniform_alpha",
                    "component_hide_on_switch", "component_hide_open_comments_on_show", "component_hide_show_on_pause",
                    CustomViewLocator.SETTINGS_KEY) || changes.hasPrefix("component_alpha_");
            boolean playbackChanged = initial || changes.any("auto_speed", "playback_speed");
            boolean autoPlayChanged = initial || changes.any("auto_play", "smart_swipe", "pause_auto_on_comments");
            boolean gestureChanged = initial || changes.any("playback_speed", "toolbar_enabled", "long_press_action",
                    "double_tap_action", LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, "remove_watermark",
                    "download_custom_name", "download_name_template", "remove_download_limits")
                    || changes.hasPrefix("long_press_") || changes.hasPrefix("double_tap_")
                    || changes.hasPrefix("toolbar_");
            boolean messageRecallChanged = initial || changes.any("prevent_message_recall", "recall_self",
                    "recall_other", "recall_background");
            boolean spoofChanged = initial || changes.any("spoof_version", "fake_version_name", "fake_version_code",
                    "spoof_profile", "profile_like_count", "profile_following_count", "profile_follower_count",
                    "profile_mutual_count");

            if (nativeFollowLists != null && (initial || changes.any("reply_scope_mode", "comment_scope_mode",
                    "reply_private_chats", "reply_global", "reply_scope_relations", "reply_targets",
                    "reply_group_targets", "reply_group_members", "comment_scope_relations", "comment_scope_users",
                    NativeFollowLists.REFRESH_KEY))) {
                nativeFollowLists.setOptions(options);
            }
            if (automationDispatcher != null && (replyChanged || commentAutomationChanged
                    || changes.any("reply_tip", "comment_tip", "reply_notice_seconds", "comment_notice_seconds",
                    "streak_tip", "reply_save_history", "comment_save_history"))) {
                automationDispatcher.setOptions(options);
            }
            FeedAdFilterHook hook = feedAdFilterHook;
            if (hook != null && feedFilterChanged) {
                hook.setSettings(settings);
                hook.setOptions(options);
            }
            if (hostBehaviorHooks != null && (initial || changes.any("remove_splash_ads", "remove_update_dialog",
                    "remove_teen_dialog", "hide_video_black_edges", "hide_camera_plus",
                    "disable_double_tap_like", "prevent_skip_hidden", "prevent_insert_video",
                    "block_long_press_speed"))) hostBehaviorHooks.setOptions(options);
            if (playbackBehaviorHooks != null && playbackChanged) playbackBehaviorHooks.setOptions(options);
            if (profileSpoofHook != null && spoofChanged) profileSpoofHook.setOptions(options);
            if (nativeMessagingHooks != null && (replyChanged || messageRecallChanged)) nativeMessagingHooks.setOptions(options);
            if (recallHighlightHook != null && messageRecallChanged) recallHighlightHook.setOptions(options);
            if (feedGestureToolsHook != null && gestureChanged) feedGestureToolsHook.setOptions(options);
            if (directMessageSpeechHook != null && (initial || changes.hasPrefix("dm_") || changes.hasPrefix("tts_"))) {
                directMessageSpeechHook.setOptions(options);
            }
            if (mediaModelHook != null && (initial || changes.any("remove_download_limits"))) mediaModelHook.setOptions(options);
            if (commentEnhancementsHook != null && (initial || changes.any("comment_append_suffix", "comment_suffix",
                    "colored_comment", "colored_comment_type"))) commentEnhancementsHook.setOptions(options);
            if (commentVoiceHook != null && (initial || changes.any("comment_voice_replace", "comment_voice_path"))) {
                commentVoiceHook.setOptions(options);
            }
            if (nativeCommentSpeechHook != null && (initial || changes.any("comment_tts", "tts_engine", "tts_language",
                    "tts_rate", "tts_pitch"))) nativeCommentSpeechHook.setOptions(options);
            AutoLikeHook autoLike = autoLikeHook;
            if (autoLike != null && (initial || changes.any("automation_enabled", "auto_like_enabled"))) {
                autoLike.setSettings(settings);
            }
            AutoFollowHook autoFollow = autoFollowHook;
            if (autoFollow != null && (initial || changes.any("automation_enabled", "auto_follow_enabled"))) {
                autoFollow.setSettings(settings);
            }
            AutoPlayHook autoPlay = autoPlayHook;
            if (autoPlay != null && autoPlayChanged) autoPlay.setSettings(extended, options);
            AutoCommentHook autoComment = autoCommentDraftHook;
            if (autoComment != null && commentAutomationChanged) autoComment.setOptions(options);
            AutoMessageDraftHook autoMessage = autoMessageDraftHook;
            if (autoMessage != null && (initial || changes.any("automation_enabled", "auto_message_enabled",
                    "native_auto_reply", "auto_message_text", "hide_interface"))) {
                autoMessage.setSettings(settings, extended);
            }
            CommentToolsHook commentHook = commentToolsHook;
            if (commentHook != null && (initial || changes.any("copy_comment_text", "comment_media_tools", "copy_source_data"))) {
                commentHook.setSettings(extended);
            }
            ImmersiveModeController immersive = immersiveModeController;
            if (immersive != null && (initial || changes.any("remove_black_edges", "hide_navigation_bar"))) {
                immersive.setSettings(extended);
            }
            HomeNavigationHideController navigation = homeNavigationHideController;
            if (navigation != null && feedUiChanged) navigation.setSettings(extended);
            DownloadActionHook downloadHook = downloadActionHook;
            if (downloadHook != null && downloadChanged) {
                downloadHook.setSettings(settings);
                downloadHook.setOptions(options);
            }
            VersionInfoHook versionHook = versionInfoHook;
            if (versionHook != null && (initial || changes.any("spoof_version", "fake_version_name", "fake_version_code"))) {
                versionHook.setSettings(extended);
            }
            TopTabFilterHook topTabHook = topTabFilterHook;
            if (topTabHook != null && (initial || changes.any("hide_top_tabs", "hidden_top_tabs"))) topTabHook.setSettings(extended);
            FeedUiHideHook uiHook = feedUiHideHook;
            if (uiHook != null && feedUiChanged) uiHook.setSettings(extended);
            RefreshConfirmHook refreshHook = refreshConfirmHook;
            if (refreshHook != null && (initial || changes.any("confirm_refresh"))) refreshHook.setSettings(extended);
            log(3, TAG, "Configuration updated: feedFilter=" + settings.feedFilterEnabled
                    + ", hideAds=" + settings.hideAds);
        } catch (RuntimeException exception) {
            log(5, TAG, "Configuration read failed; previous settings remain active", exception);
        }
    }

    private synchronized void installFeatureHooksIfReady() {
        if (hostScanReport == null || hostClassLoader == null || targetContext == null || !configurationReady) return;
        if (nativeFollowLists == null) {
            nativeFollowLists = NativeFollowLists.install(hostClassLoader, hostScanReport, localOptions,
                    message -> log(3, TAG, message));
        }
        if (automationDispatcher == null) {
            automationDispatcher = new AutomationDispatcher(targetContext, message -> log(3, TAG, message));
            automationDispatcher.setOptions(localOptions);
            Activity resumed = hostUiController == null ? null : hostUiController.getResumedActivity();
            if (resumed != null) {
                AutomationDispatcher dispatcher = automationDispatcher;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (hostUiController != null && hostUiController.getResumedActivity() == resumed) {
                        dispatcher.onActivityResumed(resumed);
                    }
                });
            }
        }
        HostUiController ui = hostUiController;
        if (ui != null) {
            try {
                ui.setSettingsPageTargets(resolveHookClass("host_settings_activity"),
                        resolveHookClass("host_settings_scroll"));
            } catch (Exception failure) {
                log(5, TAG, "Native settings entry unavailable: " + failure.getMessage());
            }
        }
        if (feedAdFilterHook == null) {
            try {
                FeedAdFilterHook hook = FeedAdFilterHook.install(this, hostScanReport,
                        hostClassLoader, currentSettings,
                        message -> log(3, TAG, message));
                feedAdFilterHook = hook;
                hook.setOptions(localOptions);
                feedHookFailure = null;
                log(3, TAG, "Feed response filter hook installed; enabled="
                        + hasActiveFeedFilters(currentSettings));
            } catch (Throwable failure) {
                feedHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Feed response filter hook installation failed", failure);
            }
        }
        if (downloadActionHook == null) {
            try {
                java.lang.reflect.Method moduleName = resolveHookMethod("download_native_module_name");
                java.lang.reflect.Method viewFactory = resolveHookMethod("download_native_view_factory");
                java.lang.reflect.Method nativeClick = resolveHookMethod("download_native_click");
                downloadActionHook = DownloadActionHook.install(this, moduleName, viewFactory,
                        nativeClick, currentSettings,
                        message -> log(3, TAG, message));
                downloadHookFailure = null;
                log(3, TAG, "Native download row extension installed; enabled="
                        + currentSettings.downloadEnabled);
            } catch (Throwable failure) {
                downloadHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Native download row extension installation failed", failure);
            }
        }
        if (versionInfoHook == null) {
            try {
                versionInfoHook = VersionInfoHook.install(this, hostClassLoader, extendedSettings,
                        message -> log(3, TAG, message));
                versionHookFailure = null;
                log(3, TAG, "PackageManager version hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.spoofVersion));
            } catch (Throwable failure) {
                versionHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "PackageManager version hook installation failed", failure);
            }
        }
        if (topTabFilterHook == null) {
            try {
                topTabFilterHook = TopTabFilterHook.install(this, hostScanReport, hostClassLoader,
                        extendedSettings, message -> log(3, TAG, message));
                topTabHookFailure = null;
                log(3, TAG, "Home tab filter hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.hideTopTabs));
            } catch (Throwable failure) {
                topTabHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Home tab filter hook installation failed", failure);
            }
        }
        if (feedUiHideHook == null) {
            try {
                feedUiHideHook = FeedUiHideHook.install(this, hostScanReport, hostClassLoader,
                        targetContext, extendedSettings, message -> log(3, TAG, message));
                feedUiHookFailure = null;
                log(3, TAG, "Feed UI visibility hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.hideInterface));
            } catch (Throwable failure) {
                feedUiHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Feed UI visibility hook installation failed", failure);
            }
        }
        if (refreshConfirmHook == null) {
            try {
                refreshConfirmHook = RefreshConfirmHook.install(this, hostScanReport,
                        hostClassLoader, extendedSettings, message -> log(3, TAG, message));
                refreshHookFailure = null;
                log(3, TAG, "Home refresh confirmation hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.confirmRefresh));
            } catch (Throwable failure) {
                refreshHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Home refresh confirmation hook installation failed", failure);
            }
        }
        if (autoLikeHook == null) {
            try {
                autoLikeHook = AutoLikeHook.install(this, hostScanReport, hostClassLoader,
                        currentSettings, message -> log(3, TAG, message));
                autoLikeHookFailure = null;
                log(3, TAG, "Auto-like host action hook installed; enabled="
                        + (currentSettings.automationEnabled && currentSettings.autoLikeEnabled));
            } catch (Throwable failure) {
                autoLikeHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Auto-like host action hook installation failed", failure);
            }
        }
        if (autoFollowHook == null) {
            try {
                autoFollowHook = AutoFollowHook.install(this, hostScanReport, hostClassLoader,
                        currentSettings, message -> log(3, TAG, message));
                autoFollowHookFailure = null;
                log(3, TAG, "Auto-follow native action hook installed; enabled="
                        + (currentSettings.automationEnabled && currentSettings.autoFollowEnabled));
            } catch (Throwable failure) {
                autoFollowHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Auto-follow native action hook installation failed", failure);
            }
        }
        if (autoPlayHook == null) {
            try {
                autoPlayHook = AutoPlayHook.install(this, hostScanReport, hostClassLoader,
                        targetContext, extendedSettings, localOptions, message -> log(3, TAG, message));
                autoPlayHookFailure = null;
                log(3, TAG, "DouAi directional auto-slide hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.autoPlay));
            } catch (Throwable failure) {
                autoPlayHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "DouAi auto-slide hook installation failed", failure);
            }
        }
        if (autoCommentDraftHook == null) {
            try {
                autoCommentDraftHook = AutoCommentHook.install(this, hostScanReport,
                        hostClassLoader, targetContext, localOptions, automationDispatcher,
                        message -> log(3, TAG, message));
                autoCommentDraftHookFailure = null;
                log(3, TAG, "Native automatic comment hook installed; enabled="
                        + (currentSettings.automationEnabled && localOptions.enabled("native_auto_comment")));
            } catch (Throwable failure) {
                autoCommentDraftHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Native automatic comment hook installation failed", failure);
            }
        }
        if (autoMessageDraftHook == null) {
            try {
                autoMessageDraftHook = AutoMessageDraftHook.install(this, hostScanReport,
                        hostClassLoader, currentSettings, extendedSettings,
                        message -> log(3, TAG, message));
                autoMessageDraftHookFailure = null;
                log(3, TAG, "Native single-chat draft hook installed; enabled="
                        + (currentSettings.automationEnabled && currentSettings.autoMessageEnabled));
            } catch (Throwable failure) {
                autoMessageDraftHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Native single-chat plain-text draft hook installation failed", failure);
            }
        }
        if (commentToolsHook == null) {
            try {
                commentToolsHook = CommentToolsHook.install(this, hostScanReport, hostClassLoader,
                        extendedSettings, message -> log(3, TAG, message));
                commentToolsHookFailure = null;
                log(3, TAG, "Comment long-press tools hook installed");
            } catch (Throwable failure) {
                commentToolsHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Comment long-press tools hook installation failed", failure);
            }
        }
        if (immersiveModeController == null && targetContext != null) {
            try {
                immersiveModeController = ImmersiveModeController.install(targetContext,
                        extendedSettings, message -> log(3, TAG, message));
                immersiveModeFailure = null;
                log(3, TAG, "Reversible status-bar controller installed; enabled="
                        + (extendedSettings != null && extendedSettings.removeBlackEdges));
            } catch (Throwable failure) {
                immersiveModeFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                log(6, TAG, "Status-bar controller installation failed", failure);
            }
        }
        if (homeNavigationHideController == null && targetContext != null) {
            try {
                homeNavigationHideController = HomeNavigationHideController.install(targetContext,
                        extendedSettings, resolveHookClass("component_top_navigation"),
                        resolveHookClass("component_bottom_navigation"), message -> log(3, TAG, message));
                Activity activity = ui == null ? null : ui.getResumedActivity();
                if (activity != null) homeNavigationHideController.onActivityResumed(activity);
                FeedUiHideHook feedUi = feedUiHideHook;
                if (feedUi != null) {
                    HomeNavigationHideController navigation = homeNavigationHideController;
                    feedUi.setComponentVisibilityListener(navigation::setTemporarilyShown);
                }
            } catch (Throwable failure) {
                log(6, TAG, "Home navigation visibility controller installation failed", failure);
            }
        }
        if (targetContext != null) installAdditionalHooks();
    }

    private void installAdditionalHooks() {
        if (downloadActionHook != null) downloadActionHook.setOptions(localOptions);
        if (hostBehaviorHooks == null) {
            try {
                hostBehaviorHooks = HostBehaviorHooks.install(this, hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> log(3, TAG, message));
            } catch (Throwable failure) { log(6, TAG, "Host behavior hooks failed", failure); }
        }
        if (playbackBehaviorHooks == null) {
            try {
                playbackBehaviorHooks = PlaybackBehaviorHooks.install(this, hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> log(3, TAG, message));
            } catch (Throwable failure) { log(6, TAG, "Playback behavior hooks failed", failure); }
        }
        if (profileSpoofHook == null) {
            try {
                profileSpoofHook = ProfileSpoofHook.install(this, hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> log(3, TAG, message));
            } catch (Throwable failure) { log(6, TAG, "Profile display hook failed", failure); }
        }
        if (nativeMessagingHooks == null) {
            try {
                nativeMessagingHooks = NativeMessagingHooks.install(this, hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> log(3, TAG, message), automationDispatcher);
            } catch (Throwable failure) { log(6, TAG, "Native messaging hooks failed", failure); }
        }
        if (recallHighlightHook == null) {
            try {
                recallHighlightHook = RecallHighlightHook.install(this, hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> log(3, TAG, message));
            } catch (Throwable failure) { log(6, TAG, "Recall background hook failed", failure); }
        }
        if (mediaModelHook == null) {
            try {
                mediaModelHook = MediaModelHook.install(this, hostScanReport, hostClassLoader,
                        localOptions, message -> log(3, TAG, message));
            } catch (Throwable failure) { log(6, TAG, "Media presentation hooks failed", failure); }
        }
        if (commentEnhancementsHook == null) {
            try {
                commentEnhancementsHook = CommentEnhancementsHook.install(this, hostScanReport, hostClassLoader,
                        localOptions, message -> log(3, TAG, message));
            } catch (Throwable failure) { log(6, TAG, "Comment publishing enhancement failed", failure); }
        }
        if (commentVoiceHook == null) {
            try {
                commentVoiceHook = CommentVoiceHook.install(this, hostScanReport, hostClassLoader,
                        localOptions, message -> log(3, TAG, message));
                commentVoiceHook.setContext(targetContext);
            } catch (Throwable failure) { log(6, TAG, "Comment voice replacement failed", failure); }
        }
        if (nativeCommentSpeechHook == null) {
            try {
                nativeCommentSpeechHook = NativeCommentSpeechHook.install(this, hostScanReport, hostClassLoader,
                        localOptions, message -> log(3, TAG, message));
                if (autoCommentDraftHook != null) nativeCommentSpeechHook.setAutomaticPublishGuard(autoCommentDraftHook::ownsPublish);
                nativeCommentSpeechHook.setContext(targetContext);
            } catch (Throwable failure) { log(6, TAG, "Comment speech workflow failed", failure); }
        }
        if (feedGestureToolsHook == null) {
            try {
                feedGestureToolsHook = FeedGestureToolsHook.install(this, hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> log(3, TAG, message));
                HostUiController ui = hostUiController;
                Activity activity = ui == null ? null : ui.getResumedActivity();
                if (activity != null) feedGestureToolsHook.onActivityResumed(activity);
                AutoPlayHook pauseState = autoPlayHook;
                if (pauseState != null) feedGestureToolsHook.setPausedStateProvider(pauseState::pauseStateFor);
            } catch (Throwable failure) { log(6, TAG, "Feed gesture tools failed", failure); }
        }
        if (feedGestureToolsHook != null && feedUiHideHook != null) {
            feedGestureToolsHook.setComponentVisibility(feedUiHideHook::setComponentsTemporarilyShown,
                    feedUiHideHook::areComponentsTemporarilyShown);
        }
        if (directMessageSpeechHook == null) {
            try {
                directMessageSpeechHook = DirectMessageSpeechHook.install(this, hostScanReport, hostClassLoader,
                        localOptions, message -> log(3, TAG, message));
                directMessageSpeechHook.setContext(targetContext);
            } catch (Throwable failure) { log(6, TAG, "Direct-message voice workflow failed", failure); }
        }
        additionalInitialized = true;
    }

    private Bundle diagnosticsSnapshot() {
        Bundle snapshot = new Bundle(scanDiagnostics);
        snapshot.putString("module_version", BuildConfig.VERSION_NAME);
        snapshot.putBoolean("additional_initialized", additionalInitialized);
        for (String key : new String[]{"remove_splash_ads", "remove_update_dialog", "remove_teen_dialog",
                "hide_video_black_edges", "hide_camera_plus", "disable_double_tap_like",
                "prevent_skip_hidden", "prevent_insert_video", "block_long_press_speed"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    hostBehaviorHooks != null && hostBehaviorHooks.supports(key));
        }
        for (String key : new String[]{"auto_speed"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    playbackBehaviorHooks != null && playbackBehaviorHooks.supports(key));
        }
        for (String key : new String[]{"pause_auto_on_comments", "smart_swipe"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    autoPlayHook != null && autoPlayHook.supports(key));
        }
        snapshot.putBoolean("feature.spoof_profile.installed", profileSpoofHook != null && profileSpoofHook.isActive());
        snapshot.putBoolean("feature.prevent_message_recall.installed", nativeMessagingHooks != null
                && nativeMessagingHooks.supports("prevent_message_recall"));
        snapshot.putBoolean("feature.native_auto_reply.installed", nativeMessagingHooks != null
                && nativeMessagingHooks.supports("native_reply"));
        snapshot.putBoolean("feature.media_model.installed", mediaModelHook != null);
        snapshot.putBoolean("feature.comment_enhancements.installed", commentEnhancementsHook != null);
        snapshot.putBoolean("feature.comment_voice.installed", commentVoiceHook != null);
        snapshot.putBoolean("feature.comment_tts.installed", nativeCommentSpeechHook != null);
        snapshot.putBoolean("feature.recall_background.installed", recallHighlightHook != null && recallHighlightHook.isActive());
        snapshot.putBoolean("feature.dm_speech.installed", directMessageSpeechHook != null);
        for (String key : new String[]{"toolbar_enabled", "long_press_action", "double_tap_action"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    feedGestureToolsHook != null && feedGestureToolsHook.supports(key));
        }
        FeedAdFilterHook hook = feedAdFilterHook;
        FeatureSettings settings = currentSettings;
        snapshot.putBoolean("feed_hook_installed", hook != null);
        snapshot.putBoolean("feed_filter_enabled", hasActiveFeedFilters(settings));
        snapshot.putInt("feed_filtered_pages", hook == null ? 0 : hook.getFilteredPages());
        if (feedHookFailure != null) snapshot.putString("feed_hook_failure", feedHookFailure);
        snapshot.putBoolean("download_hook_installed", downloadActionHook != null);
        snapshot.putBoolean("download_enabled", settings.downloadEnabled);
        if (downloadHookFailure != null) snapshot.putString("download_hook_failure", downloadHookFailure);
        snapshot.putBoolean("version_hook_installed", versionInfoHook != null);
        snapshot.putBoolean("version_spoof_enabled", extendedSettings != null && extendedSettings.spoofVersion);
        if (versionHookFailure != null) snapshot.putString("version_hook_failure", versionHookFailure);
        snapshot.putBoolean("top_tab_hook_installed", topTabFilterHook != null);
        snapshot.putBoolean("top_tab_filter_enabled", extendedSettings != null && extendedSettings.hideTopTabs);
        if (topTabHookFailure != null) snapshot.putString("top_tab_hook_failure", topTabHookFailure);
        snapshot.putBoolean("feed_ui_hook_installed", feedUiHideHook != null);
        snapshot.putBoolean("feed_ui_video_hook_installed", feedUiHideHook != null
                && feedUiHideHook.isVideoLifecycleInstalled());
        snapshot.putBoolean("feed_ui_image_hook_installed", feedUiHideHook != null
                && feedUiHideHook.isImageLifecycleInstalled());
        snapshot.putBoolean("feed_ui_hide_enabled", extendedSettings != null && extendedSettings.hideInterface);
        snapshot.putInt("feed_ui_alpha_percent",
                extendedSettings == null ? 100 : extendedSettings.videoRightAlpha);
        snapshot.putBoolean("feed_ui_active", extendedSettings != null
                && (extendedSettings.components.configured ? extendedSettings.components.enabled
                : extendedSettings.hideInterface || extendedSettings.videoRightAlpha < 100));
        snapshot.putBoolean("component_hide_configured", extendedSettings != null
                && extendedSettings.components.configured);
        snapshot.putBoolean("component_hide_enabled", extendedSettings != null
                && extendedSettings.components.enabled);
        snapshot.putBoolean("component_hide_temporarily_shown", feedUiHideHook != null
                && feedUiHideHook.areComponentsTemporarilyShown());
        snapshot.putBoolean("home_navigation_hook_installed", homeNavigationHideController != null);
        FeedUiHideHook feedUiHook = feedUiHideHook;
        snapshot.putInt("feed_ui_selected_callbacks",
                feedUiHook == null ? 0 : feedUiHook.getSelectedCallbacks());
        snapshot.putInt("feed_ui_card_scans", feedUiHook == null ? 0 : feedUiHook.getCardScans());
        snapshot.putInt("feed_ui_predraw_rechecks",
                feedUiHook == null ? 0 : feedUiHook.getPreDrawRechecks());
        snapshot.putInt("feed_ui_view_searches",
                feedUiHook == null ? 0 : feedUiHook.getViewSearches());
        snapshot.putInt("feed_ui_resource_mismatches",
                feedUiHook == null ? 0 : feedUiHook.getResourceMismatchCount());
        snapshot.putInt("feed_ui_reapplied_views",
                feedUiHook == null ? 0 : feedUiHook.getHostStateResets());
        snapshot.putInt("feed_ui_hidden_views", feedUiHook == null ? 0 : feedUiHook.getHiddenViews());
        if (feedUiHookFailure != null) snapshot.putString("feed_ui_hook_failure", feedUiHookFailure);
        snapshot.putBoolean("refresh_hook_installed", refreshConfirmHook != null);
        snapshot.putBoolean("refresh_confirm_enabled", extendedSettings != null && extendedSettings.confirmRefresh);
        if (refreshHookFailure != null) snapshot.putString("refresh_hook_failure", refreshHookFailure);
        snapshot.putBoolean("auto_like_hook_installed", autoLikeHook != null);
        snapshot.putBoolean("auto_like_enabled", settings.automationEnabled && settings.autoLikeEnabled);
        if (autoLikeHookFailure != null) snapshot.putString("auto_like_hook_failure", autoLikeHookFailure);
        snapshot.putBoolean("auto_follow_hook_installed", autoFollowHook != null);
        snapshot.putBoolean("auto_follow_enabled", settings.automationEnabled && settings.autoFollowEnabled);
        if (autoFollowHookFailure != null) snapshot.putString("auto_follow_hook_failure", autoFollowHookFailure);
        snapshot.putBoolean("auto_play_hook_installed", autoPlayHook != null && autoPlayHook.supports("auto_play"));
        if (autoPlayHook != null) snapshot.putString("auto_play_status", autoPlayHook.diagnostic());
        snapshot.putBoolean("auto_play_enabled", extendedSettings != null && extendedSettings.autoPlay);
        if (autoPlayHookFailure != null) snapshot.putString("auto_play_hook_failure", autoPlayHookFailure);
        snapshot.putBoolean("auto_comment_hook_installed", autoCommentDraftHook != null);
        snapshot.putBoolean("auto_comment_enabled",
                settings.automationEnabled && localOptions.enabled("native_auto_comment"));
        snapshot.putInt("unknown_streak_count",
                nativeMessagingHooks == null ? 0 : nativeMessagingHooks.unknownStreakCount());
        if (autoCommentDraftHookFailure != null) {
            snapshot.putString("auto_comment_hook_failure", autoCommentDraftHookFailure);
        }
        snapshot.putBoolean("auto_message_hook_installed", autoMessageDraftHook != null);
        snapshot.putBoolean("auto_message_enabled",
                settings.automationEnabled && settings.autoMessageEnabled);
        if (autoMessageDraftHookFailure != null) {
            snapshot.putString("auto_message_hook_failure", autoMessageDraftHookFailure);
        }
        snapshot.putBoolean("comment_tools_hook_installed", commentToolsHook != null);
        if (commentToolsHookFailure != null) snapshot.putString("comment_tools_hook_failure", commentToolsHookFailure);
        snapshot.putBoolean("immersive_hook_installed", immersiveModeController != null);
        snapshot.putBoolean("immersive_status_bar_enabled",
                extendedSettings != null && extendedSettings.removeBlackEdges);
        snapshot.putBoolean("immersive_navigation_bar_enabled",
                extendedSettings != null && extendedSettings.hideNavigationBar);
        snapshot.putBoolean("immersive_enabled", extendedSettings != null
                && (extendedSettings.removeBlackEdges || extendedSettings.hideNavigationBar));
        if (immersiveModeFailure != null) snapshot.putString("immersive_hook_failure", immersiveModeFailure);
        HostAdaptationDiagnostics.append(snapshot, hostVersionEvidence, hostRulesFingerprint, hostScanReport);
        return snapshot;
    }

    private java.lang.reflect.Method resolveHookMethod(String ruleId) throws Exception {
        com.douai.module.scan.RuleScanResult result = hostScanReport.getResults().get(ruleId);
        if (result == null || result.getStatus() != com.douai.module.scan.RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        com.douai.module.scan.HostCandidate candidate = result.getCandidates().get(0);
        Class<?> owner = Class.forName(descriptorClassName(candidate.getClassDescriptor()),
                false, hostClassLoader);
        java.lang.reflect.Method match = null;
        for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(candidate.getMethodName())
                    || !methodDescriptor(method).equals(candidate.getMethodDescriptor())) continue;
            if (match != null) throw new IllegalStateException("Reflection result is ambiguous: " + ruleId);
            match = method;
        }
        if (match == null) throw new NoSuchMethodException("Scanned host method is unavailable: " + ruleId);
        return match;
    }

    private Class<?> resolveHookClass(String ruleId) throws ClassNotFoundException {
        com.douai.module.scan.RuleScanResult result = hostScanReport.getResults().get(ruleId);
        if (result == null || result.getStatus() != com.douai.module.scan.RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host class rule is not unique: " + ruleId);
        }
        com.douai.module.scan.HostCandidate candidate = result.getCandidates().get(0);
        return Class.forName(descriptorClassName(candidate.getClassDescriptor()), false, hostClassLoader);
    }

    private static String descriptorClassName(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String methodDescriptor(java.lang.reflect.Method method) {
        StringBuilder result = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) result.append(typeDescriptor(parameter));
        return result.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private boolean hasActiveFeedFilters(FeatureSettings settings) {
        return settings != null && settings.feedFilterEnabled
                && (settings.hideAds || settings.hideLives || settings.hideImagePosts
                || settings.hideRecommendationCards || settings.hideRecommendationMerchants
                || settings.keywordFilterEnabled
                || settings.minimumDurationEnabled || settings.minimumLikesEnabled
                || localOptions.enabled("hide_mini_games") || localOptions.enabled("hide_long_videos"));
    }

    private static List<File> getApkArchives(ApplicationInfo appInfo) {
        ArrayList<File> files = new ArrayList<>();
        if (appInfo.sourceDir != null) {
            files.add(new File(appInfo.sourceDir));
        }
        if (appInfo.splitSourceDirs != null) {
            for (String path : appInfo.splitSourceDirs) {
                if (path != null) {
                    files.add(new File(path));
                }
            }
        }
        return files;
    }

}

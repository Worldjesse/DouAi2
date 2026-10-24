package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.Toast;

import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.feature.VoiceCatalog;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Cached provider voice picker for the feed quick-action sheet. */
public final class GestureQuickVoiceDialog {
    private static final String THIRD_PARTY_SAMPLE = "tts_voice_sample";

    private GestureQuickVoiceDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable openSettings,
                                     Consumer<AlertDialog> show) {
        SpeechSettings speech;
        List<VoiceCatalog.Entry> entries = Collections.emptyList();
        try {
            Map<String, ?> values = store.snapshot();
            speech = new SpeechSettings(new LocalOptions(values));
            if (speech.minimax) {
                entries = VoiceCatalog.parse(store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""), "", "", "");
            } else if (speech.thirdParty) {
                entries = VoiceCatalog.parse(store.getString(THIRD_PARTY_SAMPLE, ""), speech);
            }
        } catch (RuntimeException unavailable) {
            return new MiuixDialogBuilder(activity, true).setTitle("切换音色")
                    .setMessage("当前语音设置没有可用的缓存音色列表。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("打开模块设置", (dialog, which) -> openSettings.run())
                    .create();
        }
        if (speech.local) {
            return createLocalVoicePicker(activity, store, openSettings, show);
        }
        String selectedId = speech.minimax ? speech.minimaxVoiceId : speech.selectedVoice;
        String key = speech.minimax ? SpeechSettings.MINIMAX_VOICE_ID : SpeechSettings.SELECTED_VOICE;
        String nameKey = speech.minimax ? SpeechSettings.MINIMAX_VOICE_NAME : SpeechSettings.SELECTED_VOICE_NAME;
        List<VoiceCatalog.Entry> availableEntries = entries;
        if (entries.isEmpty()) {
            return new MiuixDialogBuilder(activity, true).setTitle("切换音色")
                    .setMessage("尚无缓存音色列表，请先到文本转语音设置获取音色。")
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("手动填写", (dialog, which) -> {
                        dialog.dismiss();
                        presentManualVoice(activity, store, speech.minimax, key, nameKey, selectedId, show);
                    })
                    .setPositiveButton("打开模块设置", (dialog, which) -> openSettings.run())
                    .create();
        }

        String[] labels = new String[availableEntries.size()];
        int selected = -1;
        for (int index = 0; index < availableEntries.size(); index++) {
            VoiceCatalog.Entry entry = availableEntries.get(index);
            labels[index] = entry.name + " · " + entry.id;
            if (entry.id.equals(selectedId)) selected = index;
        }
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle("切换音色");
        builder.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    VoiceCatalog.Entry entry = availableEntries.get(index);
                    store.putString(key, entry.id);
                    store.putString(nameKey, entry.name);
                    Toast.makeText(activity, "已切换到 " + entry.name, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
        builder.setNegativeButton("关闭", null);
        builder.setNeutralButton("手动填写", (dialog, which) -> {
                    dialog.dismiss();
                    presentManualVoice(activity, store, speech.minimax, key, nameKey, selectedId, show);
                });
        builder.setPositiveButton("文本转语音设置", (dialog, which) -> openSettings.run());
        return builder.create();
    }

    private static AlertDialog createLocalVoicePicker(Activity activity, SettingsStore store,
                                                      Runnable openSettings,
                                                      Consumer<AlertDialog> show) {
        MiuixDialogBuilder loadingBuilder = new MiuixDialogBuilder(activity, true);
        loadingBuilder.setTitle("切换音色");
        loadingBuilder.setMessage("正在读取设备已安装的离线语音音色…");
        loadingBuilder.setNegativeButton("取消", null);
        AlertDialog loading = loadingBuilder.create();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean resolved = new AtomicBoolean();
        TextToSpeech[] engine = new TextToSpeech[1];
        Runnable timeout = () -> {
            if (!resolved.compareAndSet(false, true) || !loading.isShowing()) return;
            shutdown(engine);
            show.accept(localVoiceMessage(activity, openSettings,
                    "读取系统语音超时，请检查设备上的 TTS 引擎。"));
        };
        loading.setOnDismissListener(ignored -> {
            main.removeCallbacks(timeout);
            if (resolved.compareAndSet(false, true)) shutdown(engine);
        });
        String configuredEngine = store.getString("tts_engine", "").trim();
        try {
            engine[0] = new TextToSpeech(activity, status -> main.post(() -> {
                if (!resolved.compareAndSet(false, true) || !loading.isShowing()) {
                    shutdown(engine);
                    return;
                }
                main.removeCallbacks(timeout);
                TextToSpeech current = engine[0];
                if (status != TextToSpeech.SUCCESS || current == null) {
                    shutdown(engine);
                    show.accept(localVoiceMessage(activity, openSettings,
                            "无法读取当前系统语音引擎，请检查引擎设置。"));
                    return;
                }
                List<Voice> voices = new ArrayList<>();
                try {
                    if (current.getVoices() != null) {
                        for (Voice voice : current.getVoices()) {
                            if (voice != null && voice.getLocale() != null
                                    && !voice.isNetworkConnectionRequired()) voices.add(voice);
                        }
                    }
                } catch (RuntimeException unavailable) { }
                shutdown(engine);
                voices.sort(Comparator.comparing((Voice voice) -> voice.getLocale().toLanguageTag())
                        .thenComparing(Voice::getName, String.CASE_INSENSITIVE_ORDER));
                if (voices.isEmpty()) {
                    show.accept(localVoiceMessage(activity, openSettings,
                            "当前引擎没有已安装的离线音色，请先在系统语音设置中下载语音数据。"));
                    return;
                }

                String savedVoice = store.getString(SpeechSettings.LOCAL_VOICE_NAME, "").trim();
                Locale savedLanguage = Locale.forLanguageTag(store.getString("tts_language", "zh-CN"));
                String[] labels = new String[voices.size()];
                int selected = -1;
                for (int index = 0; index < voices.size(); index++) {
                    Voice voice = voices.get(index);
                    Locale locale = voice.getLocale();
                    labels[index] = locale.getDisplayName(Locale.getDefault()) + " · " + voice.getName();
                    if (voice.getName().equals(savedVoice)
                            && locale.toLanguageTag().equals(savedLanguage.toLanguageTag())) selected = index;
                    else if (selected < 0 && savedVoice.isEmpty() && locale.equals(savedLanguage)) selected = index;
                }
                MiuixDialogBuilder picker = new MiuixDialogBuilder(activity, true);
                picker.setTitle("选择离线音色");
                picker.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    Voice voice = voices.get(index);
                    store.putString(SpeechSettings.LOCAL_VOICE_NAME, voice.getName());
                    store.putString("tts_language", voice.getLocale().toLanguageTag());
                    Toast.makeText(activity, "已切换到 " + voice.getLocale().getDisplayName(Locale.getDefault()),
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                picker.setNegativeButton("关闭", null);
                picker.setPositiveButton("语音设置", (dialog, which) -> openSettings.run());
                show.accept(picker.create());
            }), configuredEngine.isEmpty() ? null : configuredEngine);
            main.postDelayed(timeout, 10_000L);
        } catch (RuntimeException | LinkageError failure) {
            resolved.set(true);
            shutdown(engine);
            show.accept(localVoiceMessage(activity, openSettings,
                    "无法初始化系统语音引擎，请检查模块语音设置。"));
        }
        return loading;
    }

    private static AlertDialog localVoiceMessage(Activity activity, Runnable openSettings, String message) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle("切换音色");
        builder.setMessage(message);
        builder.setNegativeButton("关闭", null);
        builder.setPositiveButton("打开语音设置", (dialog, which) -> openSettings.run());
        return builder.create();
    }

    private static void shutdown(TextToSpeech[] engine) {
        TextToSpeech current = engine[0];
        engine[0] = null;
        if (current == null) return;
        try { current.shutdown(); } catch (RuntimeException ignored) { }
    }

    private static void presentManualVoice(Activity activity, SettingsStore store, boolean minimax,
                                           String key, String nameKey, String current,
                                           Consumer<AlertDialog> show) {
        AlertDialog prompt = MiuixDialogBuilder.prompt(activity, true, "音色编号", current, value -> {
            String candidate = value == null ? "" : value.trim();
            try {
                if (minimax) {
                    candidate = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_VOICE_ID, candidate);
                } else if (candidate.isEmpty() || candidate.length() > 256
                        || candidate.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("请输入有效的音色编号");
                }
                store.putString(key, candidate);
                store.putString(nameKey, "");
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        show.accept(prompt);
    }
}

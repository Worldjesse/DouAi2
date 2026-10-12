package com.douai.module.feature;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.douai.module.config.LocalOptions;

import java.io.File;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** A bounded offline synthesis request using an installed Android TTS engine. */
public final class LocalSpeechSynthesis implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean();
    private final BiConsumer<Result, String> callback;
    private volatile TextToSpeech engine;
    private volatile File output;

    public static final class Result {
        public final File file;
        public final long durationMillis;
        Result(File file, long durationMillis) { this.file = file; this.durationMillis = durationMillis; }
    }

    private LocalSpeechSynthesis(BiConsumer<Result, String> callback) { this.callback = callback; }

    public static LocalSpeechSynthesis synthesize(Context context, String text, LocalOptions options,
                                                    BiConsumer<Result, String> callback) {
        LocalSpeechSynthesis request = new LocalSpeechSynthesis(callback);
        request.main.post(() -> request.start(context, text, options));
        return request;
    }

    private void start(Context context, String text, LocalOptions options) {
        if (finished.get()) return;
        if (context == null || text == null || text.trim().isEmpty()
                || text.length() > TextToSpeech.getMaxSpeechInputLength()) {
            complete(null, "语音内容为空或过长");
            return;
        }
        File cache = new File(context.getCacheDir(), "douai-tts");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            complete(null, "无法创建语音缓存");
            return;
        }
        File[] files = cache.listFiles();
        if (files != null) {
            long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".wav") && file.lastModified() < cutoff) {
                    try { java.nio.file.Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
                }
            }
        }
        output = new File(cache, UUID.randomUUID() + ".wav");
        String configured = options.text("tts_engine", "").trim();
        try {
            engine = new TextToSpeech(context, status -> main.post(() -> initialize(status, text, options)),
                    configured.isEmpty() ? null : configured);
            main.postDelayed(() -> complete(null, "本地语音生成超时"), 45_000);
        } catch (RuntimeException failure) {
            complete(null, "无法初始化本地语音引擎");
        }
    }

    private void initialize(int status, String text, LocalOptions options) {
        try { initializeEngine(status, text, options); }
        catch (RuntimeException failure) { complete(null, "本地语音引擎初始化失败"); }
    }

    private void initializeEngine(int status, String text, LocalOptions options) {
        TextToSpeech current = engine;
        if (finished.get() || current == null) return;
        if (status != TextToSpeech.SUCCESS) {
            complete(null, "本地语音引擎不可用");
            return;
        }
        Locale language = Locale.forLanguageTag(options.text("tts_language", "zh-CN"));
        Voice selected = null;
        if (current.getVoices() != null) {
            for (Voice voice : current.getVoices()) {
                if (voice.isNetworkConnectionRequired()
                        || !voice.getLocale().getLanguage().equals(language.getLanguage())) continue;
                if (selected == null || voice.getLocale().equals(language)) selected = voice;
            }
        }
        if (selected == null) {
            complete(null, "请先在系统语音引擎中安装离线中文语音");
            return;
        }
        current.setVoice(selected);
        current.setSpeechRate(Math.max(0.5f, Math.min(2f, options.decimal("tts_rate", 1f))));
        current.setPitch(Math.max(0.5f, Math.min(2f, options.decimal("tts_pitch", 1f))));
        String id = UUID.randomUUID().toString();
        current.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) {
                if (!id.equals(utteranceId) || finished.get()) return;
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    if (!output.isFile() || output.length() == 0) throw new IllegalStateException("Empty synthesis output");
                    metadata.setDataSource(output.getAbsolutePath());
                    long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    if (duration <= 0) throw new IllegalStateException("Invalid synthesis duration");
                    complete(new Result(output, duration), null);
                } catch (Exception failure) {
                    complete(null, "生成的语音文件无效");
                } finally {
                    try { metadata.release(); } catch (Exception ignored) { }
                }
            }
            @Override public void onError(String utteranceId) { if (id.equals(utteranceId)) complete(null, "本地语音生成失败"); }
            @Override public void onError(String utteranceId, int errorCode) { onError(utteranceId); }
        });
        if (current.synthesizeToFile(text, new Bundle(), output, id) != TextToSpeech.SUCCESS) {
            complete(null, "语音引擎拒绝了生成请求");
        }
    }

    private void complete(Result result, String failure) {
        if (!finished.compareAndSet(false, true)) return;
        main.post(() -> {
            main.removeCallbacksAndMessages(null);
            TextToSpeech current = engine;
            engine = null;
            if (current != null) {
                try { current.stop(); current.shutdown(); } catch (RuntimeException ignored) { }
            }
            if (result == null && output != null) {
                try { java.nio.file.Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
            }
            callback.accept(result, failure);
        });
    }

    @Override public void close() { complete(null, "语音生成已停止"); }
}

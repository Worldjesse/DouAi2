package com.douai.module.feature;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** One provider selection shared by manual speech, automatic replies and comments. */
public final class SpeechSynthesis implements AutoCloseable {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "douai-speech");
                thread.setDaemon(true);
                return thread;
            });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object completionLock = new Object();
    private final BiConsumer<LocalSpeechSynthesis.Result, String> callback;
    private final SpeechHttpClient.Request http = new SpeechHttpClient.Request();
    private volatile AutoCloseable local;
    private volatile Future<?> worker;
    private volatile File output;
    private LocalSpeechSynthesis.Result pendingResult;
    private String pendingFailure;
    private boolean callbackDelivered;

    private SpeechSynthesis(BiConsumer<LocalSpeechSynthesis.Result, String> callback) {
        if (callback == null) throw new IllegalArgumentException("Speech callback is missing");
        this.callback = callback;
    }

    public static SpeechSynthesis synthesize(Context context, String text, LocalOptions options,
                                              BiConsumer<LocalSpeechSynthesis.Result, String> callback) {
        SpeechSynthesis request = new SpeechSynthesis(callback);
        if (options == null) {
            request.complete(null, "语音设置不可用");
            return request;
        }
        if (!new SpeechSettings(options).thirdParty) {
            request.local = LocalSpeechSynthesis.synthesize(context, text, options, request::complete);
            return request;
        }
        if (context == null || text == null || text.trim().isEmpty()) {
            request.complete(null, "语音内容为空");
            return request;
        }
        Context application = context.getApplicationContext();
        Context use = application == null ? context : application;
        request.main.postDelayed(() -> request.complete(null, "第三方语音生成超时"), 60_000);
        try {
            request.worker = WORKERS.submit(() -> request.generate(use, text, new SpeechSettings(options)));
            if (request.finished.get()) request.worker.cancel(true);
        } catch (RejectedExecutionException ignored) {
            request.complete(null, "语音生成任务过多，请稍后重试");
        }
        return request;
    }

    private void generate(Context context, String text, SpeechSettings settings) {
        try {
            String url = SpeechHttpClient.resolveTemplate(settings.getTemplate, text, settings.selectedVoice);
            SpeechHttpClient.Resource response = http.fetchResource(url, settings.headers);
            if (!response.isAudio()) {
                String audioUrl = SpeechHttpClient.audioUrl(response.text(), settings.audioField);
                // API authentication belongs to its origin, not an unrelated media/CDN host.
                response = http.fetchResource(audioUrl,
                        SpeechHttpClient.sameOrigin(url, audioUrl) ? settings.headers : "{}");
            }
            http.ensureActive();
            if (!response.isAudio()) throw new IllegalArgumentException("第三方语音链接未返回音频文件");
            File cache = new File(context.getCacheDir(), "douai-tts");
            if (!cache.isDirectory() && !cache.mkdirs()) throw new IllegalArgumentException("无法创建语音缓存");
            cleanOldFiles(cache);
            output = new File(cache, UUID.randomUUID() + response.audioExtension());
            http.ensureActive();
            try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(response.bytes); }
            http.ensureActive();
            long duration = audioDuration(output);
            http.ensureActive();
            complete(new LocalSpeechSynthesis.Result(output, duration), null);
        } catch (CancellationException ignored) {
            complete(null, "语音生成已停止");
        } catch (Exception failure) {
            String message = failure.getMessage();
            // Parsing/IO failures must not leak text, credentials or provider response content.
            if (message == null || !(message.startsWith("第三方语音") || message.startsWith("所选语音字段")
                    || message.equals("返回了多个语音链接，请选择音频字段")
                    || message.equals("返回了多个链接，请选择音频字段") || message.equals("无法创建语音缓存")
                    || message.equals("生成的语音文件无效"))) {
                message = "第三方语音生成失败，请检查接口及音频字段";
            }
            complete(null, message);
        } finally {
            if (finished.get()) deleteFailedOutput();
        }
    }

    static long audioDuration(File file) {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            if (!file.isFile() || file.length() == 0 || file.length() > SpeechHttpClient.MAX_AUDIO_BYTES) {
                throw new IllegalArgumentException();
            }
            metadata.setDataSource(file.getAbsolutePath());
            if (!"yes".equalsIgnoreCase(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    || "yes".equalsIgnoreCase(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                throw new IllegalArgumentException();
            }
            long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (duration <= 0) throw new IllegalArgumentException();
            return duration;
        } catch (Exception failure) {
            throw new IllegalArgumentException("生成的语音文件无效");
        } finally {
            try { metadata.release(); } catch (Exception ignored) { }
        }
    }

    private static void cleanOldFiles(File cache) {
        File[] files = cache.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        for (File file : files) if (file.isFile()
                && file.getName().matches("[0-9a-fA-F-]{36}\\.(audio|wav|mp3|m4a|aac|ogg|flac)")
                && file.lastModified() < cutoff) {
            delete(file);
        }
    }

    private void complete(LocalSpeechSynthesis.Result result, String failure) {
        if (result != null && result.durationMillis <= 0) {
            delete(result.file);
            result = null;
            failure = "生成的语音文件无效";
        }
        synchronized (completionLock) {
            if (!finished.compareAndSet(false, true)) {
                if (result != null && result != pendingResult) delete(result.file);
                return;
            }
            pendingResult = result;
            pendingFailure = failure;
            if (result != null) output = null;
        }
        stopRequests();
        main.post(this::deliver);
    }

    private void stopRequests() {
        http.cancel();
        Future<?> active = worker;
        if (active != null) active.cancel(true);
    }

    private void deliver() {
        LocalSpeechSynthesis.Result result;
        String failure;
        synchronized (completionLock) {
            if (callbackDelivered) return;
            callbackDelivered = true;
            result = pendingResult;
            failure = pendingFailure;
            pendingResult = null;
        }
        main.removeCallbacksAndMessages(null);
        AutoCloseable engine = local;
        local = null;
        if (engine != null) try { engine.close(); } catch (Exception ignored) { }
        deleteFailedOutput();
        callback.accept(result, failure);
    }

    private void deleteFailedOutput() { File file = output; if (file != null) delete(file); }
    private static void delete(File file) {
        if (file == null) return;
        try { java.nio.file.Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
    }

    @Override public void close() {
        LocalSpeechSynthesis.Result abandoned;
        synchronized (completionLock) {
            if (callbackDelivered) return;
            finished.set(true);
            abandoned = pendingResult;
            pendingResult = null;
            pendingFailure = "语音生成已停止";
        }
        if (abandoned != null) delete(abandoned.file);
        stopRequests();
        main.post(this::deliver);
    }
}

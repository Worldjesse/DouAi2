package com.douai.module.feature;

import android.content.Context;

import com.douai.module.config.LocalOptions;

import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Prepares owned audio files before automation's send acknowledgment timer starts. */
final class SpeechPreparation {
    interface Preparer {
        Audio prepare(Context context, String text, LocalOptions options, long maximumDuration,
                      BooleanSupplier valid) throws Exception;
    }
    interface Synthesizer {
        AutoCloseable synthesize(Context context, String text, LocalOptions options,
                                 BiConsumer<LocalSpeechSynthesis.Result, String> callback);
    }
    interface Encoder {
        DirectMessageSpeechHook.AudioFile encode(File source, Context context) throws Exception;
    }
    static final class Audio {
        final File file;
        final long duration;
        Audio(File file, long duration) { this.file = file; this.duration = duration; }
        void delete() { file.delete(); }
    }
    private SpeechPreparation() { }

    static Audio prepareComment(Context context, String text, LocalOptions options, long maximumDuration,
                                BooleanSupplier valid) throws Exception {
        return prepareComment(context, text, options, maximumDuration, valid, SpeechSynthesis::synthesize);
    }

    static Audio prepareComment(Context context, String text, LocalOptions options, long maximumDuration,
                                BooleanSupplier valid, Synthesizer synthesizer) throws Exception {
        // Comment upload accepts the synthesis file; AAC encoding belongs to native IM.
        return prepare(context, text, options, maximumDuration, valid, synthesizer, null);
    }

    static Audio prepare(Context context, String text, LocalOptions options, long maximumDuration,
                         BooleanSupplier valid) throws Exception {
        return prepare(context, text, options, maximumDuration, valid,
                SpeechSynthesis::synthesize, DirectMessageSpeechHook::encode);
    }

    static Audio prepare(Context context, String text, LocalOptions options, long maximumDuration,
                         BooleanSupplier valid, Synthesizer synthesizer, Encoder encoder) throws Exception {
        if (!valid.getAsBoolean()) throw new CancellationException("语音任务已取消");
        CountDownLatch ready = new CountDownLatch(1);
        Completion completion = new Completion(ready);
        AutoCloseable request = null;
        DirectMessageSpeechHook.AudioFile encoded = null;
        boolean delivered = false;
        String stage = "synthesis";
        long sourceDuration = 0;
        try {
            request = synthesizer.synthesize(context, text, options, completion::complete);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(70);
            while (!ready.await(100, TimeUnit.MILLISECONDS)) {
                if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("语音任务已取消");
                if (System.nanoTime() >= deadline) throw new IllegalStateException("语音生成超时");
            }
            if (!valid.getAsBoolean()) throw new CancellationException("语音任务已取消");
            LocalSpeechSynthesis.Result audio = completion.result();
            if (audio == null) throw new IllegalStateException(completion.failure());
            sourceDuration = audio.durationMillis;
            if (sourceDuration <= 0) throw new IllegalArgumentException("生成的语音文件无效");
            if (audio.durationMillis > maximumDuration) throw new IllegalArgumentException("语音超过当前发送入口的时长限制");
            if (encoder == null) {
                if (!valid.getAsBoolean()) throw new CancellationException("语音任务已取消");
                completion.transfer();
                return new Audio(audio.file, audio.durationMillis);
            }
            stage = "encoding";
            encoded = encoder.encode(audio.file, context);
            if (!valid.getAsBoolean()) throw new CancellationException("语音任务已取消");
            if (encoded.durationMillis <= 0) throw new IllegalArgumentException("生成的语音文件无效");
            if (encoded.durationMillis > maximumDuration) throw new IllegalArgumentException("语音超过当前发送入口的时长限制");
            delivered = true;
            return new Audio(encoded.file, encoded.durationMillis);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw new Failure(stage, sourceDuration, encoded == null ? 0 : encoded.durationMillis, failure);
        } finally {
            // A callback can arrive after cancellation; close the result owner before the request.
            completion.close();
            if (!delivered && encoded != null) encoded.file.delete();
            if (request != null) request.close();
        }
    }

    static final class Failure extends Exception {
        private final String diagnostic;

        Failure(String stage, long sourceDuration, long encodedDuration, Exception cause) {
            super("语音准备失败", cause);
            diagnostic = "stage=" + stage + "; reason=" + reason(cause.getMessage())
                    + "; sourceMillis=" + sourceDuration + "; encodedMillis=" + encodedDuration
                    + "; error=" + cause.getClass().getSimpleName();
        }

        String diagnostic() { return diagnostic; }

        private static String reason(String message) {
            if (message == null) return "unknown";
            if (message.matches("第三方语音 GET 返回 HTTP [0-9]{3}")) {
                return "http_" + message.substring(message.length() - 3);
            }
            switch (message) {
                case "语音生成超时": case "第三方语音生成超时":
                case "第三方语音 GET 请求超时": case "本地语音生成超时": return "timeout";
                case "生成的语音文件无效": return "invalid_audio";
                case "语音内容为空或过长": return "engine_text_limit";
                case "语音超过当前发送入口的时长限制": return "duration_limit";
                case "解码后的 PCM 格式不受支持": return "unsupported_pcm";
                case "PCM 缓冲区大小不受支持": return "pcm_buffer";
                case "音频采样格式不受支持": return "unsupported_sample_format";
                case "文件没有音轨": return "missing_audio_track";
                case "语音转码超时或已停止": return "encoding_timeout";
                case "语音引擎拒绝了生成请求": return "engine_rejected";
                case "第三方语音响应未包含有效音频链接":
                case "第三方语音响应未包含音频链接":
                case "所选语音字段应包含一个音频链接":
                case "返回了多个语音链接，请选择音频字段":
                case "返回了多个链接，请选择音频字段": return "audio_field";
                case "第三方语音链接未返回音频文件": return "not_audio";
                case "第三方语音 GET 请求失败": return "network";
                default: return "provider_or_audio_failure";
            }
        }
    }

    private static final class Completion {
        private final CountDownLatch ready;
        private boolean closed;
        private boolean completed;
        private boolean transferred;
        private LocalSpeechSynthesis.Result result;
        private String failure;

        Completion(CountDownLatch ready) { this.ready = ready; }

        synchronized void complete(LocalSpeechSynthesis.Result audio, String error) {
            if (closed || completed) {
                if (audio != null && (result == null || !audio.file.equals(result.file))) audio.file.delete();
                return;
            }
            completed = true;
            result = audio;
            failure = error;
            ready.countDown();
        }

        synchronized LocalSpeechSynthesis.Result result() { return result; }
        synchronized String failure() { return failure == null ? "语音生成失败" : failure; }
        synchronized void transfer() { transferred = true; }

        synchronized void close() {
            closed = true;
            if (result != null && !transferred) result.file.delete();
        }
    }
}

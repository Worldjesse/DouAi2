package com.douai.module.feature;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** User-triggered private audio sending; automatic reply APIs never enter this adapter. */
public final class DirectMessageSpeechHook implements AutoCloseable {
    static final long AUDIO_RETENTION_MILLIS = 10 * 60_000L;
    private static final String AUDIO_LOGIC = "com.ss.android.ugc.aweme.im.business.chat.feature.audio.logic.AudioLogic";
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, WeakReference<Object>> audioLogics = new WeakHashMap<>();
    private final Map<Object, Boolean> recordings = new WeakHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private final ThreadLocal<Boolean> userAction = new ThreadLocal<>();
    private final ThreadLocal<QueueEvidence> submitting = new ThreadLocal<>();
    private final Method inputApi;
    private final Method content;
    private final Method editText;
    private final Method conversationId;
    private final Method recordEvent;
    private final Method localExt;
    private final Method messageConversation;
    private final Constructor<?> callbackConstructor;
    private final Constructor<?> recordConstructor;
    private final Object recordComplete;
    private final Object recordStart;
    private final Field recordFile;
    private final Field recordDuration;
    private final Field recordWave;
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private volatile Context context;
    private volatile boolean closed;
    private volatile AudioFile replacement;
    private volatile boolean preparing;
    private String requested = "";
    private Pending pending;
    private String submittedDraft;
    private String submittedConversation;
    private long submittedAt;

    private DirectMessageSpeechHook(HostScanReport report, ClassLoader loader,
                                      LocalOptions options, Consumer<String> log) throws Exception {
        this.options = options;
        this.log = log;
        inputApi = HostTargets.method(report, "dm_speech_input_api", loader);
        content = HostTargets.method(report, "dm_speech_content", loader);
        editText = HostTargets.method(report, "dm_speech_edit_text", loader);
        conversationId = HostTargets.method(report, "dm_speech_conversation", loader);
        recordEvent = HostTargets.method(report, "dm_speech_record_event", loader);
        localExt = HostTargets.method(report, "dm_speech_message_local_ext", loader);
        messageConversation = HostTargets.method(report, "dm_speech_message_conversation", loader);
        callbackConstructor = recordEvent.getDeclaringClass().getConstructor(Object.class, int.class);
        Class<?> recordType = HostTargets.type(report, "dm_speech_record_model", loader);
        recordConstructor = recordType.getConstructor();
        recordFile = HostTargets.field(recordType, "LIZ");
        recordDuration = HostTargets.field(recordType, "LIZIZ");
        recordWave = HostTargets.field(recordType, "LIZJ");
        Class<?> stateType = HostTargets.type(report, "dm_speech_record_state", loader);
        recordComplete = HostTargets.field(stateType, "RECORD_COMPLETE").get(null);
        recordStart = HostTargets.field(stateType, "RECORD_START").get(null);
    }

    public static DirectMessageSpeechHook install(XposedInterface framework, HostScanReport report,
                                                    ClassLoader loader, LocalOptions options,
                                                    Consumer<String> log) throws Exception {
        DirectMessageSpeechHook hook = new DirectMessageSpeechHook(report, loader, options,
                log == null ? ignored -> { } : log);
        try {
            for (String id : new String[]{"dm_speech_send_click", "dm_speech_send_confirm"}) {
                hook.add(framework, HostTargets.method(report, id, loader), id, chain -> {
                    Object ui = HostTargets.read(chain.getThisObject(), id.endsWith("confirm") ? "LIZIZ" : "l0");
                    Object view = chain.getArg(0);
                    boolean eligible = ui != null && ui.getClass().getName().equals(
                            "com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.new.InputDrawerUI2")
                            && view instanceof View && ((View) view).isShown() && ((View) view).hasWindowFocus();
                    if (!eligible) return chain.proceed();
                    Boolean old = hook.userAction.get();
                    hook.userAction.set(Boolean.TRUE);
                    try { return chain.proceed(); }
                    finally { if (old == null) hook.userAction.remove(); else hook.userAction.set(old); }
                });
            }
            hook.add(framework, HostTargets.method(report, "dm_speech_send", loader), "Send", hook::send);
            hook.add(framework, HostTargets.method(report, "dm_speech_audio_create", loader), "AudioCreate", chain -> {
                Object result = chain.proceed();
                Object logic = chain.getThisObject();
                Object session = HostTargets.read(logic, "m");
                if (session != null) synchronized (hook.audioLogics) { hook.audioLogics.put(session, new WeakReference<>(logic)); }
                return result;
            });
            hook.add(framework, hook.recordEvent, "RecordComplete", hook::replaceRecording);
            hook.add(framework, HostTargets.method(report, "dm_speech_audio_destroy", loader), "AudioDestroy", chain -> {
                Object logic = chain.getThisObject();
                synchronized (hook.audioLogics) {
                    hook.audioLogics.entrySet().removeIf(entry -> entry.getValue().get() == logic);
                }
                synchronized (hook.recordings) { hook.recordings.remove(logic); }
                return chain.proceed();
            });
            hook.add(framework, HostTargets.method(report, "dm_speech_upload_enqueue", loader), "Queue", chain -> {
                QueueEvidence evidence = hook.submitting.get();
                if (evidence != null) {
                    Object message = chain.getArg(0);
                    Object raw = hook.localExt.invoke(message);
                    if (raw instanceof Map<?, ?> && evidence.file.getAbsolutePath().equals(((Map<?, ?>) raw).get("localpath"))
                            && evidence.conversation.equals(hook.messageConversation.invoke(message))) evidence.queued = true;
                }
                return chain.proceed();
            });
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw (Error) failure;
        }
    }

    public void setContext(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        setOptions(options);
    }

    public synchronized void setOptions(LocalOptions options) {
        boolean speechChanged = !this.options.samePrefix(options, "tts_");
        this.options = options;
        if ((!options.enabled("dm_tts") || speechChanged) && pending != null) {
            pending.cancel();
            pending = null;
        }
        String path = options.enabled("dm_voice_replace") ? options.text("dm_voice_path", "").trim() : "";
        if (path.equals(requested) && (path.isEmpty() || replacement != null || preparing)) return;
        requested = path;
        replacement = null;
        preparing = false;
        long epoch = generation.incrementAndGet();
        Context current = context;
        if (closed || path.isEmpty() || current == null) return;
        preparing = true;
        worker.execute(() -> {
            try {
                File copied = copySource(current, path);
                AudioFile prepared;
                try { prepared = encode(copied, current); }
                finally { java.nio.file.Files.deleteIfExists(copied.toPath()); }
                if (!closed && generation.get() == epoch) replacement = prepared;
                else java.nio.file.Files.deleteIfExists(prepared.file.toPath());
            } catch (Exception failure) {
                safeLog("Private voice preparation failed: " + failure.getMessage());
                toast("私聊语音文件不可用：" + safeMessage(failure));
            } finally { if (generation.get() == epoch) preparing = false; }
        });
    }

    private Object send(XposedInterface.Chain chain) throws Throwable {
        LocalOptions current = options;
        if (closed || !current.enabled("dm_tts") || !Boolean.TRUE.equals(userAction.get())) return chain.proceed();
        boolean handled;
        try { handled = startSpeech(chain.getThisObject(), current); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Private speech input unchanged: " + failure.getMessage());
            toast("私聊语音入口异常，草稿已保留");
            handled = true;
        }
        return handled ? null : chain.proceed();
    }

    private boolean startSpeech(Object logic, LocalOptions current) throws ReflectiveOperationException {
        Object ui = inputApi.invoke(logic);
        if (ui == null) { toast("私聊输入框不可用，草稿已保留"); return true; }
        String draft = String.valueOf(content.invoke(ui));
        if (!canStart(current, Boolean.TRUE.equals(userAction.get()), draft)) return false;
        String text = speechText(draft);
        if (text == null) return false;
        if (text.isEmpty()) { toast("语音内容为空，草稿已保留"); return true; }
        Object session = HostTargets.read(logic, "g");
        String conversation = session == null ? "" : String.valueOf(conversationId.invoke(session));
        EditText editor = (EditText) editText.invoke(ui);
        if (context == null || editor == null || !editor.isShown() || !editor.hasWindowFocus()
                || conversation.isEmpty()) { toast("当前私聊未就绪，草稿已保留"); return true; }
        Object audioLogic = findAudioLogic(conversation);
        if (audioLogic == null) { toast("私聊语音入口未就绪，草稿已保留"); return true; }
        synchronized (this) {
            if (pending != null) { toast("私聊语音正在生成，草稿已保留"); return true; }
            if (conversation.equals(submittedConversation) && draft.equals(submittedDraft)
                    && android.os.SystemClock.elapsedRealtime() - submittedAt < 30_000) {
                toast("此草稿已交给原生语音发送，请检查消息结果"); return true;
            }
            Pending request = new Pending(logic, ui, editor, audioLogic, conversation, draft);
            pending = request;
            toast("正在生成私聊语音");
            request.speech = SpeechSynthesis.synthesize(context, text, current, (result, failure) -> {
                if (result == null) { finish(request, failure == null ? "私聊语音生成失败" : failure); return; }
                if (closed) request.cancel();
                if (!request.adoptSource(result.file)) return;
                FutureTask<Void> encoding = new FutureTask<>(() -> {
                    try {
                        if (request.cancelled || closed) return null;
                        AudioFile encoded = encode(result.file, context);
                        if (request.adoptEncoded(encoded)) main.post(() -> submit(request, encoded));
                    } catch (Exception error) { finish(request, "私聊语音转码失败：" + safeMessage(error)); }
                    finally { request.releaseSource(result.file); }
                    return null;
                });
                if (!request.installEncoding(encoding)) return;
                try { worker.execute(encoding); } catch (java.util.concurrent.RejectedExecutionException stopped) {
                    finish(request, "私聊语音已停止，草稿已保留");
                }
            });
        }
        return true;
    }

    private void submit(Pending request, AudioFile audio) {
        try {
            Object ui = request.ui.get();
            Object logic = request.logic.get();
            EditText editor = request.editor.get();
            Object target = request.audioLogic.get();
            if (closed || request.cancelled || !options.enabled("dm_tts") || ui == null || logic == null
                    || target == null || editor == null || !editor.isShown() || !editor.hasWindowFocus()
                    || !request.draft.equals(String.valueOf(content.invoke(ui)))
                    || !request.conversation.equals(conversationId.invoke(HostTargets.read(logic, "g")))) {
                finish(request, "私聊或草稿已变化，已取消语音发送");
                return;
            }
            Object event = newRecord(audio);
            Object callback = callbackConstructor.newInstance(target, 1);
            QueueEvidence evidence = new QueueEvidence(audio.file, request.conversation);
            if (!request.beginNative(audio, main)) {
                finish(request, "私聊语音已停止，草稿已保留");
                return;
            }
            submitting.set(evidence);
            try { recordEvent.invoke(callback, recordComplete, event); }
            finally { submitting.remove(); }
            if (!evidence.queued) {
                request.rejectNative();
                finish(request, "原生语音未进入发送队列，草稿已保留");
                return;
            }
            synchronized (this) {
                submittedDraft = request.draft; submittedConversation = request.conversation;
                submittedAt = android.os.SystemClock.elapsedRealtime();
            }
            safeLog("Native private audio upload entry reached; original draft retained and remote result remains authoritative");
            finish(request, "已交给原生语音发送，草稿已保留");
        } catch (Exception failure) { finish(request, "私聊语音提交失败：" + safeMessage(failure)); }
    }

    private Object replaceRecording(XposedInterface.Chain chain) throws Throwable {
        if (closed || submitting.get() != null) return chain.proceed();
        Object owner = HostTargets.read(chain.getThisObject(), "l0");
        Object state = chain.getArg(0);
        if (owner == null || !AUDIO_LOGIC.equals(owner.getClass().getName())) return chain.proceed();
        if (state == recordStart) {
            synchronized (recordings) { recordings.put(owner, true); }
            return chain.proceed();
        }
        if (state != recordComplete) {
            if (state != null && ("RECORD_CANCEL".equals(state.toString()) || "RRCORD_ERROR".equals(state.toString()))) {
                synchronized (recordings) { recordings.remove(owner); }
            }
            return chain.proceed();
        }
        boolean wasRecording;
        synchronized (recordings) { wasRecording = recordings.remove(owner) != null; }
        if (!wasRecording || !options.enabled("dm_voice_replace") || chain.getArg(1) == null) return chain.proceed();
        AudioFile audio = replacement;
        if (audio == null || !audio.file.isFile()) {
            toast("替换语音未准备好，原录音文件已保留");
            return null;
        }
        Object[] args = chain.getArgs().toArray();
        try { args[1] = newRecord(audio); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Private replacement recording rejected: " + failure.getMessage());
            toast("替换语音数据异常，原录音文件已保留");
            return null;
        }
        safeLog("Replacing a user RECORD_COMPLETE event with prepared private audio");
        return chain.proceed(args);
    }

    private Object newRecord(AudioFile audio) throws ReflectiveOperationException {
        Object model = recordConstructor.newInstance();
        recordFile.set(model, audio.file);
        recordDuration.set(model, audio.durationMillis);
        recordWave.set(model, Collections.emptyList());
        return model;
    }

    private Object findAudioLogic(String conversation) throws ReflectiveOperationException {
        synchronized (audioLogics) {
            Object selected = null;
            for (Map.Entry<Object, WeakReference<Object>> entry : audioLogics.entrySet()) {
                Object value = entry.getValue().get();
                if (value == null || !conversation.equals(conversationId.invoke(entry.getKey()))) continue;
                if (selected != null && selected != value) return null;
                selected = value;
            }
            return selected;
        }
    }

    static String speechText(String draft) {
        return draft != null && draft.startsWith("#") ? draft.substring(1).trim() : null;
    }

    static boolean canStart(LocalOptions options, boolean fromUser, String draft) {
        return options.enabled("dm_tts") && fromUser && speechText(draft) != null;
    }

    private synchronized void finish(Pending request, String message) {
        request.cleanup();
        if (pending == request) pending = null;
        if (!request.cancelled && !closed) toast(message);
    }

    private static File copySource(Context context, String source) throws Exception {
        File result = File.createTempFile("source-", ".audio", audioCache(context));
        try {
            Uri uri = Uri.parse(source);
            try (InputStream input = "content".equalsIgnoreCase(uri.getScheme())
                    ? context.getContentResolver().openInputStream(uri) : new FileInputStream(new File(source));
                 FileOutputStream output = new FileOutputStream(result)) {
                if (input == null) throw new IllegalArgumentException("无法读取文件");
                byte[] bytes = new byte[16 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(bytes)) != -1) {
                    total += count;
                    if (total > 20L * 1024L * 1024L) throw new IllegalArgumentException("文件超过 20 MB");
                    output.write(bytes, 0, count);
                }
                if (total == 0) throw new IllegalArgumentException("文件为空");
            }
            return result;
        } catch (Exception failure) { java.nio.file.Files.deleteIfExists(result.toPath()); throw failure; }
    }

    static AudioFile encode(File source, Context context) throws Exception {
        if (context == null) throw new IllegalStateException("私聊上下文不可用");
        File result = File.createTempFile("voice-", ".m4a", audioCache(context));
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        boolean succeeded = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat inputFormat = null;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { inputFormat = format; extractor.selectTrack(index); break; }
            }
            if (inputFormat == null) throw new IllegalArgumentException("文件没有音轨");
            int rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            validateAudioFormat(rate, channels);
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            MediaFormat output = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels);
            output.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            output.setInteger(MediaFormat.KEY_BIT_RATE, 96000 * channels);
            output.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            encoder.configure(output, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(result.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo decoded = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoded = new MediaCodec.BufferInfo();
            boolean inputEnd = false;
            boolean decodeEnd = false;
            boolean encodeEnd = false;
            int track = -1;
            long deadline = android.os.SystemClock.elapsedRealtime() + 60_000;
            int held = -1;
            while (!encodeEnd) {
                if (Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime() > deadline) {
                    throw new IllegalStateException("语音转码超时或已停止");
                }
                if (!inputEnd) {
                    int slot = decoder.dequeueInputBuffer(1000);
                    if (slot >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(slot);
                        if (buffer == null) throw new IllegalStateException("音频解码输入不可用");
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnd = true;
                        } else {
                            decoder.queueInputBuffer(slot, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                if (!decodeEnd) {
                    if (held < 0) {
                        held = decoder.dequeueOutputBuffer(decoded, 1000);
                        if (held == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat actual = decoder.getOutputFormat();
                            if (actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) != rate
                                    || actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels
                                    || actual.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                    && actual.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                                throw new IllegalArgumentException("解码后的 PCM 格式不受支持");
                            }
                            held = -1;
                        }
                    }
                    if (held >= 0) {
                        int slot = encoder.dequeueInputBuffer(1000);
                        if (slot >= 0) {
                            ByteBuffer target = encoder.getInputBuffer(slot);
                            ByteBuffer pcm = decoder.getOutputBuffer(held);
                            if (target == null || pcm == null || target.capacity() < decoded.size) {
                                throw new IllegalStateException("PCM 缓冲区大小不受支持");
                            }
                            target.clear();
                            pcm.position(decoded.offset);
                            pcm.limit(decoded.offset + decoded.size);
                            target.put(pcm);
                            int flags = decoded.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                            encoder.queueInputBuffer(slot, 0, decoded.size, decoded.presentationTimeUs, flags);
                            decodeEnd = flags != 0;
                            decoder.releaseOutputBuffer(held, false);
                            held = -1;
                        }
                    }
                }
                int slot = encoder.dequeueOutputBuffer(encoded, 1000);
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IllegalStateException("音频输出格式重复变化");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (slot >= 0) {
                    if ((encoded.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encoded.size > 0) {
                        if (!muxing) throw new IllegalStateException("音频输出未就绪");
                        ByteBuffer buffer = encoder.getOutputBuffer(slot);
                        if (buffer == null) throw new IllegalStateException("音频编码输出不可用");
                        buffer.position(encoded.offset);
                        buffer.limit(encoded.offset + encoded.size);
                        muxer.writeSampleData(track, buffer, encoded);
                    }
                    encodeEnd = (encoded.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(slot, false);
                }
            }
            muxer.stop();
            muxing = false;
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            long duration;
            try {
                metadata.setDataSource(result.getAbsolutePath());
                duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if (duration <= 0) throw new IllegalStateException("语音时长无效");
            } finally { metadata.release(); }
            succeeded = true;
            return new AudioFile(result, duration);
        } finally {
            extractor.release();
            if (decoder != null) { try { decoder.stop(); } catch (RuntimeException ignored) { } decoder.release(); }
            if (encoder != null) { try { encoder.stop(); } catch (RuntimeException ignored) { } encoder.release(); }
            if (muxer != null) { if (muxing) try { muxer.stop(); } catch (RuntimeException ignored) { } muxer.release(); }
            if (!succeeded) java.nio.file.Files.deleteIfExists(result.toPath());
        }
    }

    private void add(XposedInterface framework, Method method, String id, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.PrivateSpeech." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(hooker);
        if (handle == null) throw new IllegalStateException("Empty private speech handle");
        handles.add(handle);
    }

    static void validateAudioFormat(int rate, int channels) {
        if (channels < 1 || channels > 2 || rate < 8000 || rate > 48000) {
            throw new IllegalArgumentException("音频采样格式不受支持");
        }
    }

    private static File audioCache(Context context) throws Exception {
        File directory = new File(context.getCacheDir(), "douai-private-voice");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建私聊语音缓存");
        File[] files = directory.listFiles();
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        if (files != null) for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) java.nio.file.Files.deleteIfExists(file.toPath());
        }
        return directory;
    }

    @Override public synchronized void close() {
        closed = true;
        generation.incrementAndGet();
        if (pending != null) { pending.cancel(); pending = null; }
        worker.shutdownNow();
        for (int index = handles.size() - 1; index >= 0; index--) {
            try { handles.get(index).unhook(); } catch (Throwable failure) { safeLog("Private speech unhook failed: " + failure.getMessage()); }
        }
        handles.clear();
        synchronized (audioLogics) { audioLogics.clear(); }
        synchronized (recordings) { recordings.clear(); }
    }

    static final class AudioFile {
        final File file;
        final long durationMillis;
        AudioFile(File file, long durationMillis) { this.file = file; this.durationMillis = durationMillis; }
    }
    private static final class QueueEvidence {
        final File file;
        final String conversation;
        boolean queued;
        QueueEvidence(File file, String conversation) { this.file = file; this.conversation = conversation; }
    }
    static final class Pending {
        final WeakReference<Object> logic;
        final WeakReference<Object> ui;
        final WeakReference<EditText> editor;
        final WeakReference<Object> audioLogic;
        final String conversation;
        final String draft;
        volatile boolean cancelled;
        SpeechSynthesis speech;
        private boolean stopped;
        private File source;
        private AudioFile encoded;
        private File nativeFile;
        private FutureTask<Void> encoding;
        Pending(Object logic, Object ui, EditText editor, Object audioLogic, String conversation, String draft) {
            this.logic = new WeakReference<>(logic); this.ui = new WeakReference<>(ui);
            this.editor = new WeakReference<>(editor); this.audioLogic = new WeakReference<>(audioLogic);
            this.conversation = conversation; this.draft = draft;
        }

        synchronized boolean adoptSource(File file) {
            if (stopped || cancelled) { delete(file); return false; }
            source = file;
            return true;
        }

        synchronized boolean adoptEncoded(AudioFile audio) {
            if (stopped || cancelled) { delete(audio.file); return false; }
            encoded = audio;
            return true;
        }

        synchronized boolean installEncoding(FutureTask<Void> task) {
            if (stopped || cancelled) { task.cancel(true); return false; }
            encoding = task;
            return true;
        }

        synchronized void releaseSource(File file) {
            if (source == file) source = null;
            delete(file);
        }

        synchronized boolean beginNative(AudioFile audio, Handler main) {
            if (stopped || cancelled || encoded != audio) return false;
            encoded = null;
            nativeFile = audio.file;
            // From this point native code may start an asynchronous upload even if invocation throws.
            main.postDelayed(() -> delete(audio.file), AUDIO_RETENTION_MILLIS);
            return true;
        }

        synchronized void rejectNative() {
            delete(nativeFile);
            nativeFile = null;
        }

        void cancel() {
            synchronized (this) { cancelled = true; }
            cleanup();
            SpeechSynthesis active = speech;
            if (active != null) active.close();
        }

        synchronized void cleanup() {
            stopped = true;
            if (encoding != null) encoding.cancel(true);
            delete(source);
            source = null;
            if (encoded != null) delete(encoded.file);
            encoded = null;
        }

        private static void delete(File file) {
            if (file == null) return;
            try { java.nio.file.Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
        }
    }
    private void toast(String message) {
        Context target = context;
        if (target != null && message != null) main.post(() -> {
            try { Toast.makeText(target, message, Toast.LENGTH_SHORT).show(); } catch (RuntimeException ignored) { }
        });
    }
    private static String safeMessage(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException ? ((InvocationTargetException) failure).getCause() : failure;
        return cause == null || cause.getMessage() == null ? "请重新选择可读取的音频文件" : cause.getMessage();
    }
    private void safeLog(String message) { try { log.accept(message); } catch (Throwable ignored) { } }
}

package com.douai.module.feature;

import android.graphics.Rect;
import android.view.View;

import io.github.libxposed.api.XposedInterface;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Temporary, bounded metadata probes for the published-audio / visible-comment boundary. */
final class CommentAudioDiagnostics implements AutoCloseable {
    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final String HOLDER = "com.ss.android.ugc.aweme.comment.adapter.vh.holders.CommentViewHolder";
    private static final String COMPONENT = "com.ss.android.ugc.aweme.comment.audiocomment.component.AudioCommentComponentV2";
    private static final long LIFETIME_MS = 15 * 60_000L;
    private final Map<Integer, Sample> samples = new LinkedHashMap<>();
    private final Map<String, Observed> comments = new LinkedHashMap<>();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final String namespace;
    private final LongSupplier clock;
    private int sequence;
    private boolean closed;

    CommentAudioDiagnostics(Consumer<String> log, String namespace) {
        this(log, namespace, () -> System.nanoTime() / 1_000_000L);
    }

    CommentAudioDiagnostics(Consumer<String> log, String namespace, LongSupplier clock) {
        this.log = log;
        this.namespace = namespace;
        this.clock = clock;
    }

    void install(XposedInterface framework, ClassLoader loader) {
        try {
            Class<?> comment = Class.forName(COMMENT, false, loader);
            Class<?> audio = HostTargets.field(comment, "commentAudio").getType();
            Method read = null;
            for (Method method : comment.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || method.getReturnType() != audio) continue;
                if (read != null) throw new IllegalStateException();
                read = method;
            }
            if (read == null) throw new NoSuchMethodException();
            add(framework.hook(read).setId(namespace + ".AudioTrace.Read")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Object result = chain.proceed();
                        try { observe("audio_read", chain.getThisObject(), ""); }
                        catch (RuntimeException | LinkageError ignored) { }
                        return result;
                    }));
            emit("probe=read; ready=1");
        } catch (Exception | LinkageError unavailable) { emit("probe=read; ready=0"); }
        try {
            Class<?> holder = Class.forName(HOLDER, false, loader);
            Method comment = holder.getDeclaredMethod("getComment");
            Method bind = null;
            if (!COMMENT.equals(comment.getReturnType().getName()) || comment.getParameterCount() != 0) {
                throw new IllegalStateException();
            }
            // Original 406/9900 DEX: U/r(item, Rect) is the ordinary root/reply row bind.
            for (Method method : holder.getDeclaredMethods()) {
                int flags = method.getModifiers();
                if (!Modifier.isPublic(flags) || !Modifier.isFinal(flags) || Modifier.isStatic(flags)
                        || method.getReturnType() != void.class || method.getParameterCount() != 2
                        || method.getParameterTypes()[1] != Rect.class || method.getParameterTypes()[0].isPrimitive()) continue;
                if (bind != null) throw new IllegalStateException();
                bind = method;
            }
            if (bind == null) throw new NoSuchMethodException();
            add(framework.hook(bind).setId(namespace + ".AudioTrace.Row")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Object result = chain.proceed();
                        try { observe("row_bound", comment.invoke(chain.getThisObject()), ""); }
                        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
                        return result;
                    }));
            emit("probe=row; ready=1");
        } catch (Exception | LinkageError unavailable) { emit("probe=row; ready=0"); }
        try {
            Class<?> component = Class.forName(COMPONENT, false, loader);
            Method bind = component.getDeclaredMethod("LJIIZILJ", Object.class);
            Field present = HostTargets.field(bind.getReturnType(), "LJ");
            if (Modifier.isStatic(bind.getModifiers()) || present.getType() != boolean.class) throw new IllegalStateException();
            add(framework.hook(bind).setId(namespace + ".AudioTrace.Component")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object argument = chain.getArg(0);
                            Field field = HostTargets.field(argument.getClass(), "a");
                            if (COMMENT.equals(field.getType().getName())) {
                                observe("audio_state", field.get(argument), "; component_audio=" + present.getBoolean(result));
                            }
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
                        return result;
                    }));
            emit("probe=component; ready=1");
        } catch (Exception | LinkageError unavailable) { emit("probe=component; ready=0"); }
        try {
            Class<?> component = Class.forName(COMPONENT, false, loader);
            Method apply = null;
            for (Method method : component.getDeclaredMethods()) {
                if (!method.getName().equals("LJIIIZ") || method.getParameterCount() != 1
                        || method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) continue;
                if (apply != null) throw new IllegalStateException();
                apply = method;
            }
            if (apply == null) throw new NoSuchMethodException();
            add(framework.hook(apply).setId(namespace + ".AudioTrace.Apply")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object state = chain.getArg(0);
                            Field field = HostTargets.field(state.getClass(), "LIZ");
                            if (COMMENT.equals(field.getType().getName())) {
                                Object owner = chain.getThisObject();
                                Object wrapper = HostTargets.read(owner, "LJFF");
                                observe("audio_bound", field.get(state), viewState(HostTargets.read(owner, "LJI"))
                                        + "; inflated=" + bool(HostTargets.read(wrapper, "LIZJ"))
                                        + "; pending_visibility=" + numeric(HostTargets.read(wrapper, "LJII")));
                            }
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
                        return result;
                    }));
            emit("probe=apply; ready=1");
        } catch (Exception | LinkageError unavailable) { emit("probe=apply; ready=0"); }
    }

    private synchronized void add(XposedInterface.HookHandle handle) {
        if (handle == null) throw new IllegalStateException();
        handles.add(handle);
    }

    synchronized Sample begin(Object model, String aid, boolean reply) {
        if (closed) return null;
        prune();
        Sample sample = new Sample(++sequence, aid, reply, clock.getAsLong());
        samples.put(sample.number, sample);
        while (samples.size() > 8) samples.remove(samples.keySet().iterator().next());
        model(sample, "prepared", model);
        return sample;
    }

    synchronized void model(Sample sample, String stage, Object model) {
        if (sample == null) return;
        try {
            Object audio = HostTargets.read(HostTargets.read(model, "A"), "audio");
            Object file = HostTargets.read(audio, "localFile");
            Object wave = HostTargets.read(audio, "waveList");
            Object vid = HostTargets.read(audio, "vid");
            if ("publish_before".equals(stage) && nonempty(vid)) sample.uploadVid = (String) vid;
            record(sample, "a" + sample.number, sample.reply ? "owned_reply" : "owned_root", stage,
                    "; model_audio=" + (audio != null) + "; vid=" + nonempty(vid)
                            + "; duration=" + numeric(HostTargets.read(audio, "duration"))
                            + "; waves=" + (wave instanceof List ? ((List<?>) wave).size() : -1)
                            + "; file=" + (file instanceof File && ((File) file).isFile())
                            + "; bytes=" + (file instanceof File ? ((File) file).length() : -1)
                            + "; container=" + container(file instanceof File ? (File) file : null));
        } catch (RuntimeException | LinkageError ignored) { }
    }

    synchronized void response(Sample sample, String stage, Object response) {
        try { responseSnapshot(sample, stage, response); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private void responseSnapshot(Sample sample, String stage, Object response) {
        if (!active(sample)) return;
        Object comment = HostTargets.read(response, "comment");
        String cid = identifier(HostTargets.read(comment, "cid"));
        Object status = HostTargets.read(response, "status_code");
        Object aid = HostTargets.read(comment, "awemeId");
        if (cid != null && status instanceof Number && ((Number) status).intValue() == 0
                && (aid == null || "".equals(aid) || sample.aid.equals(aid))) {
            comments.put(cid, new Observed(sample, "a" + sample.number, sample.reply ? "owned_reply" : "owned_root"));
            trimComments();
        }
        record(sample, "a" + sample.number, sample.reply ? "owned_reply" : "owned_root", stage,
                "; code=" + numeric(status) + "; comment=" + (comment != null) + summary(comment)
                        + "; upload_vid_match=" + vidMatch(sample, comment));
    }

    synchronized void observe(String stage, Object comment, String extra) {
        if (closed || comment == null) return;
        prune();
        String cid = identifier(HostTargets.read(comment, "cid"));
        if (cid == null) return;
        Observed observed = comments.get(cid);
        if (observed == null) {
            // Only replies to a just-published owned comment; unrelated users' audio is excluded.
            Observed parent = comments.get(identifier(HostTargets.read(comment, "replyId")));
            if (parent == null || !parent.kind.startsWith("owned_") || parent.sample.controls >= 4
                    || !parent.sample.aid.equals(HostTargets.read(comment, "awemeId"))) return;
            observed = new Observed(parent.sample, "a" + parent.sample.number + "r" + ++parent.sample.controls, "related_reply");
            comments.put(cid, observed);
            trimComments();
        }
        record(observed.sample, observed.alias, observed.kind, stage, summary(comment) + extra);
    }

    static String summary(Object comment) {
        if (comment == null) return "; audio=unknown";
        try {
            Field media = HostTargets.field(comment.getClass(), "commentAudio");
            Object audio = media.get(comment);
            if (audio == null) return "; audio=false";
            Class<?> type = media.getType();
            if (HostTargets.field(type, "a").getType() != String.class
                    || HostTargets.field(type, "b").getType() != String.class
                    || HostTargets.field(type, "d").getType() != String.class
                    || HostTargets.field(type, "e").getType() != Long.class) return "; audio=unknown";
            Object content = HostTargets.read(audio, "b");
            String kind = !(content instanceof String) || ((String) content).isEmpty() ? "empty"
                    : ((String) content).startsWith("/") ? "path"
                    : ((String) content).startsWith("https://") || ((String) content).startsWith("http://") ? "url" : "opaque";
            Object wave = HostTargets.read(audio, "d");
            return "; audio=true; dto=" + Integer.toHexString(System.identityHashCode(audio))
                    + "; vid=" + nonempty(HostTargets.read(audio, "a")) + "; content=" + kind
                    + "; local_file=" + ("path".equals(kind) ? new File((String) content).isFile() : "na")
                    + "; duration=" + numeric(HostTargets.read(audio, "e"))
                    + "; wave_chars=" + (wave instanceof String ? ((String) wave).length() : -1)
                    + "; local=" + bool(HostTargets.read(audio, "isLocal"))
                    + "; folded=" + bool(HostTargets.read(audio, "isFolded"))
                    + "; asr_status=" + numeric(HostTargets.read(audio, "f"))
                    + "; asr_text=" + nonempty(HostTargets.read(audio, "g"))
                    + "; content_type=" + numeric(HostTargets.read(comment, "contentType"))
                    + "; text=" + nonempty(HostTargets.read(comment, "text"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return "; audio=unknown"; }
    }

    private static String viewState(Object value) {
        if (!(value instanceof View)) return "; view=false";
        View view = (View) value;
        return "; view=true; visibility=" + view.getVisibility() + "; shown=" + view.isShown()
                + "; width=" + view.getWidth() + "; height=" + view.getHeight();
    }

    static String container(File file) {
        if (file == null) return "unknown";
        byte[] header = new byte[12];
        try (FileInputStream input = new FileInputStream(file)) {
            int count = input.read(header);
            if (count >= 12 && ascii(header, 0, "RIFF") && ascii(header, 8, "WAVE")) return "wav";
            if (count >= 8 && ascii(header, 4, "ftyp")) return "mp4";
            if (count >= 4 && ascii(header, 0, "fLaC")) return "flac";
            if (count >= 4 && ascii(header, 0, "OggS")) return "ogg";
            if (count >= 3 && ascii(header, 0, "ID3")) return "id3_audio";
            if (count >= 2 && (header[0] & 255) == 255) {
                int second = header[1] & 255;
                if ((second & 246) == 240) return "aac_adts";
                if ((second & 224) == 224 && (second & 6) != 0) return "mpeg_audio";
            }
        } catch (Exception ignored) { }
        return "unknown";
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        for (int index = 0; index < expected.length(); index++) if (bytes[offset + index] != expected.charAt(index)) return false;
        return true;
    }

    private synchronized void record(Sample sample, String alias, String kind, String stage, String fields) {
        if (!active(sample) || sample.logs >= 48) return;
        String message = "sample=" + alias + "; kind=" + kind + "; stage=" + stage + fields;
        // RecyclerView rebinds can repeat; retain object changes and phase changes without flooding logs.
        if (!sample.seen.add(message)) return;
        sample.logs++;
        emit(message);
    }

    private boolean active(Sample sample) {
        return !closed && sample != null && samples.get(sample.number) == sample
                && clock.getAsLong() - sample.started < LIFETIME_MS;
    }

    private void prune() {
        samples.values().removeIf(sample -> !active(sample));
        comments.values().removeIf(comment -> !active(comment.sample));
    }

    private void trimComments() {
        while (comments.size() > 40) comments.remove(comments.keySet().iterator().next());
    }

    private void emit(String fields) {
        try { if (log != null) log.accept("[DouAi.AudioTrace] publisher=" + namespace + "; " + fields); }
        catch (RuntimeException ignored) { }
    }

    private static String identifier(Object value) {
        return value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= 128 ? (String) value : null;
    }
    private static boolean nonempty(Object value) { return value instanceof String && !((String) value).isEmpty(); }
    private static Object numeric(Object value) { return value instanceof Number ? ((Number) value).longValue() : "unknown"; }
    private static Object bool(Object value) { return value instanceof Boolean ? value : "unknown"; }

    private static String vidMatch(Sample sample, Object comment) {
        Object vid = HostTargets.read(HostTargets.read(comment, "commentAudio"), "a");
        return sample.uploadVid == null || !nonempty(vid) ? "unknown"
                : sample.uploadVid.equals(vid) ? "same" : "different";
    }

    @Override public void close() {
        List<XposedInterface.HookHandle> installed;
        synchronized (this) {
            closed = true;
            samples.clear();
            comments.clear();
            installed = new ArrayList<>(handles);
            handles.clear();
        }
        for (XposedInterface.HookHandle handle : installed) {
            try { handle.unhook(); } catch (RuntimeException ignored) { }
        }
    }

    static final class Sample {
        final int number;
        final String aid;
        final boolean reply;
        final long started;
        int controls, logs;
        String uploadVid;
        final Set<String> seen = new HashSet<>();
        Sample(int number, String aid, boolean reply, long started) {
            this.number = number; this.aid = aid; this.reply = reply; this.started = started;
        }
    }

    private static final class Observed {
        final Sample sample;
        final String alias, kind;
        Observed(Sample sample, String alias, String kind) { this.sample = sample; this.alias = alias; this.kind = kind; }
    }
}

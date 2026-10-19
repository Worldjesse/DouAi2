package com.douai.module.analytics;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional local self-description; included only in an explicitly selected analytics backup. */
public final class LocalPortraitContext {
    private static final Map<String, State> STATES = new LinkedHashMap<>();
    private LocalPortraitContext() { }
    public static final class State {
        public final String purpose, feedback;
        State(String purpose, String feedback) { this.purpose = purpose; this.feedback = feedback; }
    }

    public static State current(Context context) {
        synchronized (STATES) {
            File root = root(context);
            String key = root.getAbsolutePath();
            State cached = STATES.get(key);
            if (cached != null) return cached;
            File file = new File(root, "portrait-context.json");
            State loaded = new State("", "");
            if (file.exists()) try {
                if (file.length() > 4096) throw new IllegalArgumentException();
                JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (json.getInt("version") != 1) throw new IllegalArgumentException();
                loaded = new State(purpose(json.getString("purpose")), feedback(json.getString("feedback")));
            } catch (Exception failure) { throw new IllegalStateException("本地侧写自述无法读取，可清空后重新填写"); }
            STATES.put(key, loaded);
            return loaded;
        }
    }

    /** Call disk mutations on a worker. The self-description is sent only with explicit AI analysis. */
    public static void setPurpose(Context context, String value) {
        synchronized (STATES) { save(context, new State(purpose(value), current(context).feedback)); }
    }
    public static void setFeedback(Context context, String value) {
        synchronized (STATES) { save(context, new State(current(context).purpose, feedback(value))); }
    }
    public static void clear(Context context) {
        synchronized (STATES) {
            File root = root(context);
            for (String name : List.of("portrait-context.json", "portrait-context.json.pending")) {
                File file = new File(root, name);
                if (file.exists() && !file.delete()) throw new IllegalStateException("无法清空本地侧写自述");
            }
            STATES.put(root.getAbsolutePath(), new State("", ""));
        }
    }

    static <T> T withState(Context context, java.util.function.Function<State, T> operation) {
        synchronized (STATES) { return operation.apply(current(context)); }
    }

    static State backupState(String purpose, String feedback) {
        return new State(purpose(purpose), feedback(feedback));
    }

    static void replaceFromBackup(Context context, State state) {
        synchronized (STATES) { save(context, state); }
    }

    private static File root(Context context) {
        Context app = context.getApplicationContext();
        return new File((app == null ? context : app).getNoBackupFilesDir(), "douai-analytics");
    }
    private static String purpose(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 240 || result.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw new IllegalArgumentException("使用目的最多 240 字，不能包含无效字符");
        return result;
    }
    private static String feedback(String value) {
        if (!List.of("", "符合", "部分符合", "不符合").contains(value))
            throw new IllegalArgumentException("反馈选项无效");
        return value;
    }
    private static void save(Context context, State next) {
        File root = root(context);
        File pending = new File(root, "portrait-context.json.pending");
        try {
            if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException();
            byte[] bytes = new JSONObject().put("version", 1).put("purpose", next.purpose)
                    .put("feedback", next.feedback).toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream stream = new FileOutputStream(pending)) {
                stream.write(bytes); stream.getFD().sync();
            }
            File destination = new File(root, "portrait-context.json");
            try { Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            STATES.put(root.getAbsolutePath(), next);
        } catch (Exception failure) { throw new IllegalStateException("侧写自述未能保存，请重试"); }
        finally { if (pending.exists()) pending.delete(); }
    }
}

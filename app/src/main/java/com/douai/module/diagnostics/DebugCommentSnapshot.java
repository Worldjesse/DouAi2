package com.douai.module.diagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Only explicit Comment DTOs are serialized; logging owns redaction and storage. */
public final class DebugCommentSnapshot {
    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final long WINDOW_MS = 5_000;
    private static final int MAX_JSON_CHARACTERS = 256 * 1024;
    private static final int MAX_ARGUMENTS = 16;
    private static final int MAX_COMMENTS = 4;
    private final BooleanSupplier enabled;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final Serializer serializer;
    private final ThreadLocal<Boolean> running = new ThreadLocal<>();
    private final Limits limits = new Limits();
    private final Map<Class<?>, Field[]> fieldCache = new LinkedHashMap<>();

    interface Serializer { String serialize(Object comment) throws Throwable; }

    public DebugCommentSnapshot(BooleanSupplier enabled, Consumer<String> log, LongSupplier nanoClock) {
        this(enabled, log, nanoClock, DebugCommentSnapshot::hostJson);
    }

    DebugCommentSnapshot(BooleanSupplier enabled, Consumer<String> log, LongSupplier nanoClock,
                         Serializer serializer) {
        this.enabled = Objects.requireNonNull(enabled);
        this.log = Objects.requireNonNull(log);
        this.clock = Objects.requireNonNull(nanoClock);
        this.serializer = Objects.requireNonNull(serializer);
    }

    /** Starts a fresh diagnostic capture after clearing logs or changing the debug mode. */
    public void reset() { limits.reset(); }

    /** Owner/result must themselves be Comment. Arguments may have only a typed comment/a field. */
    public void capture(String hook, String stage, Object owner, List<?> arguments, Object result) {
        if (!enabled() || Boolean.TRUE.equals(running.get())) return;
        running.set(Boolean.TRUE);
        try {
            long epoch = limits.epoch();
            String id = safeLabel(hook);
            String phase = "after".equals(stage) ? "after" : "before";
            List<Object> comments = new ArrayList<>();
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            add(owner, comments, seen);
            if (arguments != null) {
                int size = Math.min(arguments.size(), MAX_ARGUMENTS);
                for (int i = 0; i < size && comments.size() < MAX_COMMENTS; i++) {
                    Object value = arguments.get(i);
                    if (isComment(value)) add(value, comments, seen);
                    else if (mayContainComment(value)) {
                        for (Field field : commentFields(value.getClass())) {
                            try { add(field.get(value), comments, seen); }
                            catch (Throwable inaccessible) { }
                        }
                    }
                }
            }
            add(result, comments, seen);
            if (comments.isEmpty()) return;
            long now = clock.getAsLong() / 1_000_000L;
            long dropped = limits.pendingDrops(now, epoch);
            if (dropped != 0) emit("[DouAi.CommentJson] dropped=" + dropped + " windowMs=" + WINDOW_MS);
            boolean critical = critical(id);
            for (Object comment : comments) {
                if (!limits.probe(id + '|' + phase, critical, now, epoch)) continue;
                String json;
                try {
                    json = serializer.serialize(comment);
                } catch (Throwable failure) {
                    Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    if (limits.error(now, epoch)) emit("[DouAi.CommentJson] hook=" + id + " stage=" + phase
                            + " unavailable=" + cause.getClass().getName());
                    continue;
                }
                if (json == null || json.length() > MAX_JSON_CHARACTERS) {
                    if (limits.error(now, epoch)) emit("[DouAi.CommentJson] hook=" + id + " stage=" + phase
                            + " unavailable=" + (json == null ? "null_json" : "oversize")
                            + " chars=" + (json == null ? 0 : json.length()));
                    continue;
                }
                String key = id + '|' + phase + '|' + digest(json);
                if (limits.record(key, critical, now, epoch) && limits.current(epoch)) {
                    // The single caller-owned pipeline must redact JSON before LSPosed/file persistence.
                    emit("[DouAi.CommentJson] hook=" + id + " stage=" + phase + " json=" + json);
                }
            }
        } catch (Throwable unavailable) {
            // Reflection, clocks, containers and log sinks are diagnostic-only; never change the hook.
        } finally { running.remove(); }
    }

    private boolean enabled() {
        try { return enabled.getAsBoolean(); }
        catch (Throwable unavailable) { return false; }
    }

    private void emit(String line) {
        if (!enabled()) return;
        try { log.accept(line); }
        catch (Throwable unavailable) { }
    }

    private static boolean critical(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("publish_success") || lower.contains("publish_failure");
    }

    private static String safeLabel(String value) {
        if (value == null || value.isEmpty()) return "unnamed";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 120; i++) {
            char c = value.charAt(i);
            result.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == '-' || c == '$' || c == '<' || c == '>' ? c : '_');
        }
        return result.toString();
    }

    private static boolean isComment(Object value) { return value != null && commentType(value.getClass()); }
    private static boolean commentType(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) if (COMMENT.equals(c.getName())) return true;
        return false;
    }

    private static void add(Object value, List<Object> comments, IdentityHashMap<Object, Boolean> seen) {
        if (comments.size() < MAX_COMMENTS && isComment(value) && seen.put(value, Boolean.TRUE) == null) comments.add(value);
    }

    private static boolean mayContainComment(Object value) {
        if (value == null || value instanceof Throwable || value instanceof CharSequence
                || value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof Collection<?> || value instanceof Map<?, ?> || value.getClass().isArray()
                || value instanceof Class<?> || value instanceof ClassLoader) return false;
        return true;
    }

    private Field[] commentFields(Class<?> owner) {
        synchronized (fieldCache) {
            Field[] cached = fieldCache.get(owner);
            if (cached != null) return cached;
        }
        List<Field> fields = new ArrayList<>(2);
        boolean context = false;
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            if ("android.content.Context".equals(c.getName())) { context = true; break; }
        }
        if (!context) for (String name : new String[]{"comment", "a"}) {
            Field field = typedField(owner, name);
            if (field != null) fields.add(field);
        }
        Field[] result = fields.toArray(new Field[0]);
        synchronized (fieldCache) {
            if (fieldCache.size() >= 256) fieldCache.remove(fieldCache.keySet().iterator().next());
            // Cache negative lookups too, so hot View arguments do not create reflection exceptions per frame.
            fieldCache.put(owner, result);
        }
        return result;
    }

    private static Field typedField(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers()) || !commentType(field.getType())) return null;
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) { }
            catch (Throwable unavailable) { return null; }
        }
        return null;
    }

    private static String hostJson(Object comment) throws Throwable {
        Class<?> gson = Class.forName("com.google.gson.Gson", true, comment.getClass().getClassLoader());
        Object instance = gson.getConstructor().newInstance();
        Object json = gson.getMethod("toJson", Object.class).invoke(instance, comment);
        if (!(json instanceof String)) throw new IllegalStateException("Host Gson returned a non-string result");
        return (String) json;
    }

    private static String digest(String json) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte b : bytes) { result.append(Character.forDigit((b >>> 4) & 15, 16)); result.append(Character.forDigit(b & 15, 16)); }
        return result.toString();
    }

    private static final class Limits {
        private final Map<String, Long> probes = new LinkedHashMap<>();
        private final Map<String, Long> digests = new LinkedHashMap<>();
        private long start = Long.MIN_VALUE, dropped, pending, epoch;
        private int normalProbes, criticalProbes, normalRecords, criticalRecords, errors;
        private void advance(long now) {
            if (start == Long.MIN_VALUE || now < start || now - start >= WINDOW_MS) {
                pending += dropped; dropped = 0; start = now;
                normalProbes = 0; criticalProbes = 0; normalRecords = 0; criticalRecords = 0; errors = 0; probes.clear();
            }
        }
        synchronized long epoch() { return epoch; }
        synchronized boolean current(long expected) { return epoch == expected; }
        synchronized long pendingDrops(long now, long expected) {
            if (epoch != expected) return 0;
            advance(now); long count = pending; pending = 0; return count;
        }
        synchronized boolean probe(String key, boolean critical, long now, long expected) {
            if (epoch != expected) return false;
            advance(now);
            Long previous = probes.get(key);
            if (!critical && previous != null && now - previous < 500
                    || (critical ? criticalProbes >= 24 : normalProbes >= 20)) { dropped++; return false; }
            if (probes.size() >= 256 && !probes.containsKey(key)) { dropped++; return false; }
            probes.put(key, now); if (critical) criticalProbes++; else normalProbes++; return true;
        }
        synchronized boolean record(String key, boolean critical, long now, long expected) {
            if (epoch != expected) return false;
            advance(now);
            Long previous = digests.get(key);
            if (previous != null && now >= previous && now - previous < 60_000
                    || (critical ? criticalRecords >= 12 : normalRecords >= 8)) { dropped++; return false; }
            if (digests.size() >= 512) digests.remove(digests.keySet().iterator().next());
            digests.put(key, now); if (critical) criticalRecords++; else normalRecords++; return true;
        }
        synchronized boolean error(long now, long expected) {
            if (epoch != expected) return false;
            advance(now); if (errors >= 2) return false; errors++; return true;
        }
        synchronized void reset() {
            epoch++; start = Long.MIN_VALUE; dropped = 0; pending = 0;
            normalProbes = 0; criticalProbes = 0; normalRecords = 0; criticalRecords = 0; errors = 0;
            probes.clear(); digests.clear();
        }
    }
}

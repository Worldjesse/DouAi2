package com.douai.module.diagnostics;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Explicit IM DTO boundaries only. The caller owns consent, redaction and persistence. */
public final class DebugMessageSnapshot {
    private static final String MESSAGE = "com.bytedance.im.core.model.Message";
    private static final String AUDIO = "com.ss.android.ugc.aweme.im.sdk.chat.model.AudioContent";
    private static final long WINDOW_MS = 5_000;
    private static final int MAX_JSON_CHARACTERS = 256 * 1024;
    private static final int MAX_ARGUMENTS = 16;
    private static final int MAX_LIST_SCAN = 16;
    private static final int MAX_LIST_MESSAGES = 2;
    private static final int MAX_DTOS = 6;
    private final BooleanSupplier enabled;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final Serializer serializer;
    private final ThreadLocal<Boolean> running = new ThreadLocal<>();
    private final Limits limits = new Limits();

    interface Serializer { String serialize(Object value) throws Throwable; }

    public DebugMessageSnapshot(BooleanSupplier enabled, Consumer<String> log, LongSupplier nanoClock) {
        this(enabled, log, nanoClock, DebugMessageSnapshot::hostJson);
    }

    DebugMessageSnapshot(BooleanSupplier enabled, Consumer<String> log, LongSupplier nanoClock,
                         Serializer serializer) {
        this.enabled = Objects.requireNonNull(enabled);
        this.log = Objects.requireNonNull(log);
        this.clock = Objects.requireNonNull(nanoClock);
        this.serializer = Objects.requireNonNull(serializer);
    }

    /** Invalidates in-flight captures as well as the quota and deduplication caches. */
    public void reset() { limits.reset(); }

    /** Never calls DTO getters, toString, or recursively searches arbitrary object fields. */
    public void capture(String hook, String stage, Object owner, List<?> arguments, Object result) {
        if (!enabled() || Boolean.TRUE.equals(running.get())) return;
        running.set(Boolean.TRUE);
        try {
            long epoch = limits.epoch();
            String id = safeLabel(hook);
            String phase = "after".equals(stage) ? "after" : "before";
            List<Object> values = new ArrayList<>();
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            int listMessages = collect(owner, values, seen, 0);
            listMessages = collect(result, values, seen, listMessages);
            if (arguments != null) {
                int size = Math.min(arguments.size(), MAX_ARGUMENTS);
                for (int i = 0; i < size && values.size() < MAX_DTOS; i++) {
                    listMessages = collect(arguments.get(i), values, seen, listMessages);
                }
            }
            if (values.isEmpty()) return;
            long now = clock.getAsLong() / 1_000_000L;
            long dropped = limits.pendingDrops(now, epoch);
            if (dropped != 0) emit("[DouAi.MessageJson] dropped=" + dropped + " windowMs=" + WINDOW_MS, epoch);
            boolean critical = critical(id);
            // One admission per capture lets distinct owner/argument/result DTOs through together.
            if (!limits.probe(id + '|' + phase, critical, now, epoch)) return;
            for (Object value : values) {
                if (!enabled() || !limits.current(epoch)) return;
                String json;
                try { json = serializer.serialize(value); }
                catch (Throwable failure) {
                    Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    if (limits.error(now, epoch)) emit("[DouAi.MessageJson] hook=" + id + " stage=" + phase
                            + " unavailable=" + cause.getClass().getName(), epoch);
                    continue;
                }
                if (json == null || json.length() > MAX_JSON_CHARACTERS) {
                    if (limits.error(now, epoch)) emit("[DouAi.MessageJson] hook=" + id + " stage=" + phase
                            + " unavailable=" + (json == null ? "null_json" : "oversize")
                            + " chars=" + (json == null ? 0 : json.length()), epoch);
                    continue;
                }
                String key = id + '|' + phase + '|' + value.getClass().getName() + '|' + digest(json);
                if (limits.record(key, critical, now, epoch)) {
                    // Do not send directly to LSPosed: the shared pipeline must redact the full JSON first.
                    emit("[DouAi.MessageJson] hook=" + id + " stage=" + phase + " json=" + json, epoch);
                }
            }
        } catch (Throwable unavailable) {
            // Diagnostic failures must never replace the original hook's result or exception.
        } finally { running.remove(); }
    }

    private static int collect(Object value, List<Object> values, IdentityHashMap<Object, Boolean> seen,
                               int listMessages) {
        if (isDto(value)) add(value, values, seen);
        else if (value instanceof List<?> && listMessages < MAX_LIST_MESSAGES) {
            List<?> list = (List<?>) value;
            int size = Math.min(list.size(), MAX_LIST_SCAN);
            for (int i = 0; i < size && listMessages < MAX_LIST_MESSAGES && values.size() < MAX_DTOS; i++) {
                Object item = list.get(i);
                if (hasType(item, MESSAGE) && add(item, values, seen)) listMessages++;
            }
        }
        return listMessages;
    }

    private static boolean add(Object value, List<Object> values, IdentityHashMap<Object, Boolean> seen) {
        if (values.size() >= MAX_DTOS || seen.put(value, Boolean.TRUE) != null) return false;
        values.add(value);
        return true;
    }

    private static boolean isDto(Object value) { return hasType(value, MESSAGE) || hasType(value, AUDIO); }
    private static boolean hasType(Object value, String name) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }

    private boolean enabled() {
        try { return enabled.getAsBoolean(); }
        catch (Throwable unavailable) { return false; }
    }

    private void emit(String line, long epoch) {
        if (!enabled() || !limits.current(epoch)) return;
        try { log.accept(line); }
        catch (Throwable unavailable) { }
    }

    private static boolean critical(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("autoaudio") || lower.contains("privatespeech.queue");
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

    private static String hostJson(Object value) throws Throwable {
        Class<?> gson = Class.forName("com.google.gson.Gson", true, value.getClass().getClassLoader());
        Object instance = gson.getConstructor().newInstance();
        Object json = gson.getMethod("toJson", Object.class).invoke(instance, value);
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

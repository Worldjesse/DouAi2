package com.douai.module.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Structured events are written directly by DouAi-Evidence; only legacy details use this queue. */
final class DebugLogStore implements AutoCloseable {
    static final int FILE_BYTES = 1024 * 1024;
    static final int TOTAL_BYTES = FILE_BYTES * 2;
    static final int TAIL_BYTES = 128 * 1024;
    static final int ENTRY_BYTES = 256 * 1024;
    private static final int QUEUE_CHARS = 512 * 1024;
    private static final Object[] FILE_MONITORS = new Object[32];
    static { for (int index = 0; index < FILE_MONITORS.length; index++) FILE_MONITORS[index] = new Object(); }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private final ArrayDeque<Event> pending = new ArrayDeque<>();
    private final ArrayDeque<byte[]> memory = new ArrayDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { Thread thread = new Thread(runnable, "DouAi-DebugLog"); thread.setDaemon(true); return thread; });
    private final File directory;
    private final Object fileMonitor;
    private final LogLossLedger losses = new LogLossLedger();
    private int queuedChars, memoryBytes;
    private boolean scheduled, closed, clearing;
    private long dropped, totalDropped, ioFailures, serializationFailures, rotations, clears;
    private long generation;
    private String readSource = "not_read", readReason = "none";
    private String lastClear = "not_requested";

    DebugLogStore(File directory) {
        this.directory = directory;
        String path;
        try { path = directory.getCanonicalPath(); }
        catch (IOException unavailable) { path = directory.getAbsoluteFile().toPath().normalize().toString(); }
        if (java.io.File.separatorChar == '\\') path = path.toLowerCase(java.util.Locale.ROOT);
        fileMonitor = FILE_MONITORS[Math.floorMod(path.hashCode(), FILE_MONITORS.length)];
    }

    /** Call only on the evidence delivery worker, never on a host Hook or main thread. */
    void recordStructured(int priority, String json) {
        long epoch; synchronized (this) { epoch = generation; }
        recordStructured(priority, json, epoch);
    }

    private void recordStructured(int priority, String json, long epoch) {
        synchronized (this) {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (clearing && !closed && epoch == generation) {
                long left = until - System.nanoTime();
                if (left <= 0) { lose("clear_barrier_timeout", json); return; }
                try { TimeUnit.NANOSECONDS.timedWait(this, left); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); lose("delivery_interrupted", json); return; }
            }
            if (closed || epoch != generation) { lose(closed ? "store_closed" : "clear_generation", json); return; }
        }
        try {
            // Validate completeness once; do not run the already-redacted JSON through legacy text redaction.
            new org.json.JSONObject(json);
            byte[] bytes = line(priority, "DouAi.Event", false, json, "", 0).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > ENTRY_BYTES) { synchronized (this) { serializationFailures++; lose("structured_entry_capacity", json); } return; }
            write(bytes, epoch, json, true);
        } catch (RuntimeException | org.json.JSONException | LinkageError invalid) {
            synchronized (this) { serializationFailures++; lose("serialization_failure", json); }
        }
    }

    /** One bounded startup batch; initialization never performs disk IO on the host main thread. */
    void replayStartup(java.util.List<String[]> records) {
        long epoch; synchronized (this) { epoch = generation; }
        execute(() -> {
            for (String[] record : records) {
                String identity = record == null || record.length < 3 ? "" : record[2];
                try {
                    synchronized (this) { if (epoch != generation || closed) { losses.record("user_clear_startup", identity); continue; } }
                    if ("structured".equals(record[3])) {
                        int priority = Integer.parseInt(record[0]);
                        DouAiLog.deliverStartupRecord(priority, record[2], projected -> recordStructured(priority, projected, epoch));
                    }
                    else synchronized (this) { if (epoch == generation && !closed) record(false, Integer.parseInt(record[0]), record[1], record[2], null); }
                } catch (RuntimeException | LinkageError invalid) {
                    synchronized (this) { serializationFailures++; lose("startup_replay_invalid_record", identity); }
                }
            }
        }, () -> { for (String[] record : records) lose("startup_replay_unavailable", record == null || record.length < 3 ? "" : record[2]); });
    }

    private synchronized void lose(String reason, String json) {
        totalDropped++; losses.record(reason, json == null ? "" : json);
    }

    synchronized void record(boolean detail, int priority, String tag, String message, Throwable failure) {
        if (closed) { lose("store_closed", message); return; }
        String raw = message == null ? "" : message;
        if (raw.length() > DebugLogRedactor.MAX_INPUT_CHARS) {
            int json = raw.indexOf("json=");
            raw = json >= 0 ? raw.substring(0, Math.min(json + 5, 1024)) + "{\"_debug_json_truncated\":true}"
                    : raw.trim().startsWith("{") || raw.trim().startsWith("[") ? "{\"_debug_json_truncated\":true}"
                    : raw.substring(0, DebugLogRedactor.MAX_INPUT_CHARS) + " [input truncated]";
        }
        raw = DebugLogRedactor.text(raw);
        String trace = DebugLogRedactor.text(throwable(failure));
        int size = raw.length() + trace.length();
        if (size > QUEUE_CHARS || queuedChars + size > QUEUE_CHARS || pending.size() >= 128) {
            if (!detail) {
                java.util.Iterator<Event> iterator = pending.iterator();
                while (iterator.hasNext() && (queuedChars + size > QUEUE_CHARS || pending.size() >= 128)) {
                    Event queued = iterator.next();
                    if (queued.detail) { iterator.remove(); queuedChars -= queued.message.length() + queued.trace.length(); dropped++; lose("legacy_queue_evicted", queued.message); }
                }
            }
            if (size > QUEUE_CHARS || queuedChars + size > QUEUE_CHARS || pending.size() >= 128) { dropped++; lose("legacy_queue_capacity", raw); return; }
        }
        String eventTag = tag == null ? "DouAi" : tag.substring(0, Math.min(256, tag.length()));
        pending.addLast(new Event(detail, priority, eventTag, raw, trace, generation));
        queuedChars += size;
        schedule();
    }

    private void schedule() {
        if (scheduled || closed || clearing) return;
        scheduled = true;
        try { worker.execute(this::drain); }
        catch (RuntimeException rejected) { scheduled = false; dropped += pending.size(); for (Event event : pending) lose("legacy_worker_rejected", event.message); pending.clear(); queuedChars = 0; }
    }

    private void drain() {
        flush(16);
        synchronized (this) { scheduled = false; if (!pending.isEmpty()) schedule(); }
    }

    private void flush(int maximum) {
        for (int index = 0; index < maximum; index++) {
            Event event;
            long skipped;
            synchronized (this) {
                if (clearing) break;
                event = pending.pollFirst();
                if (event == null) break;
                queuedChars -= event.message.length() + event.trace.length();
                skipped = dropped; dropped = 0;
            }
            try {
                String tag = DebugLogRedactor.text(event.tag).replace('\n', '_').replace('\r', '_');
                if (tag.length() > 96) tag = tag.substring(0, 96);
                byte[] bytes = bounded(line(event.priority, tag, event.detail, DebugLogRedactor.text(event.message), DebugLogRedactor.text(event.trace), skipped).getBytes(StandardCharsets.UTF_8), ENTRY_BYTES, false);
                write(bytes, event.generation, event.message, false);
            } catch (RuntimeException | LinkageError ignored) { synchronized (this) { serializationFailures++; lose("legacy_serialization_failure", event.message); } }
        }
    }

    private static String line(int priority, String tag, boolean detail, String message, String trace, long skipped) {
        return TIME.format(Instant.now()) + " " + priority(priority) + "/" + tag
                + (detail ? " [detail] " : " [basic] ") + message
                + (trace.isEmpty() ? "" : "\n" + trace)
                + (skipped == 0 ? "" : "\n[log queue dropped " + skipped + " records]") + "\n";
    }

    private void write(byte[] bytes, long epoch, String identity, boolean structured) {
        // The monitor also serializes separate stores in this process; the OS lock covers other processes.
        synchronized (fileMonitor) {
            synchronized (this) {
                if (epoch != generation || clearing || closed) { lose("clear_or_close_generation", identity); return; }
                if (structured && bytes.length > TAIL_BYTES) {
                    losses.record("memory_entry_capacity", identity);
                    remember("{\"event\":\"memory_record_omitted\",\"reason_code\":\"memory_entry_capacity\",\"see\":\"storage_integrity\"}\n".getBytes(StandardCharsets.UTF_8));
                } else remember(bytes);
            }
            try {
                locked(() -> {
                    synchronized (this) {
                        if (epoch != generation || clearing || closed) { lose("clear_or_close_generation", identity); return null; }
                    }
                    append(bytes); return null;
                });
            } catch (IOException | RuntimeException unavailable) {
                synchronized (this) { ioFailures++; lose("disk_write_failure_memory_only", identity); }
            }
        }
    }

    synchronized String memoryTail(int maxChars) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(memoryBytes);
        for (byte[] line : memory) output.write(line, 0, line.length);
        return characters(new String(output.toByteArray(), StandardCharsets.UTF_8), maxChars);
    }

    private synchronized void remember(byte[] line) {
        byte[] tail = bounded(line, TAIL_BYTES, true);
        while (!memory.isEmpty() && memoryBytes + tail.length > TAIL_BYTES) memoryBytes -= memory.removeFirst().length;
        memory.addLast(tail); memoryBytes += tail.length;
    }

    void read(int maxChars, boolean full, Consumer<String> callback) {
        execute(() -> {
            flush(128);
            String text;
            try { text = locked(() -> readFiles(full ? TOTAL_BYTES : TAIL_BYTES)); synchronized (this) { readSource = "disk"; readReason = "none"; } }
            catch (IOException | RuntimeException unavailable) { text = memoryTail(maxChars); synchronized (this) { readSource = "memory_fallback"; readReason = "disk_read_failure"; } }
            if (text.isEmpty()) {
                String memoryText = memoryTail(maxChars);
                if (!memoryText.isEmpty()) { text = memoryText; synchronized (this) { readSource = "memory_fallback"; readReason = "disk_empty_memory_present"; } }
            }
            callback.accept(snapshotText(text, maxChars, full));
        }, () -> { synchronized (this) { readSource = "memory_fallback"; readReason = "read_worker_unavailable"; } callback.accept(snapshotText(memoryTail(maxChars), maxChars, full)); });
    }

    private String snapshotText(String text, int max, boolean full) {
        String status; synchronized (this) { status = full || "memory_fallback".equals(readSource) ? readStatus() : ""; }
        int capacity = Math.max(1, max);
        if (status.length() >= capacity) return "[storage status truncated]".substring(0, Math.min(capacity, 26));
        return status + (full ? utf8Tail(text, Math.max(1, capacity - status.getBytes(StandardCharsets.UTF_8).length))
                : characters(text, Math.max(1, capacity - status.length())));
    }

    void clear(Consumer<Boolean> callback) {
        synchronized (this) {
            generation++; clears++; clearing = true; lastClear = "pending";
            for (Event event : pending) losses.record("user_clear_pending", event.message);
            pending.clear(); queuedChars = 0; memory.clear(); memoryBytes = 0; dropped = 0;
        }
        execute(() -> {
            boolean cleared;
            try { cleared = locked(() -> {
                account(new File(directory, "debug.1.log"), "user_clear_disk_requested"); account(new File(directory, "debug.log"), "user_clear_disk_requested");
                delete(new File(directory, "debug.1.log")); delete(new File(directory, "debug.log")); return true;
            }); }
            catch (IOException | RuntimeException unavailable) { cleared = false; synchronized (this) { ioFailures++; } }
            synchronized (this) { clearing = false; lastClear = cleared ? "success" : "failed_or_partial"; notifyAll(); if (!pending.isEmpty()) schedule(); }
            callback.accept(cleared);
        }, () -> { synchronized (this) { clearing = false; lastClear = "worker_unavailable"; notifyAll(); if (!pending.isEmpty()) schedule(); } callback.accept(false); });
    }

    private void execute(Runnable task, Runnable unavailable) {
        synchronized (this) { if (closed) { unavailable.run(); return; } }
        try { worker.execute(task); } catch (RuntimeException rejected) { unavailable.run(); }
    }

    private <T> T locked(Io<T> action) throws IOException {
        synchronized (fileMonitor) {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("Log directory unavailable");
            try (RandomAccessFile guard = new RandomAccessFile(new File(directory, "debug.lock"), "rw"); FileLock ignored = guard.getChannel().lock()) { return action.run(); }
        }
    }

    private void append(byte[] bytes) throws IOException {
        File current = new File(directory, "debug.log"), previous = new File(directory, "debug.1.log");
        if (current.length() + bytes.length > FILE_BYTES) {
            account(previous, "retention_rotation_candidate");
            delete(previous);
            if (current.exists() && !current.renameTo(previous)) throw new IOException("Log rotation unavailable");
            synchronized (this) { rotations++; }
        }
        try (FileOutputStream output = new FileOutputStream(current, true)) { output.write(bytes); }
    }

    private void account(File file, String reason) throws IOException {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int marker = line.indexOf("/DouAi.Event [basic] ");
                if (marker >= 0) losses.record(reason, line.substring(marker + "/DouAi.Event [basic] ".length()));
            }
        }
    }

    private String readFiles(int limit) throws IOException {
        File current = new File(directory, "debug.log"), previous = new File(directory, "debug.1.log");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, TOTAL_BYTES));
        long currentBytes = Math.min(current.length(), limit);
        tail(previous, Math.max(0, limit - (int) currentBytes), output);
        tail(current, (int) currentBytes, output);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void tail(File file, int limit, ByteArrayOutputStream output) throws IOException {
        if (limit <= 0 || !file.isFile()) return;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] marker = "[tail truncated]\n".getBytes(StandardCharsets.UTF_8);
            long start = Math.max(0L, input.length() - limit);
            if (start > 0) {
                if (limit <= marker.length) { output.write(marker, 0, limit); return; }
                start = input.length() - limit + marker.length; input.seek(start);
                // Skip the partial first record, including its UTF-8 prefix, rather than exporting broken JSON.
                int value; while ((value = input.read()) != -1 && value != '\n') { }
                output.write(marker);
            } else input.seek(0);
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        }
    }

    private static void delete(File file) throws IOException { if (file.exists() && !file.delete()) throw new IOException("Log file unavailable"); }
    private static String priority(int value) { return value >= 6 ? "E" : value == 5 ? "W" : value == 4 ? "I" : value == 3 ? "D" : "V"; }
    static String characters(String value, int max) {
        int limit = Math.max(1, max); if (value.length() <= limit) return value;
        String marker = "[tail truncated]\n";
        if (limit <= marker.length()) return marker.substring(0, limit);
        int start = value.length() - limit + marker.length();
        if (start > 0 && Character.isLowSurrogate(value.charAt(start))) start++;
        int newline = value.indexOf('\n', start);
        if (newline >= 0) start = newline + 1;
        else if (value.contains("/DouAi.Event [basic] ")) return marker;
        return marker + value.substring(start);
    }
    private static byte[] bounded(byte[] value, int limit, boolean tail) {
        if (value.length <= limit) return value;
        byte[] marker = (tail ? "[tail truncated]\n" : "\n[record truncated]\n").getBytes(StandardCharsets.UTF_8);
        int position = tail ? value.length - limit + marker.length : limit - marker.length;
        if (tail) while (position < value.length && (value[position] & 0xc0) == 0x80) position++;
        else while (position > 0 && (value[position] & 0xc0) == 0x80) position--;
        byte[] result = new byte[marker.length + (tail ? value.length - position : position)];
        if (tail) { System.arraycopy(marker, 0, result, 0, marker.length); System.arraycopy(value, position, result, marker.length, value.length - position); }
        else { System.arraycopy(value, 0, result, 0, position); System.arraycopy(marker, 0, result, position, marker.length); }
        return result;
    }
    static String utf8Tail(String value, int limit) {
        if (limit < 32) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= limit) return value;
            return "[tail truncated]\n".substring(0, Math.max(0, Math.min(17, limit)));
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return value;
        byte[] marker = "[tail truncated]\n".getBytes(StandardCharsets.UTF_8);
        int start = bytes.length - limit + marker.length;
        while (start < bytes.length && bytes[start] != '\n') start++;
        if (start < bytes.length) start++;
        return new String(marker, StandardCharsets.UTF_8) + new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static String throwable(Throwable value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        try {
            for (int depth = 0; value != null && depth < 3; depth++) {
                if (depth > 0) result.append("Caused by: ");
                result.append(value.getClass().getName()).append(": ");
                String message = value.getMessage();
                if (message != null) result.append(message, 0, Math.min(4096, message.length()));
                result.append('\n');
                StackTraceElement[] stack = value.getStackTrace();
                for (int index = 0; index < Math.min(24, stack.length); index++) result.append(" at ").append(stack[index]).append('\n');
                Throwable next = value.getCause(); if (next == value) break; value = next;
            }
        } catch (RuntimeException ignored) { }
        return result.toString();
    }

    synchronized String integrity() {
        return "{\"event\":\"storage_integrity\",\"lost_count\":" + totalDropped + ",\"io_failures\":" + ioFailures
                + ",\"serialization_failures\":" + serializationFailures + ",\"rotations\":" + rotations
                + ",\"clears\":" + clears + ",\"last_clear_status\":\"" + lastClear + "\",\"pending_count\":" + pending.size()
                + ",\"lost_count_semantics\":\"rejected_or_not_persisted_memory_may_retain\",\"structured_delivery\":\"evidence_worker_direct_no_secondary_queue\",\"read_source\":\"" + readSource
                + "\",\"read_reason\":\"" + readReason + "\",\"affected_records\":" + losses.json()
                + ",\"file_capacity_bytes\":" + TOTAL_BYTES + ",\"retention\":\"bounded_tail\",\"retention_accounting\":\"discard_candidates_check_io_failures_and_clear_status\",\"external_loss\":\"unknown\"}";
    }

    private String readStatus() { return "{\"event\":\"storage_read_status\",\"source\":\"" + readSource + "\",\"reason_code\":\"" + readReason + "\",\"complete\":\"not_guaranteed_bounded_tail\"}\n"; }

    @Override public synchronized void close() { closed = true; for (Event event : pending) lose("store_close_pending", event.message); pending.clear(); queuedChars = 0; notifyAll(); worker.shutdownNow(); }
    private interface Io<T> { T run() throws IOException; }
    private static final class Event {
        final long when = System.currentTimeMillis();
        final boolean detail; final int priority; final String tag, message, trace; final long generation;
        Event(boolean detail, int priority, String tag, String message, String trace, long generation) { this.detail = detail; this.priority = priority; this.tag = tag; this.message = message; this.trace = trace; this.generation = generation; }
    }
}

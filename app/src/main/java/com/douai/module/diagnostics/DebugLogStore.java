package com.douai.module.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

/** One bounded in-process queue; all disk work and redaction run on its single worker. */
final class DebugLogStore implements AutoCloseable {
    static final int FILE_BYTES = 1024 * 1024;
    static final int TOTAL_BYTES = FILE_BYTES * 2;
    static final int TAIL_BYTES = 128 * 1024;
    static final int ENTRY_BYTES = 256 * 1024;
    private static final int QUEUE_CHARS = 512 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private final ArrayDeque<Event> pending = new ArrayDeque<>();
    private final ArrayDeque<byte[]> memory = new ArrayDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { Thread thread = new Thread(runnable, "DouAi-DebugLog"); thread.setDaemon(true); return thread; });
    private final File directory;
    private int queuedChars, memoryBytes;
    private boolean scheduled, closed, clearing;
    private long dropped;
    private long generation;

    DebugLogStore(File directory) { this.directory = directory; }

    synchronized void record(boolean detail, int priority, String tag, String message, Throwable failure) {
        if (closed) return;
        String raw = message == null ? "" : message;
        if (raw.length() > DebugLogRedactor.MAX_INPUT_CHARS) {
            int json = raw.indexOf("json=");
            raw = json >= 0 ? raw.substring(0, Math.min(json + 5, 1024)) + "{\"_debug_json_truncated\":true}"
                    : raw.trim().startsWith("{") || raw.trim().startsWith("[") ? "{\"_debug_json_truncated\":true}"
                    : raw.substring(0, DebugLogRedactor.MAX_INPUT_CHARS) + " [input truncated]";
        }
        String trace = throwable(failure);
        int size = raw.length() + trace.length();
        if (size > QUEUE_CHARS || queuedChars + size > QUEUE_CHARS || pending.size() >= 128) { dropped++; return; }
        String eventTag = tag == null ? "DouAi" : tag.substring(0, Math.min(256, tag.length()));
        pending.addLast(new Event(detail, priority, eventTag, raw, trace, generation));
        queuedChars += size;
        schedule();
    }

    private void schedule() {
        if (scheduled || closed || clearing) return;
        scheduled = true;
        try { worker.execute(this::drain); }
        catch (RuntimeException rejected) { scheduled = false; dropped += pending.size(); pending.clear(); queuedChars = 0; }
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
                String line = TIME.format(Instant.ofEpochMilli(event.when)) + " " + priority(event.priority) + "/" + tag
                        + (event.detail ? " [detail] " : " [basic] ") + DebugLogRedactor.text(event.message)
                        + (event.trace.isEmpty() ? "" : "\n" + DebugLogRedactor.text(event.trace))
                        + (skipped == 0 ? "" : "\n[log queue dropped " + skipped + " records]") + "\n";
                byte[] bytes = bounded(line.getBytes(StandardCharsets.UTF_8), ENTRY_BYTES, false);
                synchronized (this) { if (event.generation != generation) continue; remember(bytes); }
                try { locked(() -> { append(bytes); return null; }); } catch (IOException | RuntimeException ignored) { }
            } catch (RuntimeException | LinkageError ignored) { }
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
            try { text = locked(() -> readFiles(full ? TOTAL_BYTES : TAIL_BYTES)); }
            catch (IOException | RuntimeException unavailable) { text = memoryTail(maxChars); }
            if (text.isEmpty()) text = memoryTail(maxChars);
            callback.accept(characters(text, maxChars));
        }, () -> callback.accept(memoryTail(maxChars)));
    }

    void clear(Consumer<Boolean> callback) {
        synchronized (this) { generation++; clearing = true; pending.clear(); queuedChars = 0; memory.clear(); memoryBytes = 0; dropped = 0; }
        execute(() -> {
            boolean cleared;
            try { cleared = locked(() -> { delete(new File(directory, "debug.1.log")); delete(new File(directory, "debug.log")); return true; }); }
            catch (IOException | RuntimeException unavailable) { cleared = false; }
            synchronized (this) { clearing = false; if (!pending.isEmpty()) schedule(); }
            callback.accept(cleared);
        }, () -> { synchronized (this) { clearing = false; if (!pending.isEmpty()) schedule(); } callback.accept(false); });
    }

    private void execute(Runnable task, Runnable unavailable) {
        synchronized (this) { if (closed) { unavailable.run(); return; } }
        try { worker.execute(task); } catch (RuntimeException rejected) { unavailable.run(); }
    }

    private <T> T locked(Io<T> action) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("Log directory unavailable");
        try (RandomAccessFile guard = new RandomAccessFile(new File(directory, "debug.lock"), "rw"); FileLock ignored = guard.getChannel().lock()) { return action.run(); }
    }

    private void append(byte[] bytes) throws IOException {
        File current = new File(directory, "debug.log"), previous = new File(directory, "debug.1.log");
        if (current.length() + bytes.length > FILE_BYTES) {
            delete(previous);
            if (current.exists() && !current.renameTo(previous)) throw new IOException("Log rotation unavailable");
        }
        try (FileOutputStream output = new FileOutputStream(current, true)) { output.write(bytes); }
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
                int value; while ((value = input.read()) != -1 && (value & 0xc0) == 0x80) { }
                if (value != -1) input.seek(input.getFilePointer() - 1);
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
        return limit <= marker.length() ? marker.substring(0, limit) : marker + value.substring(value.length() - limit + marker.length());
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

    @Override public synchronized void close() { closed = true; pending.clear(); queuedChars = 0; worker.shutdownNow(); }
    private interface Io<T> { T run() throws IOException; }
    private static final class Event {
        final long when = System.currentTimeMillis();
        final boolean detail; final int priority; final String tag, message, trace; final long generation;
        Event(boolean detail, int priority, String tag, String message, String trace, long generation) { this.detail = detail; this.priority = priority; this.tag = tag; this.message = message; this.trace = trace; this.generation = generation; }
    }
}

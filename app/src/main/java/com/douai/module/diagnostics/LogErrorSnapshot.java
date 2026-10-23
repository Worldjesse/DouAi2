package com.douai.module.diagnostics;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/** Identity correlation without retaining Throwable objects or trusting their arbitrary messages. */
final class LogErrorSnapshot {
    private static final java.util.Set<String> SAFE_LOCAL_MESSAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "模型配置为空", "模型配置无法序列化", "模型配置标识无效", "配置名称需要 1 至 80 个字符",
            "接口协议无效", "响应方式无效", "推理强度无效", "请填写或获取模型名称", "模型名称无效", "API 密钥格式无效",
            "上下文轮数应为 0 至 50", "超时应为 5 至 120 秒", "温度超出当前协议范围", "最大输出 Token 应为 1 至 131072", "Top P 应为 0 至 1",
            "请设置有效的 MiniMax API Key", "语音来源无效", "请输入有效的 MiniMax API 地址，不含认证、查询参数或片段",
            "请输入有效的 MiniMax 模型", "请输入有效的 MiniMax 音色 ID", "MiniMax 音色名称无效",
            "第三方语音链接未返回音频文件", "无法创建语音缓存", "生成的语音文件无效",
            "保存位置无效，请重新选择目录", "文件名无效", "无法在所选目录创建文件"));
    private static final ReferenceQueue<Throwable> COLLECTED = new ReferenceQueue<>();
    private static final LinkedHashMap<Key, Identity> IDS = new LinkedHashMap<>();
    final String id, type, messageRef, message, causes, stack;
    final boolean first;
    final boolean truncated;

    private LogErrorSnapshot(String id, boolean first, Throwable failure) {
        this.id = id; this.first = first; type = failure.getClass().getName();
        String raw;
        try { raw = failure.getMessage(); } catch (RuntimeException unavailable) { raw = null; }
        messageRef = DouAiLog.reference(raw); message = summary(failure, raw);
        StringBuilder causeText = new StringBuilder(), frames = new StringBuilder();
        boolean limited = false;
        if (first) try {
            Throwable current = failure;
            for (int depth = 0; current != null && depth < 3; depth++) {
                if (depth > 0) causeText.append(" <- ");
                causeText.append(current.getClass().getName()).append(": ").append(summary(current, current.getMessage()));
                StackTraceElement[] trace = current.getStackTrace();
                limited |= trace.length > 8;
                for (int i = 0; i < Math.min(8, trace.length); i++) {
                    if (frames.length() >= 1024) { limited = true; break; }
                    frames.append(trace[i]).append(';');
                }
                Throwable next = current.getCause(); if (next == current) break; current = next;
                if (depth == 2 && current != null) limited = true;
            }
        } catch (RuntimeException ignored) { limited = true; }
        truncated = limited || causeText.length() > 1024 || frames.length() > 1024;
        causes = bound(causeText.toString()); stack = bound(frames.toString());
    }
    static LogErrorSnapshot capture(Throwable failure) {
        return capture(failure, true);
    }
    static LogErrorSnapshot capture(Throwable failure, boolean detailed) {
        String id; boolean first;
        synchronized (IDS) {
            Key dead; while ((dead = (Key) COLLECTED.poll()) != null) IDS.remove(dead);
            Key key = new Key(failure); Identity entry = IDS.get(key);
            if (entry == null) {
                entry = new Identity();
                if (IDS.size() >= 128) IDS.remove(IDS.keySet().iterator().next());
                IDS.put(key, entry);
            }
            id = entry.id; first = detailed && !entry.detailedCaptured;
            if (detailed) entry.detailedCaptured = true;
        }
        return new LogErrorSnapshot(id, first, failure);
    }
    static void contextDelivered(String id) {
        synchronized (IDS) {
            for (Identity entry : IDS.values()) if (entry.id.equals(id)) { entry.detailedDelivered = true; break; }
        }
    }
    static void contextNotDelivered(String id) {
        synchronized (IDS) {
            for (Identity entry : IDS.values()) if (entry.id.equals(id) && !entry.detailedDelivered) {
                entry.detailedCaptured = false; break;
            }
        }
    }
    static void retryUndeliveredContexts() {
        synchronized (IDS) {
            for (Identity entry : IDS.values()) if (!entry.detailedDelivered) entry.detailedCaptured = false;
        }
    }
    private static final class Identity {
        final String id = UUID.randomUUID().toString(); boolean detailedCaptured, detailedDelivered;
    }
    /** Static descriptions only: a provider can put a private request body in any exception message. */
    private static String summary(Throwable failure, String raw) {
        if ((failure instanceof IllegalArgumentException || failure instanceof IllegalStateException)
                && raw != null && raw.length() <= 128 && SAFE_LOCAL_MESSAGES.contains(raw)) return raw;
        if (failure instanceof java.util.concurrent.CancellationException || failure instanceof InterruptedException) return "operation_cancelled_or_interrupted";
        if (failure instanceof java.net.SocketTimeoutException || failure instanceof java.util.concurrent.TimeoutException) return "local_wait_timeout_remote_result_unknown";
        if (failure instanceof java.io.EOFException) return "stream_ended_before_expected_completion";
        if (failure instanceof java.io.FileNotFoundException) return "file_missing_or_inaccessible";
        if (failure instanceof SecurityException) return "permission_or_security_check_failed";
        if (failure instanceof java.net.UnknownHostException) return "host_name_resolution_failed";
        if (failure instanceof java.net.ConnectException) return "connection_could_not_be_established";
        String text = raw == null || raw.length() > 80 ? "" : raw.toLowerCase(Locale.ROOT);
        if (text.equals("socket closed") || text.equals("stream closed") || text.equals("closed")) return "resource_closed";
        if (text.equals("connection reset") || text.equals("connection reset by peer")) return "connection_reset";
        if (text.equals("broken pipe")) return "broken_pipe";
        return "message_omitted_untrusted_content";
    }
    private static String bound(String value) { return DebugLogRedactor.text(value.substring(0, Math.min(1024, value.length()))); }
    private static final class Key extends WeakReference<Throwable> {
        final int hash;
        Key(Throwable value) { super(value, COLLECTED); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other == this || other instanceof Key && get() != null && get() == ((Key) other).get();
        }
    }
}

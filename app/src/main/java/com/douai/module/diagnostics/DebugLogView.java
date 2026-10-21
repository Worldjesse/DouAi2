package com.douai.module.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A read-only index of an already-redacted log tail; continuations stay with their event. */
public final class DebugLogView {
    private static final Pattern HEADER = Pattern.compile(
            "(?m)^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} [VDIWEAF]/[^\\r\\n]+? \\[(?:basic|detail)\\] ");
    private static final Pattern OUTCOME = Pattern.compile(
            "(?i)\\b(?:outcome\\s*[:=]|native callback:)\\s*(sent|submitted|failed|cancelled|skipped|package_not_found)\\b");
    private static final Pattern HOOK = Pattern.compile("\\bhook=([A-Za-z0-9._$<>-]{1,120})");
    private static final Pattern STAGE = Pattern.compile("\\b(?:stage|phase)=([A-Za-z0-9_-]{1,48})");

    public enum Category {
        ALL("全部"), RESULTS("结果与错误"), COMMENT("自动评论"), CHAT("聊天与回复"), JSON("JSON 快照"), HOOK("Hook 与界面");
        public final String label;
        Category(String label) { this.label = label; }
    }

    public enum Result { NONE, SENT, FAILED, SUBMITTED, CANCELLED, SKIPPED }

    public static final class Entry {
        public final String text;
        public final boolean json, attention;
        public final Result result;
        private final String metadata, searchable, time;
        private final boolean comment, chat, hook;

        private Entry(String text, int headerEnd) {
            this.text = text;
            this.searchable = text.toLowerCase(Locale.ROOT);
            String message = text.substring(headerEnd);
            int newline = message.indexOf('\n');
            String first = newline < 0 ? message : message.substring(0, newline);
            int jsonStart = first.indexOf("json=");
            metadata = jsonStart < 0 ? first : first.substring(0, jsonStart);
            String lower = metadata.toLowerCase(Locale.ROOT);
            json = jsonStart >= 0 && (lower.startsWith("[douai.commentjson]") || lower.startsWith("[douai.messagejson]")
                    || lower.startsWith("[douai.configuration]"));
            comment = lower.startsWith("automatic comment") || lower.startsWith("comment reply")
                    || lower.contains("auto_comment") || lower.contains("autocomment")
                    || lower.contains("commentreply") || lower.contains("commentaudio")
                    || lower.contains("[douai.commentjson]");
            chat = lower.startsWith("automatic reply") || lower.startsWith("native private audio")
                    || lower.startsWith("native automatic audio") || lower.contains("privatespeech")
                    || lower.contains("autoaudio") || lower.contains("native_reply")
                    || lower.contains("prevent_message_recall") || lower.contains("message recall") || lower.contains("[douai.messagejson]")
                    || lower.contains("[douai.replyoverlay]") || lower.contains("streak");
            hook = lower.startsWith("[douai.trace]") || lower.contains("overlay]")
                    || lower.startsWith("hook call threw;");
            result = json ? Result.NONE : result(lower);
            String header = text.substring(0, headerEnd);
            attention = !json && (result == Result.FAILED || header.matches("(?s).* [WEAF]/.*")
                    || lower.contains("unavailable") || lower.contains("rejected") || lower.contains("unsupported")
                    || lower.contains("timed out") || lower.contains("timeout") || lower.contains("could not"));
            time = headerEnd > 0 && text.length() >= 23 ? text.substring(11, 23) : "日志条目";
        }

        public String title() {
            StringBuilder title = new StringBuilder(time);
            title.append(" · ").append(json ? "JSON" : chat ? "聊天" : comment ? "评论" : hook ? "Hook" : "日志");
            Matcher name = HOOK.matcher(metadata);
            boolean hasName = name.find();
            if (hasName) title.append(" · ").append(name.group(1));
            Matcher stage = STAGE.matcher(metadata);
            if (stage.find()) title.append(" · ").append(stage.group(1));
            if (result != Result.NONE) title.append(" · ").append(resultLabel(result));
            else if (!hasName && !metadata.isEmpty()) {
                String description = metadata.replace('\r', ' ').trim();
                title.append(" · ").append(description, 0, Math.min(72, description.length()));
            }
            return title.toString();
        }

        private boolean matches(Category category, String query) {
            boolean selected = category == Category.ALL
                    || category == Category.RESULTS && (result != Result.NONE || attention)
                    || category == Category.COMMENT && comment || category == Category.CHAT && chat
                    || category == Category.JSON && json || category == Category.HOOK && hook;
            return selected && (query.isEmpty() || searchable.contains(query));
        }
    }

    public static final class Selection {
        public final List<Entry> entries;
        public final String text, summary;
        private Selection(List<Entry> entries, String text, int total) {
            this.entries = Collections.unmodifiableList(entries);
            this.text = text;
            int sent = 0, failed = 0, submitted = 0, cancelled = 0, json = 0;
            for (Entry entry : entries) {
                if (entry.result == Result.SENT) sent++;
                if (entry.result == Result.FAILED) failed++;
                if (entry.result == Result.SUBMITTED) submitted++;
                if (entry.result == Result.CANCELLED) cancelled++;
                if (entry.json) json++;
            }
            summary = "显示 " + entries.size() + " / " + total + " 条 · 成功 " + sent + " · 失败 " + failed
                    + " · 待确认 " + submitted + " · 取消 " + cancelled + " · JSON " + json;
        }
    }

    private final String original;
    private final List<Entry> entries;
    private DebugLogView(String original, List<Entry> entries) { this.original = original; this.entries = entries; }

    public static DebugLogView parse(String text) {
        String input = text == null ? "" : text;
        List<Entry> entries = new ArrayList<>();
        if (input.trim().isEmpty() || "暂无调试日志".equals(input.trim())) return new DebugLogView(input, entries);
        Matcher headers = HEADER.matcher(input);
        int start = 0, headerEnd = 0;
        boolean found = false;
        while (headers.find()) {
            if (headers.start() > start) entries.add(new Entry(input.substring(start, headers.start()), headerEnd));
            start = headers.start();
            headerEnd = headers.end() - start;
            found = true;
        }
        if (start < input.length()) entries.add(new Entry(input.substring(start), found ? headerEnd : 0));
        return new DebugLogView(input, entries);
    }

    public Selection select(Category category, String query) {
        Category requested = category == null ? Category.ALL : category;
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (search.length() > 128) search = search.substring(0, 128);
        List<Entry> selected = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Entry entry : entries) if (entry.matches(requested, search)) { selected.add(entry); text.append(entry.text); }
        return new Selection(selected, requested == Category.ALL && search.isEmpty() ? original : text.toString(), entries.size());
    }

    private static Result result(String value) {
        Matcher outcome = OUTCOME.matcher(value);
        if (outcome.find()) {
            switch (outcome.group(1).toLowerCase(Locale.ROOT)) {
                case "sent": return Result.SENT;
                case "submitted": return Result.SUBMITTED;
                case "failed": return Result.FAILED;
                case "cancelled": return Result.CANCELLED;
                default: return Result.SKIPPED;
            }
        }
        if (value.contains(" throws=") || value.startsWith("hook call threw;") || value.contains(" failed")
                || value.startsWith("failed") || value.contains(" failure")) return Result.FAILED;
        if (value.contains(" skipped ") || value.contains(" skipped:")) return Result.SKIPPED;
        return Result.NONE;
    }

    private static String resultLabel(Result result) {
        switch (result) {
            case SENT: return "发送成功";
            case FAILED: return "失败";
            case SUBMITTED: return "待确认";
            case CANCELLED: return "已取消";
            case SKIPPED: return "已跳过";
            default: return "";
        }
    }
}

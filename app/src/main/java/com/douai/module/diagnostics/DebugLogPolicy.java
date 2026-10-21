package com.douai.module.diagnostics;

import java.util.Locale;

/** The default log explains outcomes; opt-in debug mode supplies steps and payload snapshots. */
public final class DebugLogPolicy {
    private DebugLogPolicy() { }

    public static boolean isBasic(int priority, String message) {
        if (message == null || message.startsWith("[DouAi.")) return false;
        if (priority >= 5) return true;
        String value = message.toLowerCase(Locale.ROOT);
        if (value.startsWith("comment reply notice skipped")
                || value.startsWith("automatic comment selection skipped:")) return false;
        if (value.startsWith("comment reply inbox scan complete:")) {
            return value.matches(".*\\bdiagnostic_errors=[1-9][0-9]*.*");
        }
        return value.startsWith("douai ") || value.startsWith("selected host profile ")
                || value.startsWith("host scan complete:") || value.startsWith("host features ready")
                || value.startsWith("configuration updated:") || value.startsWith("debug logging ")
                || value.contains("outcome=") || value.startsWith("automatic ") && value.contains(" outcome: ")
                || value.contains("native callback:")
                || value.contains("failed") || value.contains("failure") || value.contains("unsupported")
                || value.contains("could not") || value.contains("unavailable") || value.contains("rejected")
                || value.contains("timed out") || value.contains("timeout") || value.contains("exception")
                || value.contains("error") || value.contains("失败") || value.contains("无法") || value.contains("超时")
                || value.contains("completed") || value.contains("success") || value.contains("cancelled")
                || value.contains("auto-liked") || value.contains("auto-followed");
    }

    public static String basicMessage(String message) {
        if (message == null) return "";
        if (message.contains("native callback:")) {
            int details = message.indexOf(';');
            if (details > 0) {
                StringBuilder result = new StringBuilder(message.substring(0, details));
                java.util.regex.Matcher fields = java.util.regex.Pattern.compile(
                        "; (code|kind|media|audio_hint)=([a-z0-9_+\\-]{1,64})(?=;|$)")
                        .matcher(message.substring(details));
                while (fields.find()) result.append(fields.group());
                return result.toString();
            }
        }
        return message;
    }
}

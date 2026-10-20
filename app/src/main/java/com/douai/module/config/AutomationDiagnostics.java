package com.douai.module.config;

/** Local labels for a closed set of diagnostic codes and bounded elapsed times. */
public final class AutomationDiagnostics {
    private AutomationDiagnostics() { }

    public static String details(AutomationHistoryStore.Entry entry) {
        StringBuilder text = new StringBuilder("原因：").append(reason(entry.reason))
                .append("\n阶段：").append(stage(entry.stage));
        duration(text, "排队", entry.queueMillis);
        duration(text, "媒体读取", entry.mediaMillis);
        duration(text, "生成", entry.generationMillis);
        duration(text, "发送确认等待", entry.sendMillis);
        return text.toString();
    }

    private static void duration(StringBuilder text, String label, long millis) {
        if (millis >= 0) text.append('\n').append(label).append("耗时：").append(millis).append(" ms");
    }

    public static String reason(AutomationHistoryStore.Reason reason) {
        switch (reason) {
            case QUEUE_FULL: return "队列已满";
            case QUEUE_EXPIRED: return "排队已过期";
            case INVALIDATED: return "任务条件已失效";
            case CANCELLED: return "任务已取消";
            case SETTINGS_CHANGED: return "设置已变更";
            case MEDIA_FAILED: return "媒体读取失败";
            case GENERATION_TIMEOUT: return "生成超时";
            case GENERATION_FAILED: return "生成失败";
            case SPEECH_FAILED: return "语音生成失败";
            case SEND_FAILED: return "发送失败";
            case SUBMITTED_UNCONFIRMED: return "已提交，结果未确认";
            case LEDGER_WRITE_FAILED:
            case PERSISTENCE_FAILED: return "提交记录保存失败";
            case ALLOWANCE_EXHAUSTED: return "发送额度已用完";
            case DUPLICATE: return "重复任务";
            case AI_HTTP_ERROR: return "AI 接口请求失败";
            case AI_RATE_LIMITED: return "AI 接口限流";
            case AI_AUTH_FAILED: return "AI 接口认证失败";
            case INVALID_RESPONSE: return "接口响应无效";
            default: return "未记录";
        }
    }

    public static String stage(AutomationHistoryStore.Stage stage) {
        switch (stage) {
            case QUEUE: return "排队";
            case MEDIA: return "媒体读取";
            case GENERATION: return "生成";
            case SPEECH: return "语音生成";
            case SEND: return "发送";
            case COMPLETE: return "完成";
            default: return "未记录";
        }
    }
}

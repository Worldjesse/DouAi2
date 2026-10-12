package com.douai.module.feature;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Pattern;

/** Separates model control text from text eligible for the native comment publisher. */
final class CommentOutputPolicy {
    private static final Pattern NON_COMMENT = Pattern.compile(
            "(?is)(?:^\\s*(?:作为(?:一个|一名)?(?:AI|人工智能|语言模型)|作为助手|"
                    + "(?:抱歉[，,。!！\\s]*)?(?:我无法|我不能|我不能够|我需要(?:更多|你|您))|"
                    + "(?:以下|下面)是.{0,24}(?:评论|文案)|(?:这是|这里是|评论正文)[：:])"
                    + "|(?:请|麻烦)(?:先|再|您|你|向我|给我|您先|你先)*\\s*(?:提供|补充|上传|发送|发来|告诉我).{0,30}(?:作品|视频|图片|内容|标题|素材|描述|链接)"
                    + "|^\\s*(?:as (?:an? )?(?:ai|assistant|language model)|"
                    + "(?:sorry[,!.\\s]*)?i (?:cannot|can't|am unable to|need (?:you|more))|"
                    + "(?:please|could you|can you) (?:provide|share|upload|send)|"
                    + "here(?:'s| is| are) (?:the|a|your) (?:comment|draft)))");

    private CommentOutputPolicy() { }

    static String task(String style) {
        return "你正在生成一条可直接发布到当前作品下的评论。遵循一级提示词和下列评论要求，"
                + "只根据本次提供的作品作者、标题、文案及实际图片或视频画面写评论，"
                + "缺失的字段不自行编造，不把任务要求当作作品内容。\n"
                + "评论要求：\n" + (style == null ? "" : style)
                + "\n输出格式必须是严格 JSON 对象，且只有一个字符串字段：{\"comment\":\"最终评论正文\"}。"
                + "comment 内只保留最终评论正文，可按要求使用表情和换行；不要包含分析、解释、前言、"
                + "提问、索要素材、拒绝说明或格式包装。没有足够作品内容、无法满足要求或不应评论时，"
                + "返回 {\"comment\":\"\"}。对象外不得输出其他文字或 Markdown 代码块。";
    }

    static String parse(String output) {
        if (output == null || output.length() > 8000) throw invalid();
        try (JsonReader reader = new JsonReader(new StringReader(output))) {
            reader.setLenient(false);
            reader.beginObject();
            if (!reader.hasNext() || !"comment".equals(reader.nextName())
                    || reader.peek() != JsonToken.STRING) throw invalid();
            String comment = reader.nextString();
            if (reader.hasNext()) throw invalid();
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            if (comment.isEmpty()) return "";
            if (comment.trim().isEmpty() || comment.length() > 1000
                    || NON_COMMENT.matcher(comment).find()) throw invalid();
            return comment;
        } catch (IOException | IllegalStateException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Model output is not a publishable comment");
    }
}

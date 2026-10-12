package com.douai.module.feature;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class CommentOutputPolicyTest {
    @Test public void preservesConfiguredLongProseWhitespaceAndEmojiExactly() throws Exception {
        String prose = "这一段作品评论有具体内容。".repeat(25) + "\n\uD83D\uDC4D\u2728";
        assertEquals(prose, CommentOutputPolicy.parse(new JSONObject().put("comment", prose).toString()));
        assertEquals("", CommentOutputPolicy.parse("{\"comment\":\"\"}"));
    }

    @Test public void rejectsAnythingOutsideTheSingleStrictCommentObject() {
        String[] invalid = {
                "请提供作品内容。", "```json\n{\"comment\":\"内容\"}\n```",
                "解释文字{\"comment\":\"内容\"}", "{\"comment\":\"内容\"}额外文字",
                "{\"comment\":\"内容\",\"reason\":\"解释\"}", "{\"comment\":123}",
                "{\"comment\":null}", "{\"comment\":\"甲\",\"comment\":\"乙\"}",
                "{'comment':'内容'}", "{comment:\"内容\"}", "[\"内容\"]", "{}",
                "{\"comment\":\"请提供作品内容，我才能生成评论。\"}",
                "{\"comment\":\"I cannot write a comment without the video.\"}",
                "{\"comment\":\"以下是为你生成的评论：作品很好。\"}",
                "{\"comment\":\"   \"}", "{\"comment\":\"" + "字".repeat(1001) + "\"}"
        };
        for (String output : invalid) {
            assertThrows("Unexpectedly accepted: " + output.substring(0, Math.min(60, output.length())),
                    IllegalArgumentException.class, () -> CommentOutputPolicy.parse(output));
        }
    }

    @Test public void commentTaskPreservesUserStyleAndAddsOnlyTheOutputContract() {
        String secondary = "采用轻松语气，约三百字，可使用表情。";
        String task = CommentOutputPolicy.task(secondary);
        assertTrue(task.contains(secondary));
        assertTrue(task.contains("\"comment\""));
        assertTrue(task.contains("解释"));
    }
}

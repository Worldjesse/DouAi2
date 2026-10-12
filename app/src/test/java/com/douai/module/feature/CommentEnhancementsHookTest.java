package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public final class CommentEnhancementsHookTest {
    @Test public void suffixIsAddedOnceToCopiedArgumentsAndPreservesMentionMetadata() throws Exception {
        Object[] original = {"aid", "评论", "[{\"start\":0,\"end\":2,\"type\":0}]"};
        LocalOptions options = options("comment_append_suffix", true, "comment_suffix", "尾巴", "colored_comment", true);
        Object[] result = CommentEnhancementsHook.transform(original, 1, 2, options, "me", "sec");
        assertNotSame(original, result);
        assertEquals("评论", original[1]);
        assertEquals("评论\n尾巴", result[1]);
        assertEquals(original[2], result[2]);
        assertNull(CommentEnhancementsHook.transform(result, 1, 2, options, "me", "sec"));
    }

    @Test public void nativeColorSpanUsesCurrentUserAndUtf16Offsets() throws Exception {
        LocalOptions options = options("colored_comment", true, "colored_comment_type", 33);
        Object[] result = CommentEnhancementsHook.transform(new Object[]{"\ud83d\ude00中文", "[]"}, 0, 1, options, "1", "s1");
        assertEquals("____\ud83d\ude00中文 ", result[0]);
        JSONObject span = new JSONArray((String) result[1]).getJSONObject(0);
        assertEquals(0, span.getInt("start"));
        assertEquals(((String) result[0]).length(), span.getInt("end"));
        assertEquals(33, span.getInt("type"));
        assertEquals("1", span.getString("user_id"));
    }

    @Test public void missingAccountAndUnsupportedColorPreserveOriginalRequest() throws Exception {
        assertNull(CommentEnhancementsHook.transform(new Object[]{"内容", "[]"}, 0, 1,
                options("colored_comment", true), "", ""));
        assertNull(CommentEnhancementsHook.transform(new Object[]{"内容", "[]"}, 0, 1,
                options("colored_comment", true, "colored_comment_type", 99), "1", "sec"));
        assertNull(CommentEnhancementsHook.transform(new Object[]{" ", "[]"}, 0, 1,
                options("comment_append_suffix", true, "comment_suffix", "尾巴"), "1", "sec"));
    }

    static LocalOptions options(Object... entries) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < entries.length; index += 2) values.put((String) entries[index], entries[index + 1]);
        return new LocalOptions(values);
    }
}

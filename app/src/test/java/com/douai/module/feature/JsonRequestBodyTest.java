package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {28, 35})
public final class JsonRequestBodyTest {
    @Test public void countedUtf8LengthAndParsedBodyMatchForLargeInlineMediaAndEscapes() throws Exception {
        String text = "中文😀\"\\\n\r\t\b\f\u0000\u2028\u2029";
        String media = "a".repeat(8190) + "😀" + "b".repeat(100_000);
        JSONObject body = new JSONObject().put("text", text).put("media", media)
                .put("array", new JSONArray().put(true).put(3.25).put(JSONObject.NULL).put(new JSONObject().put("x", "y")));
        AtomicInteger maxWrite = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                maxWrite.accumulateAndGet(length, Math::max); super.write(bytes, offset, length);
            }
        };
        long length = JsonRequestBody.byteLength(body, () -> { });
        JsonRequestBody.write(body, output, () -> { });
        assertEquals(output.size(), length);
        JSONObject parsed = new JSONObject(output.toString(StandardCharsets.UTF_8));
        assertEquals(text, parsed.getString("text")); assertEquals(media, parsed.getString("media"));
        assertTrue(parsed.getJSONArray("array").getBoolean(0));
        assertEquals(3.25, parsed.getJSONArray("array").getDouble(1), 0);
        assertTrue(parsed.getJSONArray("array").isNull(2));
        assertEquals("y", parsed.getJSONArray("array").getJSONObject(3).getString("x"));
        assertTrue("transport writes stay bounded", maxWrite.get() <= 8192);
    }

    @Test public void cancellationIsCheckedWhileCountingAndSendingLargeStrings() throws Exception {
        JSONObject body = new JSONObject().put("media", "x".repeat(1_000_000));
        for (boolean counting : new boolean[]{true, false}) {
            AtomicInteger checks = new AtomicInteger();
            JsonRequestBody.Check check = () -> { if (checks.incrementAndGet() == 5) throw new CancellationException(); };
            try {
                if (counting) JsonRequestBody.byteLength(body, check);
                else JsonRequestBody.write(body, new ByteArrayOutputStream(), check);
                fail("cancelled body was serialized");
            } catch (CancellationException expected) { assertEquals(5, checks.get()); }
        }
    }
}

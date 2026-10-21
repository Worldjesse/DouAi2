package com.douai.module.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DebugLogRedactorTest {
    @Test public void recursiveJsonRetainsMediaAndRedactsNestedJsonUrlAndSecretKeys() throws Exception {
        JSONObject inner = new JSONObject().put("audio_url", "https://cdn.example/audio?id=private-signature&token=query-secret")
                .put("authorization", "Bearer inner-secret").put("media_type", "audio");
        JSONObject input = new JSONObject().put("cid", "public-cid").put("audio_vid", "public-vid").put("status", 1)
                .put("commentToken", "comment-secret").put("tts_minimax_api_key", "prefix-secret")
                .put("b", inner.toString()).put("items", new JSONArray().put(new JSONObject().put("session_token", "array-secret")));
        String safe = DebugLog.redactJson(input.toString());
        JSONObject result = new JSONObject(safe);
        assertEquals("public-cid", result.getString("cid"));
        assertEquals("public-vid", result.getString("audio_vid"));
        assertEquals(1, result.getInt("status"));
        assertEquals("audio", new JSONObject(result.getString("b")).getString("media_type"));
        for (String secret : new String[]{"private-signature", "query-secret", "inner-secret", "comment-secret", "prefix-secret", "array-secret"}) assertFalse(secret, safe.contains(secret));
        assertEquals("UI/file and LSPosed share idempotent redaction", safe, DebugLog.redactJson(safe));
    }

    @Test public void plainErrorsAndEscapedJsonCannotLeakCredentials() {
        String input = "Authorization: Bearer bearer-secret tts_minimax_api_key=key-secret session_token=token-secret "
                + "password='space secret' third_party_app_secret=app-secret url=https://host/path?q=query-secret&x=other-secret "
                + "embedded={\\\"commentToken\\\":\\\"escaped-secret\\\"}";
        String safe = DebugLog.redact(input);
        for (String secret : new String[]{"bearer-secret", "key-secret", "token-secret", "space secret", "app-secret", "query-secret", "other-secret", "escaped-secret"}) assertFalse(secret + ": " + safe, safe.contains(secret));
        assertEquals(safe, DebugLog.redact(safe));
    }

    @Test public void chatSnapshotsHideEncryptionKeysAndRetainPlaybackMetadata() throws Exception {
        JSONObject content = new JSONObject().put("encrypt_key", "encryption-secret")
                .put("secretKey", "audio-secret").put("key", "raw-secret")
                .put("skey", "native-audio-secret").put("tkey", "native-token-secret")
                .put("vid", "public-audio-id").put("md5", "public-md5").put("type", 1);
        String record = "[DouAi.MessageJson] hook=DouAi.AutoAudio stage=after json="
                + new JSONObject().put("content", content.toString()).put("aesKey", "aes-secret");
        String safe = DebugLog.redact(record);
        JSONObject result = new JSONObject(safe.substring(safe.indexOf("json=") + 5));
        JSONObject audio = new JSONObject(result.getString("content"));
        assertEquals("public-audio-id", audio.getString("vid"));
        assertEquals("public-md5", audio.getString("md5"));
        assertEquals(1, audio.getInt("type"));
        for (String secret : new String[]{"encryption-secret", "audio-secret", "raw-secret", "aes-secret", "native-audio-secret", "native-token-secret"}) {
            assertFalse(secret, safe.contains(secret));
        }
        String plain = DebugLog.redact("encrypt_key=plain-encryption aesKey='plain-aes' secretKey=plain-secret skey=plain-skey tkey='plain-tkey' key=plain-key");
        assertFalse(plain.contains("plain-encryption"));
        assertFalse(plain.contains("plain-aes"));
        assertFalse(plain.contains("plain-secret"));
        assertFalse(plain.contains("plain-skey"));
        assertFalse(plain.contains("plain-tkey"));
        assertFalse(plain.contains("plain-key"));
        assertEquals(safe, DebugLog.redact(safe));
    }

    @Test public void markerJsonStaysValidAndOversizeJsonFailsClosed() throws Exception {
        String message = "[DouAi.CommentJson] stage=after json={\"comment_token\":\"secret-value\",\"status\":10}";
        String safe = DebugLog.redact(message);
        assertEquals(10, new JSONObject(safe.substring(safe.indexOf("json=") + 5)).getInt("status"));
        assertFalse(safe.contains("secret-value"));
        String huge = "{\"text\":\"" + "x".repeat(DebugLogRedactor.MAX_INPUT_CHARS) + "\",\"secret\":\"hidden\"}";
        assertTrue(new JSONObject(DebugLog.redactJson(huge)).getBoolean("_debug_json_truncated"));
        assertFalse(DebugLog.redact("prefix json=" + huge).contains("hidden"));
    }

    @Test(timeout = 2000) public void longPlainRecordsDoNotCauseUnboundedRegexBacktracking() {
        String safe = DebugLog.redact("x".repeat(220_000) + " session_token=tail-secret wss://host/path?sig=url-secret");
        assertFalse(safe.contains("tail-secret")); assertFalse(safe.contains("url-secret"));
    }
}

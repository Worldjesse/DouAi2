package com.douai.module.config;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiModelResponseModeTest {
    @Test public void legacyProfilesDefaultToNonStreamingForEveryProtocol() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            AiModelConfig old = AiModelConfig.fromJson(profile("old").put("protocol", protocol));
            old.validate(true);
            assertEquals(AiModelConfig.RESPONSE_SYNC, old.responseMode);
            assertFalse(old.streaming());
            assertEquals(AiModelConfig.RESPONSE_SYNC, old.toJson().getString("responseMode"));
        }
    }

    @Test public void responseModesRoundTripIndependentlyOfProtocolAndSelectedProfile() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            AiModelConfig sync = AiModelConfig.fromJson(profile("sync").put("protocol", protocol)
                    .put("responseMode", AiModelConfig.RESPONSE_SYNC));
            AiModelConfig stream = AiModelConfig.fromJson(profile("stream").put("protocol", protocol)
                    .put("responseMode", AiModelConfig.RESPONSE_STREAM));
            sync.validate(true);
            stream.validate(true);
            AiModelSettings settings = AiModelSettings.parse(
                    new AiModelSettings(Arrays.asList(sync, stream), sync.id).serialize());
            assertFalse(settings.selected().streaming());
            assertTrue(settings.activate(stream.id).selected().streaming());
            assertEquals(protocol, settings.activate(stream.id).selected().protocol);
            AiModelConfig copied = AiModelConfig.fromJson(stream.toJson().put("id", "copy"));
            copied.validate(true);
            assertTrue(copied.streaming());
        }
    }

    @Test public void invalidExplicitResponseModesAreRejectedInsteadOfSilentlyChangingMode() throws Exception {
        for (Object value : new Object[]{"", "auto", "STREAMING", "true", false, 1, JSONObject.NULL}) {
            AiModelConfig invalid = AiModelConfig.fromJson(profile("invalid").put("responseMode", value));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> invalid.validate(true));
            assertEquals("响应方式无效", failure.getMessage());
        }
    }

    private static JSONObject profile(String id) throws Exception {
        return new JSONObject().put("id", id).put("name", "配置 " + id)
                .put("apiEndpoint", "https://example.test/v1").put("modelName", "fixture-model");
    }
}

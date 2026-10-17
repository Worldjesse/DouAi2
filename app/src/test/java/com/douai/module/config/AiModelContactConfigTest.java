package com.douai.module.config;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiModelContactConfigTest {
    public static final String AVATAR = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9z8AAAAASUVORK5CYII=";

    @Test public void oldProfilesDefaultToNoContactAndUseConfigurationName() throws Exception {
        AiModelConfig old = profile("old");
        old.validate(true);
        assertFalse(old.messageContactEnabled);
        assertEquals("", old.messageContactName);
        assertEquals("", old.messageContactAvatar);
        assertEquals("配置 old", old.messageContactDisplayName());
    }

    @Test public void multipleContactsRoundTripIndependentlyOfActiveModel() throws Exception {
        AiModelConfig first = AiModelConfig.fromJson(profile("first").toJson()
                .put("messageContactEnabled", true).put("messageContactName", "  小助手  ")
                .put("messageContactAvatar", AVATAR));
        AiModelConfig second = AiModelConfig.fromJson(profile("second").toJson()
                .put("messageContactEnabled", true));
        AiModelSettings restored = AiModelSettings.parse(new AiModelSettings(Arrays.asList(first, second), second.id).serialize());
        restored.configs.forEach(config -> config.validate(true));
        assertEquals("second", restored.activeId);
        assertTrue(restored.configs.get(0).messageContactEnabled);
        assertTrue(restored.configs.get(1).messageContactEnabled);
        assertEquals("小助手", restored.configs.get(0).messageContactDisplayName());
        assertEquals(AVATAR, restored.configs.get(0).messageContactAvatar);
        AiModelConfig disabled = AiModelConfig.fromJson(first.toJson().put("messageContactEnabled", false));
        assertEquals("小助手", disabled.messageContactName);
        assertEquals(AVATAR, disabled.messageContactAvatar);
    }

    @Test public void invalidNamesAndUnboundedOrExternalAvatarsAreRejected() throws Exception {
        for (String name : new String[]{"a".repeat(81), "line\nbreak", "line\u0000break"}) {
            AiModelConfig invalid = AiModelConfig.fromJson(profile("invalid").toJson().put("messageContactName", name));
            assertThrows(IllegalArgumentException.class, () -> invalid.validate(true));
        }
        byte[] large = new byte[AiModelConfig.MAX_CONTACT_AVATAR_BYTES + 1];
        for (String avatar : new String[]{"content://media/image/1", "https://example.com/avatar.png",
                "data:image/png;base64,not-base64", "data:image/png;base64,SGVsbG8=",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(large)}) {
            AiModelConfig invalid = AiModelConfig.fromJson(profile("invalid").toJson().put("messageContactAvatar", avatar));
            assertThrows(IllegalArgumentException.class, () -> invalid.validate(true));
        }
    }

    private static AiModelConfig profile(String id) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", "配置 " + id)
                .put("apiEndpoint", "https://example.com/v1").put("modelName", "test-model"));
    }
}

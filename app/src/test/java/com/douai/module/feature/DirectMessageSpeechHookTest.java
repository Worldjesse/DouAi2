package com.douai.module.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DirectMessageSpeechHookTest {
    @Test public void hashPrefixIsExplicitAndTheOriginalDraftIsRetained() {
        String original = "#  今天下午见  ";
        assertEquals("今天下午见", DirectMessageSpeechHook.speechText(original));
        assertEquals("#  今天下午见  ", original);
        assertNull(DirectMessageSpeechHook.speechText("普通私信"));
        assertNull(DirectMessageSpeechHook.speechText(" #前置空格不是指令"));
        assertNull(DirectMessageSpeechHook.speechText(null));
        assertEquals("", DirectMessageSpeechHook.speechText("#   "));
    }
    @Test public void defaultOffAndAutomaticRepliesCannotTriggerSpeech() {
        com.douai.module.config.LocalOptions disabled = new com.douai.module.config.LocalOptions(java.util.Collections.emptyMap());
        assertFalse(DirectMessageSpeechHook.canStart(disabled, true, "#文字"));
        com.douai.module.config.LocalOptions enabled = new com.douai.module.config.LocalOptions(java.util.Collections.singletonMap("dm_tts", true));
        assertFalse(DirectMessageSpeechHook.canStart(enabled, false, "#文字"));
        assertFalse(DirectMessageSpeechHook.canStart(enabled, true, "普通文字"));
        assertTrue(DirectMessageSpeechHook.canStart(enabled, true, "#文字"));
    }

    @Test public void invalidChannelsAndRatesFailBeforeNativeUpload() {
        DirectMessageSpeechHook.validateAudioFormat(22050, 1);
        DirectMessageSpeechHook.validateAudioFormat(48000, 2);
        for (int[] value : new int[][]{{44100, 6}, {192000, 2}, {0, 1}}) {
            try {
                DirectMessageSpeechHook.validateAudioFormat(value[0], value[1]);
                fail("Expected an unsupported input format");
            } catch (IllegalArgumentException expected) { }
        }
    }
}

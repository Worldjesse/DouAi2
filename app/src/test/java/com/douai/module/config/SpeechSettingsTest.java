package com.douai.module.config;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public final class SpeechSettingsTest {
    @Test public void existingEngineSettingsRemainLocalByDefault() {
        LocalOptions options = new LocalOptions(Map.of("tts_engine", "system.engine", "tts_language", "en-US",
                "tts_rate", "1.5", "tts_pitch", "0.8"));
        SpeechSettings settings = new SpeechSettings(options);
        assertFalse(settings.thirdParty);
        assertEquals("system.engine", options.text("tts_engine", ""));
        assertEquals("en-US", options.text("tts_language", ""));
        assertEquals(1.5f, options.decimal("tts_rate", 0), 0);
        assertEquals(0.8f, options.decimal("tts_pitch", 0), 0);
        assertEquals(SpeechSettings.DEFAULT_VOICE_GET_TEMPLATE, settings.voiceGetTemplate);
    }

    @Test public void providerAndIndependentVoiceConfigurationAreReadFromSnapshot() {
        SpeechSettings settings = new SpeechSettings(new LocalOptions(Map.of(
                SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY,
                SpeechSettings.GET_TEMPLATE, "https://api.test/tts?ID={voice}&content={text}",
                SpeechSettings.SELECTED_VOICE, "voice_2", SpeechSettings.SELECTED_VOICE_NAME, "Voice 2",
                SpeechSettings.VOICE_GET_TEMPLATE, "https://api.test/voices?key=fixture")));
        assertTrue(settings.thirdParty);
        assertEquals("voice_2", settings.selectedVoice);
        assertEquals("Voice 2", settings.selectedVoiceName);
        assertEquals("{}", settings.headers);
        assertEquals("{}", settings.voiceHeaders);
        assertEquals(SpeechSettings.DEFAULT_AUDIO_FIELD, settings.audioField);
    }
}

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
        assertEquals(1.5f, settings.rate, 0);
        assertEquals(0.8f, settings.pitch, 0);
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

    @Test public void existingLocalValuesKeepTheirEffectiveFallbackAndClamping() {
        SpeechSettings defaults = new SpeechSettings(new LocalOptions(Map.of()));
        assertEquals(1f, defaults.rate, 0);
        assertEquals(1f, defaults.pitch, 0);
        assertEquals("1", defaults.rateText());
        assertEquals("1", defaults.pitchText());
        Object[][] values = {{"0.1", 0.5f}, {"3", 2f}, {"0.5", 0.5f}, {"2", 2f},
                {"invalid", 1f}, {"NaN", 1f}, {"Infinity", 1f}, {"-Infinity", 1f}, {"", 1f},
                {"1e100", 1f}, {Float.NaN, 1f}, {Float.POSITIVE_INFINITY, 1f}};
        for (Object[] value : values) {
            SpeechSettings settings = new SpeechSettings(new LocalOptions(Map.of(
                    SpeechSettings.RATE, value[0], SpeechSettings.PITCH, value[0])));
            assertEquals(String.valueOf(value[0]), (float) value[1], settings.rate, 0);
            assertEquals(String.valueOf(value[0]), (float) value[1], settings.pitch, 0);
            assertEquals(settings.rate, Float.parseFloat(settings.rateText()), 0);
            assertEquals(settings.pitch, Float.parseFloat(settings.pitchText()), 0);
        }
    }

    @Test public void newLocalEditsRejectInvalidValuesAndNormalizeAcceptedValues() {
        for (String invalid : new String[]{"0.49", "2.01", "0.499999999", "2.00000001", "-1", "0",
                "invalid", "NaN", "Infinity", "-Infinity", "", "1e100"}) {
            IllegalArgumentException failure = assertThrows(invalid, IllegalArgumentException.class,
                    () -> SpeechSettings.validateLocalRateOrPitch(invalid));
            assertEquals("请输入 0.5 至 2 的数字", failure.getMessage());
        }
        assertEquals("0.5", SpeechSettings.validateLocalRateOrPitch("0.5"));
        assertEquals("2", SpeechSettings.validateLocalRateOrPitch("2"));
        assertEquals("1", SpeechSettings.validateLocalRateOrPitch(" 1.00 "));
        assertEquals("1.4", SpeechSettings.validateLocalRateOrPitch("1.400"));
    }
}

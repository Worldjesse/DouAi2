package com.douai.module.config;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class MiniMaxSpeechOptionsTest {
    @Test public void defaultsKeepTheExistingAudioQualityAndOmitOptionalProviderControls() throws Exception {
        MiniMaxSpeechOptions options = options(Map.of());
        JSONObject request = new JSONObject().put("model", "speech-2.8-hd").put("text", "hello")
                .put("voice_setting", new JSONObject().put("voice_id", "voice").put("speed", 1.5));
        options.applyTo(request);
        assertEquals("speech-2.8-hd", request.getString("model"));
        assertEquals("hello", request.getString("text"));
        assertEquals("voice", request.getJSONObject("voice_setting").getString("voice_id"));
        assertEquals(1.5, request.getJSONObject("voice_setting").getDouble("speed"), 0);
        assertFalse(request.has("language_boost"));
        assertFalse(request.has("text_normalization_mode"));
        assertFalse(request.has("pronunciation_dict"));
        assertFalse(request.has("voice_modify"));
        assertFalse(request.has("aigc_watermark"));
        assertFalse(request.has("timbre_weights"));
        assertFalse(options.hasMixedVoices());
        assertEquals("url", request.getString("output_format"));
        JSONObject audio = request.getJSONObject("audio_setting");
        assertEquals("mp3", audio.getString("format"));
        assertEquals(32000, audio.getInt("sample_rate"));
        assertEquals(128000, audio.getInt("bitrate"));
        assertEquals(1, audio.getInt("channel"));
    }

    @Test public void enabledFormatsPreserveEncodingAndTransportWhileMuLawUsesItsFixedRate() throws Exception {
        assertArrayEquals(new String[]{"mp3", "wav", "flac", "opus", "pcmu_wav", "aac"}, MiniMaxSpeechOptions.FORMATS);
        for (String format : MiniMaxSpeechOptions.FORMATS) {
            MiniMaxSpeechOptions options = options(Map.of(MiniMaxSpeechOptions.AUDIO_FORMAT, format,
                    MiniMaxSpeechOptions.OUTPUT_FORMAT, "hex", MiniMaxSpeechOptions.SAMPLE_RATE, "44100",
                    MiniMaxSpeechOptions.CHANNEL, "2"));
            JSONObject request = new JSONObject(); options.applyTo(request);
            JSONObject audio = request.getJSONObject("audio_setting");
            assertEquals(format, audio.getString("format"));
            assertEquals("hex", request.getString("output_format"));
            assertEquals(2, audio.getInt("channel"));
            assertEquals(MiniMaxSpeechOptions.isMuLawFormat(format) ? 8000 : 44100, audio.getInt("sample_rate"));
            assertEquals("The saved rate remains available when changing formats", 44100, options.sampleRate);
            assertEquals("mp3".equals(format), audio.has("bitrate"));
        }
        for (String format : new String[]{"pcm", "pcmu_raw", "m4a"}) {
            assertThrows(IllegalArgumentException.class, () -> MiniMaxSpeechOptions.validateValue(MiniMaxSpeechOptions.AUDIO_FORMAT, format));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MiniMaxSpeechOptions.validateValue(MiniMaxSpeechOptions.OUTPUT_FORMAT, "base64"));
    }

    @Test public void experimentalAacIsRequestedFromTheApiWithoutBecomingALocalOutputSetting() throws Exception {
        MiniMaxSpeechOptions options = options(Map.of(MiniMaxSpeechOptions.AUDIO_FORMAT, "aac"));
        JSONObject request = new JSONObject(); options.applyTo(request);
        assertEquals("aac", request.getJSONObject("audio_setting").getString("format"));
        assertEquals("url", request.getString("output_format"));
        assertFalse(request.has("voice_modify"));
        assertFalse(MiniMaxSpeechOptions.supportsVoiceModify("aac"));
        assertEquals("", MiniMaxSpeechOptions.candidateIssue(Map.of(), MiniMaxSpeechOptions.AUDIO_FORMAT, "aac"));
    }

    @Test public void mixedVoicesUseTheRootArrayAndAnExplicitEmptySingleVoiceWithoutChangingSavedSettings() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SpeechSettings.MINIMAX_VOICE_ID, "saved-single");
        values.put(MiniMaxSpeechOptions.TIMBRE_WEIGHTS, " voice-one / 030\r\nvoice-two/30 \n");
        MiniMaxSpeechOptions options = options(values);
        assertTrue(options.hasMixedVoices());
        assertEquals("voice-one/30\nvoice-two/30", MiniMaxSpeechOptions.validateValue(
                MiniMaxSpeechOptions.TIMBRE_WEIGHTS, (String) values.get(MiniMaxSpeechOptions.TIMBRE_WEIGHTS)));
        JSONObject request = new JSONObject().put("model", "speech-2.8-hd").put("text", "unchanged")
                .put("voice_setting", new JSONObject().put("voice_id", "saved-single").put("speed", 1.5));
        options.applyTo(request);
        assertEquals("", request.getJSONObject("voice_setting").getString("voice_id"));
        assertEquals(1.5, request.getJSONObject("voice_setting").getDouble("speed"), 0);
        assertEquals("voice-one", request.getJSONArray("timbre_weights").getJSONObject(0).getString("voice_id"));
        assertEquals(30, request.getJSONArray("timbre_weights").getJSONObject(1).getInt("weight"));
        assertEquals("unchanged", request.getString("text"));
        assertEquals("speech-2.8-hd", request.getString("model"));
        assertEquals("saved-single", values.get(SpeechSettings.MINIMAX_VOICE_ID));
    }

    @Test public void mixValidationAllowsFourVoicesWithoutAnInventedTotalWeightAndRejectsAmbiguousInput() {
        assertEquals("v1/1\nv2/1\nv3/1\nv4/1", MiniMaxSpeechOptions.validateValue(
                MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "v1/1\nv2/1\nv3/1\nv4/1"));
        for (String mix : new String[]{"/50", "voice", "voice/0", "voice/101", "voice/1.5", "voice/-1",
                "voice/1\nvoice/2", "voice\t/2", "voice/2\u0000", "x".repeat(257) + "/2",
                "v1/1\nv2/2\nv3/3\nv4/4\nv5/5"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MiniMaxSpeechOptions.validateValue(MiniMaxSpeechOptions.TIMBRE_WEIGHTS, mix));
        }
        assertEquals("voice/with/slash/25", MiniMaxSpeechOptions.validateValue(
                MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "voice/with/slash/25"));
    }

    @Test public void mixingKeepsTheExistingModelCompatibilityChecksAndAllowsTheProviderEmptyVoiceId() {
        options(Map.of(SpeechSettings.MINIMAX_VOICE_ID, "", MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "one/20\ntwo/30")).validate();
        assertThrows(IllegalArgumentException.class, () -> options(Map.of(SpeechSettings.MINIMAX_VOICE_ID, "")).validate());
        options(Map.of(SpeechSettings.MINIMAX_MODEL, "speech-future-hd", MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "one/10")).validate();
        assertThrows(IllegalArgumentException.class, () -> options(Map.of(SpeechSettings.MINIMAX_MODEL, "speech-01-hd",
                MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "one/10", MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality")).validate());
        assertThrows(IllegalArgumentException.class, () -> options(Map.of(MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "one/10",
                MiniMaxSpeechOptions.EMOTION, "whisper")).validate());
        options(Map.of(SpeechSettings.MINIMAX_MODEL, "speech-2.6-hd", MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "one/10",
                MiniMaxSpeechOptions.EMOTION, "whisper")).validate();
    }

    @Test public void incompatibleVoiceEffectsRequireAnExplicitResetInsteadOfBeingSilentlyOmitted() {
        for (String format : new String[]{"opus", "pcmu_wav", "aac"}) {
            for (String effect : new String[]{MiniMaxSpeechOptions.MODIFY_PITCH, MiniMaxSpeechOptions.MODIFY_INTENSITY,
                    MiniMaxSpeechOptions.MODIFY_TIMBRE, MiniMaxSpeechOptions.SOUND_EFFECT}) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put(MiniMaxSpeechOptions.AUDIO_FORMAT, format);
                values.put(effect, effect.equals(MiniMaxSpeechOptions.SOUND_EFFECT) ? "robotic" : "1");
                assertThrows(IllegalArgumentException.class, () -> options(values).validate());
                assertFalse(MiniMaxSpeechOptions.candidateIssue(values, MiniMaxSpeechOptions.OUTPUT_FORMAT, "hex").isEmpty());
            }
            Map<String, Object> saved = new LinkedHashMap<>(); saved.put(MiniMaxSpeechOptions.MODIFY_PITCH, "1");
            assertTrue(MiniMaxSpeechOptions.candidateIssue(saved, MiniMaxSpeechOptions.AUDIO_FORMAT, format).contains("重置"));
            assertFalse(saved.containsKey(MiniMaxSpeechOptions.AUDIO_FORMAT));
        }
    }

    @Test public void documentedOptionsHaveTheirCorrectRequestLayersAndNonMp3OmitsBitrate() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(MiniMaxSpeechOptions.EMOTION, "happy");
        values.put(MiniMaxSpeechOptions.LANGUAGE_BOOST, "Chinese,Yue");
        values.put(MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality");
        values.put(MiniMaxSpeechOptions.AUDIO_FORMAT, "flac");
        values.put(MiniMaxSpeechOptions.SAMPLE_RATE, "44100");
        values.put(MiniMaxSpeechOptions.BITRATE, "256000");
        values.put(MiniMaxSpeechOptions.CHANNEL, "2");
        values.put(MiniMaxSpeechOptions.MODIFY_PITCH, "-100");
        values.put(MiniMaxSpeechOptions.MODIFY_INTENSITY, "100");
        values.put(MiniMaxSpeechOptions.MODIFY_TIMBRE, "30");
        values.put(MiniMaxSpeechOptions.SOUND_EFFECT, "robotic");
        values.put(MiniMaxSpeechOptions.PRONUNCIATION_TONE, " 处理/(chu3)(li3)\r\n\n危险/danger ");
        values.put(MiniMaxSpeechOptions.AIGC_WATERMARK, "true");
        JSONObject request = new JSONObject();
        options(values).applyTo(request);
        assertEquals("happy", request.getJSONObject("voice_setting").getString("emotion"));
        assertFalse(request.getJSONObject("voice_setting").has("text_normalization_mode"));
        assertEquals("quality", request.getString("text_normalization_mode"));
        assertEquals("Chinese,Yue", request.getString("language_boost"));
        JSONObject audio = request.getJSONObject("audio_setting");
        assertEquals("flac", audio.getString("format"));
        assertEquals(44100, audio.getInt("sample_rate"));
        assertEquals(2, audio.getInt("channel"));
        assertFalse(audio.has("bitrate"));
        assertEquals(-100, request.getJSONObject("voice_modify").getInt("pitch"));
        assertEquals("robotic", request.getJSONObject("voice_modify").getString("sound_effects"));
        assertEquals(2, request.getJSONObject("pronunciation_dict").getJSONArray("tone").length());
        assertEquals("处理/(chu3)(li3)", request.getJSONObject("pronunciation_dict").getJSONArray("tone").getString(0));
        assertTrue(request.getBoolean("aigc_watermark"));
    }

    @Test public void latexRequiresChineseAndDoesNotSilentlyReplaceAnotherLanguage() throws Exception {
        JSONObject request = new JSONObject();
        options(Map.of(MiniMaxSpeechOptions.LATEX_READ, "true")).applyTo(request);
        assertTrue(request.getJSONObject("voice_setting").getBoolean("latex_read"));
        assertEquals("Chinese", request.getString("language_boost"));
        for (String language : new String[]{"English", "auto", "Chinese,Yue"}) {
            MiniMaxSpeechOptions invalid = options(Map.of(MiniMaxSpeechOptions.LATEX_READ, "true", MiniMaxSpeechOptions.LANGUAGE_BOOST, language));
            assertThrows(IllegalArgumentException.class, invalid::validate);
        }
    }

    @Test public void modelSpecificSettingsAreRejectedRatherThanDropped() {
        for (String emotion : new String[]{"fluent", "whisper"}) {
            assertThrows(IllegalArgumentException.class, () -> options(Map.of(MiniMaxSpeechOptions.EMOTION, emotion)).validate());
            options(Map.of(SpeechSettings.MINIMAX_MODEL, "speech-2.6-hd", MiniMaxSpeechOptions.EMOTION, emotion)).validate();
        }
        assertThrows(IllegalArgumentException.class, () -> options(Map.of(SpeechSettings.MINIMAX_MODEL, "speech-02-hd",
                MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality")).validate());
        for (String language : new String[]{"Persian", "Filipino", "Tamil"}) {
            assertThrows(IllegalArgumentException.class, () -> options(Map.of(SpeechSettings.MINIMAX_MODEL, "speech-01-turbo",
                    MiniMaxSpeechOptions.LANGUAGE_BOOST, language)).validate());
            options(Map.of(MiniMaxSpeechOptions.LANGUAGE_BOOST, language)).validate();
        }
    }

    @Test public void candidateHintsUseTheSameMergedValidationAndNeverMutateSavedValues() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put(SpeechSettings.MINIMAX_MODEL, "speech-2.8-hd");
        String issue = MiniMaxSpeechOptions.candidateIssue(current, MiniMaxSpeechOptions.EMOTION, "whisper");
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.put(MiniMaxSpeechOptions.EMOTION, "whisper");
        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class, () -> options(merged).validate());
        assertEquals(actual.getMessage(), issue);
        assertEquals(1, current.size());
        assertFalse(current.containsKey(MiniMaxSpeechOptions.EMOTION));
        assertEquals("", MiniMaxSpeechOptions.candidateIssue(current, MiniMaxSpeechOptions.EMOTION, "happy"));
        assertEquals("", MiniMaxSpeechOptions.candidateIssue(current, SpeechSettings.MINIMAX_MODEL, "speech-future-hd"));

        current.put(MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality");
        assertFalse(MiniMaxSpeechOptions.candidateIssue(current, SpeechSettings.MINIMAX_MODEL, "speech-01-hd").isEmpty());
        assertEquals("quality", current.get(MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE));
        assertFalse(MiniMaxSpeechOptions.candidateIssue(current, "api_key", "private-key").isEmpty());
    }

    @Test public void invalidValuesAndOversizedOrMalformedDictionariesAreRejected() {
        String[][] invalid = {{MiniMaxSpeechOptions.EMOTION, "unknown"}, {MiniMaxSpeechOptions.AUDIO_FORMAT, "pcm"},
                {MiniMaxSpeechOptions.SAMPLE_RATE, "48000"}, {MiniMaxSpeechOptions.BITRATE, "100"},
                {MiniMaxSpeechOptions.CHANNEL, "3"}, {MiniMaxSpeechOptions.MODIFY_PITCH, "100.5"},
                {MiniMaxSpeechOptions.MODIFY_TIMBRE, "101"}, {MiniMaxSpeechOptions.MODIFY_INTENSITY, "-101"},
                {MiniMaxSpeechOptions.SOUND_EFFECT, "robotic,spacious_echo"}, {MiniMaxSpeechOptions.AIGC_WATERMARK, "yes"},
                {MiniMaxSpeechOptions.PRONUNCIATION_TONE, "missing separator"}, {MiniMaxSpeechOptions.PRONUNCIATION_TONE, "/empty"},
                {MiniMaxSpeechOptions.PRONUNCIATION_TONE, "empty/"}, {MiniMaxSpeechOptions.PRONUNCIATION_TONE, "bad/line\u0000"},
                {MiniMaxSpeechOptions.PRONUNCIATION_TONE, "a/b\n".repeat(129)},
                {MiniMaxSpeechOptions.PRONUNCIATION_TONE, "a/" + "b".repeat(1024)},
                {MiniMaxSpeechOptions.PRONUNCIATION_TONE, "原/文".repeat(22000)}};
        for (String[] value : invalid) assertThrows(value[0], IllegalArgumentException.class,
                () -> MiniMaxSpeechOptions.validateValue(value[0], value[1]));
    }

    private static MiniMaxSpeechOptions options(Map<String, ?> values) { return new MiniMaxSpeechOptions(new LocalOptions(values)); }
}

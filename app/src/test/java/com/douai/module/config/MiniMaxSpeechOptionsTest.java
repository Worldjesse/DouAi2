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
        JSONObject audio = request.getJSONObject("audio_setting");
        assertEquals("mp3", audio.getString("format"));
        assertEquals(32000, audio.getInt("sample_rate"));
        assertEquals(128000, audio.getInt("bitrate"));
        assertEquals(1, audio.getInt("channel"));
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

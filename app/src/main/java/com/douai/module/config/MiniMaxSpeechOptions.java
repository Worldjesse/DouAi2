package com.douai.module.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit MiniMax synthesis options; no setting can replace credentials, text, or the selected model. */
public final class MiniMaxSpeechOptions {
    public static final String EMOTION = "tts_minimax_emotion";
    public static final String LANGUAGE_BOOST = "tts_minimax_language_boost";
    public static final String TEXT_NORMALIZATION_MODE = "tts_minimax_text_normalization_mode";
    public static final String AUDIO_FORMAT = "tts_minimax_audio_format";
    public static final String SAMPLE_RATE = "tts_minimax_sample_rate";
    public static final String BITRATE = "tts_minimax_bitrate";
    public static final String CHANNEL = "tts_minimax_channel";
    public static final String MODIFY_PITCH = "tts_minimax_modify_pitch";
    public static final String MODIFY_INTENSITY = "tts_minimax_modify_intensity";
    public static final String MODIFY_TIMBRE = "tts_minimax_modify_timbre";
    public static final String SOUND_EFFECT = "tts_minimax_sound_effect";
    public static final String PRONUNCIATION_TONE = "tts_minimax_pronunciation_tone";
    public static final String AIGC_WATERMARK = "tts_minimax_aigc_watermark";
    public static final String LATEX_READ = "tts_minimax_latex_read";
    public static final String[] KEYS = {EMOTION, LANGUAGE_BOOST, TEXT_NORMALIZATION_MODE, AUDIO_FORMAT,
            SAMPLE_RATE, BITRATE, CHANNEL, MODIFY_PITCH, MODIFY_INTENSITY, MODIFY_TIMBRE, SOUND_EFFECT,
            PRONUNCIATION_TONE, AIGC_WATERMARK, LATEX_READ};
    public static final String[] EMOTIONS = {"", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm", "fluent", "whisper"};
    public static final String[] LANGUAGES = {"", "Chinese", "Chinese,Yue", "English", "Arabic", "Russian",
            "Spanish", "French", "Portuguese", "German", "Turkish", "Dutch", "Ukrainian", "Vietnamese",
            "Indonesian", "Japanese", "Italian", "Korean", "Thai", "Polish", "Romanian", "Greek", "Czech",
            "Finnish", "Hindi", "Bulgarian", "Danish", "Hebrew", "Malay", "Persian", "Slovak", "Swedish",
            "Croatian", "Filipino", "Hungarian", "Norwegian", "Slovenian", "Catalan", "Nynorsk", "Tamil", "Afrikaans", "auto"};
    public static final String[] NORMALIZATION_MODES = {"", "basic", "quality"};
    public static final String[] FORMATS = {"mp3", "wav", "flac"};
    public static final String[] SAMPLE_RATES = {"8000", "16000", "22050", "24000", "32000", "44100"};
    public static final String[] BITRATES = {"32000", "64000", "128000", "256000"};
    public static final String[] CHANNELS = {"1", "2"};
    public static final String[] SOUND_EFFECTS = {"", "spacious_echo", "auditorium_echo", "lofi_telephone", "robotic"};
    public static final String[] OPTIONAL_BOOLEANS = {"", "true", "false"};
    private static final String[] QUALITY_MODELS = {"speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo"};
    private final LocalOptions options;
    public final String emotion, languageBoost, textNormalizationMode, audioFormat, soundEffect, pronunciationTone;
    public final String aigcWatermark, latexRead;
    public final int sampleRate, bitrate, channel, modifyPitch, modifyIntensity, modifyTimbre;

    public MiniMaxSpeechOptions(LocalOptions options) {
        this.options = options;
        emotion = value(EMOTION);
        languageBoost = value(LANGUAGE_BOOST);
        textNormalizationMode = value(TEXT_NORMALIZATION_MODE);
        audioFormat = value(AUDIO_FORMAT);
        soundEffect = value(SOUND_EFFECT);
        pronunciationTone = value(PRONUNCIATION_TONE);
        aigcWatermark = value(AIGC_WATERMARK);
        latexRead = value(LATEX_READ);
        sampleRate = options.integer(SAMPLE_RATE, 32000);
        bitrate = options.integer(BITRATE, 128000);
        channel = options.integer(CHANNEL, 1);
        modifyPitch = options.integer(MODIFY_PITCH, 0);
        modifyIntensity = options.integer(MODIFY_INTENSITY, 0);
        modifyTimbre = options.integer(MODIFY_TIMBRE, 0);
    }

    private String value(String key) {
        String value = options.text(key, defaultValue(key));
        return PRONUNCIATION_TONE.equals(key) ? value : value.trim();
    }

    public static String defaultValue(String key) {
        if (AUDIO_FORMAT.equals(key)) return "mp3";
        if (SAMPLE_RATE.equals(key)) return "32000";
        if (BITRATE.equals(key)) return "128000";
        if (CHANNEL.equals(key)) return "1";
        if (MODIFY_PITCH.equals(key) || MODIFY_INTENSITY.equals(key) || MODIFY_TIMBRE.equals(key)) return "0";
        return "";
    }

    public static boolean isKey(String key) { return Arrays.asList(KEYS).contains(key); }

    /** Pure candidate check shared by settings hints and final-save validation. */
    public static String candidateIssue(Map<String, ?> current, String key, String candidate) {
        try {
            if (!isKey(key) && !SpeechSettings.MINIMAX_MODEL.equals(key)) {
                throw new IllegalArgumentException("MiniMax 候选设置字段无效");
            }
            Map<String, Object> values = new LinkedHashMap<>(current);
            values.put(key, SpeechSettings.validateMiniMaxValue(key, candidate));
            new MiniMaxSpeechOptions(new LocalOptions(values)).validate();
            return "";
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        }
    }

    public static String validateValue(String key, String candidate) {
        if (PRONUNCIATION_TONE.equals(key)) return normalizedTone(candidate == null ? "" : candidate);
        String value = candidate == null ? "" : candidate.trim();
        String[] choices = null;
        if (EMOTION.equals(key)) choices = EMOTIONS;
        else if (LANGUAGE_BOOST.equals(key)) choices = LANGUAGES;
        else if (TEXT_NORMALIZATION_MODE.equals(key)) choices = NORMALIZATION_MODES;
        else if (AUDIO_FORMAT.equals(key)) choices = FORMATS;
        else if (SAMPLE_RATE.equals(key)) choices = SAMPLE_RATES;
        else if (BITRATE.equals(key)) choices = BITRATES;
        else if (CHANNEL.equals(key)) choices = CHANNELS;
        else if (SOUND_EFFECT.equals(key)) choices = SOUND_EFFECTS;
        else if (AIGC_WATERMARK.equals(key) || LATEX_READ.equals(key)) choices = OPTIONAL_BOOLEANS;
        if (choices != null) {
            if (!Arrays.asList(choices).contains(value)) throw new IllegalArgumentException("MiniMax 高级设置选项无效");
            return value;
        }
        if (MODIFY_PITCH.equals(key) || MODIFY_INTENSITY.equals(key) || MODIFY_TIMBRE.equals(key)) {
            try {
                int number = Integer.parseInt(value);
                if (number >= -100 && number <= 100) return Integer.toString(number);
            } catch (NumberFormatException ignored) { }
            throw new IllegalArgumentException("MiniMax 音色调整需为 -100 至 100 的整数");
        }
        throw new IllegalArgumentException("MiniMax 高级设置字段无效");
    }

    /** The dictionary bounds are module safeguards, not claimed provider limits. */
    private static String normalizedTone(String value) {
        if (value.length() > 64 * 1024 || value.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IllegalArgumentException("发音字典超过模块的 64 KiB 限制");
        }
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String raw : value.split("\\r?\\n", -1)) {
            for (int index = 0; index < raw.length(); index++) {
                if (Character.isISOControl(raw.charAt(index))) throw new IllegalArgumentException("发音字典包含无效控制字符");
            }
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int slash = line.indexOf('/');
            if (slash < 1 || line.substring(0, slash).trim().isEmpty() || line.substring(slash + 1).trim().isEmpty()) {
                throw new IllegalArgumentException("发音字典每行需填写 原文/替换内容，例如 处理/(chu3)(li3)");
            }
            if (++count > 128 || line.length() > 1024) throw new IllegalArgumentException("发音字典超过模块限制：最多 128 条，每条不超过 1024 个字符");
            if (result.length() > 0) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }

    public void validate() {
        for (String key : KEYS) validateValue(key, value(key));
        String model = options.text(SpeechSettings.MINIMAX_MODEL, SpeechSettings.DEFAULT_MINIMAX_MODEL).trim();
        if (("fluent".equals(emotion) || "whisper".equals(emotion))
                && !"speech-2.6-hd".equals(model) && !"speech-2.6-turbo".equals(model)) {
            throw new IllegalArgumentException("流利和低语情绪仅支持 speech-2.6-hd / speech-2.6-turbo，请调整模型或情绪");
        }
        if ("quality".equals(textNormalizationMode) && !Arrays.asList(QUALITY_MODELS).contains(model)) {
            throw new IllegalArgumentException("高质量文本归一化仅支持 speech-2.8 / speech-2.6 系列模型");
        }
        if ((model.startsWith("speech-01") || model.startsWith("speech-02"))
                && Arrays.asList("Persian", "Filipino", "Tamil").contains(languageBoost)) {
            throw new IllegalArgumentException("speech-01 / speech-02 不支持所选语言增强，请调整模型或语言");
        }
        if ("true".equals(latexRead) && !languageBoost.isEmpty() && !"Chinese".equals(languageBoost)) {
            throw new IllegalArgumentException("LaTeX 朗读需要将语言增强设为中文或不指定");
        }
    }

    /** Only documented synthesis fields are emitted; unrelated request fields retain their original values. */
    public void applyTo(JSONObject request) {
        validate();
        try {
            JSONObject voice = request.optJSONObject("voice_setting");
            if (voice == null) voice = new JSONObject();
            if (!emotion.isEmpty()) voice.put("emotion", emotion);
            if (!latexRead.isEmpty()) voice.put("latex_read", Boolean.parseBoolean(latexRead));
            request.put("voice_setting", voice);
            if ("true".equals(latexRead)) request.put("language_boost", "Chinese");
            else if (!languageBoost.isEmpty()) request.put("language_boost", languageBoost);
            if (!textNormalizationMode.isEmpty()) request.put("text_normalization_mode", textNormalizationMode);
            JSONObject audio = new JSONObject().put("sample_rate", sampleRate).put("format", audioFormat).put("channel", channel);
            if ("mp3".equals(audioFormat)) audio.put("bitrate", bitrate);
            request.put("audio_setting", audio);
            if (modifyPitch != 0 || modifyIntensity != 0 || modifyTimbre != 0 || !soundEffect.isEmpty()) {
                JSONObject modify = new JSONObject().put("pitch", modifyPitch).put("intensity", modifyIntensity).put("timbre", modifyTimbre);
                if (!soundEffect.isEmpty()) modify.put("sound_effects", soundEffect);
                request.put("voice_modify", modify);
            }
            String tone = normalizedTone(pronunciationTone);
            if (!tone.isEmpty()) {
                JSONArray entries = new JSONArray();
                for (String line : tone.split("\n")) entries.put(line);
                request.put("pronunciation_dict", new JSONObject().put("tone", entries));
            }
            if (!aigcWatermark.isEmpty()) request.put("aigc_watermark", Boolean.parseBoolean(aigcWatermark));
        } catch (Exception invalid) { throw new IllegalArgumentException("MiniMax 高级语音参数无法生成"); }
    }
}

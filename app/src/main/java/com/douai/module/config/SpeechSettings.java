package com.douai.module.config;

/** Host-local third-party speech settings; Android engine options keep their existing keys. */
public final class SpeechSettings {
    public static final String PROVIDER = "tts_provider";
    public static final String LOCAL = "local";
    public static final String THIRD_PARTY = "third_party";
    public static final String GET_TEMPLATE = "tts_get_template";
    public static final String HEADERS = "tts_headers";
    public static final String AUDIO_FIELD = "tts_audio_field";
    public static final String VOICE_GET_TEMPLATE = "tts_voice_get_template";
    public static final String VOICE_HEADERS = "tts_voice_headers";
    public static final String VOICE_LIST_PATH = "tts_voice_list_path";
    public static final String VOICE_ID_PATH = "tts_voice_id_path";
    public static final String VOICE_NAME_PATH = "tts_voice_name_path";
    public static final String SELECTED_VOICE = "tts_selected_voice";
    public static final String SELECTED_VOICE_NAME = "tts_selected_voice_name";
    public static final String DEFAULT_GET_TEMPLATE =
            "http://www.yx520.ltd/API/wzzyypromax/api.php?voice={voice}&apikey=&text={text}";
    public static final String DEFAULT_VOICE_GET_TEMPLATE = "https://www.yx520.ltd/API/wzzyypromax/ys.php";
    public static final String DEFAULT_AUDIO_FIELD = "url";

    public final boolean thirdParty;
    public final String getTemplate;
    public final String headers;
    public final String audioField;
    public final String voiceGetTemplate;
    public final String voiceHeaders;
    public final String voiceListPath;
    public final String voiceIdPath;
    public final String voiceNamePath;
    public final String selectedVoice;
    public final String selectedVoiceName;

    public SpeechSettings(LocalOptions options) {
        thirdParty = THIRD_PARTY.equals(options.text(PROVIDER, LOCAL));
        getTemplate = options.text(GET_TEMPLATE, DEFAULT_GET_TEMPLATE).trim();
        headers = options.text(HEADERS, "{}");
        audioField = options.text(AUDIO_FIELD, DEFAULT_AUDIO_FIELD).trim();
        voiceGetTemplate = options.text(VOICE_GET_TEMPLATE, DEFAULT_VOICE_GET_TEMPLATE).trim();
        voiceHeaders = options.text(VOICE_HEADERS, "{}");
        voiceListPath = options.text(VOICE_LIST_PATH, "").trim();
        voiceIdPath = options.text(VOICE_ID_PATH, "").trim();
        voiceNamePath = options.text(VOICE_NAME_PATH, "").trim();
        selectedVoice = options.text(SELECTED_VOICE, "1").trim();
        selectedVoiceName = options.text(SELECTED_VOICE_NAME, "").trim();
    }
}

package com.douai.module.config;

import android.net.Uri;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Host-local configuration for a user-selected GET parser. */
public final class ThirdPartyDownloadSettings {
    public static final String METHOD = "download_method";
    public static final String THIRD_PARTY = "third_party";
    public static final String GET_TEMPLATE = "third_party_get_template";
    public static final String HEADERS = "third_party_headers";
    public static final String APP_ID = "third_party_app_id";
    public static final String APP_SECRET = "third_party_app_secret";
    public static final String SIGNATURE_MODE = "third_party_signature_mode";
    public static final String SIGNATURE_HEADER = "third_party_signature_header";
    public static final String SIGNATURE_NONE = "none";
    public static final String SIGNATURE_MD5 = "php_md5";
    public static final String DOWNLOAD_FIELDS = "third_party_download_fields";
    public static final String NAMING_FIELDS = "third_party_naming_fields";
    public static final String SAMPLE = "third_party_response_sample";
    public static final String DEFAULT_GET_TEMPLATE =
            "https://gateway.diadi.cn/api/parse?app_id=&url={url}";
    private static final String LEGACY_DEFAULT_GET_TEMPLATE =
            "https://gateway.diadi.cn/api/parse?app_id={app_id}&url={url}";
    public static final String DEFAULT_DOWNLOAD_FIELDS = "[\"data.video\",\"data.images\"]";
    public static final String DEFAULT_NAMING_FIELDS = "[\"data.author.nickname\",\"data.id\"]";
    public static final String DEFAULT_SAMPLE = "{\n  \"code\": 0,\n  \"data\": {\n"
            + "    \"video\": [\"https://example.com/video.mp4\"],\n"
            + "    \"video_raw\": [\"https://example.com/original.mp4\"],\n"
            + "    \"images\": [],\n    \"cover\": \"https://example.com/cover.jpg\",\n"
            + "    \"type\": \"video\",\n    \"title\": null,\n    \"text\": \"\",\n"
            + "    \"audio\": [\"https://example.com/audio.mp3\"],\n"
            + "    \"platform\": 1,\n    \"id\": \"\",\n"
            + "    \"author\": {\"avatar\": \"https://example.com/avatar.jpg\", \"nickname\": \"\", \"id\": \"\"},\n"
            + "    \"other\": {\"create_time\": 0, \"comment_count\": 0, \"digg_count\": 0,"
            + " \"collect_count\": 0, \"share_count\": 0, \"duration\": 0,"
            + " \"is_horizontal\": 0, \"play_count\": 0}\n  },\n  \"message\": \"ok\"\n}";

    public final boolean thirdParty;
    public final String getTemplate;
    public final String headers;
    public final String appId;
    public final String appSecret;
    public final String signatureMode;
    public final String signatureHeader;
    public final List<String> downloadFields;
    public final List<String> namingFields;

    public ThirdPartyDownloadSettings(LocalOptions options) {
        thirdParty = THIRD_PARTY.equals(options.text(METHOD, "official"));
        headers = options.text(HEADERS, "{}");
        appId = options.text(APP_ID, "").trim();
        appSecret = options.text(APP_SECRET, "");
        String mode = options.text(SIGNATURE_MODE, null);
        signatureMode = mode == null ? (appSecret.isEmpty() ? SIGNATURE_NONE : SIGNATURE_MD5) : mode;
        signatureHeader = options.text(SIGNATURE_HEADER, "Sign").trim();
        String template = options.text(GET_TEMPLATE, LEGACY_DEFAULT_GET_TEMPLATE).trim()
                .replace("{app_id}", Uri.encode(appId));
        // Only the old configuration treated a trailing query value as a link prefix.
        if (mode == null && isLegacyLinkPrefix(template)) template += "{url}";
        getTemplate = template;
        downloadFields = fields(options.text(DOWNLOAD_FIELDS, DEFAULT_DOWNLOAD_FIELDS));
        namingFields = fields(options.text(NAMING_FIELDS, DEFAULT_NAMING_FIELDS));
    }

    public static boolean isLegacyLinkPrefix(String template) {
        if (template == null || template.contains("{url}")) return false;
        String query = Uri.parse(template).getEncodedQuery();
        if (query == null || !query.endsWith("=")) return false;
        int start = query.lastIndexOf('&') + 1;
        return query.indexOf('=', start) == query.length() - 1 && start < query.length() - 1;
    }

    public static List<String> fields(String encoded) {
        try {
            JSONArray array = new JSONArray(encoded);
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Object value = array.get(i);
                if (value instanceof String && !((String) value).trim().isEmpty()
                        && !values.contains(value)) values.add((String) value);
            }
            return Collections.unmodifiableList(values);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public static String encodeFields(List<String> values) {
        return new JSONArray(values).toString();
    }
}

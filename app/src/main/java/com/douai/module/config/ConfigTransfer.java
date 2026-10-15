package com.douai.module.config;

import com.douai.module.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Versioned configuration transfer. Inspect is pure; only an accepted preview can be applied. */
public final class ConfigTransfer {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int FORMAT_VERSION = 1;
    private static final String APPLICATION_ID = "io.github.yudigaga.douai";
    public enum Mode { MERGE, REPLACE }
    private ConfigTransfer() { }

    public static final class Preview {
        public final int formatVersion = FORMAT_VERSION;
        public final int entryCount, changedCount, removedCount, redactedCount, missingSecretsCount;
        public final String moduleVersion;
        public final boolean includesSecrets;
        public final Mode mode;
        public final List<String> changedKeys;
        private final Map<String, ?> expected, replacement;
        private Preview(Map<String, ?> expected, Map<String, ?> replacement, int entries, int redacted,
                        int missing, String version, boolean secrets, Mode mode) {
            this.expected = copy(expected); this.replacement = copy(replacement);
            entryCount = entries; redactedCount = redacted; missingSecretsCount = missing;
            moduleVersion = version; includesSecrets = secrets; this.mode = mode;
            List<String> changes = new ArrayList<>(); int removed = 0;
            for (String key : ConfigKeys.TYPES.keySet()) if (!Objects.equals(expected.get(key), replacement.get(key))) {
                changes.add(key); if (expected.containsKey(key) && !replacement.containsKey(key)) removed++;
            }
            Collections.sort(changes); changedKeys = Collections.unmodifiableList(changes);
            changedCount = changes.size(); removedCount = removed;
        }
    }

    public static String export(SettingsStore store, boolean includeSecrets) {
        try {
            Map<String, ?> source = store.snapshot(); JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> entry : new TreeMap<>(source).entrySet()) {
                String key = entry.getKey(); if (!ConfigKeys.TYPES.containsKey(key)) continue;
                Object value = entry.getValue(); ConfigKeys.validate(key, value);
                validateStructured(key, value);
                if (!includeSecrets && value instanceof String) value = ConfigSecrets.redact(key, (String) value);
                values.put(key, new JSONObject().put("type", ConfigKeys.TYPES.get(key)).put("value", value));
            }
            String encoded = new JSONObject().put("formatVersion", FORMAT_VERSION)
                    .put("module", new JSONObject().put("applicationId", APPLICATION_ID)
                            .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE))
                    .put("includesSecrets", includeSecrets).put("values", values).toString(2);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("配置文件超过 2 MiB 限制");
            return encoded;
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("配置无法导出，请检查设置内容"); }
    }

    public static Preview inspect(SettingsStore store, String encoded) { return inspect(store, encoded, Mode.MERGE); }

    public static Preview inspect(SettingsStore store, String encoded, Mode mode) {
        if (encoded == null || encoded.length() > MAX_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("配置文件为空或超过 2 MiB 限制");
        }
        if (mode == null) throw ConfigJson.invalid();
        try {
            Object parsed = ConfigJson.parse(encoded);
            if (!(parsed instanceof JSONObject)) throw ConfigJson.invalid();
            JSONObject root = (JSONObject) parsed;
            exactKeys(root, "formatVersion", "module", "includesSecrets", "values");
            if (!Long.valueOf(FORMAT_VERSION).equals(root.get("formatVersion"))) throw new IllegalArgumentException("不支持此配置文件版本");
            JSONObject metadata = root.getJSONObject("module");
            exactKeys(metadata, "applicationId", "versionName", "versionCode");
            if (!APPLICATION_ID.equals(metadata.get("applicationId"))) throw new IllegalArgumentException("配置文件不属于 DouAi");
            Object version = metadata.get("versionName"), code = metadata.get("versionCode");
            if (!(version instanceof String) || !((String) version).matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                    || !(code instanceof Long) || (Long) code <= 0 || (Long) code > Integer.MAX_VALUE) throw ConfigJson.invalid();
            if (!(root.get("includesSecrets") instanceof Boolean)) throw ConfigJson.invalid();
            boolean secrets = root.getBoolean("includesSecrets");
            JSONObject values = root.getJSONObject("values");
            if (values.length() > ConfigKeys.TYPES.size()) throw ConfigJson.invalid();
            Map<String, Object> incoming = new LinkedHashMap<>(); int redacted = 0;
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next(); JSONObject typed = values.getJSONObject(key);
                exactKeys(typed, "type", "value");
                String type = ConfigKeys.TYPES.get(key);
                if (type == null || !type.equals(typed.get("type"))) throw ConfigJson.invalid();
                Object value = typed.get("value");
                if ("int".equals(type)) {
                    if (!(value instanceof Long) || (Long) value < Integer.MIN_VALUE || (Long) value > Integer.MAX_VALUE) throw ConfigJson.invalid();
                    value = ((Long) value).intValue();
                }
                ConfigKeys.validate(key, value); validateStructured(key, value);
                if (value instanceof String) {
                    String text = (String) value; redacted += ConfigSecrets.count(text);
                    if (!secrets && !equivalentText(key, text, ConfigSecrets.redact(key, text))) {
                        throw new IllegalArgumentException("标记为不含密钥的配置仍包含认证内容");
                    }
                }
                incoming.put(key, value);
            }
            if (secrets && redacted > 0) throw ConfigJson.invalid();
            Map<String, ?> before = store.snapshot(); Map<String, Object> result = new LinkedHashMap<>(before);
            if (mode == Mode.REPLACE) for (String key : ConfigKeys.TYPES.keySet()) result.remove(key);
            result.putAll(incoming);
            int[] missing = {0};
            if (!secrets && mode == Mode.REPLACE) {
                for (Map.Entry<String, ?> entry : before.entrySet()) {
                    String key = entry.getKey(); Object value = entry.getValue();
                    if (ConfigKeys.TYPES.containsKey(key) && !result.containsKey(key) && value instanceof String
                            && !AiModelSettings.KEY.equals(key) && ConfigSecrets.sameEndpoint(before, result, key)
                            && !equivalentText(key, (String) value, ConfigSecrets.redact(key, (String) value))) result.put(key, value);
                }
            }
            // New endpoints must not inherit credentials that were absent from the incoming file.
            for (String key : ConfigKeys.TYPES.keySet()) {
                Object current = result.get(key);
                if (!(current instanceof String)) continue;
                boolean same = ConfigSecrets.sameEndpoint(before, result, key);
                boolean inheritedAcrossServices = !same && !incoming.containsKey(key);
                if (inheritedAcrossServices) current = ConfigSecrets.redact(key, (String) current);
                String previous = before.get(key) instanceof String ? (String) before.get(key) : null;
                String restored = secrets && !inheritedAcrossServices ? (String) current
                        : ConfigSecrets.restore(key, (String) current, previous, same, missing);
                result.put(key, restored);
            }
            for (Map.Entry<String, Object> entry : result.entrySet()) if (ConfigKeys.TYPES.containsKey(entry.getKey())) {
                ConfigKeys.validate(entry.getKey(), entry.getValue()); validateStructured(entry.getKey(), entry.getValue());
            }
            return new Preview(before, result, incoming.size(), redacted, missing[0], (String) version, secrets, mode);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw ConfigJson.invalid(); }
    }

    public static void apply(SettingsStore store, Preview preview) {
        if (preview == null) throw new IllegalArgumentException("请先预览配置文件");
        store.replaceSnapshot(preview.expected, preview.replacement);
    }

    private static Map<String, ?> copy(Map<String, ?> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        input.forEach((key, value) -> output.put(key, value instanceof java.util.Set<?>
                ? Collections.unmodifiableSet(new java.util.HashSet<>((java.util.Set<?>) value)) : value));
        return Collections.unmodifiableMap(output);
    }

    private static boolean equivalentText(String key, String left, String right) {
        if (left.equals(right)) return true;
        if (AiModelSettings.KEY.equals(key) || key.endsWith("headers")) {
            return ConfigJson.parse(left).toString().equals(ConfigJson.parse(right).toString());
        }
        return left.equals(right);
    }

    private static void exactKeys(JSONObject value, String... expected) {
        if (value.length() != expected.length) throw ConfigJson.invalid();
        for (String key : expected) if (!value.has(key)) throw ConfigJson.invalid();
    }

    private static void validateStructured(String key, Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) return;
        String text = (String) value;
        try {
            if (AiModelSettings.KEY.equals(key)) {
                JSONObject root = (JSONObject) ConfigJson.parse(text);
                exactKeys(root, "version", "activeId", "configs");
                if (!Long.valueOf(2).equals(root.get("version")) || !(root.get("activeId") instanceof String)) throw ConfigJson.invalid();
                JSONArray configs = root.getJSONArray("configs");
                if (configs.length() > 50) throw ConfigJson.invalid();
                for (int i = 0; i < configs.length(); i++) {
                    JSONObject config = configs.getJSONObject(i);
                    JSONObject shape = AiModelConfig.fromJson(config).toJson();
                    Iterator<String> fields = config.keys();
                    while (fields.hasNext()) {
                        String field = fields.next(); Object candidate = config.get(field), expected = shape.opt(field);
                        if (expected == null || expected instanceof String && !(candidate instanceof String)
                                || expected instanceof Boolean && !(candidate instanceof Boolean)
                                || expected instanceof Number && !(candidate instanceof Number)) throw ConfigJson.invalid();
                        if ((field.equals("contextRounds") || field.equals("timeoutSeconds") || field.equals("maxTokens"))
                                && (!(candidate instanceof Long) || (Long) candidate < Integer.MIN_VALUE
                                || (Long) candidate > Integer.MAX_VALUE)) throw ConfigJson.invalid();
                    }
                    for (String structured : new String[]{"customHeaders", "customParameters"}) {
                        String nested = config.optString(structured, "{}");
                        if (!(ConfigJson.parse(nested.isEmpty() ? "{}" : nested) instanceof JSONObject)) throw ConfigJson.invalid();
                    }
                    AiModelConfig.fromJson(config).validate(false);
                }
                AiModelSettings.parse(text);
            } else if (CustomViewLocator.SETTINGS_KEY.equals(key)) {
                JSONObject root = (JSONObject) ConfigJson.parse(text);
                if (!Long.valueOf(2).equals(root.get("version")) || root.getJSONArray("items").length() != CustomViewLocator.parse(text).size()) throw ConfigJson.invalid();
            } else if (key.endsWith("headers")) {
                Object parsed = ConfigJson.parse(text);
                if (!(parsed instanceof JSONObject)) throw ConfigJson.invalid();
                JSONObject headers = (JSONObject) parsed; Iterator<String> names = headers.keys();
                while (names.hasNext()) if (!(headers.get(names.next()) instanceof String)) throw ConfigJson.invalid();
            } else if (ThirdPartyDownloadSettings.DOWNLOAD_FIELDS.equals(key) || ThirdPartyDownloadSettings.NAMING_FIELDS.equals(key)) {
                Object parsed = ConfigJson.parse(text);
                if (!(parsed instanceof JSONArray)) throw ConfigJson.invalid();
                JSONArray fields = (JSONArray) parsed;
                for (int i = 0; i < fields.length(); i++) if (!(fields.get(i) instanceof String)) throw ConfigJson.invalid();
            }
        } catch (Exception failure) { throw new IllegalArgumentException("配置包含无效的模型、请求头或组件设置"); }
    }
}

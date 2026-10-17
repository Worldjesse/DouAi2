package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigTransferTest {
    @Test public void fullBackupRoundTripsTypesAndAllConfigurationFamilies() throws Exception {
        MemoryStore source = new MemoryStore();
        source.values.put("download_enabled", true);
        source.values.put("minimum_duration_seconds", 13);
        source.values.put("tts_rate", "1.4");
        source.values.put("reply_targets", "101,202");
        source.values.put("component_alpha_like", "42");
        source.values.put("component_hide_custom_views", "{\"version\":2,\"items\":[]}");
        source.values.put("ai_model_settings_v2", model("https://api.test/v1", "fixture-key", "{}", "{}"));
        MemoryStore target = new MemoryStore();
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, true));
        assertTrue(preview.includesSecrets); assertEquals(7, preview.entryCount); assertEquals(0, target.commits);
        ConfigTransfer.apply(target, preview);
        assertEquals(source.values, target.values); assertEquals(1, target.commits);
    }

    @Test public void exportNeverIncludesRuntimeHistoryConsentOrExecutionCommands() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("download_enabled", true);
        for (String key : new String[]{"streak_reset_token", "streak_retry_unknown_token", "douai_host_settings_initialized",
                "confirmed_identity", "relation_lists_refresh_token", "third_party_response_sample", "tts_voice_sample",
                "douai_automation_history", "streak.sent.123", "host_adaptation_static_verified"}) source.values.put(key, "excluded-fixture");
        String encoded = ConfigTransfer.export(source, true);
        assertFalse(encoded.contains("excluded-fixture"));
        assertEquals(1, new JSONObject(encoded).getJSONObject("values").length());
    }

    @Test public void officialDirectDownloadBooleanRoundTripsWhileThirdPartyMethodRemainsSelected() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            source.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, enabled);
            source.values.put(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
            target.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, !enabled);
            String encoded = ConfigTransfer.export(source, false);
            JSONObject typed = new JSONObject(encoded).getJSONObject("values")
                    .getJSONObject(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT);
            assertEquals("boolean", typed.getString("type"));
            assertTrue(typed.get("value") instanceof Boolean);
            assertEquals(enabled, typed.getBoolean("value"));

            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
            assertEquals(2, preview.entryCount);
            assertTrue(preview.changedKeys.contains(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT));
            assertEquals(0, target.commits);
            ConfigTransfer.apply(target, preview);
            assertEquals(source.values, target.values);
            assertEquals(enabled, new LocalOptions(target.snapshot()).officialDownloadDirect());
            assertTrue(new ThirdPartyDownloadSettings(new LocalOptions(target.snapshot())).thirdParty);
            assertEquals(1, target.commits);
        }
    }

    @Test public void commentReplyReceptionAndSharedReplySettingsRoundTripWithoutLosingLegacyValues() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        source.values.put("comment_reply_prompt", "结合原评论，简洁回复对方");
        source.values.put("comment_reply_daily_limit", "20");
        source.values.put("comment_reply_user_cooldown_minutes", "1");
        source.values.put("comment_reply_thread_limit", "3");
        source.values.put("comment_reply_tip_enabled", false);
        source.values.put("comment_reply_tip_seconds", "3");
        source.values.put("comment_reply_failure_notify", true);
        source.values.put("native_auto_reply", true);
        source.values.put("native_auto_comment", false);
        source.values.put("reply_mode", "ai");
        source.values.put("reply_prompt", "共享二级提示词");
        source.values.put("auto_message_text", "共享固定正文");
        source.values.put("reply_daily_limit", "7");
        source.values.put("reply_user_cooldown_minutes", "2");
        source.values.put("reply_tip", true);
        source.values.put("reply_notice_seconds", "8");
        source.values.put("reply_save_history", true);
        source.values.put("reply_voice", true);
        source.values.put("reply_private_chats", false);
        source.values.put("reply_group_chats", false);
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        assertEquals(source.values.size(), preview.entryCount);
        assertTrue(preview.changedKeys.contains(LocalOptions.COMMENT_REPLY_ENABLED));
        assertTrue(preview.changedKeys.contains("comment_reply_prompt"));
        ConfigTransfer.apply(target, preview);
        assertEquals(source.values, target.values);
        assertTrue(new LocalOptions(target.snapshot()).enabled(LocalOptions.COMMENT_REPLY_ENABLED));
        assertEquals(20, new LocalOptions(target.snapshot()).integer("comment_reply_daily_limit", 0));
        assertTrue(new LocalOptions(target.snapshot()).enabled("native_auto_reply"));
        assertEquals("ai", new LocalOptions(target.snapshot()).text("reply_mode", ""));
        assertEquals("共享二级提示词", new LocalOptions(target.snapshot()).text("reply_prompt", ""));
        assertEquals(7, new LocalOptions(target.snapshot()).integer("reply_daily_limit", 0));
        assertTrue(new LocalOptions(target.snapshot()).enabled("reply_save_history"));
        assertFalse(new LocalOptions(target.snapshot()).enabled("reply_private_chats"));
        assertFalse(new LocalOptions(target.snapshot()).enabled("reply_group_chats"));
        assertFalse(new LocalOptions(target.snapshot()).enabled("native_auto_comment"));
    }

    @Test public void replyRecognitionBooleansRoundTripIndependentlyOfWorkCommentRecognition() throws Exception {
        for (boolean images : new boolean[]{false, true}) {
            for (boolean video : new boolean[]{false, true}) {
                MemoryStore source = new MemoryStore(), target = new MemoryStore();
                source.values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, images);
                source.values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, video);
                source.values.put("comment_recognize_images", !images);
                source.values.put("comment_recognize_video", !video);
                String encoded = ConfigTransfer.export(source, false);
                JSONObject transferred = new JSONObject(encoded).getJSONObject("values");
                assertEquals("boolean", transferred.getJSONObject(LocalOptions.REPLY_RECOGNIZE_IMAGES).getString("type"));
                assertEquals("boolean", transferred.getJSONObject(LocalOptions.REPLY_RECOGNIZE_VIDEO).getString("type"));
                ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
                assertEquals(4, preview.entryCount);
                ConfigTransfer.apply(target, preview);
                assertEquals(source.values, target.values);
                LocalOptions restored = new LocalOptions(target.snapshot());
                assertEquals(images, restored.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true));
                assertEquals(video, restored.enabled(LocalOptions.REPLY_RECOGNIZE_VIDEO, false));
                assertEquals(!images, restored.enabled("comment_recognize_images", true));
                assertEquals(!video, restored.enabled("comment_recognize_video", false));
            }
        }
    }

    @Test public void thirdPartyDirectDownloadBooleanTransfersIndependentlyOfOfficialDefaultBehavior() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            source.values.put(ThirdPartyDownloadSettings.DIRECT, enabled);
            source.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, !enabled);
            source.values.put(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
            target.values.put(ThirdPartyDownloadSettings.DIRECT, !enabled);
            target.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, enabled);
            String encoded = ConfigTransfer.export(source, false);
            JSONObject typed = new JSONObject(encoded).getJSONObject("values")
                    .getJSONObject(ThirdPartyDownloadSettings.DIRECT);
            assertEquals("boolean", typed.getString("type"));
            assertTrue(typed.get("value") instanceof Boolean);
            assertEquals(enabled, typed.getBoolean("value"));
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
            assertEquals(3, preview.entryCount);
            assertTrue(preview.changedKeys.contains(ThirdPartyDownloadSettings.DIRECT));
            assertTrue(preview.changedKeys.contains(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT));
            ConfigTransfer.apply(target, preview);
            assertEquals(source.values, target.values);
            LocalOptions restored = new LocalOptions(target.snapshot());
            assertEquals(enabled, new ThirdPartyDownloadSettings(restored).direct);
            assertEquals(!enabled, restored.officialDownloadDirect());
            assertTrue(new ThirdPartyDownloadSettings(restored).thirdParty);
        }
    }

    @Test public void redactionCoversModelsArbitraryHeadersParametersAndUrlCredentials() throws Exception {
        MemoryStore source = new MemoryStore();
        source.values.put("ai_model_settings_v2", model("https://api.test/v1", "fixture-model-secret",
                "{\"X-Tenant\":\"fixture-custom-header\",\"Accept\":\"application/json\"}",
                "{\"nonstandardCredential\":\"fixture-custom-param\",\"temperature\":0.3}"));
        source.values.put("third_party_get_template", "https://download.test/api/appSecret/fixture-path-secret?other=fixture-query-secret&url={url}");
        source.values.put("third_party_headers", "{\"X-Whatever\":\"fixture-download-header\",\"Content-Type\":\"application/json\"}");
        source.values.put("third_party_app_secret", "fixture-app-secret");
        source.values.put("tts_get_template", "https://speech.test/fixture-opaque-path?apikey=fixture-tts-secret&text={text}");
        source.values.put("tts_voice_headers", "{\"Cookie\":\"fixture-cookie-secret\"}");
        String encoded = ConfigTransfer.export(source, false);
        for (String secret : new String[]{"fixture-model-secret", "fixture-custom-header", "fixture-custom-param", "fixture-path-secret",
                "fixture-query-secret", "fixture-download-header", "fixture-app-secret", "fixture-opaque-path", "fixture-tts-secret", "fixture-cookie-secret"}) assertFalse(secret, encoded.contains(secret));
        JSONObject exportedValues = new JSONObject(encoded).getJSONObject("values");
        assertEquals("application/json", new JSONObject(exportedValues.getJSONObject("third_party_headers").getString("value")).getString("Content-Type"));
        assertTrue(encoded.contains("temperature"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(new MemoryStore(), encoded);
        assertFalse(preview.includesSecrets); assertTrue(preview.redactedCount >= 10); assertTrue(preview.missingSecretsCount >= 10);
    }

    @Test public void secretFreeImportKeepsLocalSecretsOnlyForTheSameModelAndEndpoint() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("ai_model_settings_v2", model("https://api.test/v1", "source-secret", "{\"X-Auth\":\"source-header\"}", "{}"));
        target.values.put("ai_model_settings_v2", model("https://api.test/v1", "local-secret", "{\"X-Auth\":\"local-header\"}", "{}"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        AiModelConfig result = AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).configs.get(0);
        assertEquals("local-secret", result.apiKey); assertEquals("local-header", result.headers().get("X-Auth"));
        assertEquals(0, preview.missingSecretsCount);
    }

    @Test public void modelTokenBudgetsKeepTheirTypesAndNestedOpaqueCredentialsAreRedacted() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://api.test/v1", "fixture-secret", "{}",
                "{\"max_tokens\":2048,\"temperature\":0.5,\"opaqueList\":[\"fixture-array-secret\",37,{\"tenant\":\"fixture-nested-secret\"}]}"));
        String encoded = ConfigTransfer.export(source, false);
        assertFalse(encoded.contains("fixture-array-secret")); assertFalse(encoded.contains("fixture-nested-secret"));
        JSONObject model = new JSONObject(new JSONObject(encoded).getJSONObject("values").getJSONObject(AiModelSettings.KEY)
                .getString("value")).getJSONArray("configs").getJSONObject(0);
        assertEquals(1024, model.getInt("maxTokens")); assertFalse(model.getBoolean("maxTokensEnabled"));
        assertEquals(2048, new JSONObject(model.getString("customParameters")).getInt("max_tokens"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
        ConfigTransfer.apply(target, preview);
        assertEquals(1024, AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).configs.get(0).maxTokens);
        assertTrue(preview.missingSecretsCount >= 4);
    }

    @Test public void differentModelEndpointNeverReceivesAnExistingKey() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://new.test/v1", "source-secret", "{\"X-Auth\":\"source-header\"}", "{}"));
        target.values.put(AiModelSettings.KEY, model("https://old.test/v1", "local-secret", "{\"X-Auth\":\"local-header\"}", "{}"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        String result = (String) target.values.get(AiModelSettings.KEY);
        assertFalse(result.contains("local-secret")); assertFalse(result.contains("local-header"));
        assertTrue(preview.missingSecretsCount >= 2);
    }

    @Test public void changedServiceClearsInheritedSecretsEvenWhenBackupHasNoHeaders() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("third_party_get_template", "https://new.test/api/parse?url={url}");
        target.values.put("third_party_get_template", "https://old.test/api/parse?url={url}");
        target.values.put("third_party_headers", "{\"X-Unknown\":\"local-secret\"}");
        target.values.put("third_party_app_secret", "local-secret");
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        assertFalse(target.values.toString().contains("local-secret")); assertEquals(2, preview.missingSecretsCount);
    }

    @Test public void sameServiceRetainsHeadersNotPresentInSecretFreeBackup() {
        for (String emptyHeaders : new String[]{"{}", ""}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            String endpoint = "https://speech.test/api?text={text}";
            source.values.put("tts_get_template", endpoint); source.values.put("tts_headers", emptyHeaders);
            target.values.put("tts_get_template", endpoint); target.values.put("tts_headers", "{\"X-Custom\":\"local-secret\"}");
            ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
            assertTrue(target.values.get("tts_headers").toString().contains("local-secret"));
        }
    }

    @Test public void bareQueryCredentialsAreRedactedAndTheBackupRemainsReadable() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("tts_get_template", "https://speech.test/api?opaque-secret&text={text}");
        String encoded = ConfigTransfer.export(source, false);
        assertFalse(encoded.contains("opaque-secret"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
        ConfigTransfer.apply(target, preview);
        assertEquals(1, preview.redactedCount); assertEquals(1, preview.missingSecretsCount);
        assertFalse(target.values.toString().contains("opaque-secret"));
    }

    @Test public void invalidEndpointFieldsAreOmittedWithoutScanningOrdinaryFreeText() {
        for (String value : new String[]{"opaque-invalid-secret", "ftp://fixture-secret@example.test/api", "https://broken host/fixture-secret"}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            source.values.put("tts_get_template", value);
            String encoded = ConfigTransfer.export(source, false);
            assertFalse(encoded.contains("fixture-secret")); assertFalse(encoded.contains("opaque-invalid-secret"));
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
            ConfigTransfer.apply(target, preview);
            assertEquals("", target.values.get("tts_get_template"));
            assertEquals(1, preview.redactedCount); assertEquals(1, preview.missingSecretsCount);
        }
    }

    @Test public void ordinaryReplyTextAndModelPromptsKeepTheirPublicUrls() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        String text = "请阅读 https://example.test/articles/public-information?section=news";
        source.values.put("reply_prompt", text); source.values.put("auto_message_text", text);
        JSONObject models = new JSONObject(model("https://api.test/v1", "fixture-key", "{}", "{}"));
        models.getJSONArray("configs").getJSONObject(0).put("systemPrompt", text);
        source.values.put(AiModelSettings.KEY, models.toString());
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals(text, target.values.get("reply_prompt")); assertEquals(text, target.values.get("auto_message_text"));
        assertEquals(text, AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).configs.get(0).systemPrompt);
    }

    @Test public void appIdIsPartOfThirdPartyCredentialIdentityEvenWithTheSameTemplate() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        String endpoint = "https://download.test/api/parse?app_id={app_id}&url={url}";
        source.values.put("third_party_get_template", endpoint); source.values.put("third_party_app_id", "new-app");
        source.values.put("third_party_app_secret", "source-secret");
        target.values.put("third_party_get_template", endpoint); target.values.put("third_party_app_id", "old-app");
        target.values.put("third_party_app_secret", "old-secret");
        target.values.put("third_party_headers", "{\"X-Auth\":\"old-header\"}");
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        assertEquals("new-app", target.values.get("third_party_app_id"));
        assertEquals("", target.values.get("third_party_app_secret"));
        assertFalse(target.values.toString().contains("old-header")); assertTrue(preview.missingSecretsCount >= 2);
    }

    @Test public void mergeKeepsOtherSettingsAndReplaceKeepsLocalRuntimeState() {
        MemoryStore source = new MemoryStore(), merge = new MemoryStore(), replace = new MemoryStore();
        source.values.put("download_enabled", true);
        for (MemoryStore target : new MemoryStore[]{merge, replace}) {
            target.values.put("auto_play", true); target.values.put("streak_reset_token", "local-command");
            target.values.put("douai_host_settings_initialized", true);
        }
        String encoded = ConfigTransfer.export(source, false);
        ConfigTransfer.apply(merge, ConfigTransfer.inspect(merge, encoded));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(replace, encoded, ConfigTransfer.Mode.REPLACE);
        ConfigTransfer.apply(replace, preview);
        assertEquals(true, merge.values.get("auto_play")); assertFalse(replace.values.containsKey("auto_play"));
        assertEquals("local-command", replace.values.get("streak_reset_token"));
        assertEquals(true, replace.values.get("douai_host_settings_initialized")); assertEquals(1, preview.removedCount);
    }

    @Test public void unknownOrOperationalKeysAndWrongDeclaredOrActualTypesRejectWholeFile() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore(); source.values.put("download_enabled", true);
        for (String key : new String[]{"unknown_key", "streak_reset_token", "douai_host_settings_initialized"}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, false));
            root.getJSONObject("values").put(key, new JSONObject().put("type", "string").put("value", "never-written"));
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        for (String type : new String[]{"string", "unknown", "boolean"}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, false));
            root.getJSONObject("values").put("download_enabled", new JSONObject().put("type", type).put("value", "true"));
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        assertTrue(target.values.isEmpty()); assertEquals(0, target.commits);
    }

    @Test public void truncatedDuplicateTrailingOversizedAndWrongEnvelopeAreRejected() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore(); String good = ConfigTransfer.export(source, false);
        for (String invalid : new String[]{good.substring(0, good.length() - 1), good + " {}", good.replace("\"formatVersion\": 1", "\"formatVersion\": 1,\"formatVersion\":1"),
                good.replace("\"formatVersion\": 1", "\"formatVersion\": 999"), good.replace("io.github.yudigaga.douai", "other.app"),
                " ".repeat(ConfigTransfer.MAX_BYTES + 1), "{values:{}}"}) {
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, invalid));
        }
        assertEquals(0, target.commits);
    }

    @Test public void malformedStructuredPayloadDoesNotReachStore() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("download_enabled", true);
        for (String key : new String[]{AiModelSettings.KEY, "tts_headers", CustomViewLocator.SETTINGS_KEY}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, false));
            root.getJSONObject("values").put(key, new JSONObject().put("type", "string").put("value", "{\"broken\":true"));
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        assertEquals(0, target.commits);
    }

    @Test public void overflowingModelIntegersCannotBeTruncatedByTheLegacyReader() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://api.test/v1", "fixture-key", "{}", "{}"));
        for (String field : new String[]{"contextRounds", "timeoutSeconds", "maxTokens"}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, true));
            JSONObject typed = root.getJSONObject("values").getJSONObject(AiModelSettings.KEY);
            JSONObject models = new JSONObject(typed.getString("value"));
            models.getJSONArray("configs").getJSONObject(0).put(field, 4294967296L);
            typed.put("value", models.toString());
            assertThrows(field, IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        assertEquals(0, target.commits);
    }

    @Test public void previewIsReadOnlyImmutableAndCannotOverwriteInterveningEdits() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore(); source.values.put("auto_play", true);
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        assertTrue(target.values.isEmpty()); assertEquals(0, target.commits);
        assertThrows(UnsupportedOperationException.class, () -> preview.changedKeys.add("unsafe"));
        target.values.put("keyword", "new edit");
        assertThrows(IllegalStateException.class, () -> ConfigTransfer.apply(target, preview));
        assertFalse(target.values.containsKey("auto_play"));
    }

    @Test public void failedCommitRestoresCompleteOriginalSnapshot() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences actual = context.getSharedPreferences("transfer-failure", Context.MODE_PRIVATE);
        actual.edit().clear().putBoolean("auto_play", false).putString("keyword", "original").commit();
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(actual, args);
                    SharedPreferences.Editor delegate = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                            (editorProxy, editorMethod, editorArgs) -> {
                                Object result = editorMethod.invoke(delegate, editorArgs);
                                if (editorMethod.getName().equals("commit")) return commits.incrementAndGet() != 1;
                                return result == delegate ? editorProxy : result;
                            });
                });
        SettingsRepository target = new SettingsRepository(failing); MemoryStore source = new MemoryStore();
        source.values.put("auto_play", true); source.values.put("keyword", "replacement");
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        assertThrows(IllegalStateException.class, () -> ConfigTransfer.apply(target, preview));
        assertEquals(Map.of("auto_play", false, "keyword", "original"), actual.getAll()); assertEquals(2, commits.get());
    }

    @Test public void realRepositoryPublishesTheWholeBatchInOneCommit() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences("transfer-success", Context.MODE_PRIVATE);
        preferences.edit().clear().commit(); SettingsRepository target = new SettingsRepository(preferences);
        MemoryStore source = new MemoryStore(); source.values.put("auto_play", true); source.values.put("keyword", "complete");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals(source.values, target.snapshot());
    }

    private static String model(String endpoint, String key, String headers, String parameters) throws Exception {
        AiModelConfig model = AiModelConfig.fromJson(new JSONObject().put("id", "stable-id").put("name", "Fixture model")
                .put("apiEndpoint", endpoint).put("apiKey", key).put("modelName", "fixture-model")
                .put("customHeaders", headers).put("customParameters", parameters));
        return new AiModelSettings(java.util.List.of(model), model.id).serialize();
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>(); int commits;
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public Map<String, ?> snapshot() { return new LinkedHashMap<>(values); }
        public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            if (!values.equals(expected)) throw new IllegalStateException("stale");
            values.clear(); values.putAll(replacement); commits++;
        }
    }
}

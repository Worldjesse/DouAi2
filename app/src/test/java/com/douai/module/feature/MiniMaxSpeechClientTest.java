package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.MiniMaxSpeechOptions;
import com.douai.module.config.SpeechSettings;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class MiniMaxSpeechClientTest {
    private static final String MP3_HEX = "49443366697874757265";
    private static final byte[] MP3 = new byte[]{'I', 'D', '3', 'f', 'i', 'x', 't', 'u', 'r', 'e'};

    @Test public void sendsNativeJsonWithIndependentVoiceControlsAndPreservesUnicodeText() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(success(MP3_HEX)); server.start();
            SpeechSettings settings = new SpeechSettings(new LocalOptions(Map.of(
                    SpeechSettings.PROVIDER, SpeechSettings.MINIMAX,
                    SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString(),
                    SpeechSettings.MINIMAX_API_KEY, "private-key",
                    SpeechSettings.MINIMAX_MODEL, "speech-2.8-hd",
                    SpeechSettings.MINIMAX_VOICE_ID, "voice-中文",
                    SpeechSettings.MINIMAX_SPEED, "1.25",
                    SpeechSettings.MINIMAX_VOLUME, "2.5",
                    SpeechSettings.MINIMAX_PITCH, "-3",
                    SpeechSettings.RATE, "0.5",
                    SpeechSettings.PITCH, "2")));
            String text = "你好，\"世界\"\n第二行\\路径 😊 & + /? #";
            SpeechHttpClient.Resource audio = MiniMaxSpeechClient.synthesize(request, text, settings);
            assertArrayEquals(MP3, audio.bytes); assertTrue(audio.isAudio()); assertEquals(".mp3", audio.audioExtension());
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("POST", sent.getMethod()); assertEquals("/v1/t2a_v2", sent.getPath());
            assertEquals("Bearer private-key", sent.getHeader("Authorization"));
            assertTrue(sent.getHeader("Content-Type").startsWith("application/json"));
            JSONObject body = new JSONObject(sent.getBody().readUtf8());
            assertEquals(text, body.getString("text")); assertEquals("speech-2.8-hd", body.getString("model"));
            assertFalse(body.getBoolean("stream")); assertEquals("url", body.getString("output_format"));
            JSONObject voice = body.getJSONObject("voice_setting");
            assertEquals("voice-中文", voice.getString("voice_id"));
            assertEquals(1.25, voice.getDouble("speed"), 0.0001);
            assertEquals(2.5, voice.getDouble("vol"), 0.0001); assertEquals(-3, voice.getInt("pitch"));
            JSONObject format = body.getJSONObject("audio_setting");
            assertEquals("mp3", format.getString("format")); assertEquals(32000, format.getInt("sample_rate"));
            assertEquals(128000, format.getInt("bitrate")); assertEquals(1, format.getInt("channel"));
            assertFalse(body.toString().contains("private-key"));
        }
    }

    @Test public void signedAudioLinksNeverReceiveTheApiKeyIncludingSameOrigin() throws Exception {
        try (MockWebServer api = new MockWebServer(); MockWebServer cdn = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            api.start(); cdn.start();
            api.enqueue(success(cdn.url("/audio.mp3?signature=media-token").toString())); cdn.enqueue(mp3());
            assertArrayEquals(MP3, MiniMaxSpeechClient.synthesize(request, "hello", settings(api)).bytes);
            assertEquals("Bearer private-key", api.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
            RecordedRequest media = cdn.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("GET", media.getMethod()); assertNull(media.getHeader("Authorization"));
            assertEquals("media-token", media.getRequestUrl().queryParameter("signature"));
            api.enqueue(success(api.url("/signed-audio").toString())); api.enqueue(mp3());
            assertArrayEquals(MP3, MiniMaxSpeechClient.synthesize(request, "again", settings(api)).bytes);
            assertEquals("Bearer private-key", api.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
            assertNull(api.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
        }
    }

    @Test public void defaultsOmitOptionalAdvancedProviderParameters() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(success(MP3_HEX)); server.start();
            MiniMaxSpeechClient.synthesize(request, "hello", settings(server));
            JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            for (String field : new String[]{"language_boost", "text_normalization_mode", "voice_modify", "pronunciation_dict", "aigc_watermark"}) {
                assertFalse(field, body.has(field));
            }
            JSONObject voice = body.getJSONObject("voice_setting");
            assertFalse(voice.has("emotion")); assertFalse(voice.has("latex_read")); assertFalse(voice.has("text_normalization"));
        }
    }

    @Test public void sendsAdvancedOptionsAndDropsMp3OnlyBitrateForWavAndFlac() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            for (String format : new String[]{"wav", "flac"}) {
                Map<String, Object> options = new LinkedHashMap<>();
                options.put(MiniMaxSpeechOptions.AUDIO_FORMAT, format); options.put(MiniMaxSpeechOptions.SAMPLE_RATE, "44100");
                options.put(MiniMaxSpeechOptions.BITRATE, "64000"); options.put(MiniMaxSpeechOptions.CHANNEL, "2");
                options.put(MiniMaxSpeechOptions.EMOTION, "sad"); options.put(MiniMaxSpeechOptions.LANGUAGE_BOOST, "Chinese,Yue");
                options.put(MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality");
                options.put(MiniMaxSpeechOptions.MODIFY_PITCH, "-32"); options.put(MiniMaxSpeechOptions.MODIFY_INTENSITY, "57");
                options.put(MiniMaxSpeechOptions.MODIFY_TIMBRE, "100"); options.put(MiniMaxSpeechOptions.SOUND_EFFECT, "robotic");
                options.put(MiniMaxSpeechOptions.PRONUNCIATION_TONE, " 处理/(chu3)(li3) \r\n\r\n重行/(chong2)(xing2) ");
                options.put(MiniMaxSpeechOptions.AIGC_WATERMARK, "true"); options.put(MiniMaxSpeechOptions.LATEX_READ, "false");
                server.enqueue(success("wav".equals(format) ? "52494646303030305741564566697874757265" : "664c614366697874757265"));
                SpeechHttpClient.Resource result = MiniMaxSpeechClient.synthesize(request, "处理重行", settings(server, options));
                assertEquals("." + format, result.audioExtension());
                JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
                assertEquals("处理重行", body.getString("text")); assertEquals("speech-2.8-hd", body.getString("model"));
                assertEquals("url", body.getString("output_format")); assertFalse(body.getBoolean("stream"));
                assertEquals("Chinese,Yue", body.getString("language_boost")); assertEquals("quality", body.getString("text_normalization_mode"));
                JSONObject voice = body.getJSONObject("voice_setting");
                assertEquals("sad", voice.getString("emotion")); assertFalse(voice.getBoolean("latex_read"));
                assertFalse(voice.has("text_normalization")); assertEquals("male-qn-qingse", voice.getString("voice_id"));
                JSONObject audio = body.getJSONObject("audio_setting");
                assertEquals(format, audio.getString("format")); assertEquals(44100, audio.getInt("sample_rate"));
                assertEquals(2, audio.getInt("channel")); assertFalse(audio.has("bitrate"));
                JSONObject modify = body.getJSONObject("voice_modify");
                assertEquals(-32, modify.getInt("pitch")); assertEquals(57, modify.getInt("intensity"));
                assertEquals(100, modify.getInt("timbre")); assertEquals("robotic", modify.getString("sound_effects"));
                assertEquals(List.of("处理/(chu3)(li3)", "重行/(chong2)(xing2)"),
                        List.of(body.getJSONObject("pronunciation_dict").getJSONArray("tone").getString(0),
                                body.getJSONObject("pronunciation_dict").getJSONArray("tone").getString(1)));
                assertEquals(2, body.getJSONObject("pronunciation_dict").getJSONArray("tone").length());
                assertTrue(body.getBoolean("aigc_watermark"));
            }
        }
    }

    @Test public void selectedMp3BitrateAndModelSpecificWhisperReachTheProvider() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(success(MP3_HEX)); server.start();
            MiniMaxSpeechClient.synthesize(request, "hello", settings(server, Map.of(
                    SpeechSettings.MINIMAX_MODEL, "speech-2.6-turbo", MiniMaxSpeechOptions.EMOTION, "whisper",
                    MiniMaxSpeechOptions.AUDIO_FORMAT, "mp3", MiniMaxSpeechOptions.SAMPLE_RATE, "24000",
                    MiniMaxSpeechOptions.BITRATE, "256000", MiniMaxSpeechOptions.CHANNEL, "2")));
            JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals("speech-2.6-turbo", body.getString("model"));
            assertEquals("whisper", body.getJSONObject("voice_setting").getString("emotion"));
            JSONObject audio = body.getJSONObject("audio_setting");
            assertEquals("mp3", audio.getString("format")); assertEquals(24000, audio.getInt("sample_rate"));
            assertEquals(256000, audio.getInt("bitrate")); assertEquals(2, audio.getInt("channel"));
        }
    }

    @Test public void latexEnablesChineseAndExplicitFalseWatermarkIsPreserved() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(success(MP3_HEX)); server.start();
            MiniMaxSpeechClient.synthesize(request, "x=2", settings(server, Map.of(
                    MiniMaxSpeechOptions.LATEX_READ, "true", MiniMaxSpeechOptions.AIGC_WATERMARK, "false")));
            JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals("Chinese", body.getString("language_boost"));
            assertEquals(Boolean.TRUE, body.getJSONObject("voice_setting").get("latex_read"));
            assertEquals(Boolean.FALSE, body.get("aigc_watermark"));
        }
    }

    @Test public void incompatibleModelsAndInvalidAdvancedSettingsFailBeforeNetworkRequests() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            List<Map<String, String>> invalid = List.of(
                    Map.of(MiniMaxSpeechOptions.EMOTION, "whisper"), Map.of(MiniMaxSpeechOptions.EMOTION, "fluent"),
                    Map.of(SpeechSettings.MINIMAX_MODEL, "speech-01-hd", MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality"),
                    Map.of(SpeechSettings.MINIMAX_MODEL, "speech-02-turbo", MiniMaxSpeechOptions.LANGUAGE_BOOST, "Persian"),
                    Map.of(SpeechSettings.MINIMAX_MODEL, "speech-01-turbo", MiniMaxSpeechOptions.LANGUAGE_BOOST, "Filipino"),
                    Map.of(MiniMaxSpeechOptions.LATEX_READ, "true", MiniMaxSpeechOptions.LANGUAGE_BOOST, "English"),
                    Map.of(MiniMaxSpeechOptions.AUDIO_FORMAT, "pcm"), Map.of(MiniMaxSpeechOptions.SAMPLE_RATE, "48000"),
                    Map.of(MiniMaxSpeechOptions.BITRATE, "96000"), Map.of(MiniMaxSpeechOptions.CHANNEL, "3"),
                    Map.of(MiniMaxSpeechOptions.MODIFY_PITCH, "101"), Map.of(MiniMaxSpeechOptions.MODIFY_INTENSITY, "-101"),
                    Map.of(MiniMaxSpeechOptions.MODIFY_TIMBRE, "1.5"), Map.of(MiniMaxSpeechOptions.AIGC_WATERMARK, "yes"),
                    Map.of(MiniMaxSpeechOptions.PRONUNCIATION_TONE, "private-key private-text"));
            for (Map<String, String> values : invalid) {
                assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server, values))));
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void apiAndAudioRedirectsAreNotFollowed() throws Exception {
        try (MockWebServer api = new MockWebServer(); MockWebServer other = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            api.start(); other.start();
            api.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", other.url("/capture")));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(api))));
            api.enqueue(success(other.url("/audio").toString()));
            other.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", api.url("/capture")));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(api))));
            assertEquals(2, api.getRequestCount()); assertEquals(1, other.getRequestCount());
            assertNull(other.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
        }
    }

    @Test public void httpAndProviderErrorsCannotLeakResponseBodiesOrCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            for (int status : new int[]{401, 429, 500}) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("private-key private-text"));
                assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server))));
            }
            server.enqueue(json("{\"base_resp\":{\"status_code\":1008,\"status_msg\":\"private-key private-text\"}}"));
            IOException apiError = assertThrows(IOException.class,
                    () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server)));
            assertSafe(apiError); assertTrue(apiError.getMessage().contains("1008"));
        }
    }

    @Test public void malformedAndIncompleteSuccessEnvelopesFailBeforeFetchingAudio() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            String[] bodies = {"private-key private-text", "{}", "{\"base_resp\":{}}",
                    "{\"base_resp\":{\"status_code\":\"0\"}}", "{\"base_resp\":{\"status_code\":false}}",
                    "{\"base_resp\":{\"status_code\":0.5}}", "{\"base_resp\":{\"status_code\":-1}}",
                    "{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":1,\"audio\":\"" + server.url("/audio") + "\"}}",
                    "{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":[]}}",
                    "{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2}}"};
            for (String body : bodies) {
                server.enqueue(json(body));
                assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server))));
            }
            assertEquals(bodies.length, server.getRequestCount());
        }
    }

    @Test public void rejectsMalformedHexAndNonAudioDownloads() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            for (String value : new String[]{"", "494", "49zz33"}) {
                server.enqueue(success(value));
                assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server))));
            }
            server.enqueue(success(server.url("/audio").toString()));
            server.enqueue(json("{\"error\":\"private-key private-text\"}"));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server))));
        }
    }

    @Test public void boundsJsonIncludingHexAndTheLinkedAudioResponse() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            server.enqueue(success("49".repeat(SpeechHttpClient.MAX_TEXT_BYTES / 2 + 1)));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server))));
            server.enqueue(success(server.url("/audio").toString()));
            server.enqueue(mp3().setHeader("Content-Length", SpeechHttpClient.MAX_AUDIO_BYTES + 1));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", settings(server))));
        }
    }

    @Test public void combinesAllVoiceGroupsAndDeduplicatesById() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(json("{\"base_resp\":{\"status_code\":0},"
                    + "\"system_voice\":[{\"voice_id\":\"system\",\"voice_name\":\"系统音色\"},{\"voice_id\":\"fallback\"}],"
                    + "\"voice_cloning\":[{\"voice_id\":\"clone\",\"name\":\"克隆音色\"},{\"voice_id\":\"system\",\"voice_name\":\"Duplicate\"}],"
                    + "\"voice_generation\":[{\"voice_id\":\"generated\",\"voice_name\":\"生成音色\"},{\"voice_name\":\"missing id\"}]}"));
            server.start();
            SpeechSettings settings = new SpeechSettings(new LocalOptions(Map.of(
                    SpeechSettings.MINIMAX_API_KEY, "private-key", SpeechSettings.MINIMAX_BASE_URL, server.url("/v1/").toString())));
            List<VoiceCatalog.Entry> voices = MiniMaxSpeechClient.fetchVoices(request, settings);
            assertEquals(4, voices.size());
            assertEquals("system", voices.get(0).id); assertEquals("系统音色", voices.get(0).name);
            assertEquals("fallback", voices.get(1).id); assertEquals("fallback", voices.get(1).name);
            assertEquals("clone", voices.get(2).id); assertEquals("克隆音色", voices.get(2).name);
            assertEquals("generated", voices.get(3).id); assertEquals("生成音色", voices.get(3).name);
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("POST", sent.getMethod()); assertEquals("/v1/get_voice", sent.getPath());
            assertEquals("Bearer private-key", sent.getHeader("Authorization"));
            assertEquals("all", new JSONObject(sent.getBody().readUtf8()).getString("voice_type"));
        }
    }

    @Test public void emptyMalformedAndFailedVoiceListsReturnSafeErrors() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            for (String body : new String[]{"{\"base_resp\":{\"status_code\":0},\"system_voice\":[]}",
                    "{\"base_resp\":{\"status_code\":0},\"system_voice\":{}}",
                    "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"private-key private-text\"}}"}) {
                server.enqueue(json(body));
                assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.fetchVoices(request, settings(server))));
            }
        }
    }

    @Test public void cancellingBlockedPostPreventsBothSynthesisAndVoiceRequests() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start();
            Future<SpeechHttpClient.Resource> future = worker.submit(() -> MiniMaxSpeechClient.synthesize(request, "hello", settings(server)));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertThrows(CancellationException.class, () -> MiniMaxSpeechClient.synthesize(request, "hello", settings(server)));
            assertThrows(CancellationException.class, () -> MiniMaxSpeechClient.fetchVoices(request, settings(server)));
            assertEquals(1, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void cancellingBlockedAudioDownloadStopsTheActiveConnection() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start(); server.enqueue(success(server.url("/audio").toString()));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            Future<SpeechHttpClient.Resource> future = worker.submit(() -> MiniMaxSpeechClient.synthesize(request, "hello", settings(server)));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
        } finally { worker.shutdownNow(); }
    }

    @Test public void invalidCredentialsAndTextAreRejectedWithoutNetworkRequests() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            for (String key : new String[]{"", "private-key\r\nInjected: value"}) {
                SpeechSettings invalid = new SpeechSettings(new LocalOptions(Map.of(
                        SpeechSettings.MINIMAX_API_KEY, key, SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString())));
                assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.synthesize(request, "private-text", invalid)));
            }
            for (String text : new String[]{"", "   ", "a".repeat(10_000)}) {
                assertThrows(IllegalArgumentException.class, () -> MiniMaxSpeechClient.synthesize(request, text, settings(server)));
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void fetchesOnlySpeechModelsFromConfiguredOpenAiCompatibleModelEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(json("{\"object\":\"list\",\"data\":["
                    + "{\"id\":\"MiniMax-M2.5\"},{\"id\":\"speech-2.8-hd\"},{\"id\":\"speech-2.8-turbo\"},"
                    + "{\"id\":\"speech-2.8-hd\"},{\"id\":\"speech-02-hd\"},{\"id\":\"Speech-2.8-hd\"},"
                    + "{\"id\":\"speech-\"},{\"id\":\"speech-secret?private-key\"},{\"id\":42},{\"name\":\"speech-wrong-field\"},"
                    + "{\"id\":\"speech-" + "a".repeat(130) + "\"}]}"));
            server.start();
            SpeechSettings settings = new SpeechSettings(new LocalOptions(Map.of(
                    SpeechSettings.MINIMAX_API_KEY, "private-key", SpeechSettings.MINIMAX_BASE_URL, server.url("/gateway/v1/").toString())));
            List<String> models = MiniMaxSpeechClient.fetchModels(request, settings);
            assertEquals(List.of("speech-2.8-hd", "speech-2.8-turbo", "speech-02-hd"), models);
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("GET", sent.getMethod()); assertEquals("/gateway/v1/models", sent.getPath());
            assertEquals("Bearer private-key", sent.getHeader("Authorization")); assertEquals(0L, sent.getBodySize());
        }
    }

    @Test public void absentSpeechModelsAndFailedModelApisNeverReportSuccessOrProviderContent() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start();
            for (String body : new String[]{"{\"object\":\"list\",\"data\":[]}",
                    "{\"data\":[{\"id\":\"MiniMax-M2.5\"}]}", "{\"data\":{}}", "private-key private-text",
                    "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"private-key private-text\"},\"data\":[{\"id\":\"speech-2.8-hd\"}]}"}) {
                server.enqueue(json(body));
                assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.fetchModels(request, settings(server))));
            }
            server.enqueue(new MockResponse().setResponseCode(404).setBody("private-key private-text"));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.fetchModels(request, settings(server))));
        }
    }

    @Test public void modelsApiHonorsSuccessStatusAndDoesNotFollowRedirects() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start(); other.start();
            server.enqueue(json("{\"base_resp\":{\"status_code\":0},\"data\":[{\"id\":\"speech-2.8-hd\"}]}"));
            assertEquals(List.of("speech-2.8-hd"), MiniMaxSpeechClient.fetchModels(request, settings(server)));
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", other.url("/capture")));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.fetchModels(request, settings(server))));
            assertEquals(0, other.getRequestCount());
        }
    }

    @Test public void officialModelDocumentUpdatesNeverSendCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(json(modelDocument("[\"speech-2.8-hd\",\"speech-2.8-turbo\"]"))); server.start();
            assertEquals(List.of("speech-2.8-hd", "speech-2.8-turbo"),
                    MiniMaxSpeechClient.fetchDocumentModels(request, server.url("/speech/openapi.json").toString()));
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("GET", sent.getMethod()); assertEquals("/speech/openapi.json", sent.getPath());
            assertNull(sent.getHeader("Authorization"));
        }
    }

    @Test public void officialModelDocumentRejectsRedirectsMalformedAndOversizedResponses() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start(); other.start();
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", other.url("/capture")));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.fetchDocumentModels(request, server.url("/openapi.json").toString())));
            assertEquals(0, other.getRequestCount());
            server.enqueue(json("{\"example\":\"private-key private-text\"}"));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.fetchDocumentModels(request, server.url("/openapi.json").toString())));
            server.enqueue(json("{}").setHeader("Content-Length", SpeechHttpClient.MAX_TEXT_BYTES + 1));
            assertSafe(assertThrows(IOException.class, () -> MiniMaxSpeechClient.fetchDocumentModels(request, server.url("/openapi.json").toString())));
        }
    }

    @Test public void cancellingModelApiAndDocumentUpdatesStopsTheActiveRequest() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (boolean document : new boolean[]{false, true}) {
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
                    Future<List<String>> future = worker.submit(() -> document
                            ? MiniMaxSpeechClient.fetchDocumentModels(request, server.url("/openapi.json").toString())
                            : MiniMaxSpeechClient.fetchModels(request, settings(server)));
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); request.cancel();
                    ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof CancellationException);
                }
            }
        } finally { worker.shutdownNow(); }
    }

    private static String modelDocument(String models) {
        return "{\"components\":{\"schemas\":{\"T2aV2Req\":{\"properties\":{\"model\":{\"enum\":" + models + "}}}}}}";
    }

    private static SpeechSettings settings(MockWebServer server) {
        return new SpeechSettings(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX,
                SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString(), SpeechSettings.MINIMAX_API_KEY, "private-key")));
    }

    private static SpeechSettings settings(MockWebServer server, Map<String, ?> values) {
        return new SpeechSettings(new LocalOptions(values).withDefault(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX)
                .withDefault(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString())
                .withDefault(SpeechSettings.MINIMAX_API_KEY, "private-key"));
    }

    private static MockResponse success(String audio) throws Exception {
        return json(new JSONObject().put("base_resp", new JSONObject().put("status_code", 0))
                .put("data", new JSONObject().put("status", 2).put("audio", audio)).toString());
    }

    private static MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json; charset=utf-8").setBody(body);
    }

    private static MockResponse mp3() {
        return new MockResponse().addHeader("Content-Type", "audio/mpeg").setBody(new Buffer().write(MP3));
    }

    private static void assertSafe(Exception failure) {
        assertNotNull(failure.getMessage()); assertTrue(failure.getMessage().startsWith("MiniMax 语音"));
        assertFalse(failure.getMessage().contains("private")); assertNull(failure.getCause());
    }
}

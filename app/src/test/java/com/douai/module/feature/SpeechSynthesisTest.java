package com.douai.module.feature;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.MediaMetadataRetriever;
import android.speech.tts.TextToSpeech;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = SpeechSynthesisTest.AudioMetadata.class)
public final class SpeechSynthesisTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Implements(MediaMetadataRetriever.class)
    public static final class AudioMetadata extends ShadowMediaMetadataRetriever {
        static volatile String duration = "1000";
        static volatile String hasVideo = "no";
        @Implementation @Override protected String extractMetadata(int key) {
            if (key == MediaMetadataRetriever.METADATA_KEY_DURATION) return duration;
            if (key == MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) return hasVideo;
            if (key == MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) return "yes";
            return null;
        }
    }

    @Before public void resetMetadata() { AudioMetadata.duration = "1000"; AudioMetadata.hasVideo = "no"; }

    @Test public void directBinarySpeechReturnsAnOwnedValidatedFileExactlyOnce() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(wav()); server.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts") + "?text={text}"),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertEquals(1, calls.get()); assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(1000L, result.get().durationMillis); assertTrue(result.get().file.isFile());
            request.close(); ShadowLooper.getShadowMainLooper().idle();
            assertEquals(1, calls.get()); assertTrue(result.get().file.isFile());
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void linkedAudioDownloadsDoNotForwardApiCredentialsAcrossOrigins() throws Exception {
        try (MockWebServer api = new MockWebServer(); MockWebServer cdn = new MockWebServer()) {
            api.start(); cdn.start();
            api.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"audio\":\"" + cdn.url("/sound") + "\"}}"));
            cdn.enqueue(wav());
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY,
                    SpeechSettings.GET_TEMPLATE, api.url("/tts") + "?text={text}&speaker={voice}",
                    SpeechSettings.SELECTED_VOICE, "v2", SpeechSettings.AUDIO_FIELD, "data.audio",
                    SpeechSettings.HEADERS, "{\"Authorization\":\"private-key\"}"));
            SpeechSynthesis.synthesize(context(), "hello", options, (audio, error) -> { result.set(audio); calls.incrementAndGet(); });
            await(calls);
            assertNotNull(result.get());
            assertEquals("private-key", api.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
            assertNull(cdn.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void miniMaxSpeechRoutesToNativePostAndReturnsAnOwnedValidatedMp3() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer thirdParty = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":\"49443366697874757265\"}}"));
            server.start(); thirdParty.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = miniMaxSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    thirdParty.url("/unused") + "?text={text}");
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "你好 MiniMax", options,
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(1000L, result.get().durationMillis); assertTrue(result.get().file.isFile());
            assertTrue(result.get().file.getName().endsWith(".mp3"));
            assertArrayEquals(new byte[]{'I', 'D', '3', 'f', 'i', 'x', 't', 'u', 'r', 'e'},
                    Files.readAllBytes(result.get().file.toPath()));
            assertEquals("/v1/t2a_v2", server.takeRequest(2, TimeUnit.SECONDS).getPath());
            assertEquals(0, thirdParty.getRequestCount());
            request.close(); ShadowLooper.getShadowMainLooper().idle();
            assertEquals(1, calls.get()); assertTrue(result.get().file.isFile());
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void miniMaxFailureDoesNotFallBackToTheGenericProviderOrAndroidEngine() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer thirdParty = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("private-key private-text"));
            thirdParty.enqueue(wav()); server.start(); thirdParty.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = miniMaxSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    thirdParty.url("/unused") + "?text={text}");
            SpeechSynthesis.synthesize(context(), "private-text", options,
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(result.get()); assertNotNull(failure.get());
            assertTrue(failure.get().startsWith("MiniMax 语音")); assertFalse(failure.get().contains("private"));
            assertEquals(1, server.getRequestCount()); assertEquals(0, thirdParty.getRequestCount());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void invalidMiniMaxAudioIsRemovedWithoutDeliveringAnOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AudioMetadata.duration = "0";
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":\"49443366697874757265\"}}"));
            server.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis.synthesize(context(), "hello", miniMaxSettings(server),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(result.get()); assertEquals("生成的语音文件无效", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void closeCancelsMiniMaxPostAndCompletesOnceWithoutOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start();
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "hello", miniMaxSettings(server),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.close(); request.close(); await(calls);
            assertEquals(1, calls.get()); assertEquals("语音生成已停止", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void thirdPartySpeechDoesNotUseTheAndroidEngineTextLimit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(wav()); server.start();
            String text = "voice ".repeat(TextToSpeech.getMaxSpeechInputLength() / 6 + 1);
            assertTrue(text.length() > TextToSpeech.getMaxSpeechInputLength());
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis.synthesize(context(), text, settings(server.url("/tts") + "?text={text}"),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(text, server.takeRequest(2, TimeUnit.SECONDS).getRequestUrl().queryParameter("text"));
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void thirdPartyAudioOverTwoMinutesIsAcceptedWithItsActualDuration() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AudioMetadata.duration = "180001";
            server.enqueue(wav()); server.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts").toString()),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(180001L, result.get().durationMillis);
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void invalidAudioIsRejectedAndRemoved() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (String duration : new String[]{null, "0", "-1"}) {
                AudioMetadata.duration = duration;
                server.enqueue(wav());
                AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
                AtomicReference<String> failure = new AtomicReference<>();
                AtomicInteger calls = new AtomicInteger();
                SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts").toString()),
                        (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
                await(calls);
                assertNull(result.get()); assertNotNull(failure.get()); assertEquals(0, cachedAudioCount());
            }
        }
    }

    @Test public void videosAndNonAudioProviderErrorsAreRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(); server.enqueue(wav());
            AudioMetadata.hasVideo = "yes";
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis.synthesize(context(), "private-text", settings(server.url("/tts") + "?apikey=private-key"),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertEquals("生成的语音文件无效", failure.get()); assertEquals(0, cachedAudioCount());
            server.enqueue(new MockResponse().setBody("{\"error\":\"private-key private-text\"}"));
            calls.set(0);
            SpeechSynthesis.synthesize(context(), "private-text", settings(server.url("/tts") + "?apikey=private-key"),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertFalse(failure.get().contains("private")); assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void closeCancelsBlockedProviderAndCompletesOnceWithNoCachedOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start();
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts").toString()),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.close(); request.close(); await(calls);
            assertEquals(1, calls.get()); assertEquals("语音生成已停止", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void closeAfterWorkerCompletionBeforeDeliveryDeletesThePendingResult() throws Exception {
        AtomicReference<LocalSpeechSynthesis.Result> delivered = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        java.lang.reflect.Constructor<SpeechSynthesis> constructor =
                SpeechSynthesis.class.getDeclaredConstructor(BiConsumer.class);
        constructor.setAccessible(true);
        SpeechSynthesis request = constructor.newInstance((BiConsumer<LocalSpeechSynthesis.Result, String>) (audio, error) -> {
            delivered.set(audio); failure.set(error); calls.incrementAndGet();
        });
        java.lang.reflect.Method complete = SpeechSynthesis.class.getDeclaredMethod("complete",
                LocalSpeechSynthesis.Result.class, String.class);
        complete.setAccessible(true);
        File pending = temporary.newFile("pending.wav");
        complete.invoke(request, new LocalSpeechSynthesis.Result(pending, 1000), null);
        assertEquals(0, calls.get());
        request.close();
        ShadowLooper.getShadowMainLooper().idle();
        assertEquals(1, calls.get());
        assertNull(delivered.get());
        assertEquals("语音生成已停止", failure.get());
        assertFalse(pending.exists());
    }

    private Context context() {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return temporary.getRoot(); }
        };
    }

    private int cachedAudioCount() {
        File[] files = new File(temporary.getRoot(), "douai-tts").listFiles(File::isFile);
        return files == null ? 0 : files.length;
    }

    private static LocalOptions settings(String template) {
        return new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY, SpeechSettings.GET_TEMPLATE, template));
    }

    private static LocalOptions miniMaxSettings(MockWebServer server) {
        return new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX,
                SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString(), SpeechSettings.MINIMAX_API_KEY, "private-key"));
    }

    private static MockResponse wav() {
        return new MockResponse().addHeader("Content-Type", "audio/wav")
                .setBody(new Buffer().writeUtf8("RIFF0000WAVEfixture"));
    }

    private static void await(AtomicInteger calls) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (calls.get() == 0 && System.nanoTime() < until) {
            ShadowLooper.getShadowMainLooper().idle();
            Thread.sleep(10);
        }
        assertEquals("Speech callback should complete", 1, calls.get());
    }
}

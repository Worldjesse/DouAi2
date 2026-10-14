package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
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
public final class ThirdPartyGetClientTest {
    private static final String SHARE_URL = "https://v.douyin.com/XVo-IAGSo2s/";

    @Test public void extractsFirstLinkFromShareTextAndDropsAdjacentSentencePunctuation() {
        assertEquals(SHARE_URL, ThirdPartyGetClient.extractShareUrl(
                "3.07 \u590d\u5236\u6253\u5f00\u6296\u97f3 \u4f5c\u54c1 " + SHARE_URL + " Cus:/ 08/27"));
        assertEquals(SHARE_URL, ThirdPartyGetClient.extractShareUrl("(" + SHARE_URL + "). https://ignored.test/"));
        assertEquals("https://example.test/a?x=1&y=2", ThirdPartyGetClient.extractShareUrl(
                "https://example.test/a?x=1&y=2\u3002"));
    }

    @Test public void substitutesEncodedShareUrlAndAppIdAndSendsOnlyGet() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"code\":0,\"data\":{\"video\":[]}}"));
            server.start();
            String result = ThirdPartyGetClient.fetch(server.url("/parse")
                            + "?app_id={app_id}&url={url}", "share " + SHARE_URL,
                    "app *~+", "", "{\"X-Trace\":\"fixture\",\"Authorization\":\"Bearer user-token\"}");
            assertEquals("{\"code\":0,\"data\":{\"video\":[]}}", result);
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals(0, request.getBodySize());
            assertEquals("/parse?app_id=app+%2A%7E%2B&url=https%3A%2F%2Fv.douyin.com%2FXVo-IAGSo2s%2F",
                    request.getPath());
            assertEquals("fixture", request.getHeader("X-Trace"));
            assertEquals("Bearer user-token", request.getHeader("Authorization"));
            assertNull(request.getHeader("Sign"));
        }
    }

    @Test public void appendsEncodedUrlWhenTemplateIsAQueryPrefix() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("https://media.test/video.mp4"));
            server.start();
            assertEquals("https://media.test/video.mp4", ThirdPartyGetClient.fetch(
                    server.url("/parse") + "?app_id=fixture&url=", SHARE_URL, "", "", ""));
            assertEquals("/parse?app_id=fixture&url=https%3A%2F%2Fv.douyin.com%2FXVo-IAGSo2s%2F",
                    server.takeRequest(2, TimeUnit.SECONDS).getPath());
        }
    }

    @Test public void genericConfiguredParametersDoNotRequireOrInjectAppId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            ThirdPartyDownloadSettings config = settings(server.url("/parse")
                    + "?ID=75107541&link={url}&token=custom", Map.of());
            assertEquals("ok", ThirdPartyGetClient.fetch(config, "share " + SHARE_URL));
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/parse?ID=75107541&link=https%3A%2F%2Fv.douyin.com%2FXVo-IAGSo2s%2F&token=custom",
                    request.getPath());
            assertFalse(request.getPath().contains("app_id"));
        }
    }

    @Test public void zeroParametersKeepExactUrlAndRequireNoShareTextInBothApis() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("generic"));
            server.enqueue(new MockResponse().setBody("legacy"));
            server.start();
            String template = server.url("/parse").toString();
            assertEquals("generic", ThirdPartyGetClient.fetch(settings(template, Map.of()), ""));
            assertEquals("/parse", server.takeRequest(2, TimeUnit.SECONDS).getPath());
            assertEquals("legacy", ThirdPartyGetClient.fetch(template, null, "ignored", "", "{}"));
            assertEquals("/parse", server.takeRequest(2, TimeUnit.SECONDS).getPath());
        }
    }

    @Test public void configuredEmptyQueryValueDoesNotAppendAnUnrequestedShareUrl() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            ThirdPartyGetClient.fetch(settings(server.url("/parse") + "?ID=", Map.of()), "");
            assertEquals("/parse?ID=", server.takeRequest(2, TimeUnit.SECONDS).getPath());
        }
    }

    @Test public void sourceLinkCanBeBoundInTheEndpointPath() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            ThirdPartyGetClient.fetch(settings(server.url("/parse/") + "{url}?format=txt", Map.of()), SHARE_URL);
            assertEquals("/parse/https%3A%2F%2Fv.douyin.com%2FXVo-IAGSo2s%2F?format=txt",
                    server.takeRequest(2, TimeUnit.SECONDS).getPath());
        }
    }

    @Test public void explicitlyDisabledSigningIgnoresStaleSecretAndKeepsUserHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            ThirdPartyGetClient.fetch(settings(server.url("/parse") + "?ID=123", Map.of(
                    ThirdPartyDownloadSettings.APP_SECRET, "stale-secret",
                    ThirdPartyDownloadSettings.HEADERS, "{\"Sign\":\"manual\"}")), "");
            assertEquals("manual", server.takeRequest(2, TimeUnit.SECONDS).getHeader("Sign"));
            ThirdPartyGetClient.fetch(settings(server.url("/parse") + "?ID=123", Map.of(
                    ThirdPartyDownloadSettings.APP_SECRET, "stale-secret")), "");
            assertNull(server.takeRequest(2, TimeUnit.SECONDS).getHeader("Sign"));
        }
    }

    @Test public void legacySettingsResolveAppIdAndLinkPrefixBeforeRequestAndKeepTheirSignature() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            ThirdPartyDownloadSettings legacy = new ThirdPartyDownloadSettings(new LocalOptions(Map.of(
                    ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse") + "?ID={app_id}&link=",
                    ThirdPartyDownloadSettings.APP_ID, "old ID+",
                    ThirdPartyDownloadSettings.APP_SECRET, "secret")));
            ThirdPartyGetClient.fetch(legacy, SHARE_URL);
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("old ID+", request.getRequestUrl().queryParameter("ID"));
            assertEquals(SHARE_URL, request.getRequestUrl().queryParameter("link"));
            assertEquals(md5("ID=old+ID%2B&link=https%3A%2F%2Fv.douyin.com%2FXVo-IAGSo2s%2Fsecret"),
                    request.getHeader("Sign"));
        }
    }

    @Test public void selectedMd5SignsActualParametersInAConfigurableHeader() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            ThirdPartyDownloadSettings config = settings(server.url("/parse") + "?z=last&ID=123&link={url}", Map.of(
                    ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_MD5,
                    ThirdPartyDownloadSettings.SIGNATURE_HEADER, "X-Signature",
                    ThirdPartyDownloadSettings.APP_SECRET, "secret",
                    ThirdPartyDownloadSettings.HEADERS, "{\"x-signature\":\"old\",\"X-Token\":\"fixture\"}"));
            ThirdPartyGetClient.fetch(config, SHARE_URL);
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals(md5("ID=123&link=https%3A%2F%2Fv.douyin.com%2FXVo-IAGSo2s%2F&z=lastsecret"),
                    request.getHeader("X-Signature"));
            assertNull(request.getHeader("Sign"));
            assertEquals("fixture", request.getHeader("X-Token"));
        }
    }

    @Test public void invalidSignatureModeHeaderOrMissingSecretNeverContactsProvider() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String template = server.url("/parse").toString();
            ThirdPartyDownloadSettings unknown = settings(template,
                    Map.of(ThirdPartyDownloadSettings.SIGNATURE_MODE, "other"));
            assertEquals("Unsupported third-party GET signature mode",
                    failure(() -> ThirdPartyGetClient.fetch(unknown, "")).getMessage());
            ThirdPartyDownloadSettings missingSecret = settings(template,
                    Map.of(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_MD5));
            assertEquals("MD5 signing requires an App Secret",
                    failure(() -> ThirdPartyGetClient.fetch(missingSecret, "")).getMessage());
            for (String header : new String[]{"", "Host", "Connection", "Content-Length", "X-Sign\r\nInjected"}) {
                ThirdPartyDownloadSettings config = settings(template, Map.of(
                        ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_MD5,
                        ThirdPartyDownloadSettings.SIGNATURE_HEADER, header,
                        ThirdPartyDownloadSettings.APP_SECRET, "secret"));
                assertEquals("Signature header must be a valid request header name",
                        failure(() -> ThirdPartyGetClient.fetch(config, "")).getMessage());
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void signsDecodedSortedParametersUsingPhpFormEncodingAndRawSecret() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            String secret = "raw secret*~+";
            ThirdPartyGetClient.fetch(server.url("/parse")
                            + "?z=last&url={url}&empty=&app_id={app_id}&a=a%20b%2A~%2B",
                    SHARE_URL, "75107541", secret, "{\"sign\":\"replace-me\",\"X-App\":\"fixture\"}");
            String expected = md5("a=a+b%2A%7E%2B&app_id=75107541&empty="
                    + "&url=https%3A%2F%2Fv.douyin.com%2FXVo-IAGSo2s%2F&z=last" + secret);
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals(expected, request.getHeader("Sign"));
            assertEquals(32, request.getHeader("Sign").length());
            assertEquals("fixture", request.getHeader("X-App"));
        }
    }

    @Test public void rejectsDecodedDuplicateQueryNamesBeforeSigningOrContactingProvider() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            Exception failure = failure(() -> ThirdPartyGetClient.fetch(server.url("/parse")
                    + "?app_id=one&%61pp_id=two&url={url}", SHARE_URL, "", "private-secret", ""));
            assertTrue(failure.getMessage().contains("duplicate parameter"));
            assertEquals(0, server.getRequestCount());
            assertFalse(failure.getMessage().contains("private-secret"));
            assertNull(failure.getCause());
        }
    }

    @Test public void rejectsAllRedirectsWithoutForwardingCredentialsToAnotherOrigin() throws Exception {
        try (MockWebServer source = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            source.start();
            other.start();
            source.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", other.url("/capture")));
            Exception failure = failure(() -> ThirdPartyGetClient.fetch(source.url("/parse") + "?url={url}",
                    SHARE_URL, "", "private-secret", "{\"Authorization\":\"private-token\"}"));
            assertTrue(failure.getMessage().contains("redirects are disabled"));
            assertEquals(1, source.getRequestCount());
            assertEquals(0, other.getRequestCount());
            assertFalse(failure.getMessage().contains(other.url("/capture").toString()));
        }
    }

    @Test public void decodesDeclaredTextCharsetAndDefaultsJsonToUtf8() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "text/plain; charset=\"ISO-8859-1\"")
                    .setBody(new Buffer().write(new byte[]{'c', 'a', 'f', (byte) 0xe9})));
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("\uFEFF{\"text\":\"\u4f5c\u54c1\"}"));
            server.start();
            String template = server.url("/parse") + "?url={url}";
            assertEquals("caf\u00e9", ThirdPartyGetClient.fetch(template, SHARE_URL, "", "", ""));
            assertEquals("{\"text\":\"\u4f5c\u54c1\"}", ThirdPartyGetClient.fetch(template, SHARE_URL, "", "", ""));
        }
    }

    @Test public void rejectsResponsesOverLimitWithBothKnownAndChunkedLength() throws Exception {
        String oversized = "x".repeat(2 * 1024 * 1024 + 1);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(oversized));
            server.enqueue(new MockResponse().setChunkedBody(oversized, 8192));
            server.start();
            String template = server.url("/parse") + "?url={url}";
            assertTrue(failure(() -> ThirdPartyGetClient.fetch(template, SHARE_URL, "", "", ""))
                    .getMessage().contains("2 MiB"));
            assertTrue(failure(() -> ThirdPartyGetClient.fetch(template, SHARE_URL, "", "", ""))
                    .getMessage().contains("2 MiB"));
        }
    }

    @Test public void acceptsResponseAtSizeLimit() throws Exception {
        String body = "x".repeat(2 * 1024 * 1024);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(body));
            server.start();
            assertEquals(body, ThirdPartyGetClient.fetch(server.url("/parse") + "?url={url}",
                    SHARE_URL, "", "", ""));
        }
    }

    @Test public void errorsNeverExposeProviderUrlsBodyOrCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403).setBody("private-secret private-token"));
            server.start();
            Exception failure = failure(() -> ThirdPartyGetClient.fetch(server.url("/parse")
                            + "?token=private-token&url={url}", SHARE_URL, "", "private-secret", ""));
            assertEquals("Third-party GET returned HTTP 403", failure.getMessage());
            assertNull(failure.getCause());
            String disconnectedTemplate = server.url("/parse") + "?token=private-token&url={url}";
            server.shutdown();
            Exception disconnected = failure(() -> ThirdPartyGetClient.fetch(disconnectedTemplate,
                    SHARE_URL, "", "private-secret", ""));
            assertEquals("Third-party GET request failed", disconnected.getMessage());
            assertNull(disconnected.getCause());
        }
    }

    @Test public void invalidHeadersAndEndpointCredentialsAreRejectedWithoutNetwork() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String template = server.url("/parse") + "?url={url}";
            for (String headers : new String[]{"[]", "{\"X-Token\":42}",
                    "{\"X-Token\":\"private-token\\r\\nInjected: true\"}",
                    "{\"Host\":\"private-token\"}", "{\"X-Trace\":\"a\",\"x-trace\":\"b\"}"}) {
                Exception failure = failure(() -> ThirdPartyGetClient.fetch(template, SHARE_URL, "", "", headers));
                assertEquals("Custom headers must be a JSON object with valid string headers", failure.getMessage());
                assertNull(failure.getCause());
            }
            Exception embedded = failure(() -> ThirdPartyGetClient.fetch(
                    "https://user:private-secret@example.test/parse?url={url}", SHARE_URL, "", "", ""));
            assertEquals("Third-party GET template must be a valid HTTP(S) URL", embedded.getMessage());
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void rejectsMissingShareLinksAndInvalidTextEncodingWithSafeMessages() throws Exception {
        assertEquals("Share text does not contain an HTTP(S) URL", failure(() ->
                ThirdPartyGetClient.extractShareUrl("copy text only")).getMessage());
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "text/plain; charset=not-a-charset")
                    .setBody("fixture"));
            server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[]{(byte) 0xc3, 0x28})));
            server.start();
            String template = server.url("/parse") + "?url={url}";
            assertTrue(failure(() -> ThirdPartyGetClient.fetch(template, SHARE_URL, "", "", ""))
                    .getMessage().contains("unsupported text charset"));
            assertTrue(failure(() -> ThirdPartyGetClient.fetch(template, SHARE_URL, "", "", ""))
                    .getMessage().contains("invalid text encoding"));
        }
    }

    @Test public void cancellingBeforeStartNeverContactsProvider() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            ThirdPartyGetClient.Request request = new ThirdPartyGetClient.Request();
            request.cancel();
            request.cancel();
            Exception failure = failure(() -> request.fetch(server.url("/parse") + "?url={url}",
                    SHARE_URL, "", "", ""));
            assertTrue(failure instanceof CancellationException);
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void cancellingDisconnectsAnActiveBlockedRequest() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            ThirdPartyGetClient.Request request = new ThirdPartyGetClient.Request();
            Future<String> response = worker.submit(() -> request.fetch(server.url("/parse") + "?url={url}",
                    SHARE_URL, "", "private-secret", ""));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.cancel();
            try {
                response.get(3, TimeUnit.SECONDS);
                fail("Cancelled request must not return a response");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof CancellationException);
                assertEquals("Third-party GET request cancelled", expected.getCause().getMessage());
            }
        } finally {
            worker.shutdownNow();
        }
    }

    private static String md5(String canonical) throws Exception {
        byte[] bytes = MessageDigest.getInstance("MD5").digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static ThirdPartyDownloadSettings settings(String template, Map<String, String> extras) {
        Map<String, String> values = new HashMap<>();
        values.put(ThirdPartyDownloadSettings.GET_TEMPLATE, template);
        values.put(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE);
        values.putAll(extras);
        return new ThirdPartyDownloadSettings(new LocalOptions(values));
    }

    private static Exception failure(ThrowingRunnable action) {
        try {
            action.run();
            fail("Expected request to fail");
            throw new AssertionError();
        } catch (Exception failure) {
            return failure;
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}

package com.douai.module.feature;

import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.diagnostics.DouAiLog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GET-only adapter for user-configured share-link parsers. */
public final class ThirdPartyGetClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Pattern SHARE_URL = Pattern.compile(
            "https?://[^\\s\\p{Z}<>\\\"'\\u3000]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern CHARSET = Pattern.compile(
            "(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);

    private ThirdPartyGetClient() { }

    public static String fetch(String template, String shareText, String appId,
                               String appSecret, String headersJson) throws Exception {
        return new Request().fetch(template, shareText, appId, appSecret, headersJson);
    }

    public static String fetch(ThirdPartyDownloadSettings config, String shareText) throws Exception {
        return new Request().fetch(config, shareText);
    }

    public static final class Request {
        private final DownloadTransfer transfer;
        private final DownloadTransfer.Connections connections;
        private boolean cancelled;
        private boolean started;
        private HttpURLConnection connection;

        public Request() { this(null, uri -> (HttpURLConnection) uri.toURL().openConnection()); }
        Request(DownloadTransfer transfer, DownloadTransfer.Connections connections) {
            this.transfer = transfer; this.connections = connections;
        }

        public String fetch(String template, String shareText, String appId,
                            String appSecret, String headersJson) throws Exception {
            return fetch(template, shareText, appId, appSecret, headersJson,
                    appSecret == null || appSecret.isEmpty() ? ThirdPartyDownloadSettings.SIGNATURE_NONE
                            : ThirdPartyDownloadSettings.SIGNATURE_MD5, "Sign", true);
        }

        public String fetch(ThirdPartyDownloadSettings config, String shareText) throws Exception {
            if (config == null) throw new IllegalArgumentException("Third-party GET configuration is missing");
            return fetch(config.getTemplate, shareText, "", config.appSecret, config.headers,
                    config.signatureMode, config.signatureHeader, false);
        }

        private String fetch(String template, String shareText, String appId,
                             String appSecret, String headersJson, String signatureMode,
                             String signatureHeader, boolean legacyPrefix) throws Exception {
            synchronized (this) {
                ensureActive();
                if (started) throw new IllegalStateException("Third-party GET request already started");
                started = true;
            }
            try {
                if (transfer != null) transfer.evidence("parser_request", "template_ref=" + DouAiLog.reference(template)
                        + "; signature=" + signatureMode + "; business_result=pending");
                String response = performRequest(template, shareText, appId, appSecret, headersJson,
                        signatureMode, signatureHeader, legacyPrefix, this);
                ensureActive();
                return response;
            } catch (Exception failure) {
                ensureActive();
                throw failure;
            }
        }

        public void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                cancelled = true;
                active = connection;
            }
            if (active != null) active.disconnect();
        }

        private synchronized void attach(HttpURLConnection opened) throws Exception {
            if (transfer != null) transfer.attach(opened);
            ensureActive();
            connection = opened;
        }

        private synchronized void release(HttpURLConnection opened) {
            if (connection == opened) connection = null;
            if (transfer != null) transfer.detach(opened);
        }

        private synchronized void ensureActive() throws Exception {
            if (transfer != null) transfer.check();
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Third-party GET request cancelled");
            }
        }
    }

    private static String performRequest(String template, String shareText, String appId,
                                         String appSecret, String headersJson, String signatureMode,
                                         String signatureHeader, boolean legacyPrefix, Request active) throws Exception {
        if (template == null || template.trim().isEmpty()) {
            throw new IllegalArgumentException("Third-party GET template is empty");
        }
        String request = template.trim();
        if (legacyPrefix && ThirdPartyDownloadSettings.isLegacyLinkPrefix(request)) request += "{url}";
        if (request.contains("{url}")) request = request.replace("{url}", phpEncode(extractShareUrl(shareText)));
        request = request.replace("{app_id}", phpEncode(appId == null ? "" : appId));
        URI endpoint = httpUri(request, "Third-party GET template must be a valid HTTP(S) URL");
        if (endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Third-party GET template cannot contain a fragment");
        }
        Map<String, String> headers = parseHeaders(headersJson);
        String signature = null;
        if (ThirdPartyDownloadSettings.SIGNATURE_MD5.equals(signatureMode)) {
            if (appSecret == null || appSecret.isEmpty()) {
                throw new IllegalArgumentException("MD5 signing requires an App Secret");
            }
            validateSignatureHeader(signatureHeader);
            signature = sign(endpoint, appSecret);
        } else if (!ThirdPartyDownloadSettings.SIGNATURE_NONE.equals(signatureMode)) {
            throw new IllegalArgumentException("Unsupported third-party GET signature mode");
        }

        HttpURLConnection connection = null;
        try {
            connection = active.connections.open(endpoint);
            active.attach(connection);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(active.transfer == null ? CONNECT_TIMEOUT_MS : active.transfer.timeout(CONNECT_TIMEOUT_MS));
            connection.setReadTimeout(active.transfer == null ? READ_TIMEOUT_MS : active.transfer.timeout(READ_TIMEOUT_MS));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (signature != null && signatureHeader.equalsIgnoreCase(header.getKey())) continue;
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (signature != null) connection.setRequestProperty(signatureHeader, signature);
            active.ensureActive();
            int status = connection.getResponseCode();
            if (active.transfer != null) active.transfer.evidence("parser_http_response", "status=" + status + "; result=transport_only");
            active.ensureActive();
            if (status >= 300 && status < 400) {
                throw new IllegalStateException("Third-party GET redirects are disabled; use the final API URL");
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Third-party GET returned HTTP " + status);
            }
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw tooLarge();
            Charset charset = responseCharset(connection.getContentType());
            byte[] bytes;
            try (InputStream stream = connection.getInputStream();
                 DownloadTransfer.Streams resources = active.transfer == null ? null : active.transfer.streams()) {
                if (resources != null) resources.add(stream);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    active.ensureActive();
                    if (count > MAX_RESPONSE_BYTES - output.size()) throw tooLarge();
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            try {
                String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (active.transfer != null) active.transfer.evidence("parser_text_decoded", "bytes=" + bytes.length
                        + "; chars=" + text.length() + "; plan_validated=false; saved=false");
                return text.startsWith("\uFEFF") ? text.substring(1) : text;
            } catch (CharacterCodingException ignored) {
                throw new IllegalStateException("Third-party GET response has invalid text encoding");
            }
        } catch (SocketTimeoutException ignored) {
            if (active.transfer != null) {
                active.transfer.transportTimedOut = true;
                active.transfer.evidence("parser_timeout", "origin=transport; response_incomplete=true");
            }
            active.ensureActive();
            throw new IOException("Third-party GET request timed out");
        } catch (IOException ignored) {
            active.ensureActive();
            // HTTP exceptions may contain the full URL, including user credentials.
            throw new IOException("Third-party GET request failed");
        } catch (IllegalArgumentException ignored) {
            active.ensureActive();
            throw new IllegalArgumentException("Third-party GET request contains invalid headers");
        } finally {
            active.release(connection);
            if (connection != null) connection.disconnect();
        }
    }

    public static String extractShareUrl(String shareText) {
        Matcher matcher = SHARE_URL.matcher(shareText == null ? "" : shareText);
        if (!matcher.find()) throw new IllegalArgumentException("Share text does not contain an HTTP(S) URL");
        String candidate = matcher.group();
        while (!candidate.isEmpty() && ".,;!)]}\u3002\uFF0C\uFF1B\uFF01\uFF09\u3011\u300B"
                .indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        httpUri(candidate, "Share text does not contain a valid HTTP(S) URL");
        return candidate;
    }

    private static URI httpUri(String value, String message) {
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException(message);
            }
            return uri;
        } catch (Exception ignored) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Map<String, String> parseHeaders(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return result;
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object value = object.get(name);
                String normalized = name.toLowerCase(Locale.ROOT);
                if (!HEADER_NAME.matcher(name).matches() || !(value instanceof String)
                        || hasInvalidHeaderCharacter((String) value)
                        || normalized.equals("host") || normalized.equals("content-length")
                        || normalized.equals("connection")) throw new IllegalArgumentException();
                for (String prior : result.keySet()) {
                    if (prior.equalsIgnoreCase(name)) throw new IllegalArgumentException();
                }
                result.put(name, (String) value);
            }
            return result;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Custom headers must be a JSON object with valid string headers");
        }
    }

    private static boolean hasInvalidHeaderCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 32 && character != '\t') || character == 127) return true;
        }
        return false;
    }

    private static void validateSignatureHeader(String name) {
        if (name == null || !HEADER_NAME.matcher(name).matches()
                || "host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name)
                || "connection".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Signature header must be a valid request header name");
        }
    }

    private static String sign(URI endpoint, String secret) throws Exception {
        Map<String, String> parameters = new TreeMap<>();
        String query = endpoint.getRawQuery();
        if (query != null && !query.isEmpty()) {
            for (String part : query.split("&", -1)) {
                if (part.isEmpty()) continue;
                int equals = part.indexOf('=');
                String key;
                String value;
                try {
                    key = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), "UTF-8");
                    value = URLDecoder.decode(equals < 0 ? "" : part.substring(equals + 1), "UTF-8");
                } catch (IllegalArgumentException ignored) {
                    throw new IllegalArgumentException("Third-party GET query has invalid parameter encoding");
                }
                if (parameters.containsKey(key)) {
                    throw new IllegalArgumentException("Signed GET query cannot contain duplicate parameter names");
                }
                parameters.put(key, value);
            }
        }
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (canonical.length() > 0) canonical.append('&');
            canonical.append(parameter.getKey()).append('=').append(phpEncode(parameter.getValue()));
        }
        canonical.append(secret);
        byte[] digest = MessageDigest.getInstance("MD5").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(32);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static String phpEncode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("*", "%2A");
    }

    private static Charset responseCharset(String contentType) {
        if (contentType == null) return StandardCharsets.UTF_8;
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException("Third-party GET response uses an unsupported text charset");
        }
    }

    private static IllegalStateException tooLarge() {
        return new IllegalStateException("Third-party GET response exceeds 2 MiB");
    }
}

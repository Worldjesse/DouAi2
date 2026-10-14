package com.douai.module.feature;

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, cancellable GET transport for speech and a separately configured voice list. */
public final class SpeechHttpClient {
    public static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern CHARSET = Pattern.compile(
            "(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);

    private SpeechHttpClient() { }

    public static String resolveTemplate(String template, String text, String voice) {
        ThirdPartyRequestTemplate.parse(template, "{text}", "{voice}");
        String resolved = template.trim().replace("{text}", Uri.encode(text == null ? "" : text))
                .replace("{voice}", Uri.encode(voice == null ? "" : voice));
        endpoint(resolved);
        return resolved;
    }

    public static final class Resource {
        public final byte[] bytes;
        public final String contentType;

        private Resource(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType == null ? "" : contentType;
        }

        public String text() {
            if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("第三方语音文本响应超过 2 MiB");
            Charset charset = StandardCharsets.UTF_8;
            Matcher declared = CHARSET.matcher(contentType);
            if (declared.find()) {
                try { charset = Charset.forName(declared.group(1) == null ? declared.group(2) : declared.group(1)); }
                catch (IllegalArgumentException ignored) {
                    throw new IllegalArgumentException("第三方语音响应使用了不支持的字符编码");
                }
            }
            try {
                String value = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                return value.startsWith("\uFEFF") ? value.substring(1) : value;
            } catch (CharacterCodingException ignored) {
                throw new IllegalArgumentException("第三方语音响应不是有效的 JSON/TXT");
            }
        }

        public boolean isAudio() {
            if (starts("RIFF") && bytes.length >= 12 && ascii(8, "WAVE")) return true;
            if (starts("ID3") || starts("OggS") || starts("fLaC")
                    || (bytes.length >= 8 && ascii(4, "ftyp"))
                    || (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 224) == 224)) return true;
            String type = contentType.toLowerCase(Locale.ROOT);
            if (!type.startsWith("audio/") && !type.startsWith("application/octet-stream")) return false;
            int index = 0;
            while (index < bytes.length && (bytes[index] & 255) <= 32) index++;
            if (index == bytes.length) return false;
            byte first = bytes[index];
            return first != '{' && first != '[' && first != '"' && first != '<'
                    && !ascii(index, "http://") && !ascii(index, "https://");
        }

        public String audioExtension() {
            if (starts("RIFF") && bytes.length >= 12 && ascii(8, "WAVE")) return ".wav";
            if (starts("OggS")) return ".ogg";
            if (starts("fLaC")) return ".flac";
            if (bytes.length >= 8 && ascii(4, "ftyp")) return ".m4a";
            if (starts("ID3")) return ".mp3";
            if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 224) == 224) {
                return (bytes[1] & 246) == 240 ? ".aac" : ".mp3";
            }
            String mime = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            if (mime.equals("audio/wav") || mime.equals("audio/x-wav") || mime.equals("audio/wave")) return ".wav";
            if (mime.equals("audio/mpeg") || mime.equals("audio/mp3")) return ".mp3";
            if (mime.equals("audio/mp4") || mime.equals("audio/x-m4a")) return ".m4a";
            if (mime.equals("audio/aac")) return ".aac";
            if (mime.equals("audio/ogg")) return ".ogg";
            if (mime.equals("audio/flac")) return ".flac";
            return ".audio";
        }

        private boolean starts(String value) { return ascii(0, value); }
        private boolean ascii(int offset, String value) {
            if (offset + value.length() > bytes.length) return false;
            for (int i = 0; i < value.length(); i++) if (bytes[offset + i] != value.charAt(i)) return false;
            return true;
        }
    }

    public static final class Request implements AutoCloseable {
        private boolean cancelled;
        private boolean inProgress;
        private HttpURLConnection connection;

        public Resource fetchResource(String url, String headers) throws IOException {
            return fetch(url, headers, MAX_AUDIO_BYTES);
        }

        public String fetchText(String url, String headers) throws IOException {
            String text = fetch(url, headers, MAX_TEXT_BYTES).text();
            ensureActive();
            return text;
        }

        public List<VoiceCatalog.Entry> fetchVoices(SpeechSettings settings) throws IOException {
            String url = resolveTemplate(settings.voiceGetTemplate, "", settings.selectedVoice);
            String body = fetchText(url, settings.voiceHeaders);
            ensureActive();
            List<VoiceCatalog.Entry> entries = VoiceCatalog.parse(body, settings);
            ensureActive();
            return entries;
        }

        private Resource fetch(String url, String headersJson, int limit) throws IOException {
            URI endpoint = endpoint(url);
            Map<String, String> headers = headers(headersJson);
            synchronized (this) {
                ensureActive();
                if (inProgress) throw new IllegalStateException("第三方语音请求正在进行");
                inProgress = true;
            }
            HttpURLConnection opened = null;
            long deadline = System.nanoTime() + 45_000_000_000L;
            try {
                opened = (HttpURLConnection) endpoint.toURL().openConnection();
                synchronized (this) { ensureActive(); connection = opened; }
                opened.setRequestMethod("GET");
                opened.setInstanceFollowRedirects(false);
                opened.setConnectTimeout(15_000);
                opened.setReadTimeout(30_000);
                opened.setRequestProperty("Accept", "audio/*, application/json, text/plain, */*");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    opened.setRequestProperty(header.getKey(), header.getValue());
                }
                int status = opened.getResponseCode();
                ensureActive();
                if (System.nanoTime() > deadline) throw new SocketTimeoutException();
                if (status >= 300 && status < 400) {
                    throw new IllegalArgumentException("第三方语音 GET 不跟随重定向，请填写最终地址");
                }
                if (status < 200 || status >= 300) throw new IOException("第三方语音 GET 返回 HTTP " + status);
                if (opened.getContentLengthLong() > limit) throw tooLarge(limit);
                byte[] bytes;
                try (InputStream input = opened.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        ensureActive();
                        if (System.nanoTime() > deadline) throw new SocketTimeoutException();
                        if (count > limit - output.size()) throw tooLarge(limit);
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                }
                ensureActive();
                if (System.nanoTime() > deadline) throw new SocketTimeoutException();
                if (bytes.length == 0) throw new IllegalArgumentException("第三方语音响应为空");
                return new Resource(bytes, opened.getContentType());
            } catch (SocketTimeoutException ignored) {
                ensureActive();
                throw new IOException("第三方语音 GET 请求超时");
            } catch (IOException failure) {
                ensureActive();
                // Never retain URL-bearing transport exceptions or provider response bodies.
                String message = failure.getMessage();
                if (message != null && message.matches("第三方语音 GET 返回 HTTP [0-9]{3}")) {
                    throw new IOException(message);
                }
                throw new IOException("第三方语音 GET 请求失败");
            } catch (IllegalArgumentException failure) {
                ensureActive();
                if (failure.getMessage() != null && failure.getMessage().startsWith("第三方语音")) throw failure;
                throw new IllegalArgumentException("第三方语音 GET 请求无效");
            } finally {
                synchronized (this) { connection = null; inProgress = false; }
                if (opened != null) opened.disconnect();
            }
        }

        public void cancel() {
            HttpURLConnection active;
            synchronized (this) { cancelled = true; active = connection; }
            if (active != null) active.disconnect();
        }

        public synchronized void ensureActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("第三方语音请求已停止");
            }
        }

        @Override public void close() { cancel(); }
    }

    public static String audioUrl(String body, String field) {
        if (body == null || body.length() > MAX_TEXT_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("第三方语音响应为空或超过 2 MiB");
        }
        // Direct TXT/JSON string links have no named field; a JSON field selection does not apply.
        String direct = body.trim();
        if (direct.startsWith("\uFEFF")) direct = direct.substring(1).trim();
        if (direct.startsWith("\"")) {
            // Android's strict JsonReader requires an array/object root, so wrap the scalar.
            try (JsonReader reader = new JsonReader(new StringReader("[" + direct + "]"))) {
                reader.setLenient(false);
                reader.beginArray();
                if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException();
                String link = reader.nextString();
                reader.endArray();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
                endpoint(link);
                return link;
            } catch (Exception ignored) {
                throw new IllegalArgumentException("第三方语音响应未包含有效音频链接");
            }
        } else {
            try { endpoint(direct); return direct; } catch (IllegalArgumentException ignored) { }
        }
        ThirdPartyResponse parsed = ThirdPartyResponse.parse(body);
        Map<String, List<String>> fields = parsed.downloadFields();
        if (field != null && !field.trim().isEmpty()) {
            List<String> selected = fields.get(field.trim());
            // A URL-only JSON array is flattened as one field; still allow an explicit item index.
            if (selected == null && field.trim().endsWith("]")) {
                String path = field.trim();
                int start = path.lastIndexOf('[');
                if (start > 0) {
                    List<String> array = fields.get(path.substring(0, start));
                    try {
                        int index = Integer.parseInt(path.substring(start + 1, path.length() - 1));
                        if (array != null && index >= 0 && index < array.size()) {
                            selected = java.util.Collections.singletonList(array.get(index));
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (selected == null || selected.size() != 1) {
                throw new IllegalArgumentException("所选语音字段应包含一个音频链接");
            }
            endpoint(selected.get(0));
            return selected.get(0);
        }
        String[] preferred = {"audio", "audio_url", "voice", "voice_url", "file", "file_url", "url", "download_url"};
        for (String name : preferred) {
            String match = null;
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                String key = entry.getKey();
                if (!(key.equalsIgnoreCase(name) || key.toLowerCase(Locale.ROOT).endsWith("." + name))) continue;
                if (entry.getValue().size() != 1) continue;
                String candidate = entry.getValue().get(0);
                if (match != null && !match.equals(candidate)) {
                    throw new IllegalArgumentException("返回了多个语音链接，请选择音频字段");
                }
                match = candidate;
            }
            if (match != null) { endpoint(match); return match; }
        }
        String match = null;
        for (List<String> links : fields.values()) for (String link : links) {
            if (match != null && !match.equals(link)) {
                throw new IllegalArgumentException("返回了多个链接，请选择音频字段");
            }
            match = link;
        }
        if (match == null) throw new IllegalArgumentException("第三方语音响应未包含音频链接");
        endpoint(match);
        return match;
    }

    public static boolean sameOrigin(String first, String second) {
        URI a = endpoint(first), b = endpoint(second);
        int aPort = a.getPort() == -1 ? ("https".equalsIgnoreCase(a.getScheme()) ? 443 : 80) : a.getPort();
        int bPort = b.getPort() == -1 ? ("https".equalsIgnoreCase(b.getScheme()) ? 443 : 80) : b.getPort();
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && aPort == bPort;
    }

    private static URI endpoint(String value) {
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("第三方语音 GET 地址必须是有效的 HTTP(S) 地址");
        }
    }

    private static Map<String, String> headers(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return values;
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object value = object.get(name);
                if (!HEADER_NAME.matcher(name).matches() || !(value instanceof String)
                        || "host".equalsIgnoreCase(name) || "connection".equalsIgnoreCase(name)
                        || "content-length".equalsIgnoreCase(name)) throw new IllegalArgumentException();
                String text = (String) value;
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    if ((ch < 32 && ch != '\t') || ch == 127) throw new IllegalArgumentException();
                }
                for (String existing : values.keySet()) if (existing.equalsIgnoreCase(name)) throw new IllegalArgumentException();
                values.put(name, text);
            }
            return values;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("第三方语音请求头应为有效的 JSON 字符串键值对象");
        }
    }

    private static IllegalArgumentException tooLarge(int limit) {
        return new IllegalArgumentException(limit == MAX_TEXT_BYTES ? "第三方语音响应超过 2 MiB" : "第三方语音音频超过 20 MiB");
    }
}

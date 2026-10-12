package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Provider-aware text client for module-local model profiles. */
public final class ChatCompletionClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    public static final int MAX_IMAGES_PER_MESSAGE = 4;
    private static final int MAX_HISTORY_IMAGE_BYTES = 12 * 1024 * 1024;
    private final Map<String, ArrayDeque<HistoryTurn>> history = new LinkedHashMap<>();
    private final ArrayDeque<HistoryTurn> historyOrder = new ArrayDeque<>();
    private long historyImageBytes;
    private final Object requestLock = new Object();
    private ActiveRequest activeRequest;
    private long historyEpoch;
    private final TestChatMemory memory;
    private final Consumer<String> diagnostics;

    public ChatCompletionClient() { this(null, null); }

    public ChatCompletionClient(TestChatMemory memory, Consumer<String> diagnostics) {
        this.memory = memory;
        this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
    }

    public List<TestChatMemory.Turn> savedTurns() {
        return memory == null ? Collections.emptyList() : memory.read();
    }

    public synchronized String reply(String conversation, String prompt, AiModelSettings settings) throws Exception {
        return reply(conversation, prompt, Collections.emptyList(), settings);
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings) throws Exception {
        return reply(conversation, prompt, images, settings, "");
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings, String secondaryPrompt) throws Exception {
        return reply(conversation, prompt, images, settings, secondaryPrompt, true);
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings, String secondaryPrompt, boolean remember) throws Exception {
        return reply(conversation, prompt, images, settings, secondaryPrompt, remember, "");
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings, String secondaryPrompt, boolean remember,
                                     String document) throws Exception {
        AiModelConfig config = settings.selected();
        String secondary = secondaryPrompt == null ? "" : secondaryPrompt;
        List<ChatImage> attachments = images == null ? Collections.emptyList() : new ArrayList<>(images);
        if (attachments.size() > MAX_IMAGES_PER_MESSAGE) throw new IllegalArgumentException("每条消息最多 4 张图片");
        for (ChatImage image : attachments) {
            if (image == null) throw new IllegalArgumentException("图片内容为空");
        }
        return send(config, conversation == null ? "" : conversation, prompt == null ? "" : prompt,
                Collections.unmodifiableList(attachments), secondary, remember, document == null ? "" : document);
    }

    /** Automatic replies enter model context only after the host acknowledges sending. */
    public void rememberReply(String conversation, String prompt, List<ChatImage> images,
                              AiModelSettings settings, String secondaryPrompt, String answer) throws Exception {
        AiModelConfig config = settings.selected();
        if (config.contextRounds == 0) return;
        synchronized (requestLock) {
            rememberTurn(new HistoryTurn(historyKey(conversation, config), prompt,
                    Collections.unmodifiableList(new ArrayList<>(images)), answer), config.contextRounds);
        }
    }

    /** Cancels without waiting for the serialized reply operation. */
    public void cancelCurrentRequest() {
        HttpURLConnection connection;
        synchronized (requestLock) {
            if (activeRequest == null) return;
            activeRequest.cancelled = true;
            connection = activeRequest.connection;
        }
        if (connection != null) connection.disconnect();
    }

    /** Pending replies cannot restore context after it has been cleared. */
    public void clearHistory() {
        synchronized (requestLock) {
            historyEpoch++;
            history.clear();
            historyOrder.clear();
            historyImageBytes = 0;
            if (memory != null) memory.clear();
        }
    }

    public static List<String> fetchModels(AiModelConfig config) throws Exception {
        return new ModelCatalogRequest().fetch(config);
    }

    /** One catalog operation; cancellation also releases a blocked HTTP connection. */
    public static final class ModelCatalogRequest {
        private boolean cancelled;
        private boolean started;
        private HttpURLConnection connection;

        public List<String> fetch(AiModelConfig config) throws Exception {
            synchronized (this) {
                ensureActive();
                if (started) throw new IllegalStateException("模型列表请求已启动");
                started = true;
            }
            config.validate(false);
            String url = modelsEndpoint(config);
            HttpURLConnection opened = (HttpURLConnection) new URL(url).openConnection();
            try {
                synchronized (this) {
                    ensureActive();
                    connection = opened;
                }
                configure(opened, config);
                opened.setRequestMethod("GET");
                setAuthentication(opened, config, url);
                ensureActive();
                int status = opened.getResponseCode();
                String body = readResponse(opened, status);
                ensureActive();
                if (status < 200 || status >= 300) throw httpFailure(status);
                List<String> models = parseModelIds(config.protocol, body);
                ensureActive();
                return models;
            } catch (Exception failure) {
                ensureActive();
                throw failure;
            } finally {
                synchronized (this) {
                    if (connection == opened) connection = null;
                }
                opened.disconnect();
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

        private synchronized void ensureActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw cancelled();
        }
    }

    public static String requestEndpoint(AiModelConfig config, String model) {
        String base = config.apiEndpoint.replaceAll("/+$", "");
        switch (config.protocol) {
            case AiModelConfig.CHAT: return appendSuffix(base, "chat/completions");
            case AiModelConfig.RESPONSES: return appendSuffix(base, "responses");
            case AiModelConfig.ANTHROPIC: return appendSuffix(base, "messages");
            case AiModelConfig.GEMINI:
                return appendSuffix(base, "models/" + encodePath(model) + ":generateContent");
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
    }

    public static String modelsEndpoint(AiModelConfig config) {
        if (!config.modelsEndpoint.isEmpty()) return config.modelsEndpoint;
        String base = stripOperation(config.apiEndpoint, config.protocol);
        switch (config.protocol) {
            case AiModelConfig.ANTHROPIC:
            case AiModelConfig.GEMINI:
                return appendSuffix(base, "models");
            case AiModelConfig.CHAT:
            case AiModelConfig.RESPONSES:
                return appendSuffix(base, "models");
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
    }

    private synchronized String send(AiModelConfig config, String conversation, String prompt,
                                     List<ChatImage> images, String secondaryPrompt, boolean remember,
                                     String document) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
        config.validate(true);
        if (prompt.trim().isEmpty() && images.isEmpty() && document.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        if (prompt.length() > 4000) throw new IllegalArgumentException("消息最多 4000 个字符");
        if (document.length() > 32768) throw new IllegalArgumentException("附件文本最多 32768 个字符");
        String url = requestEndpoint(config, config.modelName);
        List<HistoryTurn> prior = new ArrayList<>();
        String historyKey = historyKey(conversation, config);
        int rounds = config.contextRounds;
        ActiveRequest request = new ActiveRequest();
        long epoch;
        long savedEpoch = memory == null ? 0 : memory.epoch();
        synchronized (requestLock) {
            epoch = historyEpoch;
            if (memory != null) {
                for (TestChatMemory.Turn turn : memory.read()) {
                    prior.add(new HistoryTurn(historyKey, turn.prompt, turn.document, turn.images, turn.answer));
                }
            } else {
                ArrayDeque<HistoryTurn> previous = history.get(historyKey);
                if (previous != null) {
                    while (previous.size() > rounds) discardTurn(previous.peekFirst());
                    prior.addAll(previous);
                }
            }
            activeRequest = request;
        }
        HttpURLConnection connection = null;
        try {
            ensureActive(request);
            diagnostic(config, secondaryPrompt, prior.size(), "prepare", 0);
            JSONObject body = requestBody(config, prior, withDocument(prompt, document), images, secondaryPrompt);
            connection = (HttpURLConnection) new URL(url).openConnection();
            synchronized (requestLock) {
                request.connection = connection;
                ensureActive(request);
            }
            configure(connection, config);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            setAuthentication(connection, config, url);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            ensureActive(request);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            diagnostic(config, secondaryPrompt, prior.size(), "sent", 0);
            ensureActive(request);
            int status = connection.getResponseCode();
            diagnostic(config, secondaryPrompt, prior.size(), "response", status);
            String response = readResponse(connection, status);
            ensureActive(request);
            if (status < 200 || status >= 300) throw httpFailure(status);
            String result = parseReply(config.protocol, response);
            if (result.isEmpty() || result.length() > 4000) throw new IllegalStateException("AI 回复为空或过长");
            synchronized (requestLock) {
                ensureActive(request);
                if (remember && rounds > 0 && epoch == historyEpoch) {
                    if (memory != null) memory.append(savedEpoch, new TestChatMemory.Turn(prompt, document, images, result));
                    else rememberTurn(new HistoryTurn(historyKey, prompt, document, images, result), rounds);
                }
                activeRequest = null;
            }
            return result;
        } catch (Exception error) {
            diagnostic(config, secondaryPrompt, prior.size(), "failed", 0);
            ensureActive(request);
            throw error;
        } finally {
            synchronized (requestLock) {
                if (activeRequest == request) activeRequest = null;
            }
            if (connection != null) connection.disconnect();
        }
    }

    private void diagnostic(AiModelConfig config, String secondary, int rounds, String stage, int status) {
        try { diagnostics.accept("protocol=" + config.protocol + " primaryChars=" + config.systemPrompt.length()
                + " secondaryChars=" + secondary.length() + " historyRounds=" + rounds
                + " stage=" + stage + " status=" + status); }
        catch (RuntimeException ignored) { }
    }

    private static String withDocument(String prompt, String document) {
        return document.isEmpty() ? prompt : prompt + "\n\n[附件文本]\n" + document;
    }

    private static String historyKey(String conversation, AiModelConfig config) throws Exception {
        String memory = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(config.systemPrompt.getBytes(StandardCharsets.UTF_8)));
        return (conversation == null ? "" : conversation) + "|" + config.id + "|" + config.provider + "|"
                + config.protocol + "|" + config.apiEndpoint + "|" + config.modelName + "|"
                + memory;
    }

    private static void ensureActive(ActiveRequest request) {
        if (request.cancelled || Thread.currentThread().isInterrupted()) throw cancelled();
    }

    private static CancellationException cancelled() { return new CancellationException("请求已取消"); }

    private static final class ActiveRequest {
        volatile boolean cancelled;
        HttpURLConnection connection;
    }

    private void rememberTurn(HistoryTurn turn, int rounds) {
        ArrayDeque<HistoryTurn> previous = history.computeIfAbsent(turn.historyKey, ignored -> new ArrayDeque<>());
        previous.addLast(turn);
        historyOrder.addLast(turn);
        historyImageBytes += turn.imageBytes;
        while (previous.size() > rounds) discardTurn(previous.peekFirst());
        while (history.size() > 100) {
            ArrayDeque<HistoryTurn> oldest = history.values().iterator().next();
            while (!oldest.isEmpty()) discardTurn(oldest.peekFirst());
        }
        // Evict image-bearing turns together with their answers, preserving text-only context.
        while (historyImageBytes > MAX_HISTORY_IMAGE_BYTES) {
            HistoryTurn oldestImage = null;
            for (HistoryTurn candidate : historyOrder) {
                if (candidate.imageBytes > 0) { oldestImage = candidate; break; }
            }
            discardTurn(oldestImage);
        }
    }

    private void discardTurn(HistoryTurn turn) {
        ArrayDeque<HistoryTurn> previous = history.get(turn.historyKey);
        if (previous != null) {
            previous.remove(turn);
            if (previous.isEmpty()) history.remove(turn.historyKey);
        }
        historyOrder.remove(turn);
        historyImageBytes -= turn.imageBytes;
    }

    private static final class HistoryTurn {
        final String historyKey;
        final String prompt;
        final List<ChatImage> images;
        final String answer;
        final String document;
        final int imageBytes;

        HistoryTurn(String historyKey, String prompt, List<ChatImage> images, String answer) {
            this(historyKey, prompt, "", images, answer);
        }

        HistoryTurn(String historyKey, String prompt, String document, List<ChatImage> images, String answer) {
            this.historyKey = historyKey;
            this.prompt = prompt;
            this.images = images;
            this.answer = answer;
            this.document = document;
            int bytes = 0;
            for (ChatImage image : images) bytes += image.sizeBytes();
            this.imageBytes = bytes;
        }
    }

    private static JSONObject requestBody(AiModelConfig config, List<HistoryTurn> prior, String prompt,
                                          List<ChatImage> images, String secondaryPrompt) throws Exception {
        String systemPrompt = config.systemPrompt;
        String currentPrompt = taskPrompt(prompt, secondaryPrompt);
        JSONObject body = new JSONObject().put("model", config.modelName);
        JSONObject parameters = config.parameters();
        java.util.Iterator<String> parameterKeys = parameters.keys();
        while (parameterKeys.hasNext()) {
            String key = parameterKeys.next();
            body.put(key, parameters.get(key));
        }
        switch (config.protocol) {
            case AiModelConfig.CHAT: {
                JSONArray messages = new JSONArray();
                if (!systemPrompt.isEmpty()) messages.put(new JSONObject()
                        .put("role", "system").put("content", systemPrompt));
                appendHistory(messages, prior, config.protocol);
                messages.put(userMessage(config.protocol, currentPrompt, images));
                body.put("messages", messages).put("stream", false);
                if (config.temperatureEnabled) body.put("temperature", config.temperature);
                if (config.topPEnabled) body.put("top_p", config.topP);
                if (config.maxTokensEnabled) body.put("max_tokens", config.maxTokens);
                break;
            }
            case AiModelConfig.RESPONSES: {
                JSONArray input = new JSONArray();
                appendHistory(input, prior, config.protocol);
                input.put(userMessage(config.protocol, currentPrompt, images));
                body.put("input", input).put("stream", false).put("store", false);
                if (!systemPrompt.isEmpty()) body.put("instructions", systemPrompt);
                if (config.temperatureEnabled) body.put("temperature", config.temperature);
                if (config.topPEnabled) body.put("top_p", config.topP);
                if (config.maxTokensEnabled) body.put("max_output_tokens", config.maxTokens);
                break;
            }
            case AiModelConfig.ANTHROPIC: {
                JSONArray messages = new JSONArray();
                appendHistory(messages, prior, config.protocol);
                messages.put(userMessage(config.protocol, currentPrompt, images));
                body.put("messages", messages).put("max_tokens",
                        config.maxTokensEnabled ? config.maxTokens : 1024).put("stream", false);
                if (!systemPrompt.isEmpty()) body.put("system", systemPrompt);
                if (config.temperatureEnabled) body.put("temperature", config.temperature);
                if (config.topPEnabled) body.put("top_p", config.topP);
                break;
            }
            case AiModelConfig.GEMINI: {
                JSONArray contents = new JSONArray();
                appendHistory(contents, prior, config.protocol);
                contents.put(userMessage(config.protocol, currentPrompt, images));
                body.remove("model");
                body.put("contents", contents);
                if (!systemPrompt.isEmpty()) body.put("systemInstruction",
                        new JSONObject().put("parts", new JSONArray()
                                .put(new JSONObject().put("text", systemPrompt))));
                JSONObject generation = new JSONObject();
                if (config.temperatureEnabled) generation.put("temperature", config.temperature);
                if (config.topPEnabled) generation.put("topP", config.topP);
                if (config.maxTokensEnabled) generation.put("maxOutputTokens", config.maxTokens);
                if (generation.length() > 0) body.put("generationConfig", generation);
                break;
            }
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
        return body;
    }

    private static String taskPrompt(String prompt, String secondary) {
        if (secondary.trim().isEmpty()) return prompt;
        return "【本次任务要求】\n" + secondary + "\n\n【本次输入】\n" + prompt;
    }

    private static void appendHistory(JSONArray target, List<HistoryTurn> history, String protocol) throws Exception {
        for (HistoryTurn turn : history) {
            target.put(userMessage(protocol, withDocument(turn.prompt, turn.document), turn.images));
            JSONObject answer = new JSONObject();
            if (AiModelConfig.GEMINI.equals(protocol)) {
                answer.put("role", "model").put("parts", new JSONArray()
                        .put(new JSONObject().put("text", turn.answer)));
            } else answer.put("role", "assistant").put("content", turn.answer);
            target.put(answer);
        }
    }

    private static JSONObject userMessage(String protocol, String prompt, List<ChatImage> images) throws Exception {
        JSONObject message = new JSONObject().put("role", "user");
        boolean gemini = AiModelConfig.GEMINI.equals(protocol);
        if (!gemini && images.isEmpty()) return message.put("content", prompt);
        JSONArray parts = new JSONArray();
        if (!prompt.trim().isEmpty() || images.isEmpty()) {
            JSONObject text = new JSONObject().put("text", prompt);
            if (!gemini) text.put("type", AiModelConfig.RESPONSES.equals(protocol) ? "input_text" : "text");
            parts.put(text);
        }
        for (ChatImage image : images) {
            switch (protocol) {
                case AiModelConfig.CHAT:
                    parts.put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", image.dataUrl())));
                    break;
                case AiModelConfig.RESPONSES:
                    parts.put(new JSONObject().put("type", "input_image").put("image_url", image.dataUrl()));
                    break;
                case AiModelConfig.ANTHROPIC:
                    parts.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                            .put("type", "base64").put("media_type", image.mimeType).put("data", image.base64Data)));
                    break;
                case AiModelConfig.GEMINI:
                    parts.put(new JSONObject().put("inline_data", new JSONObject()
                            .put("mime_type", image.mimeType).put("data", image.base64Data)));
                    break;
                default: throw new IllegalArgumentException("不支持的接口协议");
            }
        }
        return message.put(gemini ? "parts" : "content", parts);
    }

    private static String parseReply(String protocol, String json) throws Exception {
        JSONObject response = new JSONObject(json);
        switch (protocol) {
            case AiModelConfig.CHAT:
                return response.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim();
            case AiModelConfig.RESPONSES:
                String direct = response.optString("output_text", "").trim();
                if (!direct.isEmpty()) return direct;
                StringBuilder output = new StringBuilder();
                JSONArray items = response.optJSONArray("output");
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONArray blocks = items.optJSONObject(i) == null ? null : items.optJSONObject(i).optJSONArray("content");
                    if (blocks == null) continue;
                    for (int j = 0; j < blocks.length(); j++) {
                        JSONObject block = blocks.optJSONObject(j);
                        if (block != null && ("output_text".equals(block.optString("type"))
                                || "text".equals(block.optString("type")))) output.append(block.optString("text"));
                    }
                }
                return output.toString().trim();
            case AiModelConfig.ANTHROPIC:
                JSONArray content = response.getJSONArray("content");
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("type"))) text.append(block.optString("text"));
                }
                return text.toString().trim();
            case AiModelConfig.GEMINI:
                return response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text").trim();
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
    }

    private static List<String> parseModelIds(String protocol, String json) throws Exception {
        JSONObject response = new JSONObject(json);
        JSONArray items = AiModelConfig.GEMINI.equals(protocol)
                ? response.optJSONArray("models") : response.optJSONArray("data");
        if (items == null) items = response.optJSONArray("models");
        if (items == null) throw new IllegalStateException("模型列表格式无法识别");
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (AiModelConfig.GEMINI.equals(protocol)) {
                JSONArray supported = item.optJSONArray("supportedGenerationMethods");
                if (supported != null && !contains(supported, "generateContent")) continue;
            }
            String id = AiModelConfig.GEMINI.equals(protocol)
                    ? item.optString("name", "") : item.optString("id", item.optString("name", ""));
            if (id.startsWith("models/")) id = id.substring("models/".length());
            if (!id.isEmpty() && id.length() <= 256) sorted.add(id);
        }
        return Collections.unmodifiableList(new ArrayList<>(sorted));
    }

    private static boolean contains(JSONArray values, String expected) {
        for (int i = 0; i < values.length(); i++) if (expected.equals(values.optString(i))) return true;
        return false;
    }

    private static void configure(HttpURLConnection connection, AiModelConfig config) {
        int timeout = config.timeoutSeconds * 1000;
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
    }

    private static void setAuthentication(HttpURLConnection connection, AiModelConfig config,
                                          String requestUrl) throws Exception {
        for (Map.Entry<String, String> header : config.headers().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (config.apiKey.isEmpty()) return;
        switch (config.protocol) {
            case AiModelConfig.ANTHROPIC:
                connection.setRequestProperty("x-api-key", config.apiKey);
                connection.setRequestProperty("anthropic-version", "2023-06-01");
                break;
            case AiModelConfig.GEMINI:
                connection.setRequestProperty("x-goog-api-key", config.apiKey);
                break;
            default:
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
        }
    }

    private static String readResponse(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        try (InputStream input = stream) { return readBounded(input, MAX_RESPONSE_BYTES); }
    }

    private static IllegalStateException httpFailure(int status) {
        return new IllegalStateException("AI 服务 HTTP " + status + "；请检查地址、协议、密钥和模型权限");
    }

    public static String readBounded(InputStream input, int maximumBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maximumBytes) throw new IllegalStateException("响应内容过大");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String appendSuffix(String base, String suffix) {
        String clean = base.replaceAll("/+$", "");
        return clean.endsWith("/" + suffix) || clean.equals(suffix) ? clean : clean + "/" + suffix;
    }

    private static String stripOperation(String endpoint, String protocol) {
        String base = endpoint.replaceAll("/+$", "");
        String[] endings;
        switch (protocol) {
            case AiModelConfig.CHAT: endings = new String[]{"/chat/completions"}; break;
            case AiModelConfig.RESPONSES: endings = new String[]{"/responses"}; break;
            case AiModelConfig.ANTHROPIC: endings = new String[]{"/messages"}; break;
            case AiModelConfig.GEMINI: endings = new String[]{"/generateContent", "/models"}; break;
            default: endings = new String[0];
        }
        for (String ending : endings) {
            if (base.endsWith(ending)) return base.substring(0, base.length() - ending.length());
        }
        return base;
    }

    private static String encodePath(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}

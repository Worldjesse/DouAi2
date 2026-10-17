package com.douai.module.feature;

import android.content.Context;
import com.douai.module.config.AiModelConfig;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded private test-chat history. Epochs prevent an older request from undoing Clear. */
public final class TestChatMemory {
    public static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_DIRECTORY_BYTES = 48 * 1024 * 1024;
    private static final ConcurrentHashMap<String, State> STATES = new ConcurrentHashMap<>();
    private static final Object IO_LOCK = new Object();
    private final File file;
    private final State state;
    private final int rounds;

    public TestChatMemory(Context context, AiModelConfig config) {
        this(new File(context.getFilesDir(), "douai_test_chat"), config);
    }

    /** Message-page conversations have their own history; test requests never enter it. */
    public static TestChatMemory messageConversation(Context context, AiModelConfig config) {
        return new TestChatMemory(new File(context.getFilesDir(), "douai_message_chat"), config);
    }

    TestChatMemory(File directory, AiModelConfig config) {
        rounds = Math.max(0, Math.min(50, config.contextRounds));
        file = new File(directory, key(config) + ".bin");
        state = STATES.computeIfAbsent(file.getAbsolutePath(), ignored -> new State());
        if (rounds == 0) clear();
    }

    public long epoch() { synchronized (IO_LOCK) { return state.epoch; } }

    public List<Turn> read() {
        synchronized (IO_LOCK) {
            if (rounds == 0) return Collections.emptyList();
            try {
                if (file.length() > MAX_FILE_BYTES) throw new IOException("History size");
                try (DataInputStream input = new DataInputStream(new java.io.FileInputStream(file))) {
                    if (input.readInt() != 1) throw new IOException("History version");
                    int count = input.readInt();
                    if (count < 0 || count > 50) throw new IOException("History count");
                    List<Turn> result = new ArrayList<>();
                    int bytes = 0;
                    for (int index = 0; index < count; index++) {
                        String prompt = text(input, 16000);
                        String document = text(input, 131072);
                        String answer = text(input, 16000);
                        int images = input.readInt();
                        if (images < 0 || images > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) throw new IOException("Images");
                        List<ChatImage> attachments = new ArrayList<>();
                        for (int image = 0; image < images; image++) {
                            String mime = text(input, 100);
                            int size = input.readInt();
                            if (size <= 0 || size > ChatImage.MAX_BYTES || bytes + size > MAX_IMAGE_BYTES) throw new IOException("Image size");
                            byte[] data = new byte[size];
                            input.readFully(data);
                            bytes += size;
                            attachments.add(new ChatImage(mime, data));
                        }
                        result.add(new Turn(prompt, document, attachments, answer));
                    }
                    return bounded(result);
                }
            } catch (java.io.FileNotFoundException ignored) { return Collections.emptyList(); }
            catch (IOException | RuntimeException failure) {
                file.delete(); return Collections.emptyList();
            }
        }
    }

    public boolean append(long expectedEpoch, Turn turn) {
        synchronized (IO_LOCK) {
            if (rounds == 0 || expectedEpoch != state.epoch) return false;
            List<Turn> turns = new ArrayList<>(read());
            turns.add(turn);
            turns = bounded(turns);
            File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) return false;
            File temporary = null;
            try {
                temporary = File.createTempFile("memory-", ".tmp", directory);
                try (FileOutputStream stream = new FileOutputStream(temporary);
                     DataOutputStream output = new DataOutputStream(stream)) {
                    output.writeInt(1);
                    output.writeInt(turns.size());
                    for (Turn saved : turns) {
                        text(output, saved.prompt);
                        text(output, saved.document);
                        text(output, saved.answer);
                        output.writeInt(saved.images.size());
                        for (ChatImage image : saved.images) {
                            text(output, image.mimeType);
                            byte[] data = Base64.getDecoder().decode(image.base64Data);
                            output.writeInt(data.length);
                            output.write(data);
                        }
                    }
                    output.flush();
                    stream.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                evictOtherFiles(directory);
                return true;
            } catch (IOException | RuntimeException failure) {
                return false;
            } finally { if (temporary != null) temporary.delete(); }
        }
    }

    public void clear() {
        synchronized (IO_LOCK) { state.epoch++; file.delete(); }
    }

    private List<Turn> bounded(List<Turn> turns) {
        int bytes = 0;
        List<Turn> retained = new ArrayList<>();
        for (int index = turns.size() - 1; index >= 0 && retained.size() < rounds; index--) {
            Turn turn = turns.get(index);
            int imageBytes = 0;
            for (ChatImage image : turn.images) imageBytes += image.sizeBytes();
            if (bytes + imageBytes > MAX_IMAGE_BYTES) continue;
            if (turn.prompt.length() > 4000 || turn.document.length() > 32768 || turn.answer.length() > 4000) continue;
            bytes += imageBytes;
            retained.add(0, turn);
        }
        return Collections.unmodifiableList(retained);
    }

    private void evictOtherFiles(File directory) {
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".bin"));
        if (files == null) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        long bytes = 0;
        for (File candidate : files) bytes += candidate.length();
        for (File candidate : files) {
            if (bytes <= MAX_DIRECTORY_BYTES) break;
            if (candidate.equals(file)) continue;
            STATES.computeIfAbsent(candidate.getAbsolutePath(), ignored -> new State()).epoch++;
            bytes -= candidate.length();
            candidate.delete();
        }
    }

    private static String key(AiModelConfig config) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{config.id, config.protocol, config.apiEndpoint, config.modelName, config.systemPrompt}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder key = new StringBuilder();
            for (byte value : digest.digest()) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static String text(DataInputStream input, int maximum) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > maximum) throw new IOException("Text size");
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void text(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static final class State { long epoch; }

    public static final class Turn {
        public final String prompt;
        public final String document;
        public final List<ChatImage> images;
        public final String answer;
        public Turn(String prompt, String document, List<ChatImage> images, String answer) {
            this.prompt = prompt;
            this.document = document;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.answer = answer;
        }
    }
}

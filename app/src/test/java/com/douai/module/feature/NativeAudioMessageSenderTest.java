package com.douai.module.feature;

import com.bytedance.im.core.client.callback.IRequestListener;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class NativeAudioMessageSenderTest {
    @Before public void reset() { Uploader.kind = 17; Uploader.last = null; Uploader.count = 0; LocalInsert.fail = false; LocalInsert.defer = false; LocalInsert.callback = null; }

    @Test public void nativeAttachmentAndContentMatchNativePathDigestContract() throws Exception {
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader())) {
            SpeechPreparation.Audio audio = audio();
            Message message = new Message();
            NativeAudioMessageSender.Content content = sender.content(audio);
            sender.attach(message, audio);
            assertEquals(17, content.type);
            AudioContent value = (AudioContent) content.value;
            assertEquals(1250, value.duration);
            assertEquals(Collections.emptyList(), value.waveList);
            byte[] expected = MessageDigest.getInstance("MD5").digest(audio.file.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            StringBuilder md5 = new StringBuilder();
            for (byte b : expected) md5.append(String.format("%02x", b & 255));
            assertEquals(md5.toString(), value.md5);
            assertEquals(message.uuid, message.attachments.get(0).uuid);
            assertEquals(audio.file.getAbsolutePath(), message.attachments.get(0).path);
            assertEquals(audio.file.getAbsolutePath(), message.localExt.get("localpath"));
            assertEquals(0, message.status);
            audio.delete();
        }
    }

    @Test public void encryptedAudioUsesNativeContentTypeAndRetainsFields() throws Exception {
        Uploader.kind = 109;
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader())) {
            SpeechPreparation.Audio audio = audio();
            NativeAudioMessageSender.Content content = sender.content(audio);
            assertEquals(109, content.type);
            assertTrue(content.value instanceof EncryptedAudio);
            assertEquals(10900, ((EncryptedAudio) content.value).type);
            assertEquals(1250, ((EncryptedAudio) content.value).duration);
            audio.delete();
        }
    }

    @Test public void localInsertionAndUploadQueueAreNotDeliveryAcknowledgment() throws Throwable {
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader())) {
            SpeechPreparation.Audio audio = audio();
            Message message = new Message();
            sender.attach(message, audio);
            AtomicInteger ack = new AtomicInteger();
            sender.submit(message, sender.content(audio), audio, success -> ack.set(success ? 1 : -1), () -> true);
            assertEquals(1, Uploader.count);
            assertEquals(0, ack.get());
            assertTrue(audio.file.isFile());
            message.localExt.put("s:status_code", "0");
            framework.invoke(Message.class.getMethod("setMsgStatus", int.class), message, 2);
            assertEquals(0, ack.get());
            framework.invoke(Message.class.getMethod("setMsgId", long.class), message, 700L);
            assertEquals(1, ack.get());
            assertFalse(audio.file.exists());
        }
    }

    @Test public void remotePolicyFailureAndUploadFailureNeverReportSent() throws Throwable {
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader())) {
            SpeechPreparation.Audio audio = audio();
            Message message = new Message();
            sender.attach(message, audio);
            AtomicInteger ack = new AtomicInteger();
            sender.submit(message, sender.content(audio), audio, success -> ack.set(success ? 1 : -1), () -> true);
            message.localExt.put("s:status_code", "5");
            framework.invoke(Message.class.getMethod("setMsgId", long.class), message, 700L);
            framework.invoke(Message.class.getMethod("setMsgStatus", int.class), message, 2);
            assertEquals(0, ack.get());
            framework.invoke(Message.class.getMethod("setMsgStatus", int.class), message, 3);
            assertEquals(-1, ack.get());
            assertFalse(audio.file.exists());
        }
    }

    @Test public void failedLocalInsertionDoesNotQueueAnUpload() throws Exception {
        LocalInsert.fail = true;
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader())) {
            SpeechPreparation.Audio audio = audio();
            Message message = new Message(); sender.attach(message, audio);
            AtomicInteger ack = new AtomicInteger();
            sender.submit(message, sender.content(audio), audio, success -> ack.set(success ? 1 : -1), () -> true);
            assertEquals(-1, ack.get()); assertEquals(0, Uploader.count); assertFalse(audio.file.exists());
        }
    }

    @Test public void cancellationWhileLocalInsertionIsPendingStopsTheUpload() throws Exception {
        LocalInsert.defer = true;
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader())) {
            SpeechPreparation.Audio audio = audio();
            Message message = new Message(); sender.attach(message, audio);
            java.util.concurrent.atomic.AtomicBoolean valid = new java.util.concurrent.atomic.AtomicBoolean(true);
            AtomicInteger ack = new AtomicInteger();
            sender.submit(message, sender.content(audio), audio, success -> ack.set(success ? 1 : -1), valid::get);
            assertEquals(0, Uploader.count);
            valid.set(false); LocalInsert.callback.onSuccess(message);
            assertEquals(-1, ack.get()); assertEquals(0, Uploader.count); assertFalse(audio.file.exists());
        }
    }

    @Test public void unknownUploadRetainsFileUntilBoundedTimeoutEvenAfterClosingHook() throws Exception {
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader());
        SpeechPreparation.Audio audio = audio();
        Message message = new Message(); sender.attach(message, audio);
        AtomicInteger ack = new AtomicInteger();
        sender.submit(message, sender.content(audio), audio, success -> ack.incrementAndGet(), () -> true);
        sender.close();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(31));
        assertTrue(audio.file.exists()); assertEquals(0, ack.get());
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMinutes(10));
        assertFalse(audio.file.exists()); assertEquals(0, ack.get());
    }

    @Test public void insertionCallbackAfterRetentionDeadlineCannotUploadDeletedAudio() throws Exception {
        LocalInsert.defer = true;
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (NativeAudioMessageSender sender = new NativeAudioMessageSender(framework.api, report(), getClass().getClassLoader())) {
            SpeechPreparation.Audio audio = audio();
            Message message = new Message(); sender.attach(message, audio);
            AtomicInteger ack = new AtomicInteger();
            sender.submit(message, sender.content(audio), audio, success -> ack.incrementAndGet(), () -> true);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(NativeAudioMessageSender.RETENTION_MILLIS + 1));
            assertFalse(audio.file.exists());
            LocalInsert.callback.onSuccess(message);
            assertEquals(0, Uploader.count);
            assertEquals(0, ack.get());
        }
    }

    static SpeechPreparation.Audio audio() throws Exception {
        File file = File.createTempFile("douai-native-audio", ".m4a");
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        return new SpeechPreparation.Audio(file, 1250);
    }

    static void addRules(List<RuleScanResult> rules) throws Exception {
        add(rules, "dm_auto_audio_type", Uploader.class.getMethod("type"));
        type(rules, "dm_auto_audio_content", AudioContent.class);
        type(rules, "dm_auto_audio_encrypted_content", EncryptedAudio.class);
        add(rules, "dm_auto_audio_content_type", BaseContent.class.getMethod("setType", int.class));
        type(rules, "dm_auto_audio_attachment", Attachment.class);
        add(rules, "dm_auto_audio_message_status", Message.class.getMethod("setMsgStatus", int.class));
        add(rules, "dm_auto_audio_message_server_id", Message.class.getMethod("setMsgId", long.class));
        add(rules, "dm_speech_message_local_ext", Message.class.getMethod("getLocalExt"));
        add(rules, "dm_auto_audio_local_path", LocalInsert.class.getMethod("path", Message.class, String.class));
        add(rules, "dm_auto_audio_insert", LocalInsert.class.getMethod("insert", Message.class, Runnable.class, IRequestListener.class, Object.class));
        add(rules, "dm_auto_audio_uploader", Singleton.class.getMethod("get"));
        add(rules, "dm_speech_upload_enqueue", Uploader.class.getMethod("enqueue", Message.class, BaseContent.class, long.class, Object.class));
    }
    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>(); addRules(rules); return new HostScanReport("fixture", rules, false);
    }
    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "reply", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }
    private static void type(List<RuleScanResult> rules, String id, Class<?> type) {
        rules.add(new RuleScanResult(id, "reply", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(type), null, null))));
    }

    public static class BaseContent { public int type; public void setType(int value) { type = value; } }
    public static class AudioContent extends BaseContent { public long duration; public List<?> waveList; public String md5; }
    public static final class EncryptedAudio extends AudioContent { }
    public static final class Attachment {
        String uuid; String path;
        public void setMsgUuid(String value) { uuid = value; }
        public void setLocalPath(String value) { path = value; }
    }
    public static class Message {
        public final String uuid = java.util.UUID.randomUUID().toString();
        public int status; public long id; public List<Attachment> attachments;
        public final ConcurrentHashMap<String, String> localExt = new ConcurrentHashMap<>();
        public String getUuid() { return uuid; }
        public int getMsgStatus() { return status; }
        public long getMsgId() { return id; }
        public ConcurrentHashMap<String, String> getLocalExt() { return localExt; }
        public void setAttachments(List<Attachment> values) { attachments = values; }
        public void setMsgStatus(int value) { status = value; }
        public void setMsgId(long value) { id = value; }
    }
    public static final class LocalInsert {
        static boolean fail; static boolean defer; static IRequestListener callback;
        public static void path(Message message, String value) { message.localExt.put("localpath", value); }
        public static void insert(Message message, Runnable ready, IRequestListener listener, Object extras) {
            callback = listener;
            if (defer) return;
            if (fail) listener.onFailure(new IllegalStateException("local insertion failed"));
            else listener.onSuccess(message);
        }
    }
    public static final class Singleton { public Object get() { return new Uploader(); } }
    public static final class Uploader {
        public static final Singleton LJFF = new Singleton();
        static int kind = 17; static int count; static Message last;
        public static int type() { return kind; }
        public void enqueue(Message message, BaseContent content, long started, Object extras) { count++; last = message; }
    }
}

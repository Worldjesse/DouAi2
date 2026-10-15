package com.douai.module.feature;

import android.app.Activity;
import android.content.ContentValues;
import android.view.View;

import com.bytedance.im.core.client.callback.IRequestListener;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class NativeAutoReplyIntegrationTest {
    @Test public void receivedMessageIsPreviewedThenSentThroughNativeSdkAndRecordedAfterAck() throws Throwable { flow(false); }
    @Test public void longPressCancelsReceivedMessageWithoutCallingNativeSender() throws Throwable { flow(true); }
    @Test public void refreshedWhitelistCancelsPreparedReplyBeforeNativeSend() throws Throwable { flow(false, true); }
    @Test public void receivedMessageRepliesWithInteractionMasterOff() throws Throwable { flow(false, false, false); }
    @Test public void interactionMasterChangeDoesNotCancelDelayedReply() throws Throwable { flow(false, false, true, true); }
    @Test public void disablingDefaultPrivateChatScopeCancelsPreparedReply() throws Throwable { flow(false, false, true, false, true); }

    @Test public void dailyLimitStopsASecondReplyPreparedBeforeTheFirstSend() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            fixture.receive(200, "thread");
            fixture.receive(201, "other");
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            List<AutomationHistoryStore.Entry> records = fixture.history();
            assertEquals(2, records.size());
            assertEquals(1, records.stream().filter(entry -> "sent".equals(entry.status)).count());
            assertEquals(1, records.stream().filter(entry -> "cancelled".equals(entry.status)).count());
        }
    }

    @Test public void nativeSendBoundaryRechecksAllowanceConsumedAfterPreviewValidation() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            fixture.database.imSdkContext.beforeSender = () -> assertNotNull(new AutomationRateLimiter(fixture.activity)
                    .tryReserve(AutomationHistoryStore.REPLY, 100, "201", new LocalOptions(fixture.values)));
            fixture.receive(200, "thread");
            fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals("cancelled", fixture.history().get(0).status);
        }
    }

    @Test public void sameSenderIntervalSpansPrivateAndGroupChatsButAllowsAnotherGroupMember() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_user_cooldown_minutes", "30")) {
            fixture.receive(200, "thread");
            fixture.receive(200, "group");
            fixture.receive(201, "group");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            fixture.receive(200, "group");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            assertEquals(3, fixture.history().size());
        }
    }

    @Test public void nativeFailureRestoresDailyAndSameSenderAllowance() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1",
                "reply_user_cooldown_minutes", "30")) {
            fixture.database.imSdkContext.failNext = true;
            fixture.receive(200, "thread");
            fixture.flush();
            fixture.receive(200, "thread");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            assertEquals(1, fixture.history().stream().filter(entry -> "failed".equals(entry.status)).count());
            assertEquals(1, fixture.history().stream().filter(entry -> "sent".equals(entry.status)).count());
        }
    }

    @Test public void missingNativeAcknowledgmentKeepsAllowanceConsumedAfterTimeout() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            fixture.database.imSdkContext.suppressCallbacks = true;
            fixture.receive(200, "thread");
            fixture.flush();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(31));
            fixture.flush();
            fixture.receive(201, "other");
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals("submitted", fixture.history().get(0).status);
        }
    }

    @Test public void enablingLimitCountsEarlierUnlimitedSendsAndCancelsPreparedReply() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            fixture.receive(200, "thread");
            fixture.flush();
            fixture.receive(201, "other");
            fixture.values.put("reply_daily_limit", "1");
            LocalOptions changed = new LocalOptions(fixture.values);
            fixture.hook.setOptions(changed);
            fixture.dispatcher.setOptions(changed);
            fixture.flush();
            fixture.receive(202, "third");
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals(2, fixture.history().size());
        }
    }

    @Test public void voiceReplyUsesAudioOnlyInPrivateAndGroupChatsAndRecordsAfterRemoteAck() throws Throwable {
        for (String conversation : Arrays.asList("thread", "group")) {
            try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
                NativeAudioMessageSenderTest.Uploader.count = 0;
                NativeAudioMessageSenderTest.Uploader.kind = 17;
                NativeAudioMessageSenderTest.LocalInsert.fail = false;
                NativeAudioMessageSenderTest.LocalInsert.defer = false;
                java.util.concurrent.atomic.AtomicReference<SpeechPreparation.Audio> generated = new java.util.concurrent.atomic.AtomicReference<>();
                fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                    assertEquals("reply", text); assertEquals(120_000, maximum); assertTrue(valid.getAsBoolean());
                    SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio(); generated.set(audio); return audio;
                };
                fixture.receive(200, conversation);
                fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals(1, NativeAudioMessageSenderTest.Uploader.count);
                Message message = (Message) NativeAudioMessageSenderTest.Uploader.last;
                assertEquals(17, message.type);
                assertEquals(conversation, message.conversation.id());
                assertEquals(100, message.sender);
                assertEquals("group".equals(conversation) ? 2 : 1, message.conversationType);
                assertEquals(501, message.orderIndex);
                org.json.JSONObject body = new org.json.JSONObject(message.content);
                assertEquals(1250, body.getLong("duration")); assertFalse(body.has("text"));
                assertEquals("sending", fixture.history().get(0).status);
                assertTrue(generated.get().file.exists());
                message.localExt.put("s:status_code", "0");
                fixture.framework.invoke(NativeAudioMessageSenderTest.Message.class.getMethod("setMsgStatus", int.class), message, 2);
                fixture.framework.invoke(NativeAudioMessageSenderTest.Message.class.getMethod("setMsgId", long.class), message, 900L);
                fixture.flush();
                assertEquals("sent", fixture.history().get(0).status);
                assertFalse(generated.get().file.exists());
            }
        }
    }

    @Test public void voiceSynthesisFailureNeverFallsBackToTextOrConsumesAllowance() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true", "reply_daily_limit", "1")) {
            fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> { throw new IllegalStateException("provider failed"); };
            fixture.receive(200, "thread"); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals("failed", fixture.history().get(0).status);
            assertTrue(new AutomationRateLimiter(fixture.activity)
                    .canSend("reply", 100, "200", new LocalOptions(fixture.values)));
        }
    }

    @Test public void changingSpeechSettingsAfterPreparationCancelsVoiceAndDeletesPreparedFile() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
            java.util.concurrent.atomic.AtomicReference<SpeechPreparation.Audio> generated = new java.util.concurrent.atomic.AtomicReference<>();
            fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio(); generated.set(audio); return audio;
            };
            fixture.receive(200, "thread");
            fixture.values.put("tts_voice", "changed");
            LocalOptions changed = new LocalOptions(fixture.values);
            fixture.hook.setOptions(changed); fixture.dispatcher.setOptions(changed); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals("cancelled", fixture.history().get(0).status);
            assertFalse(generated.get().file.exists());
        }
    }

    @Test public void voiceReplyKeepsUnknownSendAllowanceAndFileAfterDispatcherTimeout() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true", "reply_daily_limit", "1")) {
            NativeAudioMessageSenderTest.LocalInsert.fail = false; NativeAudioMessageSenderTest.LocalInsert.defer = false;
            java.util.concurrent.atomic.AtomicReference<SpeechPreparation.Audio> generated = new java.util.concurrent.atomic.AtomicReference<>();
            fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio(); generated.set(audio); return audio;
            };
            fixture.receive(200, "thread"); fixture.flush();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(31)); fixture.flush();
            assertEquals("submitted", fixture.history().get(0).status);
            assertTrue(generated.get().file.exists());
            assertFalse(new AutomationRateLimiter(fixture.activity)
                    .canSend("reply", 100, "201", new LocalOptions(fixture.values)));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMinutes(10));
            assertFalse(generated.get().file.exists());
        }
    }

    @Test public void disablingRepliesDisconnectsBlockedImageAndAllowsNextReply() throws Throwable {
        for (boolean headers : new boolean[]{true, false}) {
            BlockingMediaConnection connection = new BlockingMediaConnection(headers);
            try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
                try {
                    fixture.startBlockedImage(connection);
                    fixture.values.put("native_auto_reply", false);
                    fixture.applyOptions();
                    awaitDisconnected(connection);
                    fixture.awaitWorker();
                    fixture.flush();
                    assertEquals(0, fixture.database.imSdkContext.sends);
                    fixture.values.put("native_auto_reply", true);
                    fixture.values.put("reply_mode", "fixed");
                    fixture.applyOptions();
                    fixture.receive(201, "other"); fixture.flush();
                    assertEquals(1, fixture.database.imSdkContext.sends);
                    assertEquals("other", fixture.database.imSdkContext.last.conversation.id());
                } finally { connection.disconnect(); }
            }
        }
    }

    @Test public void accountSwitchWithoutAnotherIncomingMessageDisconnectsBlockedReply() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            try {
                fixture.startBlockedImage(connection);
                fixture.database.imSdkContext.account = 101;
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
                awaitDisconnected(connection);
                fixture.awaitWorker(); fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void closingRepliesDisconnectsBlockedImageBeforeItsSocketTimeout() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(false);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            try {
                fixture.startBlockedImage(connection);
                fixture.hook.close();
                awaitDisconnected(connection);
                fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void overallReplyDeadlineDisconnectsBlockedImageAndAllowsNextReply() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            try {
                fixture.startBlockedImage(connection);
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(121));
                awaitDisconnected(connection);
                fixture.awaitWorker(); fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals("failed", fixture.history().get(0).status);
                fixture.values.put("reply_mode", "fixed"); fixture.applyOptions();
                fixture.receive(201, "other"); fixture.flush();
                assertEquals(1, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void lateSpeechPreparationAfterReplyDeadlineDeletesAudioWithoutSending() throws Throwable {
        java.util.concurrent.CountDownLatch preparing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio();
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
            try {
                fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                    preparing.countDown();
                    boolean ready = false;
                    while (!ready) {
                        try { ready = release.await(2, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { }
                    }
                    return audio;
                };
                Runnable reply = fixture.enqueue(200, "thread", false);
                assertNotNull(reply);
                fixture.scheduled.execute(reply);
                assertTrue(preparing.await(2, TimeUnit.SECONDS));
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(121));
                fixture.flush();
                assertEquals("failed", fixture.history().get(0).status);
                release.countDown(); fixture.awaitWorker(); fixture.flush();
                assertFalse(audio.file.exists());
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals(1, fixture.history().size());
                assertEquals("failed", fixture.history().get(0).status);
            } finally { release.countDown(); audio.delete(); }
        }
    }

    @Test public void installedStreakTickStillRunsWhileReplyImageIsBlocked() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            try {
                fixture.startBlockedImage(connection);
                ScheduledThreadPoolExecutor ticker = (ScheduledThreadPoolExecutor)
                        HostTargets.read(fixture.hook, "streakWorker");
                Runnable tick = ticker.getQueue().peek();
                assertNotNull(tick);
                ticker.getQueue().remove(tick);
                ticker.execute(tick);
                assertTrue("Installed streak timer was blocked by reply generation",
                        fixture.database.imSdkContext.enumerated.await(2, TimeUnit.SECONDS));
                assertFalse(connection.disconnected);
                assertEquals(0, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void lateGenerationCannotHandoffWhenMainWatchdogHasNotRun() throws Throwable {
        java.util.concurrent.CountDownLatch preparing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio();
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
            try {
                fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                    preparing.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return audio;
                };
                Runnable reply = fixture.enqueue(200, "thread", false);
                assertNotNull(reply);
                fixture.scheduled.execute(reply);
                assertTrue(preparing.await(2, TimeUnit.SECONDS));
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(121));
                release.countDown();
                fixture.awaitWorker();
                assertFalse("Late audio must be rejected before the delayed main watchdog runs", audio.file.exists());
                fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals(1, fixture.history().size());
                assertEquals("failed", fixture.history().get(0).status);
            } finally { release.countDown(); audio.delete(); }
        }
    }

    private static void awaitDisconnected(BlockingMediaConnection connection) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!connection.disconnected && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Blocked reply media must be actively disconnected", connection.disconnected);
    }

    private void flow(boolean cancel) throws Throwable {
        flow(cancel, false);
    }

    private void flow(boolean cancel, boolean removedFromList) throws Throwable {
        flow(cancel, removedFromList, true);
    }

    private void flow(boolean cancel, boolean removedFromList, boolean interactionMaster) throws Throwable {
        flow(cancel, removedFromList, interactionMaster, false);
    }

    private void flow(boolean cancel, boolean removedFromList, boolean interactionMaster, boolean changeMaster) throws Throwable {
        flow(cancel, removedFromList, interactionMaster, changeMaster, false);
    }

    private void flow(boolean cancel, boolean removedFromList, boolean interactionMaster, boolean changeMaster,
                      boolean disablePrivate) throws Throwable {
        FollowListSnapshot.reset();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("douai_automation_history", Activity.MODE_PRIVATE).edit().clear().commit();
        ManualExecutor worker = new ManualExecutor();
        AutomationDispatcher dispatcher = new AutomationDispatcher(activity, ignored -> { }, worker);
        dispatcher.onActivityResumed(activity);
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", interactionMaster); values.put("native_auto_reply", true); values.put("reply_global", true);
        values.put("reply_tip", true); values.put("reply_save_history", true); values.put("auto_message_text", "固定回复");
        if (removedFromList) {
            values.put("reply_scope_mode", "whitelist");
            values.put("reply_scope_relations", "following");
            FollowListSnapshot.activateAccount("100");
            FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                    Collections.singleton("200"));
        }
        LocalOptions options = new LocalOptions(values);
        dispatcher.setOptions(options);
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        List<String> diagnostics = new ArrayList<>();
        NativeMessagingHooks hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                activity, options, diagnostics::add, dispatcher);
        try {
            assertTrue(diagnostics.toString(), hook.supports("native_reply"));
            Database database = new Database();
            ContentValues incoming = new ContentValues();
            incoming.put("conversation_id", "thread"); incoming.put("conversation_type", 1);
            incoming.put("sender", 200L); incoming.put("msg_server_id", "7001");
            incoming.put("created_time", System.currentTimeMillis()); incoming.put("type", 7);
            incoming.put("content", "{\"text\":\"收到消息\"}");
            framework.invoke(Database.class.getMethod("insert", String.class, String.class, ContentValues.class),
                    database, "msg", null, incoming);
            ScheduledThreadPoolExecutor scheduled = (ScheduledThreadPoolExecutor) HostTargets.read(hook, "worker");
            Runnable reply = scheduled.getQueue().peek();
            if (changeMaster) {
                values.put("automation_enabled", false);
                LocalOptions changed = new LocalOptions(values);
                hook.setOptions(changed);
                dispatcher.setOptions(changed);
            }
            assertNotNull(reply); scheduled.getQueue().remove(reply); reply.run();
            ShadowLooper.idleMainLooper();
            View notice = activity.getWindow().getDecorView().findViewWithTag("automation.notice.100:thread:7001");
            assertNotNull(notice);
            assertEquals(0, database.imSdkContext.sends);
            if (disablePrivate) {
                values.put("reply_private_chats", false);
                LocalOptions changed = new LocalOptions(values);
                hook.setOptions(changed);
                dispatcher.setOptions(changed);
            }
            if (removedFromList) {
                FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                        Collections.emptySet());
            }
            if (cancel) notice.performLongClick();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(9));
            worker.runAll(); ShadowLooper.idleMainLooper(); worker.runAll();
            List<AutomationHistoryStore.Entry> records = new AutomationHistoryStore(activity).read("reply");
            assertEquals(1, records.size());
            assertEquals(cancel || removedFromList || disablePrivate ? "cancelled" : "sent", records.get(0).status);
            assertEquals(cancel || removedFromList || disablePrivate ? 0 : 1, database.imSdkContext.sends);
            if (!cancel && !removedFromList && !disablePrivate) {
                assertEquals(7, database.imSdkContext.last.type);
                assertEquals("固定回复", new org.json.JSONObject(database.imSdkContext.last.content).getString("text"));
                assertEquals("thread", database.imSdkContext.last.conversation.id());
            }
        } finally {
            hook.close(); dispatcher.close(); ShadowLooper.idleMainLooper(); worker.runAll(); activity.finish();
            FollowListSnapshot.reset();
        }
    }

    public static final class Sdk {
        int sends; Message last;
        volatile long account = 100;
        final java.util.concurrent.CountDownLatch enumerated = new java.util.concurrent.CountDownLatch(1);
        boolean failNext; boolean suppressCallbacks;
        Runnable beforeSender = () -> { };
        public long uid() { return account; }
        public Bean bean() { return new Bean(); }
        public Conversations conversations() { return new Conversations(enumerated::countDown); }
    }
    public static final class Database {
        public Sdk imSdkContext = new Sdk();
        public long insert(String table, String nullColumn, ContentValues values) { return 1; }
    }
    public static final class Bean { public Builder builder() { return new Builder(); } }
    public static final class Builder {
        Message message = new Message();
        public void type(int type) { message.type = type; }
        public void content(String content) { message.content = content; }
        public void conversation(Conversation conversation) {
            message.conversation = conversation;
            message.conversationType = conversation.single() ? 1 : 2;
            message.orderIndex = 501;
        }
        public Message build() { message.sender = 100; return message; }
    }
    public static final class Message extends NativeAudioMessageSenderTest.Message {
        int type; String content; Conversation conversation;
        long sender; int conversationType; long orderIndex;
    }
    public static final class Conversations {
        private final Runnable enumerate;
        Conversations(Runnable enumerate) { this.enumerate = enumerate; }
        public Conversation find(String id) {
            return Arrays.asList("thread", "other", "third", "group").contains(id) ? new Conversation(id) : null;
        }
        public List<Conversation> all() { enumerate.run(); return Collections.emptyList(); }
    }
    public static final class Conversation {
        final String id;
        public Conversation(String id) { this.id = id; }
        public String id() { return id; }
        public List<Long> members() { return Arrays.asList(100L, 200L); }
        public Core core() { return new Core(); }
        public boolean single() { return !"group".equals(id); }
    }
    public static final class Core { public String name() { return "目标用户"; } }
    public static final class Text { public String text; public static Text create(String value) { Text text = new Text(); text.text = value; return text; } }
    public static final class Sender {
        final Sdk sdk; final IRequestListener listener;
        public Sender(Sdk sdk, IRequestListener listener) {
            this.sdk = sdk; this.listener = listener;
            sdk.beforeSender.run();
        }
        public void send(Message message, Map<String, String> extras) {
            sdk.sends++; sdk.last = message;
            if (sdk.suppressCallbacks) return;
            if (sdk.failNext) {
                sdk.failNext = false;
                listener.onFailure(new IllegalStateException("Rejected by native SDK"));
            } else listener.onSuccess(message);
        }
    }

    private final class LimitedReplies implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final ManualExecutor worker = new ManualExecutor();
        final AutomationDispatcher dispatcher = new AutomationDispatcher(activity, ignored -> { }, worker);
        final NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        final Map<String, Object> values = new HashMap<>();
        final Database database = new Database();
        final NativeMessagingHooks hook;
        final ScheduledThreadPoolExecutor scheduled;
        int serverId = 8000;

        LimitedReplies(String... limits) throws Exception {
            FollowListSnapshot.reset();
            values.put("native_auto_reply", true);
            values.put("reply_global", true);
            values.put("reply_group_chats", true);
            values.put("reply_save_history", true);
            values.put("auto_message_text", "reply");
            for (int i = 0; i < limits.length; i += 2) values.put(limits[i],
                    "reply_voice".equals(limits[i]) ? Boolean.parseBoolean(limits[i + 1]) : limits[i + 1]);
            LocalOptions options = new LocalOptions(values);
            dispatcher.setOptions(options);
            List<String> diagnostics = new ArrayList<>();
            hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                    activity, options, diagnostics::add, dispatcher);
            assertTrue(diagnostics.toString(), hook.supports("native_reply"));
            scheduled = (ScheduledThreadPoolExecutor) HostTargets.read(hook, "worker");
            for (Runnable task : scheduled.getQueue()) ((java.util.concurrent.Future<?>) task).cancel(false);
            scheduled.getQueue().clear();
        }

        void receive(long sender, String conversation) throws Throwable {
            Runnable reply = enqueue(sender, conversation, false);
            if (reply != null) reply.run();
            ShadowLooper.idleMainLooper();
        }

        Runnable enqueue(long sender, String conversation, boolean image) throws Throwable {
            ContentValues incoming = new ContentValues();
            incoming.put("conversation_id", conversation);
            incoming.put("conversation_type", "group".equals(conversation) ? 2 : 1);
            incoming.put("sender", sender);
            incoming.put("msg_server_id", Integer.toString(++serverId));
            incoming.put("created_time", System.currentTimeMillis());
            incoming.put("type", image ? 27 : 7);
            incoming.put("content", image
                    ? "{\"resource_url\":{\"url_list\":[\"https://p.byteimg.com/image.png\"]}}"
                    : "{\"text\":\"incoming\"}");
            framework.invoke(Database.class.getMethod("insert", String.class, String.class, ContentValues.class),
                    database, "msg", null, incoming);
            Runnable reply = scheduled.getQueue().peek();
            if (reply != null) {
                scheduled.getQueue().remove(reply);
            }
            return reply;
        }

        void startBlockedImage(BlockingMediaConnection connection) throws Throwable {
            hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> connection);
            Runnable reply = enqueue(200, "thread", true);
            assertNotNull(reply);
            scheduled.execute(reply);
            assertTrue("Image request did not reach the blocked transport", connection.blocked.await(2, TimeUnit.SECONDS));
        }

        void applyOptions() {
            LocalOptions changed = new LocalOptions(values);
            hook.setOptions(changed); dispatcher.setOptions(changed);
        }

        void awaitWorker() throws Exception {
            scheduled.submit(() -> { }).get(2, TimeUnit.SECONDS);
        }

        void flush() {
            worker.runAll();
            ShadowLooper.idleMainLooper();
            worker.runAll();
        }

        List<AutomationHistoryStore.Entry> history() { return new AutomationHistoryStore(activity).read("reply"); }

        @Override public void close() {
            hook.close(); dispatcher.close(); ShadowLooper.idleMainLooper(); worker.runAll(); activity.finish();
            FollowListSnapshot.reset();
        }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "adv_im_uid", Sdk.class.getMethod("uid"));
        add(rules, "adv_im_bean", Sdk.class.getMethod("bean"));
        add(rules, "adv_im_conversations", Sdk.class.getMethod("conversations"));
        add(rules, "adv_im_builder_factory", Bean.class.getMethod("builder"));
        add(rules, "adv_im_builder_type", Builder.class.getMethod("type", int.class));
        add(rules, "adv_im_builder_content", Builder.class.getMethod("content", String.class));
        add(rules, "adv_im_builder_conversation", Builder.class.getMethod("conversation", Conversation.class));
        add(rules, "adv_im_builder_build", Builder.class.getMethod("build"));
        add(rules, "adv_im_conversation", Conversations.class.getMethod("find", String.class));
        add(rules, "adv_im_all_conversations", Conversations.class.getMethod("all"));
        add(rules, "adv_im_conversation_id", Conversation.class.getMethod("id"));
        add(rules, "adv_im_conversation_members", Conversation.class.getMethod("members"));
        add(rules, "adv_im_conversation_core", Conversation.class.getMethod("core"));
        add(rules, "adv_im_conversation_name", Core.class.getMethod("name"));
        add(rules, "adv_im_single_chat", Conversation.class.getMethod("single"));
        add(rules, "adv_im_text_content", Text.class.getMethod("create", String.class));
        add(rules, "adv_im_send", Sender.class.getMethod("send", Message.class, Map.class));
        add(rules, "adv_im_db_insert", Database.class.getMethod("insert", String.class, String.class, ContentValues.class));
        NativeAudioMessageSenderTest.addRules(rules);
        return new HostScanReport("fixture", rules, false);
    }
    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "reply", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>(); boolean stopped;
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { return stopped; }
    }
}

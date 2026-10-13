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
        public long uid() { return 100; }
        public Bean bean() { return new Bean(); }
        public Conversations conversations() { return new Conversations(); }
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
        public void conversation(Conversation conversation) { message.conversation = conversation; }
        public Message build() { return message; }
    }
    public static final class Message { int type; String content; Conversation conversation; }
    public static final class Conversations {
        public Conversation find(String id) { return "thread".equals(id) ? new Conversation() : null; }
        public List<Conversation> all() { return Collections.emptyList(); }
    }
    public static final class Conversation {
        public String id() { return "thread"; }
        public List<Long> members() { return Arrays.asList(100L, 200L); }
        public Core core() { return new Core(); }
        public boolean single() { return true; }
    }
    public static final class Core { public String name() { return "目标用户"; } }
    public static final class Text { public String text; public static Text create(String value) { Text text = new Text(); text.text = value; return text; } }
    public static final class Sender {
        final Sdk sdk; final IRequestListener listener;
        public Sender(Sdk sdk, IRequestListener listener) { this.sdk = sdk; this.listener = listener; }
        public void send(Message message, Map<String, String> extras) { sdk.sends++; sdk.last = message; listener.onSuccess(message); }
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

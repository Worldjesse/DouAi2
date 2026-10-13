package com.douai.module.feature;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Observes committed incoming messages and submits opt-in tasks through the native IM SDK. */
public final class NativeMessagingHooks implements AutoCloseable {
    private final Context context;
    private final SharedPreferences state;
    private final StreakSendLedger streakLedger;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final AtomicLong generation = new AtomicLong();
    private final Object sendLock = new Object();
    private static volatile Map<String, String> contacts = Collections.emptyMap();
    private static volatile Map<String, String> groups = Collections.emptyMap();
    private static volatile Map<String, ProfileListEntry> contactProfiles = Collections.emptyMap();
    private static volatile Map<String, ProfileListEntry> groupProfiles = Collections.emptyMap();
    private static volatile Map<String, Map<String, ProfileListEntry>> groupMembers = Collections.emptyMap();
    private static volatile long contactAccount;
    private final ChatCompletionClient ai = new ChatCompletionClient();
    private final AutomationDispatcher dispatcher;
    private final boolean ownsDispatcher;
    private final Random random = new Random();
    private final Set<String> pendingStreak = Collections.synchronizedSet(new HashSet<>());
    private final Set<java.util.concurrent.ScheduledFuture<?>> pendingReplies = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> entry) { return size() > 512; }
    };
    private final BehaviorHookGroup recall;
    private final BehaviorHookGroup receive;
    private Method sdkUid;
    private Method sdkBean;
    private Method sdkConversations;
    private Method builderFactory;
    private Method builderType;
    private Method builderContent;
    private Method builderConversation;
    private Method builderBuild;
    private Method findConversation;
    private Method allConversations;
    private Method conversationId;
    private Method conversationMembers;
    private Method conversationCore;
    private Method conversationName;
    private Method isSingle;
    private Method send;
    private Constructor<?> senderConstructor;
    private Class<?> listenerClass;
    private Class<?> gsonClass;
    private Method createText;
    private Method imProfileCache;
    private Method imProfileGet;
    private volatile LocalOptions options;
    private volatile Object sdk;
    private volatile boolean closed;
    private volatile long startedAt = System.currentTimeMillis();
    private volatile String resetToken = "";
    private final ThreadLocal<Long> localRecallActor = new ThreadLocal<>();
    private final Map<String, Long> localRecallIdentities = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(512, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 512; }
            });

    private NativeMessagingHooks(Context context, LocalOptions options, Consumer<String> log,
                                 AutomationDispatcher dispatcher) {
        this.context = context.getApplicationContext();
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
        ownsDispatcher = dispatcher == null;
        this.dispatcher = ownsDispatcher ? new AutomationDispatcher(context, this.log) : dispatcher;
        this.dispatcher.setOptions(options);
        state = context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        streakLedger = new StreakSendLedger(state);
        resetToken = state.getString("streak.reset_token", "");
        recall = new BehaviorHookGroup("prevent_message_recall", this.log);
        receive = new BehaviorHookGroup("native_reply", this.log);
        worker.setRemoveOnCancelPolicy(true);
    }

    public static NativeMessagingHooks install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, Context context,
                                                LocalOptions options, Consumer<String> log) {
        return install(framework, report, loader, context, options, log, null);
    }

    public static NativeMessagingHooks install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, Context context,
                                                LocalOptions options, Consumer<String> log,
                                                AutomationDispatcher dispatcher) {
        NativeMessagingHooks hook = new NativeMessagingHooks(context, options, log, dispatcher);
        hook.installRecall(framework, report, loader);
        hook.installReceive(framework, report, loader);
        hook.setOptions(options);
        hook.worker.scheduleWithFixedDelay(hook::streakTick, 5, 30, TimeUnit.SECONDS);
        return hook;
    }

    public boolean supports(String key) {
        return "prevent_message_recall".equals(key) ? recall.isActive() : receive.isActive();
    }

    public static Map<String, String> availableContacts() { return contacts; }
    public static Map<String, String> availableGroups() { return groups; }
    public static Map<String, ProfileListEntry> availableContactProfiles() { return profiles(contacts, contactProfiles); }
    public static Map<String, ProfileListEntry> availableGroupProfiles() { return profiles(groups, groupProfiles); }
    public static Map<String, ProfileListEntry> availableGroupMembers() {
        Map<String, ProfileListEntry> result = new LinkedHashMap<>();
        for (Map<String, ProfileListEntry> members : groupMembers.values()) result.putAll(members);
        return Collections.unmodifiableMap(result);
    }

    /** Number of streak sends whose outcome is unknown, including unmigrated legacy pending entries. */
    public int unknownStreakCount() { return streakLedger.unknownCount(); }

    /** Read-only count for status surfaces that do not hold the installed hook instance. */
    public static int unknownStreakCount(Context context) {
        SharedPreferences state = context.getApplicationContext()
                .getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        return StreakSendLedger.countUnknown(state);
    }

    private static Map<String, ProfileListEntry> profiles(Map<String, String> names, Map<String, ProfileListEntry> details) {
        Map<String, ProfileListEntry> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : names.entrySet()) result.put(entry.getKey(), details.getOrDefault(entry.getKey(),
                new ProfileListEntry(entry.getKey(), entry.getValue(), "")));
        return Collections.unmodifiableMap(result);
    }

    public void setOptions(LocalOptions value) {
        boolean invalidate = automationOptionsChanged(options, value);
        synchronized (sendLock) {
            options = value;
            if (invalidate) generation.incrementAndGet();
        }
        if (invalidate) {
            ai.cancelCurrentRequest();
            dispatcher.cancelKind(AutomationHistoryStore.REPLY);
            synchronized (pendingReplies) {
                for (java.util.concurrent.ScheduledFuture<?> task : pendingReplies) task.cancel(true);
                pendingReplies.clear();
            }
        }
        if (ownsDispatcher) dispatcher.setOptions(value);
        if (!streakLedger.updateRetryUnknownToken(value.text("streak_retry_unknown_token", ""))) {
            safeLog("Could not persist streak retry token");
        }
        String token = value.text("streak_reset_token", "");
        if (!token.equals(resetToken)) {
            synchronized (sendLock) {
                if (streakLedger.reset(token)) {
                    resetToken = token;
                    pendingStreak.clear();
                } else {
                    safeLog("Could not persist streak reset token");
                }
            }
        }
    }

    private static boolean automationOptionsChanged(LocalOptions previous, LocalOptions next) {
        for (String key : new String[]{"native_auto_reply", "reply_global", "reply_targets",
                "reply_mode", "reply_prompt", "reply_delay_seconds", "auto_message_text", "reply_keywords",
                "reply_scope_mode", "reply_scope_relations", "reply_scope_fields", "reply_scope_keywords",
                "reply_filter_mode", "reply_filter_keywords", "reply_filter_fields",
                "reply_private_chats", "reply_group_chats",
                "reply_group_scope_mode", "reply_group_targets", "reply_group_mode", "reply_group_members",
                "reply_random", AiModelSettings.KEY, "streak_enabled", "streak_targets", "streak_content",
                "streak_content_api", "streak_start", "streak_end"}) {
            boolean defaultEnabled = "reply_private_chats".equals(key);
            if (previous.enabled(key, defaultEnabled) != next.enabled(key, defaultEnabled)
                    || !previous.text(key, "").equals(next.text(key, ""))) return true;
        }
        return false;
    }

    private void installRecall(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        try {
            Method update = HostTargets.method(report, "adv_im_db_update", loader);
            sdkUid = HostTargets.method(report, "adv_im_uid", loader);
            recall.add(framework, update, chain -> {
                captureSdk(chain.getThisObject());
                if (!"msg".equals(chain.getArg(0)) || !(chain.getArg(1) instanceof ContentValues)) return chain.proceed();
                ContentValues original = (ContentValues) chain.getArg(1);
                ContentValues identified = original;
                if (localRecallActor.get() != null && original.getAsString("ext") != null) {
                    try {
                        JSONObject extension = new JSONObject(original.getAsString("ext"));
                        if (extension.optString("s:recall_uid", "").isEmpty()) {
                            extension.put("s:recall_uid", Long.toString(localRecallActor.get()));
                            identified = new ContentValues(original);
                            identified.put("ext", extension.toString());
                        }
                    } catch (org.json.JSONException ignored) { }
                }
                ContentValues replacement = MessageRecallPolicy.protect(identified, ownUid(), options);
                if (replacement == identified) return chain.proceed();
                Object[] arguments = chain.getArgs().toArray();
                arguments[1] = replacement;
                Object result = chain.proceed(arguments);
                if (result instanceof Number && ((Number) result).intValue() > 0) {
                    String uuid = MessageRecallPolicy.uuid(original, chain.getArg(2), chain.getArg(3));
                    if (uuid != null) markRecall(ownUid(), uuid);
                }
                return result;
            });
            Method isRecalled = HostTargets.method(report, "adv_im_message_recalled", loader);
            Method extension = HostTargets.method(report, "adv_im_message_ext", loader);
            Method uuid = HostTargets.method(report, "adv_im_message_uuid", loader);
            recall.add(framework, isRecalled, chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result) || !options.enabled("prevent_message_recall")) return result;
                try {
                    Object raw = extension.invoke(chain.getThisObject());
                    if (!(raw instanceof Map<?, ?>)) return result;
                    JSONObject values = new JSONObject((Map<?, ?>) raw);
                    String id = (String) uuid.invoke(chain.getThisObject());
                    Long localActor = localRecallIdentities.get(ownUid() + "." + id);
                    if (localActor == null && id != null) {
                        long recorded = state.getLong("recall.actor." + ownUid() + "." + id, 0);
                        if (recorded > 0) localActor = recorded;
                    }
                    if (values.optString("s:recall_uid", "").isEmpty() && localActor != null) {
                        values.put("s:recall_uid", Long.toString(localActor));
                    }
                    if (!MessageRecallPolicy.shouldProtect(values, ownUid(), options)) return result;
                    if (id != null) markRecall(ownUid(), id);
                    return false;
                } catch (Exception failure) { recall.fail(failure); return result; }
            });
            Method localRecall = HostTargets.method(report, "adv_im_local_recall", loader);
            recall.add(framework, localRecall, chain -> {
                captureSdk(chain.getThisObject());
                long account = ownUid();
                String id = null;
                try { id = (String) uuid.invoke(chain.getArg(0)); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
                if (account > 0 && id != null) {
                    localRecallIdentities.put(account + "." + id, account);
                    state.edit().putLong("recall.actor." + account + "." + id, account).apply();
                }
                localRecallActor.set(account);
                try { return chain.proceed(); }
                finally { localRecallActor.remove(); }
            });
            log.accept("Native message recall protection registered");
        } catch (Throwable failure) { recall.fail(failure); }
    }

    private void installReceive(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        try {
            sdkUid = HostTargets.method(report, "adv_im_uid", loader);
            sdkBean = HostTargets.method(report, "adv_im_bean", loader);
            sdkConversations = HostTargets.method(report, "adv_im_conversations", loader);
            builderFactory = HostTargets.method(report, "adv_im_builder_factory", loader);
            builderType = HostTargets.method(report, "adv_im_builder_type", loader);
            builderContent = HostTargets.method(report, "adv_im_builder_content", loader);
            builderConversation = HostTargets.method(report, "adv_im_builder_conversation", loader);
            builderBuild = HostTargets.method(report, "adv_im_builder_build", loader);
            findConversation = HostTargets.method(report, "adv_im_conversation", loader);
            allConversations = HostTargets.method(report, "adv_im_all_conversations", loader);
            conversationId = HostTargets.method(report, "adv_im_conversation_id", loader);
            conversationMembers = HostTargets.method(report, "adv_im_conversation_members", loader);
            conversationCore = HostTargets.method(report, "adv_im_conversation_core", loader);
            conversationName = HostTargets.method(report, "adv_im_conversation_name", loader);
            isSingle = HostTargets.method(report, "adv_im_single_chat", loader);
            createText = HostTargets.method(report, "adv_im_text_content", loader);
            send = HostTargets.method(report, "adv_im_send", loader);
            listenerClass = Class.forName("com.bytedance.im.core.client.callback.IRequestListener", false, loader);
            senderConstructor = send.getDeclaringClass().getConstructor(sdkUid.getDeclaringClass(), listenerClass);
            gsonClass = Class.forName("com.google.gson.Gson", false, loader);
            Method insert = HostTargets.method(report, "adv_im_db_insert", loader);
            receive.add(framework, insert, chain -> {
                captureSdk(chain.getThisObject());
                ContentValues incoming = "msg".equals(chain.getArg(0)) && chain.getArg(2) instanceof ContentValues
                        ? new ContentValues((ContentValues) chain.getArg(2)) : null;
                Object result = chain.proceed();
                try {
                    if (incoming != null && result instanceof Number && ((Number) result).longValue() >= 0) onIncoming(incoming);
                } catch (Throwable failure) { receive.fail(failure); }
                return result;
            });
            Method contextReady = HostTargets.method(report, "adv_im_conversations", loader);
            receive.add(framework, contextReady, chain -> {
                sdk = chain.getThisObject();
                return chain.proceed();
            });
            try {
                imProfileCache = HostTargets.method(report, "relation_im_user_cache", loader);
                imProfileGet = Class.forName("com.bytedance.im.core.model.cache.IMMemoryCache", false, loader)
                        .getMethod("get", Object.class);
            } catch (Exception ignored) { }
            log.accept("Native IM receive and sender contracts registered");
        } catch (Throwable failure) { receive.fail(failure); }
    }

    private void captureSdk(Object receiver) {
        Object value = HostTargets.read(receiver, "imSdkContext");
        if (value != null && sdkUid != null && sdkUid.getDeclaringClass().isInstance(value)) sdk = value;
    }

    private long ownUid() {
        try { return sdk == null || sdkUid == null ? 0 : ((Number) sdkUid.invoke(sdk)).longValue(); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return 0; }
    }

    private void markRecall(long account, String uuid) {
        String key = "recall." + account + "." + uuid;
        if (!state.getBoolean(key, false)) state.edit().putBoolean(key, true).apply();
    }

    private boolean automatic(String key, LocalOptions current) {
        return !closed && receive.isActive() && current.enabled(key)
                && ("native_auto_reply".equals(key) || current.enabled("automation_enabled"));
    }

    private void onIncoming(ContentValues values) {
        LocalOptions current = options;
        if (!automatic("native_auto_reply", current)) return;
        long account = ownUid();
        if (contactAccount != account) { clearContacts(); contactAccount = account; }
        String currentConversation = values.getAsString("conversation_id");
        Map<String, ProfileListEntry> members = groupMembers.get(currentConversation);
        Long senderUid;
        try { senderUid = values.getAsLong("sender"); }
        catch (RuntimeException ignored) { return; }
        if (members != null && senderUid != null && values.getAsString("sender_name") == null) {
            ProfileListEntry profile = members.get(Long.toString(senderUid));
            if (profile != null && !profile.name.isEmpty()) values.put("sender_name", profile.name);
        }
        if (senderUid != null && values.getAsString("sender_name") == null) {
            ProfileListEntry profile = memberProfile(senderUid.toString());
            if (!profile.name.isEmpty()) values.put("sender_name", profile.name);
        }
        if (!NativeFollowLists.ensureAccount(Long.toString(account))) return;
        ReplyPolicy.Incoming incoming = ReplyPolicy.incoming(values, account, startedAt, current);
        if (incoming == null) return;
        String conversation = incoming.conversation;
        String text = incoming.text;
        String identity = account + ":" + conversation + ":" + incoming.serverId;
        synchronized (seen) { if (seen.put(identity, true) != null) return; }
        long epoch = generation.get();
        int delay = Math.max(2, Math.min(3600, current.integer("reply_delay_seconds", 2)));
        if (worker.getQueue().size() >= 100) return;
        java.util.concurrent.ScheduledFuture<?> future = worker.schedule(() -> {
            if (generation.get() != epoch || ownUid() != account || !automatic("native_auto_reply", options)) return;
            if (!replyStillAllowed(values, account, current)) return;
            if (!conversationExists(conversation, incoming.group)) return;
            try {
                String modelName = "";
                AiModelSettings models = "ai".equals(current.text("reply_mode", "fixed")) ? AiModelSettings.read(current) : null;
                List<ChatImage> images = models == null ? Collections.emptyList() : AutomationImages.load(incoming.imageUrls);
                if (generation.get() != epoch || ownUid() != account || !automatic("native_auto_reply", options)) return;
                String secondary = current.text("reply_prompt", "自然简洁地回复对方，只输出回复正文。");
                String response;
                if (models != null) {
                    modelName = models.selected().modelName;
                    response = ai.reply("reply:" + account + ":" + conversation, text, images, models,
                            secondary, false);
                } else response = ReplyPolicy.reply(text, current, random);
                if (generation.get() != epoch || ownUid() != account || !automatic("native_auto_reply", options)) return;
                if (!replyStillAllowed(values, account, current)) return;
                if (response == null || response.trim().isEmpty()) return;
                String name = incoming.group ? groups.getOrDefault(conversation, conversation)
                        : contacts.getOrDefault(Long.toString(incoming.sender), Long.toString(incoming.sender));
                dispatcher.prepare(AutomationHistoryStore.REPLY, identity, conversation, name,
                        incoming.imageUrls.isEmpty() ? text : "[图片] " + text, response, modelName,
                        () -> generation.get() == epoch && ownUid() == account && automatic("native_auto_reply", options)
                                && replyStillAllowed(values, account, current),
                        done -> {
                            if (!replyStillAllowed(values, account, current)) { done.accept("cancelled"); return; }
                            sendText(conversation, response, account, epoch, incoming.group, success -> {
                                if (success && models != null) {
                                    try { ai.rememberReply("reply:" + account + ":" + conversation, text, images, models, secondary, response); }
                                    catch (Exception failure) { safeLog("Could not retain acknowledged reply context"); }
                                }
                                done.accept(success ? "sent" : "failed");
                            });
                        });
            } catch (Exception failure) {
                if (generation.get() == epoch && ownUid() == account && automatic("native_auto_reply", options)) {
                    dispatcher.reportFailure(AutomationHistoryStore.REPLY, identity, conversation,
                            Long.toString(incoming.sender), incoming.imageUrls.isEmpty() ? text : "[图片] " + text, "");
                }
                safeLog("Automatic reply failed: " + failure.getClass().getSimpleName());
            }
        }, delay, TimeUnit.SECONDS);
        synchronized (pendingReplies) {
            pendingReplies.removeIf(java.util.concurrent.Future::isDone);
            pendingReplies.add(future);
        }
    }

    private boolean conversationExists(String id, boolean group) {
        try {
            Object conversation = findConversation.invoke(sdkConversations.invoke(sdk), id);
            return conversation != null && Boolean.TRUE.equals(isSingle.invoke(conversation)) != group;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    private boolean replyStillAllowed(ContentValues values, long account, LocalOptions current) {
        return NativeFollowLists.ensureAccount(Long.toString(account))
                && ReplyPolicy.incoming(values, account, startedAt, current) != null;
    }

    private void sendText(String id, String text, long account, long epoch, boolean group,
                          Consumer<Boolean> done) throws Exception {
        sendText(id, text, account, epoch, group, done, () -> automatic("native_auto_reply", options));
    }

    private void sendText(String id, String text, long account, long epoch, boolean group,
                          Consumer<Boolean> done, BooleanSupplier beforeNativeSend) throws Exception {
        if (closed || !receive.isActive() || ownUid() != account || generation.get() != epoch
                || sdk == null || text == null || text.trim().isEmpty()
                || text.length() > 4000) { done.accept(false); return; }
        Object conversations = sdkConversations.invoke(sdk);
        Object conversation = findConversation.invoke(conversations, id);
        if (conversation == null || Boolean.TRUE.equals(isSingle.invoke(conversation)) == group) {
            done.accept(false); return;
        }
        Object builder = builderFactory.invoke(sdkBean.invoke(sdk));
        Object content = createText.invoke(null, text);
        Object gson = gsonClass.getConstructor().newInstance();
        String json = (String) gsonClass.getMethod("toJson", Object.class).invoke(gson, content);
        builderType.invoke(builder, 7);
        builderContent.invoke(builder, json);
        builderConversation.invoke(builder, conversation);
        Object message = builderBuild.invoke(builder);
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        Object callback = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "DouAiMessageCallback";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    }
                    if ("onSuccess".equals(method.getName()) && completed.compareAndSet(false, true)) done.accept(true);
                    if ("onFailure".equals(method.getName()) && completed.compareAndSet(false, true)) done.accept(false);
                    return null;
                });
        Object handler = senderConstructor.newInstance(sdk, callback);
        synchronized (sendLock) {
            if (generation.get() != epoch || ownUid() != account || !receive.isActive()
                    || closed) { done.accept(false); return; }
            if (!beforeNativeSend.getAsBoolean()) { done.accept(false); return; }
            send.invoke(handler, message, Collections.emptyMap());
        }
    }

    private void streakTick() {
        LocalOptions current = options;
        if (closed || !receive.isActive() || sdk == null || ownUid() <= 0) return;
        try {
            Object model = sdkConversations.invoke(sdk);
            Object raw = allConversations.invoke(model);
            if (!(raw instanceof List<?>)) return;
            Set<String> targets = ReplyPolicy.targets(current.text("streak_targets", ""));
            Map<String, String> discovered = new LinkedHashMap<>();
            Map<String, String> discoveredGroups = new LinkedHashMap<>();
            Map<String, ProfileListEntry> discoveredProfiles = new LinkedHashMap<>();
            Map<String, ProfileListEntry> discoveredGroupProfiles = new LinkedHashMap<>();
            Map<String, Map<String, ProfileListEntry>> discoveredGroupMembers = new LinkedHashMap<>();
            Map<String, ProfileListEntry> knownProfiles = new LinkedHashMap<>(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING));
            knownProfiles.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS));
            String day = LocalDate.now().toString();
            long self = ownUid();
            long epoch = generation.get();
            for (Object conversation : (List<?>) raw) {
                if (!Boolean.TRUE.equals(isSingle.invoke(conversation))) {
                    Object id = conversationId.invoke(conversation);
                    Object core = conversationCore.invoke(conversation);
                    Object name = core == null ? null : conversationName.invoke(core);
                    if (id instanceof String && !((String) id).isEmpty()) {
                        String groupName = name instanceof String && !((String) name).isEmpty() ? (String) name : (String) id;
                        discoveredGroups.put((String) id, groupName);
                        Object icon = HostTargets.invoke(core, "getIcon");
                        discoveredGroupProfiles.put((String) id, new ProfileListEntry((String) id, groupName,
                                icon instanceof String ? (String) icon : ""));
                        Map<String, ProfileListEntry> knownMembers = new LinkedHashMap<>();
                        Object rawMembers = HostTargets.invoke(conversation, "getMembers");
                        if (rawMembers instanceof Iterable<?>) for (Object member : (Iterable<?>) rawMembers) {
                            Object uid = HostTargets.invoke(member, "getUid");
                            if (!(uid instanceof Number)) uid = HostTargets.read(member, "uid");
                            if (!(uid instanceof Number) || ((Number) uid).longValue() <= 0) continue;
                            String memberUid = uid.toString();
                            ProfileListEntry profile = knownProfiles.get(memberUid);
                            if (profile == null) {
                                profile = memberProfile(memberUid, new ProfileListEntry(memberUid, "", ""));
                                knownProfiles.put(memberUid, profile);
                            }
                            Object alias = HostTargets.invoke(member, "getAlias");
                            if (!(alias instanceof String)) alias = HostTargets.read(member, "alias");
                            knownMembers.put(memberUid, new ProfileListEntry(memberUid,
                                    !profile.name.isEmpty() ? profile.name
                                            : alias instanceof String ? (String) alias : "",
                                    profile.avatarUrl));
                        }
                        discoveredGroupMembers.put((String) id, Collections.unmodifiableMap(knownMembers));
                    }
                    continue;
                }
                Object members = conversationMembers.invoke(conversation);
                if (!(members instanceof List<?>)) continue;
                String target = null;
                for (Object member : (List<?>) members) {
                    if (member instanceof Number && ((Number) member).longValue() != self) {
                        String value = member.toString();
                        Object core = conversationCore.invoke(conversation);
                        Object name = core == null ? null : conversationName.invoke(core);
                        discovered.put(value, name instanceof String && !((String) name).isEmpty() ? (String) name : value);
                        Object icon = HostTargets.invoke(core, "getIcon");
                        discoveredProfiles.put(value, new ProfileListEntry(value, discovered.get(value),
                                icon instanceof String ? (String) icon : memberProfile(value).avatarUrl));
                        if (targets.contains(value)) target = value;
                    }
                }
                if (target == null || !automatic("streak_enabled", current)
                        || !ReplyPolicy.inWindow(current.text("streak_start", ""), current.text("streak_end", ""), LocalTime.now())) continue;
                String key = "streak." + self + "." + day + "." + target;
                if (!streakLedger.canBegin(key) || !pendingStreak.add(key)) continue;
                String id = (String) conversationId.invoke(conversation);
                StreakSendLedger.Attempt[] attempt = new StreakSendLedger.Attempt[1];
                try {
                    String content = streakContent(current);
                    if (generation.get() != epoch || !automatic("streak_enabled", options)) { pendingStreak.remove(key); continue; }
                    boolean[] reachedPersistence = {false};
                    sendText(id, content, self, epoch, false, success -> {
                        StreakSendLedger.Attempt active = attempt[0];
                        if (active == null) {
                            pendingStreak.remove(key);
                            return;
                        }
                        boolean recorded = streakLedger.complete(key, active.token, success, System.currentTimeMillis());
                        if (recorded) {
                            pendingStreak.remove(key);
                            safeLog(success ? "Streak send acknowledged by native SDK" : "Streak native SDK send failed");
                            if (current.enabled("streak_tip")) toast(success ? "续火花已发送" : "续火花发送失败");
                        } else {
                            pendingStreak.remove(key);
                            safeLog("Could not persist streak send result; outcome remains unknown");
                        }
                    }, () -> {
                        if (!automatic("streak_enabled", options)) return false;
                        reachedPersistence[0] = true;
                        attempt[0] = streakLedger.begin(key, System.currentTimeMillis());
                        if (attempt[0] == null) return false;
                        String attemptToken = attempt[0].token;
                        worker.schedule(() -> {
                            if (streakLedger.markUnknown(key, attemptToken, System.currentTimeMillis())) {
                                pendingStreak.remove(key);
                                safeLog("Streak send callback timed out; outcome is unknown");
                            }
                        }, 30, TimeUnit.SECONDS);
                        return true;
                    });
                    if (attempt[0] == null && reachedPersistence[0]) {
                        pendingStreak.remove(key);
                        safeLog("Streak send prevented because its state could not be persisted");
                    }
                } catch (Exception failure) {
                    if (attempt[0] != null) {
                        streakLedger.markUnknown(key, attempt[0].token, System.currentTimeMillis());
                    }
                    pendingStreak.remove(key);
                    safeLog("Streak task failed: " + failure.getClass().getSimpleName());
                }
            }
            if (ownUid() == self) {
                contacts = Collections.unmodifiableMap(discovered);
                groups = Collections.unmodifiableMap(discoveredGroups);
                contactProfiles = Collections.unmodifiableMap(discoveredProfiles);
                groupProfiles = Collections.unmodifiableMap(discoveredGroupProfiles);
                groupMembers = Collections.unmodifiableMap(discoveredGroupMembers);
                contactAccount = self;
            }
        } catch (Exception failure) { safeLog("Could not enumerate native single chats: " + failure.getClass().getSimpleName()); }
    }

    private static String streakContent(LocalOptions options) throws Exception {
        String api = options.text("streak_content_api", "").trim();
        if (api.isEmpty()) return options.text("streak_content", "").trim();
        URI uri = URI.create(api);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("续火花内容接口必须使用 HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("内容接口不可用");
            try (java.io.InputStream input = connection.getInputStream()) {
                return ChatCompletionClient.readBounded(input, 16_000).trim();
            }
        } finally { connection.disconnect(); }
    }

    private void toast(String text) { main.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show()); }
    private void safeLog(String message) { try { log.accept(message); } catch (Throwable ignored) { } }

    @Override public void close() {
        synchronized (sendLock) {
            closed = true;
            generation.incrementAndGet();
        }
        worker.shutdownNow();
        ai.cancelCurrentRequest();
        dispatcher.cancelKind(AutomationHistoryStore.REPLY);
        if (ownsDispatcher) dispatcher.close();
        receive.close();
        recall.close();
        sdk = null;
        clearContacts();
        contactAccount = 0;
    }

    private static void clearContacts() {
        contacts = Collections.emptyMap(); groups = Collections.emptyMap();
        contactProfiles = Collections.emptyMap(); groupProfiles = Collections.emptyMap(); groupMembers = Collections.emptyMap();
    }

    private ProfileListEntry memberProfile(String uid) {
        return memberProfile(uid, NativeFollowLists.findMember(uid));
    }

    private ProfileListEntry memberProfile(String uid, ProfileListEntry fallback) {
        try {
            if (imProfileCache == null || imProfileGet == null) return fallback;
            Object user = imProfileGet.invoke(imProfileCache.invoke(null), uid);
            if (user == null || !uid.equals(HostTargets.invoke(user, "getUid"))) return fallback;
            Object name = HostTargets.invoke(user, "getNickname");
            String avatar = ProfileListEntry.avatar(user);
            return new ProfileListEntry(uid, name instanceof String && !((String) name).isEmpty() ? (String) name : fallback.name,
                    !avatar.isEmpty() ? avatar : fallback.avatarUrl);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return fallback; }
    }
}

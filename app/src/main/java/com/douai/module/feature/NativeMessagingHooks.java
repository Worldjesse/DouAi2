package com.douai.module.feature;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Observes committed incoming messages and submits opt-in tasks through the native IM SDK. */
public final class NativeMessagingHooks implements AutoCloseable {
    private final Context context;
    private final SharedPreferences state;
    private final StreakSendLedger streakLedger;
    private final AutomationRateLimiter rateLimiter;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final ScheduledThreadPoolExecutor streakWorker = new ScheduledThreadPoolExecutor(1);
    private static final long REPLY_GENERATION_TIMEOUT_MILLIS = 120_000;
    private final Object replyLock = new Object();
    private ReplyTask activeReply;
    private final AtomicLong generation = new AtomicLong();
    private final Object sendLock = new Object();
    private static volatile Map<String, String> contacts = Collections.emptyMap();
    private static volatile Map<String, String> groups = Collections.emptyMap();
    private static volatile Map<String, ProfileListEntry> contactProfiles = Collections.emptyMap();
    private static volatile Map<String, ProfileListEntry> groupProfiles = Collections.emptyMap();
    private static volatile Map<String, Map<String, ProfileListEntry>> groupMembers = Collections.emptyMap();
    private static volatile long contactAccount;
    private final ChatCompletionClient ai = new ChatCompletionClient();
    SpeechPreparation.Preparer speechPreparer = SpeechPreparation::prepare;
    Supplier<MediaReadCancellation> mediaReads = MediaReadCancellation::new;
    private NativeAudioMessageSender audioSender;
    private final AutomationDispatcher dispatcher;
    private final boolean ownsDispatcher;
    private final Random random = new Random();
    private final Set<String> pendingStreak = Collections.synchronizedSet(new HashSet<>());
    private final Set<ReplyTask> pendingReplies = Collections.synchronizedSet(new HashSet<>());
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
    private Method findConversationAsync;
    private IncomingMessageSnapshot incomingSnapshot;
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
        rateLimiter = new AutomationRateLimiter(context);
        resetToken = state.getString("streak.reset_token", "");
        recall = new BehaviorHookGroup("prevent_message_recall", this.log);
        receive = new BehaviorHookGroup("native_reply", this.log);
        worker.setRemoveOnCancelPolicy(true);
        streakWorker.setRemoveOnCancelPolicy(true);
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
        hook.streakWorker.scheduleWithFixedDelay(hook::streakTick, 5, 30, TimeUnit.SECONDS);
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
        boolean invalidate;
        synchronized (sendLock) {
            invalidate = automationOptionsChanged(options, value);
            boolean mediaChanged = options.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true)
                    != value.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true)
                    || options.enabled(LocalOptions.REPLY_RECOGNIZE_VIDEO, false)
                    != value.enabled(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
            options = value;
            if (invalidate) generation.incrementAndGet();
            if (mediaChanged) ai.clearHistory();
        }
        if (invalidate) {
            cancelReplies();
            dispatcher.cancelKind(AutomationHistoryStore.REPLY);
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
        if (!previous.samePrefix(next, "tts_")) return true;
        for (String key : new String[]{"native_auto_reply", "reply_global", "reply_targets",
                "reply_mode", "reply_voice", "reply_prompt", "reply_delay_seconds", "reply_daily_limit",
                LocalOptions.REPLY_RECOGNIZE_IMAGES, LocalOptions.REPLY_RECOGNIZE_VIDEO,
                "reply_user_cooldown_minutes",
                "auto_message_text", "reply_keywords",
                "reply_scope_mode", "reply_scope_relations", "reply_scope_fields", "reply_scope_keywords",
                "reply_filter_mode", "reply_filter_keywords", "reply_filter_fields",
                "reply_private_chats", "reply_group_chats",
                "reply_group_scope_mode", "reply_group_targets", "reply_group_mode", "reply_group_members",
                "reply_random", AiModelSettings.KEY, "streak_enabled", "streak_targets", "streak_content",
                "streak_content_api", "streak_start", "streak_end"}) {
            boolean defaultEnabled = "reply_private_chats".equals(key) || LocalOptions.REPLY_RECOGNIZE_IMAGES.equals(key);
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
            findConversationAsync = HostTargets.method(report, "adv_im_conversation_async", loader);
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
            incomingSnapshot = new IncomingMessageSnapshot(builderBuild.getReturnType());
            try { audioSender = new NativeAudioMessageSender(framework, report, loader); }
            catch (Exception failure) { safeLog("Native automatic audio sender unavailable: " + failure.getClass().getSimpleName()); }
            Method insert = HostTargets.method(report, "adv_im_db_insert", loader);
            receive.add(framework, insert, chain -> {
                captureSdk(chain.getThisObject());
                ContentValues incoming = "msg".equals(chain.getArg(0)) && chain.getArg(2) instanceof ContentValues
                        ? new ContentValues((ContentValues) chain.getArg(2)) : null;
                Object result = chain.proceed();
                try {
                    if (incoming != null && result instanceof Number && ((Number) result).longValue() >= 0) onIncoming(incoming, "database");
                } catch (Throwable failure) { receive.fail(failure); }
                return result;
            });
            Method contextReady = HostTargets.method(report, "adv_im_conversations", loader);
            receive.add(framework, contextReady, chain -> {
                sdk = chain.getThisObject();
                return chain.proceed();
            });
            Method globalReceived = HostTargets.method(report, "adv_im_global_received", loader);
            receive.add(framework, globalReceived, chain -> {
                captureSdk(chain.getThisObject());
                Object raw = chain.getArg(0);
                List<?> messages = raw instanceof List<?> ? new ArrayList<>((List<?>) raw) : Collections.emptyList();
                Object result = chain.proceed();
                captureSdk(chain.getThisObject());
                for (Object message : messages) {
                    try {
                        ContentValues values = incomingSnapshot.copy(message);
                        if (values != null) onIncoming(values, "global");
                    } catch (ReflectiveOperationException | RuntimeException invalid) {
                        safeLog("Automatic reply receipt skipped source=global reason=invalid_message");
                    }
                }
                return result;
            });
            try {
                imProfileCache = HostTargets.method(report, "relation_im_user_cache", loader);
                imProfileGet = Class.forName("com.bytedance.im.core.model.cache.IMMemoryCache", false, loader)
                        .getMethod("get", Object.class);
            } catch (Exception ignored) { }
            log.accept("Native IM receive and sender contracts registered; global receipts and cold conversations enabled");
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

    private void onIncoming(ContentValues values, String source) {
        LocalOptions current = options;
        if (!automatic("native_auto_reply", current)) return;
        long account = ownUid();
        if (account <= 0) {
            safeLog("Automatic reply receipt skipped source=" + source + " reason=account_unavailable");
            return;
        }
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
        if (!NativeFollowLists.ensureAccount(Long.toString(account))) {
            safeLog("Automatic reply receipt skipped source=" + source + " reason=account_changed");
            return;
        }
        ReplyPolicy.Incoming incoming = ReplyPolicy.incoming(values, account, startedAt, current);
        if (incoming == null) {
            safeLog("Automatic reply receipt skipped source=" + source + " reason=policy");
            return;
        }
        String recipient = Long.toString(incoming.sender);
        if (!rateLimiter.canSend(AutomationHistoryStore.REPLY, account, recipient, current)) {
            safeLog("Automatic reply receipt skipped source=" + source + " reason=allowance");
            return;
        }
        String conversation = incoming.conversation;
        String text = incoming.text;
        String identity = account + ":" + conversation + ":" + incoming.serverId;
        synchronized (seen) {
            if (seen.put(identity, true) != null) {
                safeLog("Automatic reply receipt skipped source=" + source + " reason=duplicate");
                return;
            }
        }
        long epoch = generation.get();
        int delay = Math.max(2, Math.min(3600, current.integer("reply_delay_seconds", 2)));
        if (worker.getQueue().size() >= 100) {
            synchronized (seen) { seen.remove(identity); }
            safeLog("Automatic reply receipt skipped source=" + source + " reason=queue_full");
            return;
        }
        ReplyTask operation = new ReplyTask(account, epoch, identity, incoming, delay);
        pendingReplies.add(operation);
        safeLog("Automatic reply receipt queued source=" + source + " kind=" + (incoming.group ? "group" : "private"));
        main.postDelayed(operation.watchdog, 1000);
        try {
            operation.attach(worker.schedule(() -> {
            if (!operation.begin()) { operation.release(); return; }
            try {
                if (!replyStillAllowed(values, account, current)) return;
                if (!rateLimiter.canSend(AutomationHistoryStore.REPLY, account, recipient, options)) return;
                operation.conversation = resolveReplyConversation(operation);
                if (operation.conversation == null) {
                    synchronized (seen) { seen.remove(identity); }
                    safeLog("Automatic reply skipped reason=conversation_unavailable");
                    return;
                }
                if (!operation.current()) return;
                String modelName = "";
                AiModelSettings models = "ai".equals(current.text("reply_mode", "fixed")) ? AiModelSettings.read(current) : null;
                List<ChatImage> images = models == null ? Collections.emptyList()
                        : AutomationReplyMedia.loadIncoming(context, incoming.imageUrls, incoming.videoUrl,
                                current, operation.mediaCancellation);
                if (!operation.current()) return;
                String secondary = current.text("reply_prompt", "自然简洁地回复对方，只输出回复正文。");
                String response;
                if (models != null) {
                    modelName = models.selected().modelName;
                    operation.modelName = modelName;
                    response = ai.reply("reply:" + account + ":" + conversation, text, images, models,
                            secondary, false, "", imageCount -> {
                                operation.requestImageCount = imageCount;
                                operation.modelRequestSent = true;
                            });
                } else response = ReplyPolicy.reply(text, current, random);
                if (!operation.current()) return;
                if (!replyStillAllowed(values, account, current)) return;
                if (response == null || response.trim().isEmpty()) return;
                String name = incoming.group ? groups.getOrDefault(conversation, conversation)
                        : contacts.getOrDefault(Long.toString(incoming.sender), Long.toString(incoming.sender));
                BooleanSupplier valid = () -> operation.current() && replyStillAllowed(values, account, current)
                        && conversationExists(conversation, incoming.group, operation.conversation)
                        && rateLimiter.canSend(AutomationHistoryStore.REPLY, account, recipient, options);
                SpeechPreparation.Audio audio;
                if (current.enabled("reply_voice")) {
                    if (audioSender == null) throw new IllegalStateException("Native audio sender unavailable");
                    audio = speechPreparer.prepare(context, response, current, 120_000, valid);
                    if (!valid.getAsBoolean()) { audio.delete(); return; }
                } else audio = null;
                if (!operation.handoff()) { if (audio != null) audio.delete(); return; }
                java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
                dispatcher.prepare(new AutomationHistoryStore.Entry(identity, AutomationHistoryStore.REPLY, conversation, name,
                        input(incoming), response, modelName, "pending", operation.createdAt, System.currentTimeMillis(),
                        operation.requestImageCount, operation.modelRequestSent,
                        audio == null ? AutomationHistoryStore.DELIVERY_TEXT : AutomationHistoryStore.DELIVERY_VOICE),
                        valid,
                        done -> {
                            if (!valid.getAsBoolean()) { done.accept("cancelled"); return; }
                            AtomicReference<AutomationRateLimiter.Reservation> reservation = new AtomicReference<>();
                            try {
                                Consumer<Boolean> acknowledged = success -> {
                                    AutomationRateLimiter.Reservation active = reservation.get();
                                    if (!success && active != null) rateLimiter.release(active);
                                    if (success && models != null) {
                                        synchronized (sendLock) {
                                            // Selection changes clear history under this same lock. An older send's late
                                            // acknowledgment must not put its images back into the current context.
                                            if (!closed && generation.get() == epoch && ownUid() == account
                                                    && !automationOptionsChanged(current, options)) {
                                                try { ai.rememberReply("reply:" + account + ":" + conversation, text, images, models, secondary, response); }
                                                catch (Exception failure) { safeLog("Could not retain acknowledged reply context"); }
                                            }
                                        }
                                    }
                                    done.accept(success ? "sent" : active == null ? "cancelled" : "failed");
                                };
                                BooleanSupplier reserve = () -> {
                                    if (!operation.current()
                                            || !replyStillAllowed(values, account, options)) return false;
                                    reservation.set(rateLimiter.tryReserve(AutomationHistoryStore.REPLY,
                                            account, recipient, options));
                                    return reservation.get() != null;
                                };
                                if (audio == null) sendText(conversation, response, account, epoch,
                                        incoming.group, acknowledged, reserve, operation.conversation);
                                else sendAudio(conversation, audio, account, epoch, incoming.group,
                                        acknowledged, reserve, submitted, operation.conversation);
                            } catch (IllegalAccessException | IllegalArgumentException failure) {
                                AutomationRateLimiter.Reservation active = reservation.get();
                                if (active != null) rateLimiter.release(active);
                                throw failure;
                            }
                        }, outcome -> { if (audio != null && !submitted.get()) audio.delete(); });
            } catch (Exception failure) {
                if (operation.current()) operation.reportFailure();
                safeLog("Automatic reply failed: " + failure.getClass().getSimpleName());
            } finally {
                operation.release();
            }
            }, delay, TimeUnit.SECONDS));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            operation.cancel(false);
        }
    }

    private void cancelReplies() {
        List<ReplyTask> tasks;
        synchronized (pendingReplies) { tasks = new ArrayList<>(pendingReplies); }
        for (ReplyTask task : tasks) if (closed || task.epoch != generation.get()) task.cancel(false);
    }

    /** Owns cancellation until generation hands the result to the existing preview/send lifecycle. */
    private final class ReplyTask {
        final long account;
        final long epoch;
        final String identity;
        final ReplyPolicy.Incoming incoming;
        final Object sdkContext;
        volatile Object conversation;
        final long createdAt = System.currentTimeMillis();
        final MediaReadCancellation mediaCancellation = mediaReads.get();
        final Runnable watchdog = this::watch;
        final java.util.concurrent.atomic.AtomicBoolean failureReported = new java.util.concurrent.atomic.AtomicBoolean();
        volatile java.util.concurrent.Future<?> future;
        volatile boolean cancelled;
        volatile boolean finished;
        volatile long deadline;
        volatile int requestImageCount = -1;
        volatile boolean modelRequestSent;
        volatile String modelName = "";

        ReplyTask(long account, long epoch, String identity, ReplyPolicy.Incoming incoming, int delay) {
            this.account = account;
            this.epoch = epoch;
            this.identity = identity;
            this.incoming = incoming;
            sdkContext = sdk;
            deadline = SystemClock.elapsedRealtime() + delay * 1000L + REPLY_GENERATION_TIMEOUT_MILLIS;
        }

        void attach(java.util.concurrent.Future<?> value) {
            future = value;
            if (cancelled) value.cancel(true);
        }

        boolean current() {
            return !cancelled && sdk == sdkContext && generation.get() == epoch && ownUid() == account
                    && automatic("native_auto_reply", options)
                    && NativeFollowLists.ensureAccount(Long.toString(account));
        }

        boolean begin() {
            if (SystemClock.elapsedRealtime() >= deadline) { cancel(true); return false; }
            synchronized (replyLock) {
                if (!current()) return false;
                deadline = Math.min(deadline, SystemClock.elapsedRealtime() + REPLY_GENERATION_TIMEOUT_MILLIS);
                activeReply = this;
                return true;
            }
        }

        boolean handoff() {
            boolean expired;
            synchronized (this) {
                if (!current()) return false;
                expired = SystemClock.elapsedRealtime() >= deadline;
                // The preview/native sender now owns completion; generation timeout cannot relabel a send.
                if (!expired) finished = true;
            }
            if (expired) { cancel(true); return false; }
            release();
            return true;
        }

        void watch() {
            if (finished) return;
            if (!current()) { cancel(false); return; }
            if (SystemClock.elapsedRealtime() >= deadline) { cancel(true); return; }
            main.postDelayed(watchdog, 1000);
        }

        void cancel(boolean timeout) {
            synchronized (this) {
                if (finished) return;
                cancelled = true;
                finished = true;
            }
            mediaCancellation.cancel();
            synchronized (replyLock) {
                // An old task must never disconnect the next task's AI request.
                if (activeReply == this) ai.cancelCurrentRequest();
                java.util.concurrent.Future<?> value = future;
                if (value != null) value.cancel(true);
            }
            main.removeCallbacks(watchdog);
            pendingReplies.remove(this);
            if (timeout && generation.get() == epoch && ownUid() == account
                    && automatic("native_auto_reply", options)) {
                reportFailure();
                safeLog("Automatic reply generation deadline reached");
            }
        }

        void release() {
            synchronized (this) { finished = true; }
            synchronized (replyLock) { if (activeReply == this) activeReply = null; }
            main.removeCallbacks(watchdog);
            pendingReplies.remove(this);
        }

        void reportFailure() {
            if (!failureReported.compareAndSet(false, true)) return;
            dispatcher.reportFinalFailure(new AutomationHistoryStore.Entry(identity, AutomationHistoryStore.REPLY,
                    incoming.conversation, Long.toString(incoming.sender), input(incoming), "", modelName,
                    "failed", createdAt, System.currentTimeMillis(), requestImageCount, modelRequestSent));
        }
    }

    private static String input(ReplyPolicy.Incoming incoming) {
        return (!incoming.videoUrl.isEmpty() ? "[视频] " : incoming.imageUrls.isEmpty() ? "" : "[图片] ") + incoming.text;
    }

    private boolean conversationExists(String id, boolean group) {
        return conversationExists(id, group, null);
    }

    private boolean conversationExists(String id, boolean group, Object resolved) {
        try {
            return conversationForSend(sdk, id, group, resolved) != null;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    private boolean matchesConversation(Object conversation, String id, boolean group) throws ReflectiveOperationException {
        return conversation != null && conversationId.getDeclaringClass().isInstance(conversation)
                && id.equals(conversationId.invoke(conversation))
                && Boolean.TRUE.equals(isSingle.invoke(conversation)) != group;
    }

    private Object conversationForSend(Object sdkContext, String id, boolean group, Object resolved)
            throws ReflectiveOperationException {
        Object cached = findConversation.invoke(sdkConversations.invoke(sdkContext), id);
        // The fallback belongs to one reply task and was read by the SDK from local storage.
        // Never manufacture a Conversation or retain a second permanent conversation cache.
        Object candidate = cached != null ? cached : resolved;
        return matchesConversation(candidate, id, group) ? candidate : null;
    }

    private Object resolveReplyConversation(ReplyTask operation) throws InterruptedException {
        try {
            String id = operation.incoming.conversation;
            Object model = sdkConversations.invoke(operation.sdkContext);
            Object cached = findConversation.invoke(model, id);
            if (cached != null) return matchesConversation(cached, id, operation.incoming.group) ? cached : null;
            if (!operation.current()) return null;
            safeLog("Automatic reply conversation lookup started reason=cache_miss");
            AtomicReference<Object> loaded = new AtomicReference<>();
            java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
            Object callback = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("toString".equals(method.getName())) return "DouAiConversationLookup";
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                        }
                        if (("onSuccess".equals(method.getName()) || "onFailure".equals(method.getName()))
                                && terminal.compareAndSet(false, true)) {
                            if ("onSuccess".equals(method.getName()) && arguments != null && arguments.length > 0)
                                loaded.set(arguments[0]);
                            completed.countDown();
                        }
                        return null;
                    });
            findConversationAsync.invoke(model, id, callback);
            // This is the reply worker, never the SDK/UI receipt thread. A late callback
            // only completes these local holders and cannot send or restore a cancelled task.
            if (!completed.await(10, TimeUnit.SECONDS) || !operation.current()) return null;
            Object value = loaded.get();
            boolean ready = matchesConversation(value, id, operation.incoming.group);
            safeLog("Automatic reply conversation lookup finished ready=" + ready);
            return ready ? value : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            safeLog("Automatic reply conversation lookup unavailable: " + unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private boolean replyStillAllowed(ContentValues values, long account, LocalOptions current) {
        return NativeFollowLists.ensureAccount(Long.toString(account))
                && ReplyPolicy.incoming(values, account, startedAt, current) != null;
    }

    private void sendText(String id, String text, long account, long epoch, boolean group,
                          Consumer<Boolean> done, BooleanSupplier beforeNativeSend) throws Exception {
        sendText(id, text, account, epoch, group, done, beforeNativeSend, null);
    }

    private void sendText(String id, String text, long account, long epoch, boolean group,
                          Consumer<Boolean> done, BooleanSupplier beforeNativeSend, Object resolved) throws Exception {
        if (closed || !receive.isActive() || ownUid() != account || generation.get() != epoch
                || sdk == null || text == null || text.trim().isEmpty()
                || text.length() > 4000) { done.accept(false); return; }
        Object activeSdk = sdk;
        Object conversation = conversationForSend(activeSdk, id, group, resolved);
        if (conversation == null) {
            done.accept(false); return;
        }
        Object builder = builderFactory.invoke(sdkBean.invoke(activeSdk));
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
        Object handler = senderConstructor.newInstance(activeSdk, callback);
        synchronized (sendLock) {
            if (generation.get() != epoch || ownUid() != account || !receive.isActive()
                    || closed || sdk != activeSdk) { done.accept(false); return; }
            if (!beforeNativeSend.getAsBoolean()) { done.accept(false); return; }
            send.invoke(handler, message, Collections.emptyMap());
        }
    }

    private void sendAudio(String id, SpeechPreparation.Audio audio, long account, long epoch, boolean group,
                           Consumer<Boolean> done, BooleanSupplier beforeNativeSend,
                           java.util.concurrent.atomic.AtomicBoolean submitted) throws Exception {
        sendAudio(id, audio, account, epoch, group, done, beforeNativeSend, submitted, null);
    }

    private void sendAudio(String id, SpeechPreparation.Audio audio, long account, long epoch, boolean group,
                           Consumer<Boolean> done, BooleanSupplier beforeNativeSend,
                           java.util.concurrent.atomic.AtomicBoolean submitted, Object resolved) throws Exception {
        NativeAudioMessageSender sender = audioSender;
        if (closed || !receive.isActive() || sender == null || ownUid() != account
                || generation.get() != epoch || sdk == null) { done.accept(false); return; }
        Object activeSdk = sdk;
        Object conversation = conversationForSend(activeSdk, id, group, resolved);
        if (conversation == null) {
            done.accept(false); return;
        }
        NativeAudioMessageSender.Content content = sender.content(audio);
        Object builder = builderFactory.invoke(sdkBean.invoke(activeSdk));
        Object gson = gsonClass.getConstructor().newInstance();
        String json = (String) gsonClass.getMethod("toJson", Object.class).invoke(gson, content.value);
        builderType.invoke(builder, content.type);
        builderContent.invoke(builder, json);
        builderConversation.invoke(builder, conversation);
        Object message = builderBuild.invoke(builder);
        sender.attach(message, audio);
        synchronized (sendLock) {
            if (generation.get() != epoch || ownUid() != account || !receive.isActive() || closed || sdk != activeSdk) {
                done.accept(false); return;
            }
            if (!beforeNativeSend.getAsBoolean()) { done.accept(false); return; }
            submitted.set(true);
            sender.submit(message, content, audio, done,
                    () -> !closed && receive.isActive() && generation.get() == epoch && ownUid() == account
                            && sdk == activeSdk && automatic("native_auto_reply", options) && conversationExists(id, group, resolved));
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
                        streakWorker.schedule(() -> {
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
        cancelReplies();
        worker.shutdownNow();
        streakWorker.shutdownNow();
        dispatcher.cancelKind(AutomationHistoryStore.REPLY);
        if (ownsDispatcher) dispatcher.close();
        receive.close();
        recall.close();
        if (audioSender != null) audioSender.close();
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

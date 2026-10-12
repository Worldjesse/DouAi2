package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Queues original works and publishes through the host's permission checks. */
public final class AutoCommentHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private final Context context;
    private final Application application;
    private final ClassLoader loader;
    private final AutomationDispatcher dispatcher;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final ChatCompletionClient ai = new ChatCompletionClient(null,
            message -> safeLog("Automatic comment AI " + message));
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<String, String> inFlight = new java.util.HashMap<>();
    private final LinkedHashMap<String, Task> queuedTasks = new LinkedHashMap<>();
    private volatile Task runningTask;
    private final AtomicLong generation = new AtomicLong();
    private final AutoCommentQueue<Task> queue;
    private final NativeCommentPublisher publisher;
    private final AutoCloseable followListUpdates;
    private final Field holderRoot;
    private final Method holderAweme;
    private final Field imageRoot;
    private final Method imageAweme;
    private volatile LocalOptions options;
    private volatile boolean closed;
    private WeakReference<Object> holder = new WeakReference<>(null);
    private WeakReference<View> root = new WeakReference<>(null);

    private AutoCommentHook(Context context, ClassLoader loader, LocalOptions options,
                            AutomationDispatcher dispatcher, Field holderRoot, Method holderAweme,
                            Field imageRoot, Method imageAweme, NativeCommentPublisher publisher, Consumer<String> log) {
        this.context = context.getApplicationContext();
        application = (Application) this.context;
        this.loader = loader;
        this.options = options;
        this.dispatcher = dispatcher;
        this.holderRoot = holderRoot;
        this.holderAweme = holderAweme;
        this.imageRoot = imageRoot;
        this.imageAweme = imageAweme;
        this.publisher = publisher;
        this.log = log;
        queue = new AutoCommentQueue<>(new AutoCommentQueue.Scheduler() {
            @Override public void post(Runnable action) {
                if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
            }
            @Override public void later(Runnable action, long delayMillis) { main.postDelayed(action, delayMillis); }
        }, this::attempt, this::finished, this::queueChanged);
        worker.setRemoveOnCancelPolicy(true);
        application.registerActivityLifecycleCallbacks(this);
        followListUpdates = NativeFollowLists.listen(() -> {
            Object selected = holder.get();
            if (!closed && selected != null) selected(selected);
        });
    }

    public static AutoCommentHook install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, Context context, LocalOptions options,
                                          AutomationDispatcher dispatcher, Consumer<String> log) throws Exception {
        Consumer<String> logger = log == null ? ignored -> { } : log;
        Method selected = HostTargets.method(report, "feed_holder_selected", loader);
        Method imageSelected = HostTargets.method(report, "image_holder_page_selected", loader);
        Field root = HostTargets.field(selected.getDeclaringClass(), "z");
        Field photoRoot = HostTargets.field(imageSelected.getDeclaringClass(), "f");
        Method aweme = selected.getDeclaringClass().getMethod("getAweme");
        Method photoAweme = imageSelected.getDeclaringClass().getMethod("getAweme");
        if (!View.class.isAssignableFrom(root.getType()) || !View.class.isAssignableFrom(photoRoot.getType())
                || aweme.getReturnType() != photoAweme.getReturnType()) throw new IllegalStateException("Comment holder contract changed");
        NativeCommentPublisher publisher = NativeCommentPublisher.install(framework, report, loader, logger);
        AutoCommentHook hook = new AutoCommentHook(context, loader, options, dispatcher, root, aweme,
                photoRoot, photoAweme, publisher, logger);
        try {
            for (Method target : new Method[]{selected, imageSelected}) {
                XposedInterface.HookHandle handle = framework.hook(target).setId("DouAi.AutoComment." + target.getName())
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object result = chain.proceed();
                            Object owner = chain.getThisObject();
                            hook.main.post(() -> hook.selected(owner));
                            return result;
                        });
                if (handle == null) throw new IllegalStateException("Empty automatic comment hook");
                hook.handles.add(handle);
            }
            if (report.getResults().containsKey("component_player_playing")) {
                Method playing = HostTargets.method(report, "component_player_playing", loader);
                XposedInterface.HookHandle handle = framework.hook(playing).setId("DouAi.AutoComment.Playing")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object result = chain.proceed();
                            Object argument = chain.getArg(0);
                            hook.main.post(() -> {
                                Object current = hook.holder.get();
                                if (current == null || !(argument instanceof String)) return;
                                try {
                                    boolean video = hook.holderAweme.getDeclaringClass().isInstance(current);
                                    Object work = (video ? hook.holderAweme : hook.imageAweme).invoke(current);
                                    if (argument.equals(HostTargets.invoke(work, "getAid"))) hook.selected(current);
                                } catch (ReflectiveOperationException ignored) { }
                            });
                            return result;
                        });
                if (handle == null) throw new IllegalStateException("Empty comment playback hook");
                hook.handles.add(handle);
            }
            return hook;
        } catch (Exception failure) { hook.close(); throw failure; }
    }

    public void setOptions(LocalOptions next) {
        LocalOptions before = options;
        options = next;
        for (String key : new String[]{"automation_enabled", "native_auto_comment", "comment_mode", "comment_prompt",
                "comment_delay_seconds", "auto_comment_text", AutomationCommentMedia.RECOGNIZE_IMAGES,
                AutomationCommentMedia.RECOGNIZE_VIDEO, "comment_scope_mode", "comment_scope_relations",
                "comment_scope_fields", "comment_scope_keywords", "comment_scope_users", "comment_filter_mode",
                "comment_filter_keywords", "comment_filter_fields", AiModelSettings.KEY}) {
            boolean defaultEnabled = AutomationCommentMedia.RECOGNIZE_IMAGES.equals(key);
            if (before.enabled(key, defaultEnabled) != next.enabled(key, defaultEnabled)
                    || !before.text(key, "").equals(next.text(key, ""))
                    || "comment_delay_seconds".equals(key) && before.integer(key, 3) != next.integer(key, 3)) {
                generation.incrementAndGet();
                ai.cancelCurrentRequest();
                worker.getQueue().clear();
                queue.reset();
                dispatcher.cancelKind(AutomationHistoryStore.COMMENT);
                main.post(() -> { Object selected = holder.get(); if (!closed && selected != null) selected(selected); });
                safeLog("Automatic comment configuration changed; queue reset");
                break;
            }
        }
    }

    private boolean enabled() { return !closed && options.enabled("automation_enabled") && options.enabled("native_auto_comment"); }
    public boolean ownsPublish(Object model) { return publisher.ownsPublish(model); }

    private void selected(Object selected) {
        if (closed) return;
        try {
            boolean video = holderAweme.getDeclaringClass().isInstance(selected);
            Object work = (video ? holderAweme : imageAweme).invoke(selected);
            Object author = modelValue(work, "author", "getAuthor");
            AutomationScopePolicy.rememberUser(author);
            String aid = string(HostTargets.invoke(work, "getAid"));
            holder = new WeakReference<>(selected);
            Object view = (video ? holderRoot : imageRoot).get(selected);
            View selectedRoot = view instanceof View ? (View) view : null;
            root = new WeakReference<>(selectedRoot);
            if (!enabled()) return;
            long account = ownAccount();
            if (!NativeFollowLists.ensureAccount(Long.toString(account))) return;
            Activity activity = activity(selectedRoot);
            if (aid.isEmpty() || account <= 0 || selectedRoot == null || !selectedRoot.isAttachedToWindow()
                    || !selectedRoot.isShown() || activity == null || !dispatcher.isForeground(activity)
                    || Boolean.TRUE.equals(HostTargets.invoke(work, "getIsAdAweme"))) {
                safeLog("Automatic comment selection skipped: target/account/foreground unavailable"); return;
            }
            String identity = "comment:" + account + ':' + aid;
            if (context.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE).contains(identity)) return;
            Task task = new Task(generation.get(), account, aid, identity, work, workInput(work),
                    workAuthor(work), options);
            String authorId = string(modelValue(author, "uid", "getUid"));
            if (!ContentScopePolicy.allows("comment", options, authorId, task.name,
                    workDescription(work), workTitle(work), AutomationScopePolicy.fromUser(author))) {
                safeLog("Automatic comment skipped by author scope");
                return;
            }
            synchronized (queuedTasks) {
                if (!queuedTasks.containsKey(identity)) queuedTasks.put(identity, task);
            }
            queue.offer(identity, task, Math.max(2, Math.min(3600, options.integer("comment_delay_seconds", 3))) * 1000L);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Automatic comment selection unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private boolean valid(Task task) {
        long account = ownAccount();
        boolean accountCurrent = NativeFollowLists.ensureAccount(Long.toString(account));
        Object author = modelValue(task.work, "author", "getAuthor");
        return accountCurrent && enabled() && !task.cancelled && task.epoch == generation.get() && task.account == account
                && task.aid.equals(HostTargets.invoke(task.work, "getAid"))
                && ContentScopePolicy.allows("comment", options,
                string(modelValue(author, "uid", "getUid")), workAuthor(task.work),
                workDescription(task.work), workTitle(task.work), AutomationScopePolicy.fromUser(author));
    }

    private void attempt(Task task, int attempt, Consumer<String> result) {
        if (!valid(task)) { result.accept("cancelled"); return; }
        safeLog("Automatic comment attempt=" + attempt + "/3 stage=" + (task.approved ? "publish" : "generate"));
        if (task.approved) { prepare(task, result); return; }
        dispatcher.commentStage(task.id, "generating");
        worker.execute(() -> {
            runningTask = task;
            try {
                if (task.text == null) {
                    if ("ai".equals(task.options.text("comment_mode", "ai"))) {
                        AiModelSettings models = AiModelSettings.read(task.options);
                        task.model = models.selected().modelName;
                        List<ChatImage> images = AutomationCommentMedia.load(context, task.work, task.options);
                        if (!valid(task)) { result.accept("cancelled"); return; }
                        if (!hasWorkContent(task.work) && images.isEmpty()) {
                            safeLog("Automatic comment skipped: captured work has no usable content");
                            result.accept("cancelled");
                            return;
                        }
                        safeLog("Automatic comment model request started; images=" + images.size());
                        // Comments are independent tasks, even if the model has conversation rounds configured.
                        ai.clearHistory();
                        String output = ai.reply(task.identity, task.input, images, models,
                                CommentOutputPolicy.task(task.options.text("comment_prompt",
                                        "根据当前作品自然地写一条评论。")), false);
                        String comment = CommentOutputPolicy.parse(output);
                        if (comment.isEmpty()) {
                            safeLog("Automatic comment skipped: model returned an empty comment");
                            result.accept("cancelled");
                            return;
                        }
                        task.text = comment;
                    } else task.text = task.options.text("auto_comment_text", "").trim();
                }
                if (!valid(task)) { result.accept("cancelled"); return; }
                if (task.text.isEmpty() || task.text.length() > 1000) {
                    task.text = null; throw new IllegalArgumentException("Comment text is empty or too long");
                }
                safeLog("Automatic comment generation completed; preview prepared for original work");
                prepare(task, result);
            } catch (Exception failure) {
                String http = "";
                java.util.regex.Matcher status = java.util.regex.Pattern.compile("HTTP ([0-9]{3})").matcher(String.valueOf(failure.getMessage()));
                if (status.find()) http = "; HTTP=" + status.group(1);
                safeLog("Automatic comment generation failed: " + failure.getClass().getSimpleName() + http + "; attempt=" + attempt + "/3");
                result.accept(valid(task) ? "failed" : "cancelled");
            } finally {
                if (runningTask == task) runningTask = null;
            }
        });
    }

    private void prepare(Task task, Consumer<String> result) {
        dispatcher.prepareComment(entry(task, "pending"), task.approved, () -> valid(task),
                done -> { task.approved = true; publish(task, done); }, result);
    }

    private void queueChanged(Task current, int count, int attempts, String phase) {
        int retries = Math.max(0, 3 - Math.max(1, attempts + ("retrying".equals(phase) ? 1 : 0)));
        dispatcher.trackComment(current == null ? null : entry(current, "pending"), count, retries, phase,
                () -> current != null && valid(current),
                () -> cancelTask(current), this::cancelLatestTask);
    }

    private void cancelTask(Task task) {
        if (task == null || task.terminal) return;
        task.cancelled = true;
        if (runningTask == task) ai.cancelCurrentRequest();
        queue.cancel(task);
    }

    private void cancelLatestTask() {
        Task latest = null;
        synchronized (queuedTasks) {
            for (Task task : queuedTasks.values()) latest = task;
        }
        cancelTask(latest);
    }

    private void publish(Task task, Consumer<String> result) {
        dispatcher.commentStage(task.id, "publishing");
        publisher.send(task.work, task.aid, task.text, () -> valid(task), () -> {
            inFlight.put(task.identity, task.id);
            rememberSubmission(task);
            main.postDelayed(() -> inFlight.remove(task.identity, task.id), 120_000);
        }, status -> {
            if ("failed".equals(status) && inFlight.remove(task.identity, task.id)) {
                context.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE).edit().remove(task.identity).apply();
            } else if ("sent".equals(status)) inFlight.remove(task.identity, task.id);
            if (task.terminal && ("sent".equals(status) || "failed".equals(status))) dispatcher.recordOutcome(entry(task, status));
            result.accept(status);
        });
    }

    private void finished(Task task, String status, int attempts) {
        synchronized (queuedTasks) { queuedTasks.remove(task.identity); }
        task.terminal = true;
        if ("cancelled".equals(status) && task.id.equals(inFlight.get(task.identity))) status = "submitted";
        if ("cancelled".equals(status)) task.cancelled = true;
        safeLog("Automatic comment queue outcome=" + status + "; attempts=" + attempts);
        if ("sent".equals(status) || "submitted".equals(status)) rememberSubmission(task);
        AutomationHistoryStore.Entry entry = entry(task, status);
        if ("failed".equals(status)) dispatcher.reportFinalFailure(entry);
        else dispatcher.recordOutcome(entry);
    }

    private void rememberSubmission(Task task) {
            android.content.SharedPreferences records = context.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor saved = records.edit();
            if (records.getAll().size() >= 1000 && !records.contains(task.identity)) {
                String oldest = records.getAll().entrySet().stream()
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue() instanceof Long ? (Long) entry.getValue() : 0L))
                        .map(Map.Entry::getKey).orElse(null);
                if (oldest != null) saved.remove(oldest);
            }
            saved.putLong(task.identity, System.currentTimeMillis()).apply();
    }

    private static AutomationHistoryStore.Entry entry(Task task, String status) {
        return new AutomationHistoryStore.Entry(task.id, AutomationHistoryStore.COMMENT, task.aid, task.name,
                task.input, task.text == null ? "" : task.text, task.model, status, task.createdAt, System.currentTimeMillis());
    }

    static String workInput(Object work) {
        String description = workDescription(work);
        String title = workTitle(work);
        if (title.trim().isEmpty()) title = description;
        return "作品作者：" + limited(workAuthor(work), 300)
                + "\n作品标题：" + limited(title, 1000)
                + "\n作品文案：" + limited(description, 2100);
    }

    private static boolean hasWorkContent(Object work) {
        return !workTitle(work).trim().isEmpty() || !workDescription(work).trim().isEmpty();
    }

    private static String workDescription(Object work) {
        return string(modelValue(work, "desc", "getDesc"));
    }

    private static String workTitle(Object work) {
        for (String[] property : new String[][]{{"title", "getTitle"},
                {"item_title", "getItemTitle"}, {"preview_title", "getPreviewTitle"}}) {
            String title = string(modelValue(work, property[0], property[1]));
            if (!title.trim().isEmpty()) return title;
        }
        return "";
    }

    private static String workAuthor(Object work) {
        Object author = modelValue(work, "author", "getAuthor");
        return string(modelValue(author, "nickname", "getNickname"));
    }

    private static Object modelValue(Object model, String field, String getter) {
        Object value = DownloadActionHook.jsonValue(model, field);
        return value == null ? HostTargets.invoke(model, getter) : value;
    }

    private static String limited(String value, int maximum) {
        int end = Math.min(maximum, value.length());
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    static List<String> workImages(Object work, LocalOptions options) {
        List<String> urls = new ArrayList<>();
        Object images = DownloadActionHook.jsonValue(work, "images");
        if (!(images instanceof Iterable<?>)) return urls;
        for (Object image : (Iterable<?>) images) {
            String source = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "url_list"));
            if (source == null) source = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "download_url_list"));
            if (source == null || !AutomationImages.isMediaUrl(source)) throw new IllegalArgumentException("图文图片无法读取");
            urls.add(source);
            if (urls.size() == ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) break;
        }
        return urls;
    }

    private long ownAccount() {
        try {
            Object service = Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService", false, loader)
                    .getMethod("userService").invoke(null);
            if (!Boolean.TRUE.equals(HostTargets.invoke(service, "isLogin"))) return 0;
            return Long.parseLong(string(HostTargets.invoke(service, "getCurUserId")));
        } catch (ReflectiveOperationException | RuntimeException ignored) { return 0; }
    }

    private static Activity activity(View root) {
        Context context = root == null ? null : root.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
    private void safeLog(String message) { try { log.accept(message); } catch (Throwable ignored) { } }

    @Override public void onActivityResumed(Activity activity) {
        Object selected = holder.get();
        if (!closed && selected != null && activity(root.get()) == activity) main.post(() -> selected(selected));
    }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityDestroyed(Activity activity) { }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { followListUpdates.close(); } catch (Exception ignored) { }
        generation.incrementAndGet();
        ai.cancelCurrentRequest();
        queue.close();
        dispatcher.cancelKind(AutomationHistoryStore.COMMENT);
        publisher.close();
        worker.shutdownNow();
        application.unregisterActivityLifecycleCallbacks(this);
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (RuntimeException ignored) { }
        }
        handles.clear();
    }

    private static final class Task {
        final long epoch;
        final long account;
        final String aid;
        final String identity;
        final String id;
        final Object work;
        final String input;
        final String name;
        final LocalOptions options;
        final long createdAt = System.currentTimeMillis();
        volatile String text;
        volatile String model = "";
        volatile boolean approved;
        volatile boolean terminal;
        volatile boolean cancelled;
        Task(long epoch, long account, String aid, String identity, Object work, String input, String name, LocalOptions options) {
            this.epoch = epoch; this.account = account; this.aid = aid; this.identity = identity;
            this.id = identity + ':' + java.util.UUID.randomUUID(); this.work = work; this.input = input;
            this.name = name; this.options = options;
        }
    }
}

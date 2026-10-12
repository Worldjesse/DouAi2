package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AutomationHistoryStore.Entry;
import com.douai.module.config.LocalOptions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Optional previews and cancellation shared by automatic replies and comments. */
public final class AutomationDispatcher implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    public interface Action { void send(Consumer<String> result) throws Exception; }

    private static final int GENERATING = -1;
    private static final int WAITING = 0;
    private static final int DETAILS = 1;
    private static final int QUEUED = 2;
    private static final int SENDING = 3;
    private static final int UNCONFIRMED = 4;
    private static final int FINISHED = 5;
    private static final int MAX_PENDING = 16;
    private final Application application;
    private final AutomationHistoryStore history;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>();
    private final List<Entry> failureNotices = new ArrayList<>();
    private volatile LocalOptions options = new LocalOptions(Collections.emptyMap());
    private volatile boolean closed;
    private volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private LinearLayout banners;
    private ScrollView noticeScroll;
    private final CommentRingOverlay commentRing;
    private String commentHead = "";
    private int commentQueueCount = -1;
    private int commentRetries = 2;
    private String commentQueuePhase = "waiting";
    private long commentFailureAttentionUntil;
    private Runnable cancelLatestComment = () -> { };

    public AutomationDispatcher(Context context, Consumer<String> log) {
        this(context, log, Executors.newSingleThreadExecutor());
    }

    AutomationDispatcher(Context context, Consumer<String> log, ExecutorService worker) {
        if (context == null || worker == null) throw new IllegalArgumentException("Context and worker are required");
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) throw new IllegalArgumentException("Application context is required");
        application = (Application) app;
        history = new AutomationHistoryStore(application);
        this.log = log == null ? ignored -> { } : log;
        this.worker = worker;
        commentRing = new CommentRingOverlay(application, this::openCommentDetails, this::cancelRingComment);
        application.registerActivityLifecycleCallbacks(this);
    }

    public void setOptions(LocalOptions next) {
        if (next == null) throw new IllegalArgumentException("Options are required");
        options = next;
        onMain(() -> {
            if (closed) return;
            for (Pending item : new ArrayList<>(pending.values())) {
                if (!enabled(item.kind)) cancel(item);
                else if (!tipEnabled(item.kind) && item.state.get() == WAITING) dispatch(item);
            }
            failureNotices.removeIf(entry -> !tipEnabled(entry.kind));
            renderBanners();
        });
    }

    public boolean isForeground(Activity activity) { return !closed && activity != null && resumed.get() == activity; }

    public void trackComment(Entry entry, int count, int retries, String phase,
                             BooleanSupplier stillValid, Runnable cancelGeneration,
                             Runnable cancelLatest) {
        onMain(() -> {
            if (closed) return;
            cancelLatestComment = cancelLatest == null ? () -> { } : cancelLatest;
            String next = entry == null ? "" : entry.id;
            if (!commentHead.equals(next)) {
                Pending previous = pending.get(key(AutomationHistoryStore.COMMENT, commentHead));
                if (previous != null && previous.early && previous.state.get() < SENDING) {
                    previous.state.set(FINISHED);
                    pending.remove(key(previous.kind, previous.entry.id));
                    removeNotice(previous);
                }
            }
            commentHead = next;
            commentQueueCount = count;
            commentRetries = Math.max(0, retries);
            commentQueuePhase = "running".equals(phase) ? "generating" : phase;
            if (entry != null) {
                String identity = key(entry.kind, entry.id);
                Pending item = pending.get(identity);
                if (item == null) {
                    item = new Pending(entry, stillValid, result -> { }, status -> {
                        if ("cancelled".equals(status) && cancelGeneration != null) cancelGeneration.run();
                    });
                    item.early = true;
                    item.cancelFuture = cancelGeneration;
                    item.ready = false;
                    item.state.set(GENERATING);
                    pending.put(identity, item);
                    remember(entry.kind, entry.id);
                    record(entry);
                }
                if (!"running".equals(phase) || !item.ready) item.phase = "running".equals(phase) ? "generating" : phase;
            }
            renderBanners();
        });
    }

    public void commentStage(String id, String phase) {
        onMain(() -> {
            Pending item = pending.get(key(AutomationHistoryStore.COMMENT, id));
            if (item == null) return;
            item.phase = phase;
            refreshDetails(item);
            renderCommentRing();
        });
    }

    public void prepareComment(Entry entry, boolean alreadyApproved, BooleanSupplier stillValid,
                               Action action, Consumer<String> outcome) {
        onMain(() -> prepareEntry(entry, stillValid, action, outcome, alreadyApproved));
    }

    public void prepare(String kind, String id, String targetId, String targetName,
                        String input, String output, String modelName,
                        BooleanSupplier stillValid, Action action) {
        prepare(kind, id, targetId, targetName, input, output, modelName, stillValid, action, ignored -> { });
    }

    public void prepare(String kind, String id, String targetId, String targetName,
                        String input, String output, String modelName,
                        BooleanSupplier stillValid, Action action, Consumer<String> outcome) {
        if (stillValid == null || action == null || outcome == null) throw new IllegalArgumentException("Validity, action and outcome are required");
        long now = System.currentTimeMillis();
        Entry entry = new Entry(id, kind, targetId, targetName, input, output, modelName, "pending", now, now);
        onMain(() -> prepareEntry(entry, stillValid, action, outcome, false));
    }

    private void prepareEntry(Entry entry, BooleanSupplier stillValid, Action action, Consumer<String> outcome, boolean alreadyApproved) {
            if (closed) { outcome.accept("cancelled"); return; }
            String kind = entry.kind;
            Pending existing = pending.get(key(kind, entry.id));
            if (existing != null && existing.early && !existing.ready && existing.state.get() < SENDING) {
                existing.entry = entry;
                existing.action = action;
                existing.outcome = outcome;
                existing.ready = true;
                existing.phase = "preview";
                if (!enabled(kind) || !valid(existing)) { cancel(existing); return; }
                record(entry);
                if (existing.state.get() == DETAILS) {
                    refreshDetails(existing);
                    renderCommentRing();
                } else {
                    existing.state.set(WAITING);
                    if (alreadyApproved || !tipEnabled(kind)) dispatch(existing);
                    else startPreview(existing);
                }
                return;
            }
            if (closed || seen.containsKey(key(entry.kind, entry.id))) { outcome.accept("cancelled"); return; }
            remember(entry.kind, entry.id);
            if (pending.size() >= MAX_PENDING) {
                record(withStatus(entry, "cancelled"));
                safeLog("Automatic action cancelled because the preview queue is full");
                outcome.accept("cancelled");
                return;
            }
            Pending item = new Pending(entry, stillValid, action, outcome);
            pending.put(key(item.kind, item.entry.id), item);
            if (!enabled(kind) || !valid(item)) { cancel(item); return; }
            record(entry);
            if (tipEnabled(kind)) {
                startPreview(item);
            } else dispatch(item);
    }

    private void startPreview(Pending item) {
        item.noticeDuration = noticeSeconds(item.kind) * 1000L;
        item.deadline = SystemClock.elapsedRealtime() + item.noticeDuration;
        renderBanners();
        tick(item);
    }

    public void reportFailure(String kind, String id, String targetId, String targetName,
                              String input, String modelName) {
        long now = System.currentTimeMillis();
        Entry entry = new Entry(id, kind, targetId, targetName, input, "", modelName, "failed", now, now);
        onMain(() -> {
            if (closed || seen.containsKey(key(kind, id))) return;
            remember(kind, id);
            record(entry);
            if (tipEnabled(kind)) {
                if (failureNotices.size() >= 8) failureNotices.remove(0);
                failureNotices.add(entry);
                renderBanners();
                main.postDelayed(() -> { failureNotices.remove(entry); renderBanners(); }, noticeSeconds(kind) * 1000L);
            }
        });
    }

    public void recordOutcome(Entry entry) { onMain(() -> record(entry)); }

    public void reportFinalFailure(Entry entry) {
        onMain(() -> {
            if (closed) return;
            record(entry);
            if (!tipEnabled(entry.kind)) return;
            failureNotices.removeIf(previous -> previous.id.equals(entry.id) && previous.kind.equals(entry.kind));
            if (failureNotices.size() >= 8) failureNotices.remove(0);
            failureNotices.add(entry);
            if (AutomationHistoryStore.COMMENT.equals(entry.kind)) {
                commentFailureAttentionUntil = SystemClock.elapsedRealtime() + 1500;
                main.postDelayed(this::renderCommentRing, 1500);
            }
            renderBanners();
            main.postDelayed(() -> { failureNotices.remove(entry); renderBanners(); }, noticeSeconds(entry.kind) * 1000L);
        });
    }

    public void cancelKind(String kind) {
        if (!AutomationHistoryStore.REPLY.equals(kind) && !AutomationHistoryStore.COMMENT.equals(kind)) {
            throw new IllegalArgumentException("Unknown automation kind");
        }
        onMain(() -> {
            for (Pending item : new ArrayList<>(pending.values())) if (kind.equals(item.kind)) cancel(item);
        });
    }

    private void remember(String kind, String id) {
        seen.put(key(kind, id), true);
        while (seen.size() > 512) seen.remove(seen.keySet().iterator().next());
    }

    private static String key(String kind, String id) { return kind + ':' + id; }

    private boolean enabled(String kind) {
        LocalOptions current = options;
        return current.enabled("automation_enabled") && current.enabled(AutomationHistoryStore.REPLY.equals(kind)
                ? "native_auto_reply" : "native_auto_comment");
    }

    private boolean tipEnabled(String kind) { return options.enabled(kind + "_tip"); }

    private int noticeSeconds(String kind) {
        return Math.max(2, Math.min(120, options.integer(kind + "_notice_seconds", 8)));
    }

    private boolean valid(Pending item) {
        try { return item.stillValid.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private void tick(Pending item) {
        if (closed || item.state.get() != WAITING) return;
        if (!enabled(item.kind) || !valid(item)) { cancel(item); return; }
        long remaining = item.deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) { dispatch(item); return; }
        if (item.countdown != null) item.countdown.setText((remaining + 999) / 1000 + " 秒");
        if (AutomationHistoryStore.COMMENT.equals(item.kind)) renderCommentRing();
        main.postDelayed(item.timer, Math.min(1000, Math.max(0, item.deadline - SystemClock.elapsedRealtime())));
    }

    private void dispatch(Pending item) {
        if (!item.ready) return;
        int state = item.state.get();
        if ((state != WAITING && state != DETAILS) || !item.state.compareAndSet(state, QUEUED)) return;
        removeNotice(item);
        if (closed || !enabled(item.kind) || !valid(item)) { cancel(item); return; }
        try {
            worker.execute(() -> {
                if (closed || !enabled(item.kind) || !valid(item)) { onMain(() -> cancel(item)); return; }
                if (!item.state.compareAndSet(QUEUED, SENDING)) return;
                item.phase = "publishing";
                onMain(this::renderCommentRing);
                record(withStatus(item.entry, "sending"));
                main.postDelayed(item.acknowledgmentTimeout, 30_000);
                try { item.action.send(status -> finish(item, status)); }
                catch (Exception failure) {
                    safeLog("Automatic action failed: " + failure.getClass().getSimpleName());
                    finish(item, "failed");
                }
            });
        } catch (RejectedExecutionException ignored) { cancel(item); }
    }

    private void finish(Pending item, String status) {
        String result = "sent".equals(status) || "submitted".equals(status) || "cancelled".equals(status) ? status : "failed";
        if (!item.state.compareAndSet(SENDING, FINISHED) && !item.state.compareAndSet(UNCONFIRMED, FINISHED)) return;
        onMain(() -> {
            main.removeCallbacks(item.acknowledgmentTimeout);
            pending.remove(key(item.kind, item.entry.id));
            removeNotice(item);
            record(withStatus(item.entry, result));
            safeLog("Automatic " + item.kind + " outcome: " + result);
            item.outcome.accept(result);
        });
    }

    private void unconfirmed(Pending item) {
        if (!item.state.compareAndSet(SENDING, UNCONFIRMED)) return;
        pending.remove(key(item.kind, item.entry.id));
        removeNotice(item);
        record(withStatus(item.entry, "submitted"));
        item.outcome.accept("submitted");
    }

    private void cancel(Pending item) {
        int state;
        do {
            state = item.state.get();
            if (state >= SENDING) return;
        } while (!item.state.compareAndSet(state, FINISHED));
        pending.remove(key(item.kind, item.entry.id));
        removeNotice(item);
        record(withStatus(item.entry, "cancelled"));
        item.outcome.accept("cancelled");
    }

    private static Entry withStatus(Entry entry, String status) {
        return new Entry(entry.id, entry.kind, entry.targetId, entry.targetName,
                entry.input, entry.output, entry.modelName, status,
                entry.createdAt, System.currentTimeMillis());
    }

    private void record(Entry entry) {
        if (!options.enabled(entry.kind + "_save_history")) return;
        try {
            worker.execute(() -> {
                if (!options.enabled(entry.kind + "_save_history")) return;
                try { history.upsert(entry); }
                catch (RuntimeException failure) { safeLog("Could not write automatic action history"); }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private void renderBanners() {
        renderCommentRing();
        Activity activity = resumed.get();
        if (closed || activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        if (banners == null) {
            noticeScroll = new ScrollView(activity) {
                @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int limit = Math.max(dp(getContext(), 120), getResources().getDisplayMetrics().heightPixels / 3);
                    if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                        limit = Math.min(limit, View.MeasureSpec.getSize(heightMeasureSpec));
                    }
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
                }
            };
            banners = new LinearLayout(activity);
            banners.setTag("automation.notices");
            banners.setOrientation(LinearLayout.VERTICAL);
            banners.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                    Math.min(activity.getResources().getDisplayMetrics().widthPixels, dp(activity, 560)),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            layout.topMargin = dp(activity, 56);
            noticeScroll.addView(banners);
            ((ViewGroup) decor).addView(noticeScroll, layout);
            noticeScroll.setElevation(dp(activity, 16));
        }
        banners.removeAllViews();
        for (Pending item : pending.values()) {
            item.countdown = null;
            if (AutomationHistoryStore.COMMENT.equals(item.kind)) continue;
            if (item.state.get() != WAITING) continue;
            LinearLayout banner = new LinearLayout(activity);
            banner.setTag("automation.notice." + item.entry.id);
            banner.setOrientation(LinearLayout.VERTICAL);
            banner.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setCornerRadius(dp(activity, 8));
            background.setStroke(dp(activity, 1), Color.rgb(194, 214, 212));
            banner.setBackground(background);
            banner.setClickable(true);
            banner.setFocusable(true);
            banner.setTooltipText("长按取消" + verb(item.kind));
            LinearLayout titleRow = new LinearLayout(activity);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(activity, kindName(item.kind) + " · " + target(item.entry), 14, Color.rgb(31, 39, 45));
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            item.countdown = text(activity, "", 12, Color.rgb(0, 121, 107));
            item.countdown.setGravity(Gravity.END);
            titleRow.addView(item.countdown, new LinearLayout.LayoutParams(dp(activity, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
            long remaining = Math.max(0, item.deadline - SystemClock.elapsedRealtime());
            item.countdown.setText((remaining + 999) / 1000 + " 秒");
            banner.addView(titleRow);
            TextView preview = text(activity, item.entry.output, 14, Color.rgb(72, 84, 91));
            preview.setMaxLines(2);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            banner.addView(preview);
            banner.setOnClickListener(ignored -> showDetails(item));
            banner.setOnLongClickListener(ignored -> { cancel(item); return true; });
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            row.bottomMargin = dp(activity, 8);
            banners.addView(banner, row);
        }
        for (Entry entry : failureNotices) {
            if (AutomationHistoryStore.COMMENT.equals(entry.kind)) continue;
            TextView failure = text(activity, kindName(entry.kind) + "失败"
                    + "\n" + target(entry), 14, Color.rgb(174, 49, 57));
            failure.setTag("automation.failure." + entry.id);
            failure.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            failure.setMaxLines(3);
            failure.setBackgroundColor(Color.WHITE);
            failure.setOnLongClickListener(ignored -> { failureNotices.remove(entry); renderBanners(); return true; });
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            row.bottomMargin = dp(activity, 8);
            banners.addView(failure, row);
        }
        banners.setVisibility(banners.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        noticeScroll.setVisibility(banners.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private Pending currentComment() {
        if (commentQueueCount >= 0) return pending.get(key(AutomationHistoryStore.COMMENT, commentHead));
        for (Pending item : pending.values()) if (AutomationHistoryStore.COMMENT.equals(item.kind) && item.state.get() < FINISHED) return item;
        return null;
    }

    private Entry lastCommentFailure() {
        for (int index = failureNotices.size() - 1; index >= 0; index--) {
            Entry entry = failureNotices.get(index);
            if (AutomationHistoryStore.COMMENT.equals(entry.kind)) return entry;
        }
        return null;
    }

    private void renderCommentRing() {
        Activity activity = resumed.get();
        if (closed || !enabled(AutomationHistoryStore.COMMENT) || !tipEnabled(AutomationHistoryStore.COMMENT)
                || activity == null || activity.isFinishing() || activity.isDestroyed()) { commentRing.detach(); return; }
        Pending item = currentComment();
        int count = commentQueueCount;
        if (count < 0) {
            count = 0;
            for (Pending value : pending.values()) if (AutomationHistoryStore.COMMENT.equals(value.kind) && value.state.get() < FINISHED) count++;
        }
        Entry failure = lastCommentFailure();
        if (count == 0 && failure == null) { commentRing.detach(); return; }
        String phase = count == 0 ? "failed" : item == null ? commentQueuePhase : item.phase;
        float progress = -1;
        if (item != null) {
            int state = item.state.get();
            if (state == DETAILS) phase = "details";
            else if (state == WAITING && item.ready) {
                phase = "preview";
                progress = Math.max(0, Math.min(1, (item.deadline - SystemClock.elapsedRealtime()) / (float) Math.max(1, item.noticeDuration)));
            } else if (state >= QUEUED) phase = "publishing";
        }
        if (failure != null && !"details".equals(phase) && SystemClock.elapsedRealtime() < commentFailureAttentionUntil) {
            phase = "failed";
            progress = -1;
        }
        commentRing.show(activity, count, count == 0 ? 0 : commentRetries, phase, progress);
    }

    private void openCommentDetails() {
        Pending item = currentComment();
        if (item != null) { showDetails(item); return; }
        Entry failure = lastCommentFailure();
        Activity activity = resumed.get();
        if (failure == null || activity == null) return;
        TextView content = text(activity, target(failure) + "\n\n" + failure.input
                + (failure.output.isEmpty() ? "" : "\n\n" + failure.output), 15, Color.rgb(31, 39, 45));
        content.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("自动评论失败").setView(scroll).setPositiveButton("关闭", null).create();
        dialog.setOwnerActivity(activity);
        dialog.show();
    }

    private void cancelRingComment() {
        if (commentQueueCount > 0) {
            cancelLatestComment.run();
            return;
        }
        Pending item = currentComment();
        if (item != null) {
            if (item.cancelFuture != null) item.cancelFuture.run();
            else cancel(item);
            return;
        }
        Entry failure = lastCommentFailure();
        if (failure != null) { failureNotices.remove(failure); renderCommentRing(); }
    }

    private void showDetails(Pending item) {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (item.details != null) return;
        int state = item.state.get();
        boolean readOnly = state >= SENDING;
        if (!readOnly && (state != WAITING && state != GENERATING || !item.state.compareAndSet(state, DETAILS))) return;
        if (!readOnly) main.removeCallbacks(item.timer);
        renderBanners();
        LinearLayout body = new LinearLayout(activity);
        body.setTag("automation.details");
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        body.addView(text(activity, target(item.entry), 14, Color.rgb(105, 115, 122)));
        item.detailStatus = text(activity, phaseName(item), 13, Color.rgb(0, 121, 107));
        if (AutomationHistoryStore.COMMENT.equals(item.kind)) body.addView(item.detailStatus);
        TextView inputTitle = text(activity, AutomationHistoryStore.REPLY.equals(item.kind) ? "收到的消息" : "视频内容", 13, Color.rgb(105, 115, 122));
        inputTitle.setPadding(0, dp(activity, 16), 0, dp(activity, 4));
        body.addView(inputTitle);
        TextView input = text(activity, item.entry.input, 15, Color.rgb(31, 39, 45));
        input.setTag("automation.details.input");
        input.setTextIsSelectable(true);
        body.addView(input);
        TextView outputTitle = text(activity, "生成的" + verb(item.kind), 13, Color.rgb(105, 115, 122));
        outputTitle.setPadding(0, dp(activity, 16), 0, dp(activity, 4));
        body.addView(outputTitle);
        TextView output = text(activity, item.entry.output, 15, Color.rgb(31, 39, 45));
        output.setTag("automation.details.output");
        output.setTextIsSelectable(true);
        item.detailOutput = output;
        body.addView(output);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(kindName(item.kind)).setView(scroll);
        if (readOnly) builder.setPositiveButton("关闭", null);
        else builder.setPositiveButton("立即" + verb(item.kind), (ignored, which) -> dispatch(item))
                .setNegativeButton("取消" + verb(item.kind), (ignored, which) -> cancel(item));
        AlertDialog dialog = builder.create();
        dialog.setOwnerActivity(activity);
        item.details = dialog;
        dialog.setOnDismissListener(ignored -> {
            item.details = null;
            item.detailOutput = null;
            item.detailStatus = null;
            if (item.state.get() == DETAILS) cancel(item);
        });
        dialog.show();
        refreshDetails(item);
    }

    private void refreshDetails(Pending item) {
        if (item.detailOutput != null) item.detailOutput.setText(item.ready ? item.entry.output : "正在生成…");
        if (item.detailStatus != null) item.detailStatus.setText(phaseName(item));
        if (item.details instanceof AlertDialog && item.state.get() == DETAILS) {
            ((AlertDialog) item.details).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(item.ready && valid(item));
        }
    }

    private static String phaseName(Pending item) {
        if (item.state.get() >= SENDING) return "正在发布";
        if (item.ready) return "等待确认";
        if ("waiting".equals(item.phase)) return "等待生成";
        if ("retrying".equals(item.phase)) return "等待重试";
        return "正在生成";
    }

    private void removeNotice(Pending item) {
        main.removeCallbacks(item.timer);
        if (item.details != null) {
            Dialog dialog = item.details;
            item.details = null;
            dialog.dismiss();
        }
        renderBanners();
    }

    private void detachBanners() {
        if (noticeScroll != null && noticeScroll.getParent() instanceof ViewGroup) {
            ((ViewGroup) noticeScroll.getParent()).removeView(noticeScroll);
        }
        banners = null;
        noticeScroll = null;
        for (Pending item : pending.values()) item.countdown = null;
    }

    private static String target(Entry entry) {
        return entry.targetName.isEmpty() ? entry.targetId : entry.targetName;
    }

    private static String verb(String kind) { return AutomationHistoryStore.REPLY.equals(kind) ? "回复" : "评论"; }
    private static String kindName(String kind) { return "自动" + verb(kind); }

    private static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void safeLog(String value) {
        try { log.accept(value); } catch (RuntimeException ignored) { }
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @Override public void onActivityResumed(Activity activity) {
        detachBanners();
        resumed = new WeakReference<>(activity);
        renderBanners();
    }

    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() != activity) return;
        resumed.clear();
        detachBanners();
        commentRing.detach();
        for (Pending item : new ArrayList<>(pending.values())) if (item.details != null) {
            if (item.state.get() >= SENDING) item.details.dismiss(); else cancel(item);
        }
    }

    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        onMain(() -> {
            for (Pending item : new ArrayList<>(pending.values())) cancel(item);
            detachBanners();
            commentRing.detach();
            failureNotices.clear();
            resumed.clear();
            application.unregisterActivityLifecycleCallbacks(this);
            worker.shutdown();
        });
    }

    private final class Pending {
        Entry entry;
        final String kind;
        final BooleanSupplier stillValid;
        Action action;
        Consumer<String> outcome;
        Runnable cancelFuture;
        final AtomicInteger state = new AtomicInteger(WAITING);
        final Runnable timer = () -> tick(this);
        final Runnable acknowledgmentTimeout = () -> unconfirmed(this);
        long deadline;
        long noticeDuration;
        boolean early;
        boolean ready = true;
        String phase = "preview";
        TextView countdown;
        TextView detailOutput;
        TextView detailStatus;
        Dialog details;

        Pending(Entry entry, BooleanSupplier stillValid, Action action, Consumer<String> outcome) {
            this.entry = entry;
            this.kind = entry.kind;
            this.stillValid = stillValid;
            this.action = action;
            this.outcome = outcome;
        }
    }
}

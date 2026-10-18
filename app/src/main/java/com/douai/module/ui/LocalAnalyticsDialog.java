package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.analytics.AnalyticsSnapshot;
import com.douai.module.analytics.AnalyticsSnapshot.Bucket;
import com.douai.module.analytics.AnalyticsSnapshot.Rank;
import com.douai.module.analytics.AnalyticsSnapshot.Recent;
import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.analytics.AnalyticsAiAnalysis;
import com.douai.module.analytics.LocalPortraitContext;
import com.douai.module.config.SettingsStore;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

/** Native local report with optional, explicit AI analysis. Workers never retain the host Activity. */
public final class LocalAnalyticsDialog implements AutoCloseable {
    private static final int TEXT = 0xFF1F272D, MUTED = 0xFF69737A, LINE = 0xFFE2E7E8;
    private static final int BLUE = 0xFF0085E1, BACKGROUND = 0xFFF8FAFA;
    private static final int[] RANGES = {1, 7, 30, 90, 0};
    private static final String[] RANGE_LABELS = {"今天", "7 天", "30 天", "90 天", "全部"};
    private static final int[] PALETTE = {0xFF1683EE, 0xFF54B76A, 0xFF9869D8,
            0xFFF5A33C, 0xFFE66C9B, 0xFF89959F};

    interface Source {
        boolean isEnabled();
        void setEnabled(boolean enabled);
        AnalyticsSnapshot snapshot(int days);
        void clear();
        default LocalPortraitContext.State portraitContext() { return null; }
        default void purpose(String value) { }
        default void feedback(String value) { }
    }

    interface AnalysisService {
        boolean isConfigured();
        String modelLabel();
        Request prepare(AnalyticsSnapshot snapshot, int days);
        default Request prepare(AnalyticsSnapshot snapshot, int days, String purpose) { return prepare(snapshot, days); }
        interface Request {
            String modelLabel();
            String run() throws Exception;
            void cancel();
        }
    }

    private static final AnalysisService NO_ANALYSIS = new AnalysisService() {
        @Override public boolean isConfigured() { return false; }
        @Override public String modelLabel() { return "未配置模型"; }
        @Override public Request prepare(AnalyticsSnapshot snapshot, int days) {
            throw new IllegalStateException("未配置模型");
        }
    };

    private final Context context;
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout root;
    private final LinearLayout report;
    private final Source source;
    private final Executor worker;
    private final AnalysisService analysisService;
    private final Executor analysisWorker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Switch recording;
    private final TextView status;
    private final Button refresh;
    private final Button clear;
    private final Button purposeButton;
    private final Button feedbackButton;
    private LocalPortraitContext.State currentContext;
    private AlertDialog contextDialog;
    private final List<Button> ranges = new ArrayList<>();
    private AlertDialog clearConfirmation;
    private volatile boolean closed;
    private int generation;
    private int days = 7;
    private boolean binding;
    private boolean enabled;
    private AnalyticsSnapshot currentSnapshot;
    private LinearLayout analysisBody;
    private AnalysisOperation analysisOperation;
    private int analysisGeneration;
    private AnalyticsAiAnalysis.Result analysisResult;
    private final Map<String, LinearLayout> chartAnalysisSlots = new LinkedHashMap<>();
    private String analysisError;
    private String analyzedModel;
    private long analyzedAt;
    private int analyzedDays;

    public LocalAnalyticsDialog(Context context) {
        this(context, source(context), executor("DouAi-analytics-report"));
    }

    public LocalAnalyticsDialog(Context context, SettingsStore settings) {
        this(context, source(context), executor("DouAi-analytics-report"),
                analysisService(settings), executor("DouAi-analytics-analysis"));
    }

    private static ExecutorService executor(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static AnalysisService analysisService(SettingsStore settings) {
        return new AnalysisService() {
            @Override public boolean isConfigured() { return AnalyticsAiAnalysis.isConfigured(settings); }
            @Override public String modelLabel() { return AnalyticsAiAnalysis.modelLabel(settings); }
            @Override public Request prepare(AnalyticsSnapshot snapshot, int days) {
                return prepare(snapshot, days, "");
            }
            @Override public Request prepare(AnalyticsSnapshot snapshot, int days, String purpose) {
                AnalyticsAiAnalysis.AnalysisRequest request = AnalyticsAiAnalysis.prepare(settings, snapshot, days, purpose);
                return new Request() {
                    @Override public String modelLabel() { return request.modelLabel(); }
                    @Override public String run() throws Exception { return request.run(); }
                    @Override public void cancel() { request.cancel(); }
                };
            }
        };
    }

    private static Source source(Context context) {
        Context app = context.getApplicationContext();
        return new Source() {
            private LocalAnalytics store() { return LocalAnalytics.get(app); }
            @Override public boolean isEnabled() { return store().isEnabled(); }
            @Override public void setEnabled(boolean value) { store().setEnabled(value); }
            @Override public AnalyticsSnapshot snapshot(int days) { return store().snapshot(days); }
            @Override public void clear() { store().clear(); LocalPortraitContext.clear(app); }
            @Override public LocalPortraitContext.State portraitContext() { return LocalPortraitContext.current(app); }
            @Override public void purpose(String value) { LocalPortraitContext.setPurpose(app, value); }
            @Override public void feedback(String value) { LocalPortraitContext.setFeedback(app, value); }
        };
    }

    LocalAnalyticsDialog(Context base, Source source, Executor worker) {
        this(base, source, worker, NO_ANALYSIS, executor("DouAi-analytics-analysis"));
    }

    LocalAnalyticsDialog(Context base, Source source, Executor worker,
                         AnalysisService analysisService, Executor analysisWorker) {
        this.context = ModuleTheme.themedContext(base);
        this.activity = activity(base);
        this.source = source;
        this.worker = worker;
        this.analysisService = analysisService;
        this.analysisWorker = analysisWorker;
        dialog = new Dialog(context, ModuleTheme.dialogTheme(context));
        if (activity != null) dialog.setOwnerActivity(activity);
        dialog.setCanceledOnTouchOutside(true);
        root = column();
        root.setTag("analytics.root");
        root.setBackground(rounded(BACKGROUND, 20));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(5), dp(12), dp(5));
        TextView title = text("本地数据分析", 20, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回", "analytics.back", dialog::dismiss);
        header.addView(back, new LinearLayout.LayoutParams(dp(64), dp(48)));
        root.addView(header, matchWrap());

        ScrollView scroll = new ScrollView(context);
        scroll.setTag("analytics.scroll");
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = column();
        body.setTag("analytics.body");
        body.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout privacy = card(body);
        recording = new Switch(context);
        recording.setTag("analytics.recording");
        recording.setText("开启本地记录");
        recording.setTextSize(16);
        recording.setTextColor(TEXT);
        recording.setMinHeight(dp(48));
        recording.setEnabled(false);
        recording.setContentDescription("开启本地记录，默认关闭");
        privacy.addView(recording, matchWrap());
        TextView note = text("默认关闭。开启后记录刷到的作品类型、作者、话题和模块功能使用。日常采集仅保存在本机抖音的私有目录，保留最近 365 天；不自动上传，不进入配置备份或系统云备份。只有点击页底“请求 AI 分析”才会发送当前图表汇总。关闭记录后停止新增，已有记录仍可查看或清空。", 12, MUTED, false);
        note.setLineSpacing(dp(3), 1);
        privacy.addView(note, matchWrap());
        purposeButton = button("我的使用目的 · 可选", "analytics.purpose", this::editPurpose);
        privacy.addView(purposeButton, new LinearLayout.LayoutParams(-1, -2));
        feedbackButton = button("最近一次侧写反馈 · 尚未填写", "analytics.feedback", this::editFeedback);
        privacy.addView(feedbackButton, new LinearLayout.LayoutParams(-1, -2));
        recording.setOnCheckedChangeListener((view, checked) -> {
            if (!binding) load(checked, false);
        });

        HorizontalScrollView filterScroll = new HorizontalScrollView(context);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setFillViewport(true);
        LinearLayout filterRow = new LinearLayout(context);
        for (int index = 0; index < RANGES.length; index++) {
            final int range = RANGES[index];
            Button filter = button(RANGE_LABELS[index], "analytics.range." + range, () -> {
                days = range;
                updateRanges();
                load(null, false);
            });
            filter.setMinWidth(dp(56));
            filter.setTextSize(13);
            filter.setPadding(dp(9), 0, dp(9), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (index > 0) params.leftMargin = dp(5);
            filterRow.addView(filter, params);
            ranges.add(filter);
        }
        filterScroll.addView(filterRow, new HorizontalScrollView.LayoutParams(-1, -2));
        body.addView(filterScroll, matchWrap());
        updateRanges();
        status = text("正在读取本地记录…", 12, MUTED, false);
        status.setTag("analytics.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(12), 0, dp(4));
        body.addView(status, matchWrap());
        LinearLayout actions = new LinearLayout(context);
        refresh = button("刷新", "analytics.refresh", () -> load(null, false));
        clear = button("清空记录", "analytics.clear", this::confirmClear);
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(48), 1));
        body.addView(actions, matchWrap());
        report = column();
        report.setTag("analytics.report");
        body.addView(report, matchWrap());
        dialog.setContentView(root);
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            if (activity != null) HostDialogBounds.install(activity, dialog, false);
            load(null, false);
        });
        dialog.setOnDismissListener(ignored -> close());
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { close(); }
        });
        ModuleTheme.bind(root);
    }

    public Dialog dialog() { return dialog; }

    private void updateRanges() {
        for (int index = 0; index < ranges.size(); index++) {
            Button button = ranges.get(index);
            boolean selected = days == RANGES[index];
            button.setSelected(selected);
            button.setTextColor(selected ? BLUE : MUTED);
            button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackground(rounded(selected ? 0xFFE7F5F2 : 0xFFEEEFF3, 22));
            button.setContentDescription(RANGE_LABELS[index] + (selected ? "，已选择" : "，查看统计"));
        }
        ModuleTheme.bind(root);
    }

    /** Work captures only application-backed storage and a weak view reference. */
    private void load(Boolean nextEnabled, boolean erase) {
        load(nextEnabled, erase, null);
    }

    private void load(Boolean nextEnabled, boolean erase, Consumer<Source> contextChange) {
        if (closed) return;
        // A new snapshot must never be paired with an interpretation of older chart values.
        resetAnalysis();
        int request = ++generation;
        int range = days;
        recording.setEnabled(false);
        refresh.setEnabled(false);
        clear.setEnabled(false);
        purposeButton.setEnabled(false);
        feedbackButton.setEnabled(false);
        status.setText(erase ? "正在清空本地记录…" : "正在读取本地记录…");
        currentSnapshot = null;
        analysisBody = null;
        chartAnalysisSlots.clear();
        report.removeAllViews();
        Source data = source;
        Handler dispatcher = main;
        WeakReference<LocalAnalyticsDialog> target = new WeakReference<>(this);
        worker.execute(() -> {
            AnalyticsSnapshot snapshot = null;
            boolean recordingEnabled = false;
            boolean failed = false;
            LocalPortraitContext.State localContext = null;
            try {
                if (contextChange != null) contextChange.accept(data);
                if (nextEnabled != null) data.setEnabled(nextEnabled);
                if (erase) data.clear();
                snapshot = data.snapshot(range);
                localContext = data.portraitContext();
            } catch (RuntimeException failure) {
                failed = true;
            }
            try {
                // Storage may disable collection after a write/read failure.
                recordingEnabled = data.isEnabled();
            } catch (RuntimeException failure) {
                failed = true;
            }
            AnalyticsSnapshot result = snapshot;
            boolean state = recordingEnabled;
            boolean error = failed;
            LocalPortraitContext.State resultContext = localContext;
            dispatcher.post(() -> {
                LocalAnalyticsDialog page = target.get();
                if (page != null && !page.closed && request == page.generation) {
                    page.loaded(result, state, error, resultContext);
                }
            });
        });
    }

    private void loaded(AnalyticsSnapshot snapshot, boolean state, boolean error,
                        LocalPortraitContext.State localContext) {
        recording.setEnabled(true);
        refresh.setEnabled(true);
        clear.setEnabled(true);
        currentContext = localContext;
        purposeButton.setEnabled(true);
        feedbackButton.setEnabled(true);
        purposeButton.setText(localContext == null || localContext.purpose.isEmpty() ? "我的使用目的 · 可选" : "我的使用目的 · 已填写");
        feedbackButton.setText("最近一次侧写反馈 · " + (localContext == null || localContext.feedback.isEmpty() ? "尚未填写" : localContext.feedback));
        binding = true;
        enabled = state;
        recording.setChecked(enabled);
        binding = false;
        if (error || snapshot == null) {
            status.setText("本地记录读取失败，请点击刷新核对。记录状态以上方开关为准。");
            return;
        }
        status.setText((enabled ? "正在本地记录" : "记录已关闭") + " · "
                + (days == 0 ? "全部（最近 365 天）" : days == 1 ? "今天" : "最近 " + days + " 天")
                + " · 手动刷新获取最新数据");
        render(snapshot);
        ModuleTheme.bind(root);
    }

    private void confirmClear() {
        if (closed || clearConfirmation != null) return;
        clearConfirmation = new AlertDialog.Builder(context, ModuleTheme.alertTheme(context))
                .setTitle("清空全部本地分析记录？")
                .setMessage("将删除所有时间范围的浏览、功能使用记录以及使用目的和反馈，无法撤销。记录开关保持当前状态。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空全部", (ignored, which) -> load(null, true))
                .create();
        clearConfirmation.setOnDismissListener(ignored -> clearConfirmation = null);
        clearConfirmation.setOnShowListener(ignored -> {
            if (activity != null) HostDialogBounds.install(activity, clearConfirmation, true);
        });
        clearConfirmation.show();
    }

    private void editPurpose() {
        if (closed || contextDialog != null) return;
        EditText input = new EditText(context);
        input.setMinLines(3); input.setMaxLines(6); input.setTextSize(14);
        input.setHint("例如：放松、学习摄影、找做饭灵感。可以留空。");
        input.setText(currentContext == null ? "" : currentContext.purpose);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(240)});
        contextDialog = new AlertDialog.Builder(context, ModuleTheme.alertTheme(context))
                .setTitle("我的使用目的").setMessage("这是你的自述，用来区分行为与动机。先保存在本机；主动请求 AI 分析时才一并发送。")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    load(null, false, data -> data.purpose(value));
                }).create();
        showContextDialog();
    }

    private void editFeedback() {
        if (closed || contextDialog != null) return;
        String[] values = {"符合", "部分符合", "不符合", "清除反馈"};
        contextDialog = new AlertDialog.Builder(context, ModuleTheme.alertTheme(context))
                .setTitle("当前侧写与你的感受符合吗？")
                .setItems(values, (dialog, index) -> {
                    String value = index == 3 ? "" : values[index];
                    saveFeedback(value);
                }).setNegativeButton("取消", null).create();
        showContextDialog();
    }

    /** Rating describes the already displayed result; it must not invalidate that result. */
    private void saveFeedback(String value) {
        if (closed) return;
        int request = generation;
        feedbackButton.setEnabled(false);
        Source data = source;
        Handler dispatcher = main;
        WeakReference<LocalAnalyticsDialog> target = new WeakReference<>(this);
        worker.execute(() -> {
            LocalPortraitContext.State savedContext = null;
            boolean failed = false;
            try {
                data.feedback(value);
                savedContext = data.portraitContext();
            } catch (RuntimeException failure) {
                failed = true;
            }
            LocalPortraitContext.State result = savedContext;
            boolean error = failed;
            dispatcher.post(() -> {
                LocalAnalyticsDialog page = target.get();
                if (page == null || page.closed || page.generation != request) return;
                page.feedbackButton.setEnabled(true);
                if (error) {
                    page.feedbackButton.setText("反馈保存未确认 · 点击重试");
                } else {
                    if (result != null) page.currentContext = result;
                    page.feedbackButton.setText("最近一次侧写反馈 · " + (value.isEmpty() ? "尚未填写" : value));
                }
            });
        });
    }

    private void showContextDialog() {
        AlertDialog shown = contextDialog;
        if (shown == null) return;
        shown.setOnDismissListener(ignored -> { if (contextDialog == shown) contextDialog = null; });
        shown.setOnShowListener(ignored -> {
            if (activity != null) HostDialogBounds.install(activity, shown, true);
            if (shown.getWindow() != null) ModuleTheme.bind(shown.getWindow().getDecorView());
        });
        shown.show();
    }

    private void render(AnalyticsSnapshot data) {
        currentSnapshot = data;
        report.removeAllViews();
        chartAnalysisSlots.clear();
        boolean portrait = !data.portrait.days.isEmpty();
        PortraitReportViews portraitViews = new PortraitReportViews(context, data.portrait, new PortraitReportViews.Host() {
            @Override public LinearLayout section(String title) { return LocalAnalyticsDialog.this.section(title); }
            @Override public void analysis(LinearLayout section, String key) { attachAnalysis(section, key); }
        });
        if (portrait) portraitViews.overview();
        LinearLayout overview = section("浏览概览");
        metric(overview, "刷到次数", data.totalViews);
        metric(overview, "不同作品", data.uniqueWorks);
        metric(overview, "模块功能使用", data.totalFeatureUses);
        if (data.advertisementViews > 0) metric(overview, "其中广告作品", data.advertisementViews);
        caption(overview, "只统计开启记录后实际展示的作品；重复刷到会累加次数，不同作品按作品 ID 去重。功能次数表示已接入统计的实际触发，不代表开关数量。");
        if (data.totalViews == 0 && data.totalFeatureUses == 0) {
            TextView empty = text(enabled ? "这个时间范围还没有记录。继续刷作品或使用模块功能后，点击刷新查看。"
                    : "还没有记录。开启上方开关后开始收集；不会补录过去的浏览历史。", 14, MUTED, false);
            empty.setTag("analytics.empty");
            overview.addView(empty, matchWrap());
        }
        attachAnalysis(overview, "overview");
        if (portrait) {
            portraitViews.charts();
            attachAnalysis(ranking("模块功能排行 Top 10", data.features, "暂无模块功能使用记录"), "features");
        } else {
        LinearLayout trend = section("浏览趋势");
        TextView trendValue = text("点击折线上的位置查看具体次数", 13, BLUE, true);
        trendValue.setTag("analytics.trend.selection");
        trend.addView(trendValue, matchWrap());
        if (data.trend.isEmpty()) caption(trend, "暂无趋势数据");
        else {
            TrendChart chart = new TrendChart(context, data.trend, index -> {
                Bucket point = data.trend.get(index);
                trendValue.setText(point.label + " · " + number(point.count) + " 次");
                trendValue.announceForAccessibility(trendValue.getText());
            });
            chart.setTag("analytics.trend.chart");
            trend.addView(chart, new LinearLayout.LayoutParams(-1, dp(180)));
            long max = 0;
            String label = "";
            for (Bucket item : data.trend) if (item.count >= max) { max = item.count; label = item.label; }
            caption(trend, "共 " + number(data.totalViews) + " 次 · 最高 " + label + "：" + number(max) + " 次");
        }
        attachAnalysis(trend, "trend");

        LinearLayout types = section("作品类型占比");
        long typeTotal = total(data.types);
        if (typeTotal > 0) {
            DonutChart donut = new DonutChart(context, data.types);
            donut.setTag("analytics.types.chart");
            types.addView(donut, new LinearLayout.LayoutParams(-1, dp(210)));
            int index = 0;
            for (Rank type : data.types) {
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                View swatch = new View(context);
                swatch.setBackground(rounded(PALETTE[index++ % PALETTE.length], 3));
                row.addView(swatch, new LinearLayout.LayoutParams(dp(9), dp(9)));
                TextView label = text(type.label, 13, TEXT, false);
                label.setPadding(dp(8), dp(7), dp(4), dp(7));
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                TextView value = text(number(type.count) + " · " + percent(type.count, typeTotal), 13, MUTED, false);
                row.addView(value, new LinearLayout.LayoutParams(-2, -2));
                row.setContentDescription(type.label + "，" + number(type.count) + " 次，占 " + percent(type.count, typeTotal));
                types.addView(row, matchWrap());
            }
        } else caption(types, "暂无作品类型数据");
        caption(types, "Live 指实况照片，含任意实况照片的混合图集计为一次实况图集。长视频为时长 ≥ 5 分钟的视频；无法识别的内容列为未知/其他作品。直播卡片单列，不代表进入直播间或观看时长。");
        attachAnalysis(types, "types");
        attachAnalysis(ranking("作者排行 Top 10", data.authors, "暂无作者记录"), "authors");
        attachAnalysis(ranking("话题排行 Top 10", data.topics, "暂无话题记录；无话题的作品不计入此排行"), "topics");
        attachAnalysis(ranking("模块功能排行 Top 10", data.features, "暂无模块功能使用记录"), "features");

        LinearLayout hours = section("浏览时段分布");
        HourChart histogram = new HourChart(context, data.hours);
        histogram.setTag("analytics.hours.chart");
        hours.addView(histogram, new LinearLayout.LayoutParams(-1, dp(180)));
        int peakHour = 0;
        for (int index = 1; index < data.hours.length; index++) if (data.hours[index] > data.hours[peakHour]) peakHour = index;
        caption(hours, data.totalViews == 0 ? "暂无浏览时段数据" : "按记录时的本地时间统计 · 最活跃 "
                + String.format(Locale.ROOT, "%02d:00", peakHour) + "，" + number(data.hours[peakHour]) + " 次");
        attachAnalysis(hours, "hours");
        }

        LinearLayout recent = section("最近记录");
        if (data.recent.isEmpty()) caption(recent, "暂无最近记录");
        for (Recent event : data.recent) {
            TextView label = text(event.label, 14, TEXT, false);
            label.setPadding(0, dp(8), 0, dp(2));
            recent.addView(label, matchWrap());
            caption(recent, formatDate(event.timestamp, "MM-dd HH:mm") + " · "
                    + ("feature".equals(event.kind) ? "模块功能" : "浏览作品"));
            View divider = new View(context);
            divider.setBackgroundColor(LINE);
            recent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }

        LinearLayout history = section("使用历程");
        metric(history, "活跃天数", data.activeDays);
        metric(history, "连续活跃天数", data.currentStreak);
        TextView first = text("首次记录  " + (data.firstSeen == 0 ? "暂无" : formatDate(data.firstSeen, "yyyy/MM/dd")), 14, TEXT, true);
        history.addView(first, matchWrap());
        caption(history, "活跃天数与连续天数基于所选范围内保留的记录；空白日期可能是采集关闭或未覆盖。连续活跃允许今天尚未产生记录。首次记录从上次清空后计算，可早于 365 天。 ");
        LinearLayout analysis = section("AI 图表分析");
        analysis.setTag("analytics.analysis.card");
        analysisBody = column();
        analysis.addView(analysisBody, matchWrap());
        renderAnalysis();
    }

    private void renderAnalysis() {
        if (closed || analysisBody == null) return;
        analysisBody.removeAllViews();
        if (analysisResult != null) {
            caption(analysisBody, rangeLabel(analyzedDays) + " · " + analyzedModel + " · "
                    + formatDate(analyzedAt, "MM-dd HH:mm")
                    + "\n基于本次图表统计。刷新图表或切换时间范围后需重新分析，不会自动请求 AI。");
            TextView result = text("分析已显示在各图表下方", 14, BLUE, true);
            result.setTag("analytics.analysis.result");
            result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            analysisBody.addView(result, matchWrap());
            Button share = button("分享", "analytics.analysis.share", this::shareAnalysis);
            analysisBody.addView(share, new LinearLayout.LayoutParams(-1, dp(48)));
        } else {
            boolean configured;
            String model;
            try {
                configured = analysisService.isConfigured();
                model = analysisOperation == null ? analysisService.modelLabel() : analyzedModel;
            } catch (RuntimeException failure) {
                configured = false;
                model = "未配置模型";
            }
            caption(analysisBody, "仅在点击下方按钮时，将当前范围图表、前期及 7/30 天对照、侧写指标、关联统计和你填写的使用目的（含作者、话题和模块功能排行）发送给所选模型："
                    + model + "。日常采集只存本地，不自动请求 AI。结果仅保留在当前页面。\nAI 的判断仅供参考，图表统计不代表实际观看时长。");
            if (analysisOperation != null) {
                TextView progress = text("正在请求 AI 分析…", 14, BLUE, false);
                progress.setTag("analytics.analysis.progress");
                progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                analysisBody.addView(progress, matchWrap());
                analysisBody.addView(button("取消分析", "analytics.analysis.cancel", () -> {
                    resetAnalysis();
                    analysisError = "已取消分析，可重新请求。";
                    renderAnalysis();
                }), new LinearLayout.LayoutParams(-1, dp(48)));
            } else {
                if (analysisError != null) {
                    TextView error = text(analysisError, 13, MUTED, false);
                    error.setTag("analytics.analysis.error");
                    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    analysisBody.addView(error, matchWrap());
                }
                boolean hasData = currentSnapshot != null
                        && (currentSnapshot.totalViews > 0 || currentSnapshot.totalFeatureUses > 0);
                if (!configured) caption(analysisBody, "请先配置并启用 AI 模型，再刷新本页。");
                else if (!hasData) caption(analysisBody, "当前范围暂无可分析数据，产生记录后即可分析。");
                Button request = button(analysisError == null ? "请求 AI 分析" : "重新请求 AI 分析",
                        "analytics.analysis.request", this::requestAnalysis);
                request.setEnabled(configured && hasData);
                analysisBody.addView(request, new LinearLayout.LayoutParams(-1, dp(48)));
            }
        }
        ModuleTheme.bind(root);
    }

    private void attachAnalysis(LinearLayout chartCard, String key) {
        chartCard.setTag("analytics.chartcard." + key);
        LinearLayout slot = column();
        slot.setTag("analytics.analysis.section." + key);
        chartCard.addView(slot, matchWrap());
        chartAnalysisSlots.put(key, slot);
        renderChartAnalysis(key, slot);
    }

    private void renderChartAnalysis(String key, LinearLayout slot) {
        slot.removeAllViews();
        if (analysisResult == null) return;
        if (("portrait".equals(key) || "changes".equals(key) || "associations".equals(key)) && !analysisResult.hasPortrait()) return;
        TextView heading = text("AI 解读", 13, BLUE, true);
        heading.setPadding(0, dp(16), 0, dp(4));
        slot.addView(heading, matchWrap());
        caption(slot, "基于 " + formatDate(analyzedAt, "MM-dd HH:mm") + " 请求时的图表统计");
        String content;
        switch (key) {
            case "overview": content = analysisResult.overview; break;
            case "trend": content = analysisResult.trend; break;
            case "types": content = analysisResult.types; break;
            case "authors": content = analysisResult.authors; break;
            case "topics": content = analysisResult.topics; break;
            case "features": content = analysisResult.features; break;
            case "hours": content = analysisResult.hours; break;
            case "portrait": content = analysisResult.portraitTitle + "\n\n" + analysisResult.portrait; break;
            case "changes": content = analysisResult.contrasts; break;
            case "associations": content = analysisResult.questions; break;
            default: return;
        }
        TextView result = text(content, 14, TEXT, false);
        result.setTag("analytics.analysis.text." + key);
        result.setTextIsSelectable(true);
        result.setLineSpacing(dp(4), 1);
        slot.addView(result, matchWrap());
    }

    private void showChartAnalyses() {
        ScrollView scroll = root.findViewWithTag("analytics.scroll");
        int previousScroll = scroll.getScrollY();
        // Update each existing chart in place: its selection and accessibility focus survive.
        for (Map.Entry<String, LinearLayout> entry : chartAnalysisSlots.entrySet()) {
            renderChartAnalysis(entry.getKey(), entry.getValue());
        }
        renderAnalysis();
        scroll.post(() -> { if (!closed) scroll.scrollTo(0, previousScroll); });
    }

    private void requestAnalysis() {
        if (closed || analysisOperation != null || analysisResult != null || currentSnapshot == null
                || currentSnapshot.totalViews == 0 && currentSnapshot.totalFeatureUses == 0) return;
        AnalysisService.Request request;
        try {
            if (!analysisService.isConfigured()) throw new IllegalStateException();
            request = analysisService.prepare(currentSnapshot, days, currentContext == null ? "" : currentContext.purpose);
            analyzedModel = request.modelLabel();
        } catch (RuntimeException failure) {
            analysisError = "无法准备分析，请检查所选 AI 模型配置后重试。";
            renderAnalysis();
            return;
        }
        int token = ++analysisGeneration;
        int range = days;
        analyzedDays = range;
        analyzedAt = System.currentTimeMillis();
        analysisError = null;
        AnalysisOperation operation = new AnalysisOperation(request);
        analysisOperation = operation;
        renderAnalysis();
        Handler dispatcher = main;
        WeakReference<LocalAnalyticsDialog> target = new WeakReference<>(this);
        analysisWorker.execute(() -> {
            if (operation.cancelled) return;
            AnalyticsAiAnalysis.Result response = null;
            try {
                response = AnalyticsAiAnalysis.parseResult(operation.request.run());
            } catch (Exception ignored) {
                // Provider errors can contain request bodies or credentials; never display them.
            }
            if (operation.cancelled) return;
            AnalyticsAiAnalysis.Result result = response;
            dispatcher.post(() -> {
                LocalAnalyticsDialog page = target.get();
                if (page == null || page.closed || page.analysisGeneration != token || page.days != range
                        || page.analysisOperation != operation || operation.cancelled) return;
                page.analysisOperation = null;
                page.analysisResult = result;
                page.analysisError = result == null ? "分析失败或模型未返回完整图表解读，请检查网络及模型配置后重试。" : null;
                page.showChartAnalyses();
            });
        });
    }

    private void shareAnalysis() {
        if (closed || analysisResult == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "DouAi · 人物侧写与图表分析")
                .putExtra(Intent.EXTRA_TEXT, "DouAi · 人物侧写与图表分析\n" + rangeLabel(analyzedDays)
                        + " · " + formatDate(analyzedAt, "yyyy-MM-dd HH:mm") + "\n\n" + analysisResult.shareText());
        Intent chooser = Intent.createChooser(send, "分享 AI 图表分析");
        if (activity == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooser);
            LocalAnalytics.feature("analytics_share", "打开分析分享");
        }
        catch (RuntimeException failure) {
            if (analysisBody != null) caption(analysisBody, "无法打开系统分享面板，可长按分析文字复制。");
        }
    }

    private void resetAnalysis() {
        analysisGeneration++;
        AnalysisOperation operation = analysisOperation;
        analysisOperation = null;
        if (operation != null) operation.cancel();
        analysisResult = null;
        analysisError = null;
        analyzedModel = null;
        analyzedAt = 0;
    }

    private static String rangeLabel(int days) {
        return days == 0 ? "全部（最近 365 天）" : days == 1 ? "今天" : "最近 " + days + " 天";
    }

    private static final class AnalysisOperation {
        final AnalysisService.Request request;
        volatile boolean cancelled;
        AnalysisOperation(AnalysisService.Request request) { this.request = request; }
        void cancel() {
            cancelled = true;
            try { request.cancel(); } catch (RuntimeException ignored) { }
        }
    }

    private LinearLayout ranking(String title, List<Rank> entries, String empty) {
        LinearLayout card = section(title);
        if (entries.isEmpty()) { caption(card, empty); return card; }
        long max = 1;
        for (Rank item : entries) max = Math.max(max, item.count);
        for (int index = 0; index < Math.min(10, entries.size()); index++) {
            Rank item = entries.get(index);
            LinearLayout line = new LinearLayout(context);
            line.setGravity(Gravity.TOP);
            TextView label = text((index + 1) + "  " + item.label, 13, TEXT, false);
            label.setPadding(0, dp(7), dp(10), dp(5));
            line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView count = text(number(item.count), 13, MUTED, true);
            count.setPadding(0, dp(7), 0, dp(5));
            line.addView(count, new LinearLayout.LayoutParams(-2, -2));
            card.addView(line, matchWrap());
            Bar bar = new Bar(context, item.count / (float) max, PALETTE[index % PALETTE.length]);
            bar.setContentDescription(item.label + "，" + number(item.count) + " 次");
            card.addView(bar, new LinearLayout.LayoutParams(-1, dp(7)));
        }
        return card;
    }

    private void metric(LinearLayout parent, String label, long count) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 14, MUTED, false);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(number(count), 23, BLUE, true);
        value.setPadding(dp(8), dp(4), 0, dp(4));
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        row.setContentDescription(label + "，" + number(count));
        parent.addView(row, matchWrap());
    }

    private LinearLayout section(String title) {
        LinearLayout card = card(report);
        TextView heading = text(title, 17, TEXT, true);
        heading.setPadding(0, 0, 0, dp(12));
        heading.setAccessibilityHeading(true);
        card.addView(heading, matchWrap());
        return card;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(Color.WHITE, 16));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(14);
        parent.addView(card, params);
        return card;
    }

    private void caption(LinearLayout parent, String value) {
        TextView text = text(value, 12, MUTED, false);
        text.setLineSpacing(dp(3), 1);
        text.setPadding(0, dp(6), 0, dp(6));
        parent.addView(text, matchWrap());
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button button(String label, String tag, Runnable action) {
        Button button = new Button(context);
        button.setTag(tag);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(BLUE);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(Color.TRANSPARENT, 12));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private static long total(List<Rank> ranks) {
        long result = 0;
        for (Rank item : ranks) result += item.count;
        return result;
    }
    private static String percent(long count, long total) {
        return String.format(Locale.ROOT, "%.2f%%", total == 0 ? 0 : count * 100.0 / total);
    }
    private static String number(long count) { return String.format(Locale.US, "%,d", count); }
    private static String formatDate(long timestamp, String pattern) {
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(timestamp));
    }
    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        resetAnalysis();
        generation++;
        main.removeCallbacksAndMessages(null);
        // A confirmed clear or opt-out must finish even if the report closes immediately.
        if (worker instanceof ExecutorService) ((ExecutorService) worker).shutdown();
        if (analysisWorker instanceof ExecutorService) ((ExecutorService) analysisWorker).shutdownNow();
        if (clearConfirmation != null) clearConfirmation.dismiss();
        clearConfirmation = null;
        if (contextDialog != null) contextDialog.dismiss();
        contextDialog = null;
    }

    private abstract static class Chart extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        Chart(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        float d(float value) { return value * density; }
        int color(int light) { return ModuleTheme.color(getContext(), light); }
        void label(Canvas canvas, String value, float x, float y, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(MUTED));
            paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextAlign(align);
            canvas.drawText(value, x, y, paint);
        }
        void line(Canvas canvas, float x1, float y1, float x2, float y2) {
            paint.setColor(color(LINE));
            paint.setStrokeWidth(d(1));
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
        @Override protected void onConfigurationChanged(Configuration config) {
            super.onConfigurationChanged(config);
            invalidate();
        }
    }

    private static final class Bar extends Chart {
        private final float fraction;
        private final int barColor;
        Bar(Context context, float fraction, int barColor) {
            super(context);
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.barColor = barColor;
        }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(color(0xFFEEEFF3));
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), d(4), d(4), paint);
            paint.setColor(barColor);
            canvas.drawRoundRect(0, 0, getWidth() * fraction, getHeight(), d(4), d(4), paint);
        }
    }

    private static final class TrendChart extends Chart {
        private final List<Bucket> values;
        private final IntConsumer selection;
        private final Path linePath = new Path();
        private int selected = -1;
        private float downX, downY;
        TrendChart(Context context, List<Bucket> values, IntConsumer selection) {
            super(context);
            this.values = values;
            this.selection = selection;
            setClickable(true);
            setFocusable(true);
            StringBuilder summary = new StringBuilder("浏览趋势。点击图表选择日期；辅助功能点击可依次查看。");
            for (Bucket value : values) summary.append(value.label).append(' ').append(number(value.count)).append(" 次；");
            setContentDescription(summary.toString());
        }
        @Override protected void onDraw(Canvas canvas) {
            if (values.isEmpty()) return;
            long max = 1;
            for (Bucket value : values) max = Math.max(max, value.count);
            paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            float left = Math.min(getWidth() * .38f, Math.max(d(32), paint.measureText(number(max)) + d(8)));
            float right = getWidth() - d(9), top = d(18), bottom = getHeight() - d(30);
            float width = Math.max(1, right - left);
            for (int row = 0; row <= 2; row++) {
                float y = top + (bottom - top) * row / 2;
                line(canvas, left, y, right, y);
                label(canvas, number(Math.round(max * (2 - row) / 2.0)), left - d(6), y + d(4), Paint.Align.RIGHT);
            }
            linePath.reset();
            for (int index = 0; index < values.size(); index++) {
                float x = values.size() == 1 ? left + width / 2 : left + width * index / (values.size() - 1);
                float y = bottom - (bottom - top) * values.get(index).count / max;
                if (index == 0) linePath.moveTo(x, y); else linePath.lineTo(x, y);
            }
            paint.setColor(color(BLUE));
            paint.setStrokeWidth(d(2.5f));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(linePath, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int index = 0; index < values.size(); index++) {
                if (values.size() > 31 && index != selected) continue;
                float x = values.size() == 1 ? left + width / 2 : left + width * index / (values.size() - 1);
                float y = bottom - (bottom - top) * values.get(index).count / max;
                canvas.drawCircle(x, y, d(index == selected ? 5 : 2.5f), paint);
            }
            label(canvas, values.get(0).label, left, getHeight() - d(8), Paint.Align.LEFT);
            if (values.size() > 1) label(canvas, values.get(values.size() - 1).label, right, getHeight() - d(8), Paint.Align.RIGHT);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY) < d(20)) {
                    float fraction = (event.getX() - d(42)) / Math.max(1, getWidth() - d(51));
                    selected = Math.max(0, Math.min(values.size() - 1, Math.round(fraction * (values.size() - 1))));
                    if (selected >= 0) { selection.accept(selected); invalidate(); }
                    // Accessibility services invoke performClick directly to cycle through points.
                    super.performClick();
                }
                return true;
            }
            return event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        }
        @Override public boolean performClick() {
            super.performClick();
            if (values.isEmpty()) return false;
            selected = (selected + 1) % values.size();
            selection.accept(selected);
            invalidate();
            return true;
        }
    }

    private static final class DonutChart extends Chart {
        private final List<Rank> values;
        private final RectF bounds = new RectF();
        DonutChart(Context context, List<Rank> values) {
            super(context);
            this.values = values;
            StringBuilder summary = new StringBuilder("作品类型占比：");
            long total = total(values);
            for (Rank item : values) summary.append(item.label).append(' ').append(number(item.count))
                    .append(" 次，").append(percent(item.count, total)).append("；");
            setContentDescription(summary.toString());
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * .39f;
            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            long total = total(values);
            float angle = -90;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(radius * .32f);
            for (int index = 0; index < values.size(); index++) {
                float sweep = total == 0 ? 0 : 360f * values.get(index).count / total;
                paint.setColor(PALETTE[index % PALETTE.length]);
                // Do not discard a small nonzero category by imposing a minimum slice gap.
                canvas.drawArc(bounds, angle, sweep, false, paint);
                angle += sweep;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(25 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setColor(color(TEXT));
            String count = number(total);
            if (paint.measureText(count) > radius * 1.4f) paint.setTextSize(paint.getTextSize() * radius * 1.4f / paint.measureText(count));
            canvas.drawText(count, cx, cy + d(2), paint);
            label(canvas, "刷到次数", cx, cy + d(24), Paint.Align.CENTER);
        }
    }

    private static final class HourChart extends Chart {
        private final long[] counts;
        HourChart(Context context, long[] counts) {
            super(context);
            this.counts = counts;
            StringBuilder summary = new StringBuilder("浏览时段分布：");
            for (int hour = 0; hour < counts.length; hour++) summary.append(hour).append(" 时 ")
                    .append(number(counts[hour])).append(" 次；");
            setContentDescription(summary.toString());
        }
        @Override protected void onDraw(Canvas canvas) {
            long max = 1;
            for (long count : counts) max = Math.max(max, count);
            paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            float left = Math.min(getWidth() * .38f, Math.max(d(32), paint.measureText(number(max)) + d(8)));
            float top = d(16), bottom = getHeight() - d(28), right = getWidth() - d(4);
            for (int row = 0; row <= 2; row++) {
                float y = top + (bottom - top) * row / 2;
                line(canvas, left, y, right, y);
                label(canvas, number(Math.round(max * (2 - row) / 2.0)), left - d(5), y + d(4), Paint.Align.RIGHT);
            }
            float unit = (right - left) / 24;
            for (int hour = 0; hour < Math.min(24, counts.length); hour++) {
                paint.setColor(PALETTE[1]);
                float height = (bottom - top) * counts[hour] / max;
                canvas.drawRoundRect(left + unit * hour + d(1), bottom - height,
                        left + unit * (hour + 1) - d(1), bottom, d(2), d(2), paint);
                if (hour % 6 == 0 || hour == 23) label(canvas, String.valueOf(hour), left + unit * (hour + .5f),
                        getHeight() - d(7), Paint.Align.CENTER);
            }
        }
    }
}

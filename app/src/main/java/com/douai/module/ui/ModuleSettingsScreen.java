package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AiProviderPresets;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.feature.AutomationScopePolicy;
import com.douai.module.feature.FeedGestureToolsHook;
import com.douai.module.feature.FollowListSnapshot;
import com.douai.module.feature.TopTabFilterHook;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.feature.ProfileListEntry;
import com.douai.module.feature.GroupReplyPolicy;
import com.douai.module.feature.ReplyScopePolicy;
import com.douai.module.feature.ContentScopePolicy;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.ui.miuix.HostComposeEnvironment;
import com.douai.module.ui.miuix.MiuixComponentHidePage;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixExtraControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Shared settings behavior with Miuix host UI and a native recovery UI. */
public final class ModuleSettingsScreen {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int ACCENT = Color.rgb(0, 151, 136);
    private static final int LINE = Color.rgb(226, 231, 232);

    private final Context context;
    private final SettingsStore repository;
    private final Runnable onClose;
    private FeatureSettings settings;
    private LinearLayout content;
    private TextView scanTitle;
    private TextView scanDetail;
    private View scanMark;
    private TextView syncTitle;
    private TextView syncDetail;
    private View syncMark;
    private ComponentHideSettingsPage componentHidePage;
    private Dialog categoryDialog;
    private final List<Dialog> optionDialogs = new ArrayList<>();
    private final java.util.Map<Dialog, Runnable> optionDismissActions = new java.util.HashMap<>();
    private final List<EnabledControl> feedFilterControls = new ArrayList<>();
    private final List<CategoryRow> categoryRows = new ArrayList<>();
    private MiuixSettingsViews.Home miuixHome;
    private MiuixComponentHidePage miuixComponentHidePage;
    private Context miuixRowContext;

    public ModuleSettingsScreen(Context context, SettingsStore repository) {
        this(context, repository, null);
    }

    public ModuleSettingsScreen(Context context, SettingsStore repository, Runnable onClose) {
        this.context = context;
        this.repository = repository;
        this.onClose = onClose;
        this.settings = repository.load();
    }

    public View createView() {
        if (miuixHome != null) {
            miuixHome.close();
            miuixHome = null;
        }
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(248, 250, 250));
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                dismissChildPages();
            }
        });

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addTitle("DouAi", "抖音 40.6.0");
        addScanStatus(ScanStatus.pending());
        if (repository.isInHost()) {
            addHostSettingsNotice();
        } else {
            addFrameworkSyncStatus();
            addIndependentSettingsNotice();
            return scroll;
        }
        addCategorySearch();
        addCategoryList();
        return scroll;
    }

    public View createMiuixView() {
        Activity activity = findOwnerActivity(context);
        if (activity == null || !repository.isInHost()) return createView();
        Category[] categories = Category.values();
        String[] titles = new String[categories.length];
        int[] counts = new int[categories.length];
        for (int index = 0; index < categories.length; index++) {
            titles[index] = categories[index].title;
            counts[index] = categories[index].itemCount;
        }
        miuixHome = new MiuixSettingsViews.Home(activity, titles, counts,
                index -> openCategory(categories[index]), () -> { if (onClose != null) onClose.run(); });
        View home = miuixHome.getView();
        home.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                dismissChildPages();
                miuixHome.close();
            }
        });
        return home;
    }

    public void bindMiuixBackNavigation(Dialog dialog) {
        if (miuixHome != null) miuixHome.bindBackNavigation(dialog);
    }

    private void addCategorySearch() {
        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint("搜索功能");
        search.setContentDescription("搜索设置分类");
        search.setPadding(dp(16), dp(8), dp(16), dp(8));
        search.setBackground(makeRounded(Color.rgb(238, 239, 243), LINE, dp(24)));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(18);
        content.addView(search, params);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (CategoryRow row : categoryRows) {
                    row.view.setVisibility(query.isEmpty() || row.category.title.toLowerCase(java.util.Locale.ROOT).contains(query)
                            ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void addCategoryList() {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(makeRounded(Color.WHITE, LINE, dp(8)));
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = dp(14);
        content.addView(list, listParams);
        categoryRows.clear();
        for (Category category : Category.values()) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(15), dp(14), dp(15));
            row.setMinimumHeight(dp(58));
            TextView title = text(category.title, 16, TEXT, true);
            row.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView count = text(category.itemCount == 0 ? "未适配" : category.itemCount + "项",
                    13, MUTED, false);
            count.setGravity(Gravity.CENTER);
            count.setPadding(dp(10), dp(4), dp(10), dp(4));
            count.setBackground(makeRounded(Color.rgb(242, 243, 247),
                    Color.rgb(242, 243, 247), dp(16)));
            row.addView(count, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView arrow = text("›", 24, MUTED, false);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(32)));
            row.setFocusable(true);
            row.setContentDescription(category.title);
            row.setTag("settings.category." + category.name());
            row.setOnClickListener(ignored -> openCategory(category));
            list.addView(row, matchWrap());
            categoryRows.add(new CategoryRow(category, row));
            if (category != Category.values()[Category.values().length - 1]) addDividerTo(list);
        }
    }

    private void openCategory(Category category) {
        settings = repository.load();
        if (category == Category.COMPONENT_HIDE) {
            if (miuixHome != null) {
                if (miuixComponentHidePage == null) {
                    miuixComponentHidePage = new MiuixComponentHidePage(context, repository);
                }
                miuixComponentHidePage.show();
                return;
            }
            if (componentHidePage != null && componentHidePage.isShowing()) return;
            componentHidePage = new ComponentHideSettingsPage(context, repository);
            componentHidePage.show();
            return;
        }
        if (categoryDialog != null && categoryDialog.isShowing()) return;
        Dialog dialog = new Dialog(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        Activity owner = findOwnerActivity(context);
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        dialog.setOwnerActivity(owner);
        dialog.setCanceledOnTouchOutside(true);
        HostComposeEnvironment session = miuixHome == null ? null
                : new HostComposeEnvironment(owner, dialog::dismiss);
        dialog.setOnDismissListener(ignored -> {
            if (session != null) session.close();
            if (categoryDialog == dialog) {
                dismissOptionDialogs();
                categoryDialog = null;
            }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout oldContent = content;
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(12));
        content.setBackgroundColor(Color.TRANSPARENT);
        feedFilterControls.clear();
        miuixRowContext = session == null ? null : session.context();
        try {
            buildCategoryControls(category);
        } finally {
            miuixRowContext = null;
        }
        LinearLayout categoryContent = content;
        content = oldContent;
        scroll.addView(categoryContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (session != null) {
            dialog.setContentView(MiuixSettingsViews.categoryPage(
                    session, category.title, scroll, dialog::dismiss));
        } else {
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(12), 0, dp(12), dp(12));
            panel.setBackground(makeRounded(Color.rgb(245, 246, 249), LINE, dp(8)));
            panel.addView(createCategoryHeader(category), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
            panel.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            dialog.setContentView(panel);
        }
        categoryDialog = dialog;
        try {
            dialog.show();
            if (session != null) session.bindBackNavigation(dialog);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
            }
            HostDialogBounds.install(owner, dialog, false);
        } catch (RuntimeException failure) {
            if (session != null) session.close();
            categoryDialog = null;
        }
    }

    private View createCategoryHeader(Category category) {
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹ 根目录", 16, Color.rgb(0, 133, 225), false);
        back.setContentDescription("返回设置分类");
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setOnClickListener(ignored -> {
            if (categoryDialog != null) categoryDialog.dismiss();
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView title = text(category.title, 20, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        header.addView(new View(context), new LinearLayout.LayoutParams(dp(72), 1));
        return header;
    }

    private void buildCategoryControls(Category category) {
        switch (category) {
            case BASIC:
                addLocalSwitch("去除启动和闪屏广告", "关闭启动广告展示入口。", "remove_splash_ads");
                addLocalSwitch("去除更新弹窗", "阻止应用内更新提示。", "remove_update_dialog");
                addLocalSwitch("去除青少年弹窗", "关闭青少年模式引导提示。", "remove_teen_dialog");
                break;
            case INTERFACE:
                addLocalSwitch("悬浮条", "当前视频页的快捷工具条。", "toolbar_enabled");
                addLocalSwitch("隐藏拍摄+号图标", "保留原按钮点击区域。", "hide_camera_plus");
                addLocalSwitch("隐藏上下黑边", "隐藏视频页顶部和底部留白。", "hide_video_black_edges");
                addLocalSwitch("禁用双击点赞", "关闭视频双击点赞动作。", "disable_double_tap_like");
                addLocalSwitch("不允许系统跳过已隐藏视频", "阻止当前信息流的删除跳过动作。", "prevent_skip_hidden");
                addLocalSwitch("不允许中途插入视频", "阻止播放过程中追加插播内容。", "prevent_insert_video");
                addLocalSwitch("拦截长按倍数启用场景", "关闭抖音自带的长按倍速入口。", "block_long_press_speed");
                addLocalSwitch("拦截系统长按手势", "未设置快捷动作时阻止抖音原生长按。",
                        LocalOptions.INTERCEPT_NATIVE_LONG_PRESS);
                addOption("长按事件", "", null, true, () -> showGestureSettings("长按事件", "long_press"));
                addOption("双击事件", "", null, true, () -> showGestureSettings("双击事件", "double_tap"));
                addOption("沉浸式全屏", "", null, true, this::showSystemBarSettings);
                addSwitch("隐藏顶部Tab开关", "按名称隐藏抖音主页顶部 Tab。",
                        repository.getBoolean("hide_top_tabs", false), true,
                        value -> repository.putBoolean("hide_top_tabs", value));
                addOption("设置顶部Tab", "", repository.getString("hidden_top_tabs", ""),
                        true, this::showTopTabs);
                addSwitch("首页刷新确认", "重复点击首页标签时先确认。",
                        repository.getBoolean("confirm_refresh", false), true,
                        value -> repository.putBoolean("confirm_refresh", value));
                break;
            case FILTER:
                buildFeedFilters();
                break;
            case DOWNLOAD:
                ToggleControl downloadMenu = addSwitch("启用下载菜单", "控制全部扩展下载入口。", settings.downloadEnabled, true, false,
                        value -> update(s -> copy(s, value, null)));
                addSwitch("是否启用抖音下载自定义命名", "按模板保存视频、图集和音频。",
                        repository.getBoolean("download_custom_name", false), true, value -> {
                            if (value) downloadMenu.setChecked(true);
                            repository.putBoolean("download_custom_name", value);
                        });
                addEditableOption("文件名模板", "#author#、#desc#、#aid#、#date#、#index#、#ext#",
                        repository.getString("download_name_template", "#author#_#aid#_#index#.#ext#"),
                        false, true, false, value -> repository.putString("download_name_template", value));
                addSwitch("去除下载限制", "允许保存当前作品已有的可读取媒体来源。",
                        repository.getBoolean("remove_download_limits", false), true, value -> {
                            if (value) downloadMenu.setChecked(true);
                            repository.putBoolean("remove_download_limits", value);
                        });
                addSwitch("无水印下载图集或者视频",
                        "通过抖音原生长按下载入口保存；仅对已识别的无水印资源生效。",
                        settings.removeWatermark, true,
                        value -> update(s -> copy(s, null, value)));
                break;
            case AUTOPLAY:
                addSwitch("自动滑动到下一个视频", "播放结束后跟随最近一次手动滑动方向。",
                        repository.getBoolean("auto_play", false), true,
                        value -> repository.putBoolean("auto_play", value));
                addLocalSwitch("自动倍速播放", "切换视频时应用设定倍速。", "auto_speed");
                addEditableOption("倍速数值", "0.25 至 4", effectivePlaybackSpeed(
                                repository.getString("playback_speed", "2")), true, true, false,
                        value -> repository.putString("playback_speed", validatePlaybackSpeed(value)));
                addLocalSwitch("打开评论时,不滑动视频", "评论展开时暂停自动下一条。", "pause_auto_on_comments");
                addSwitch("滑动智能判断上下", "开启时跟随最近一次手动滑动方向。",
                        repository.getBoolean("smart_swipe", true), true,
                        value -> repository.putBoolean("smart_swipe", value));
                addOption("互动自动化", "", null, true, this::showAutomationSettings);
                break;
            case AUTO_REPLY:
                buildAutoReplyControls();
                break;
            case AUTO_COMMENT:
                buildAutoCommentControls();
                break;
            case COMMENT:
                addLocalSwitch("评论自动添加后缀", "在本人提交的文字评论末尾添加。", "comment_append_suffix");
                addEditableOption("后缀内容", "", repository.getString("comment_suffix", ""),
                        false, true, false, value -> repository.putString("comment_suffix", value));
                addSwitch("替换语音文件", "使用所选文件替换本人录制的评论语音。",
                        repository.getBoolean("comment_voice_replace", false), true, value -> {
                            repository.putBoolean("comment_voice_replace", value);
                            if (value) showAudioPathEditor("评论语音文件", "comment_voice_path");
                        });
                addLocalSwitch("评论自动文本转语音(文本前面输入\"#\"符号将自动转换)",
                        "由系统离线引擎生成，提交时进入原生语音上传。", "comment_tts");
                addCommentLongPressOption();
                addLocalSwitch("彩色评论", "使用抖音原生文字附加参数；显示取决于客户端支持。", "colored_comment");
                addChoiceOption("彩色评论类型", "colored_comment_type", new String[]{"类型 0", "类型 33"},
                        new String[]{"0", "33"}, "0");
                break;
            case STREAK:
                addSwitch("自动续火花", "每 30 秒检查，只对所选单聊每日发送一次。",
                        repository.getBoolean("streak_enabled", false), true, value -> {
                            repository.putBoolean("streak_enabled", value);
                            if (value) update(s -> withAutomation(s, true, null, null, null, null));
                        });
                addOption("选择用户", "", repository.getString("streak_targets", ""),
                        true, () -> showTargetSelection("选择用户", "streak_targets"));
                addEditableOption("续火花内容", "", repository.getString("streak_content", ""),
                        false, true, false, value -> repository.putString("streak_content", value));
                addLocalSwitch("续火花成功弹出Tip", "发送结果提醒。", "streak_tip");
                addOption("重试结果未知的续火花", "重新发送可能造成重复消息。", null, true,
                        this::confirmUnknownStreakRetry);
                addOption("清空续火花记录", "", null, true, () -> {
                    repository.putString("streak_reset_token", Long.toString(System.currentTimeMillis()));
                    android.widget.Toast.makeText(context, "续火花记录已清空", android.widget.Toast.LENGTH_SHORT).show();
                });
                addOption("续火花限制时间", "", null, true, this::showStreakWindow);
                addEditableOption("内容Api", "HTTPS，返回的文字优先于固定内容。",
                        repository.getString("streak_content_api", ""), false, true, false,
                        value -> repository.putString("streak_content_api", value));
                break;
            case MESSAGE:
                addLocalSwitch("消息防撤回", "保留本地已有的消息内容。", "prevent_message_recall");
                addSwitch("自己撤回的消息", "", repository.getBoolean("recall_self", true), true,
                        value -> repository.putBoolean("recall_self", value));
                addSwitch("对方撤回的消息", "", repository.getBoolean("recall_other", true), true,
                        value -> repository.putBoolean("recall_other", value));
                addColorOption();
                addSwitch("替换私聊语音", "替换本人录音完成后的语音文件。",
                        repository.getBoolean("dm_voice_replace", false), true, value -> {
                            repository.putBoolean("dm_voice_replace", value);
                            if (value) showAudioPathEditor("私聊语音文件", "dm_voice_path");
                        });
                addLocalSwitch("自动文本转语音(文本前面输入\"#\"符号将自动转换)",
                        "点击原生发送按钮时生成语音，失败时保留草稿。", "dm_tts");
                break;
            case SPOOF:
                addLocalSwitch("伪装总开关", "只影响本人主页的本地显示。", "spoof_profile");
                addProfileCount("获赞数量", "profile_like_count");
                addProfileCount("关注数量", "profile_following_count");
                addProfileCount("粉丝数量", "profile_follower_count");
                addProfileCount("互关数量", "profile_mutual_count");
                addOption("版本信息", "", null, true, this::showVersionSettings);
                break;
            case EXTENSION:
                addOption("AI 模型设置", "", null, true, this::showAiModelSettings);
                addOption("文本转语音设置", "", null, true, this::showSpeechSettings);
                addOption("关于", "", "DouAi " + com.douai.module.BuildConfig.VERSION_NAME, true,
                        () -> showOptionDialog(AboutDialog.create(context, miuixHome != null)));
                break;
            case COMPONENT_HIDE:
                break;
        }
    }

    private void buildFeedFilters() {
        addSwitch("过滤总开关", "按下列条件筛选推荐内容。", settings.feedFilterEnabled, true,
                false, value -> {
                    update(s -> withFeedEnabled(s, value));
                    setFeedFilterControlsEnabled(value);
                });
        addSwitch("中断开关", "整页筛空时保留原列表，跳过本次筛选。",
                repository.getBoolean("filter_interrupt", true), settings.feedFilterEnabled, true,
                value -> repository.putBoolean("filter_interrupt", value));
        addSwitch("去广告开关", "隐藏标记为广告的推荐内容。", settings.hideAds, settings.feedFilterEnabled,
                true, value -> update(s -> withFilter(s, value, Filter.AD)));
        addSwitch("去直播开关", "隐藏直播推荐内容。", settings.hideLives, settings.feedFilterEnabled,
                true, value -> update(s -> withFilter(s, value, Filter.LIVE)));
        addSwitch("去推荐卡片开关", "隐藏推荐卡片。", settings.hideRecommendationCards,
                settings.feedFilterEnabled, true, value -> update(s -> withFilter(s, value, Filter.CARD)));
        addSwitch("去推荐商家开关", "隐藏推荐商家。", settings.hideRecommendationMerchants,
                settings.feedFilterEnabled, true,
                value -> update(s -> withRecommendationMerchants(s, value)));
        addSwitch("去小游戏开关", "过滤明确标记小游戏的内容。",
                repository.getBoolean("hide_mini_games", false), settings.feedFilterEnabled, true,
                value -> repository.putBoolean("hide_mini_games", value));
        addSwitch("去长视频开关", "过滤长视频作品及超过设定时长的视频。",
                repository.getBoolean("hide_long_videos", false), settings.feedFilterEnabled, true,
                value -> repository.putBoolean("hide_long_videos", value));
        addEditableOption("长视频时间界定", "单位：秒，默认 120",
                repository.getString("maximum_duration_seconds", "120"), true,
                settings.feedFilterEnabled, true,
                value -> repository.putString("maximum_duration_seconds", value));
        addEditableOption("去低赞视频开关", "实际赞；0 表示关闭", settings.minimumLikesEnabled
                        ? Integer.toString(settings.minimumLikeCount) : "0",
                true, settings.feedFilterEnabled, true,
                value -> update(s -> withLikes(withFilter(s, parseNonNegative(value) > 0, Filter.LIKES),
                        parseNonNegative(value))));
        addSwitch("去图集开关", "隐藏图文或图集作品。", settings.hideImagePosts,
                settings.feedFilterEnabled, true,
                value -> update(s -> withFilter(s, value, Filter.IMAGE)));
        addEditableOption("过滤字符串", "文案、音乐标题、作者名等；多个词以逗号分隔，留空关闭",
                settings.keyword, false, settings.feedFilterEnabled, true,
                value -> update(s -> withKeyword(withFilter(s, !value.trim().isEmpty(), Filter.KEYWORD), value)));
        addSwitch("过滤短视频", "过滤低于设定时长的视频。", settings.minimumDurationEnabled,
                settings.feedFilterEnabled, true,
                value -> update(s -> withFilter(s, value, Filter.DURATION)));
        addEditableOption("最短时长（秒）", "", Integer.toString(settings.minimumDurationSeconds),
                true, settings.feedFilterEnabled, true,
                value -> update(s -> withDuration(s, parseNonNegative(value))));
        setFeedFilterControlsEnabled(settings.feedFilterEnabled);
    }

    private void buildAutoReplyControls() {
        ToggleControl[] master = new ToggleControl[1];
        master[0] = addSwitch("开启自动回复", repository.getBoolean("native_auto_reply", false) && !settings.automationEnabled
                        ? "已暂停：自动化总开关关闭" : "",
                repository.getBoolean("native_auto_reply", false), true, false, value -> {
                    repository.putBoolean("native_auto_reply", value);
                    if (value) update(s -> withAutomation(s, true, null, null, null, false));
                    master[0].setSummary(value && !settings.automationEnabled ? "已暂停：自动化总开关关闭" : "");
                });
        addChoiceOption("回复模式", "reply_mode", new String[]{"固定内容", "AI 模型", "关键词规则", "随机内容"},
                new String[]{"fixed", "ai", "keyword", "random"}, "fixed");
        addOption("回复内容", "", null, true, this::showReplyContentSettings);
        OptionRow[] privateRange = new OptionRow[1];
        addSwitch("接收私聊", "", repository.getBoolean("reply_private_chats", true), true,
                value -> { repository.putBoolean("reply_private_chats", value); privateRange[0].setValue(privateReplyPreview()); });
        privateRange[0] = addOption("私聊范围", "", privateReplyPreview(), true, () -> showPrivateReplySettings(privateRange[0]));
        OptionRow[] groupRange = new OptionRow[1];
        addSwitch("接收群聊", "", repository.getBoolean("reply_group_chats", false), true,
                value -> { repository.putBoolean("reply_group_chats", value); groupRange[0].setValue(groupReplyPreview()); });
        groupRange[0] = addOption("群聊范围", "", groupReplyPreview(), true, () -> showGroupReplySettings(groupRange[0]));
        addOption("发送与记录", "", null, true, this::showReplyDeliverySettings);
    }

    private static String scopeLabel(String mode) {
        return "whitelist".equals(mode) ? "白名单" : "blacklist".equals(mode) ? "黑名单" : "全部";
    }

    private String privateReplyPreview() {
        return repository.getBoolean("reply_private_chats", true)
                ? scopeLabel(ReplyScopePolicy.scopeMode(replyScopeOptions())) : "未启用";
    }

    private String groupReplyPreview() {
        return repository.getBoolean("reply_group_chats", false)
                ? scopeLabel(repository.getString("reply_group_scope_mode", "all")) : "未启用";
    }

    private void rebuildAutomationPanel(LinearLayout panel, Runnable build) {
        panel.removeAllViews();
        LinearLayout previous = content;
        content = panel;
        try { build.run(); } finally { content = previous; }
    }

    private void showAutomationPanel(String title, LinearLayout panel, Runnable build) {
        rebuildAutomationPanel(panel, build);
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title)
                .setView(scrolling(panel)).setNegativeButton("返回", null).create());
    }

    private void showReplyContentSettings() {
        String mode = repository.getString("reply_mode", "fixed");
        if ("keyword".equals(mode) || "random".equals(mode)) { showReplyPool("keyword".equals(mode)); return; }
        LinearLayout panel = form();
        showAutomationPanel("回复内容", panel, () -> {
            if ("ai".equals(mode)) {
                String selected = "未启用模型";
                try { selected = AiModelSettings.read(repository).selected().name; } catch (RuntimeException ignored) { }
                addOption("模型配置", "", selected, true, this::showAiModelSettings);
                addPromptOption("二级提示词", repository.getString("reply_prompt", "自然简洁地回复对方，只输出回复正文。"),
                        value -> repository.putString("reply_prompt", value));
            } else {
                addEditableOption("固定回复内容", "", repository.getString("auto_message_text", ""), false, true, false,
                        value -> repository.putString("auto_message_text", value));
            }
        });
    }

    private LocalOptions replyScopeOptions() {
        return automationScopeOptions("reply");
    }

    private LocalOptions automationScopeOptions(String prefix) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        for (String suffix : new String[]{"_scope_mode", "_scope_relations", "_scope_keywords", "_scope_fields", "_filter_mode"}) {
            String key = prefix + suffix;
            values.put(key, repository.getString(key, ""));
        }
        String targets = "reply".equals(prefix) ? "reply_targets" : prefix + "_scope_users";
        values.put(targets, repository.getString(targets, ""));
        values.put("reply_global", repository.getBoolean("reply_global", false));
        values.put(prefix + "_filter_keywords", repository.getString(prefix + "_filter_keywords", repository.getString(prefix + "_scope_keywords", "")));
        String selectedFields = repository.getString(prefix + "_filter_fields", null);
        if (selectedFields != null) values.put(prefix + "_filter_fields", selectedFields);
        return new LocalOptions(values);
    }

    private void prepareContentFilter(String prefix) {
        LocalOptions legacy = automationScopeOptions(prefix);
        if (repository.getString(prefix + "_filter_mode", "").isEmpty()) {
            repository.putString(prefix + "_filter_fields", ContentScopePolicy.fields(prefix, legacy));
            repository.putString(prefix + "_filter_keywords", ContentScopePolicy.keywords(prefix, legacy));
            repository.putString(prefix + "_filter_mode", ContentScopePolicy.filterMode(prefix, legacy));
            repository.putString(prefix + "_scope_mode", ContentScopePolicy.scopeMode(prefix, legacy));
        }
    }

    private void showPrivateReplySettings(OptionRow preview) {
        prepareContentFilter("reply");
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            String scope = ReplyScopePolicy.scopeMode(replyScopeOptions());
            addChoiceOption("用户范围", "reply_scope_mode", new String[]{"全部", "白名单", "黑名单"},
                    new String[]{"all", "whitelist", "blacklist"}, scope, () -> {
                        preview.setValue(privateReplyPreview());
                        rebuildAutomationPanel(panel, build[0]);
                    });
            if (!"all".equals(scope)) {
                addMultiChoiceCsvOption("关注关系", "reply_scope_relations", new String[]{"我关注的", "关注我的", "陌生人"},
                        new String[]{AutomationScopePolicy.FOLLOWING, AutomationScopePolicy.FOLLOWERS, AutomationScopePolicy.STRANGERS});
                addOption("指定用户", "", repository.getString("reply_targets", ""), true,
                        () -> showTargetSelection("指定用户", "reply_targets"));
            }
            addFollowListsOption();
            String filter = ReplyScopePolicy.filterMode(replyScopeOptions());
            addChoiceOption("消息筛选", "reply_filter_mode", new String[]{"不限", "包含关键词", "排除关键词"},
                    new String[]{"off", "include", "exclude"}, filter, () -> rebuildAutomationPanel(panel, build[0]));
            if (!ReplyScopePolicy.OFF.equals(filter)) {
                addEditableOption("筛选关键词", "", ReplyScopePolicy.keywords(replyScopeOptions()), false, true, false,
                        value -> repository.putString("reply_filter_keywords", value));
                addMultiChoiceCsvOption("匹配对象", "reply_filter_fields", new String[]{"发送者", "消息内容"},
                        new String[]{AutomationScopePolicy.AUTHOR, AutomationScopePolicy.CAPTION});
            }
        };
        showAutomationPanel("私聊范围", panel, build[0]);
    }

    private void showGroupReplySettings(OptionRow preview) {
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            String scope = repository.getString("reply_group_scope_mode", "all");
            addChoiceOption("群聊作用域", "reply_group_scope_mode",
                    new String[]{"全部", "白名单", "黑名单"}, new String[]{"all", "whitelist", "blacklist"}, "all", () -> {
                        preview.setValue(groupReplyPreview());
                        rebuildAutomationPanel(panel, build[0]);
                    });
            if (!"all".equals(scope)) {
                OptionRow[] groups = new OptionRow[1];
                groups[0] = addOption("选择群聊", "", selectedGroupPreview(), true, () -> showGroupSelection(groups[0]));
            }
            addChoiceOption("触发条件", "reply_group_mode", new String[]{"全员", "指定成员", "仅 @我"},
                    new String[]{"all", "selected", "mentions"}, "all", () -> rebuildAutomationPanel(panel, build[0]));
            if ("selected".equals(repository.getString("reply_group_mode", "all"))) {
                addOption("指定成员", "", repository.getString("reply_group_members", ""), true, this::showGroupMemberSelection);
            }
        };
        showAutomationPanel("群聊范围", panel, build[0]);
    }

    private void showReplyDeliverySettings() {
        showDeliverySettings(AutomationHistoryStore.REPLY);
    }

    private void showDeliverySettings(String kind) {
        boolean reply = AutomationHistoryStore.REPLY.equals(kind);
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            addEditableOption(reply ? "延迟回复" : "延迟评论", "", repository.getString(kind + "_delay_seconds", reply ? "2" : "3"), true, true, false, value -> {
                int seconds = Integer.parseInt(value.trim());
                if (seconds < 2 || seconds > 3600) throw new IllegalArgumentException("延迟应为 2 至 3600 秒");
                repository.putString(kind + "_delay_seconds", value);
            });
            addSwitch("悬浮提示", "", repository.getBoolean(kind + "_tip", false), true, value -> {
                repository.putBoolean(kind + "_tip", value);
                rebuildAutomationPanel(panel, build[0]);
            });
            if (repository.getBoolean(kind + "_tip", false)) {
                addEditableOption("提示超时（秒）", "", repository.getString(kind + "_notice_seconds", "8"), true, true, false, value -> {
                    int seconds = Integer.parseInt(value.trim());
                    if (seconds < 2 || seconds > 120) throw new IllegalArgumentException("提示超时应为 2 至 120 秒");
                    repository.putString(kind + "_notice_seconds", value);
                });
            }
            addLocalSwitch("保存历史记录", "", kind + "_save_history");
            addOption(reply ? "回复历史记录" : "评论历史记录", "", null, true, () -> {
                AutomationHistoryDialog history = new AutomationHistoryDialog(context, kind, miuixHome != null);
                showOptionDialog(history.dialog(), history::closeDetails);
            });
        };
        showAutomationPanel("发送与记录", panel, build[0]);
    }

    private String selectedGroupPreview() {
        int selected = GroupReplyPolicy.entries(repository.getString("reply_group_targets", "")).size();
        return selected == 0 ? "未选择" : "已选择 " + selected + " 个群聊";
    }

    private void showGroupSelection(OptionRow preview) {
        java.util.Map<String, String> groups = NativeMessagingHooks.availableGroups();
        java.util.Set<String> selected = GroupReplyPolicy.entries(repository.getString("reply_group_targets", ""));
        if (groups.isEmpty() && selected.isEmpty()) {
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("选择群聊")
                    .setMessage("暂无可用群会话。")
                    .setNegativeButton("返回", null).create());
            return;
        }
        showProfileSelection("选择群聊", NativeMessagingHooks.availableGroupProfiles(), selected, next -> {
                    repository.putString("reply_group_targets", String.join(",", next));
                    preview.setValue(selectedGroupPreview());
                }, null);
    }

    private void showGroupMemberSelection() {
        java.util.Map<String, ProfileListEntry> members = NativeMessagingHooks.availableGroupMembers();
        java.util.Set<String> selected = new java.util.LinkedHashSet<>(GroupReplyPolicy.entries(
                repository.getString("reply_group_members", "")));
        java.util.Set<String> configured = new java.util.LinkedHashSet<>(selected);
        for (ProfileListEntry entry : members.values()) {
            if (entry.name.isEmpty()) continue;
            for (String name : configured) if (name.equalsIgnoreCase(entry.name)) {
                selected.remove(name);
                selected.add(entry.uid);
            }
        }
        showProfileSelection("选择群成员", members, selected, next ->
                repository.putString("reply_group_members", String.join(",", next)), this::showGroupMemberIds);
    }

    private void showGroupMemberIds() {
        LinearLayout form = form();
        EditText input = formInput(form, "成员 UID 或昵称", repository.getString("reply_group_members", ""), false);
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("指定成员").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) ->
                        repository.putString("reply_group_members", String.join(",", GroupReplyPolicy.entries(input.getText().toString())))).create());
    }

    private void showProfileSelection(String title, java.util.Map<String, ProfileListEntry> available,
                                      java.util.Set<String> selected, java.util.function.Consumer<java.util.Set<String>> saved,
                                      Runnable manual) {
        java.util.Map<String, ProfileListEntry> entries = new java.util.LinkedHashMap<>(available);
        for (String uid : selected) if (!entries.containsKey(uid)) {
            entries.put(uid, "选择群成员".equals(title) && !uid.matches("[1-9][0-9]{0,19}")
                    ? ProfileListEntry.named(uid) : new ProfileListEntry(uid, "", ""));
        }
        MemberListView list = new MemberListView(miuixHome != null ? rowContext() : context, miuixHome != null,
                new ArrayList<>(entries.values()), selected, true);
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuixHome != null);
        builder.setTitle(title).setView(list).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> saved.accept(list.selected()));
        if (manual != null) builder.setNeutralButton("填写 UID", (dialog, which) ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(manual));
        showOptionDialog(builder.create(), list::close);
    }

    private void buildAutoCommentControls() {
        ToggleControl[] master = new ToggleControl[1];
        master[0] = addSwitch("开启自动评论", repository.getBoolean("native_auto_comment", false) && !settings.automationEnabled
                        ? "已暂停：自动化总开关关闭" : "",
                repository.getBoolean("native_auto_comment", false), true, false, value -> {
                    repository.putBoolean("native_auto_comment", value);
                    update(s -> withAutomation(s, value ? true : null, null, null, value, null));
                    master[0].setSummary(value && !settings.automationEnabled ? "已暂停：自动化总开关关闭" : "");
                });
        OptionRow[] media = new OptionRow[1];
        addChoiceOption("评论模式", "comment_mode", new String[]{"AI 模型", "固定内容"}, new String[]{"ai", "fixed"}, "ai", () -> {
            boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
            media[0].setValue(commentMediaPreview());
            media[0].setEnabled(ai);
        });
        addOption("评论内容", "", null, true, this::showCommentContentSettings);
        OptionRow[] scope = new OptionRow[1];
        scope[0] = addOption("作品范围", "", scopeLabel(ContentScopePolicy.scopeMode("comment", automationScopeOptions("comment"))),
                true, () -> showCommentScopeSettings(scope[0]));
        boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
        media[0] = addOption("素材识别", "", commentMediaPreview(), ai, () -> showCommentMediaSettings(media[0]));
        addOption("发送与记录", "", null, true, () -> showDeliverySettings(AutomationHistoryStore.COMMENT));
    }

    private void showCommentContentSettings() {
        boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
        LinearLayout panel = form();
        showAutomationPanel("评论内容", panel, () -> {
            if (ai) {
                String selected = "未启用模型";
                try { selected = AiModelSettings.read(repository).selected().name; } catch (RuntimeException ignored) { }
                addOption("模型配置", "", selected, true, this::showAiModelSettings);
                addPromptOption("二级提示词", repository.getString("comment_prompt", "根据当前作品自然地写一条简短评论，只输出评论正文。"),
                        value -> repository.putString("comment_prompt", value));
            } else {
                addEditableOption("固定评论内容", "", repository.getString("auto_comment_text", ""), false, true, false,
                        value -> repository.putString("auto_comment_text", value));
            }
        });
    }

    private void showCommentScopeSettings(OptionRow preview) {
        prepareContentFilter("comment");
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            String mode = ContentScopePolicy.scopeMode("comment", automationScopeOptions("comment"));
            addChoiceOption("作者范围", "comment_scope_mode", new String[]{"全部", "白名单", "黑名单"},
                    new String[]{"all", "whitelist", "blacklist"}, mode, () -> {
                        preview.setValue(scopeLabel(repository.getString("comment_scope_mode", "all")));
                        rebuildAutomationPanel(panel, build[0]);
                    });
            if (!"all".equals(mode)) {
                addMultiChoiceCsvOption("关注关系", "comment_scope_relations", new String[]{"我关注的", "关注我的", "陌生人"},
                        new String[]{AutomationScopePolicy.FOLLOWING, AutomationScopePolicy.FOLLOWERS, AutomationScopePolicy.STRANGERS});
                addOption("指定作者", "", repository.getString("comment_scope_users", ""), true, this::showCommentAuthorSelection);
            }
            addFollowListsOption();
            String filter = ContentScopePolicy.filterMode("comment", automationScopeOptions("comment"));
            addChoiceOption("作品筛选", "comment_filter_mode", new String[]{"不限", "包含关键词", "排除关键词"},
                    new String[]{"off", "include", "exclude"}, filter, () -> rebuildAutomationPanel(panel, build[0]));
            if (!ContentScopePolicy.OFF.equals(filter)) {
                addEditableOption("筛选关键词", "", ContentScopePolicy.keywords("comment", automationScopeOptions("comment")),
                        false, true, false, value -> repository.putString("comment_filter_keywords", value));
                addMultiChoiceCsvOption("匹配对象", "comment_filter_fields", new String[]{"作者", "文案", "标题"},
                        new String[]{AutomationScopePolicy.AUTHOR, AutomationScopePolicy.CAPTION, AutomationScopePolicy.TITLE});
            }
        };
        showAutomationPanel("作品范围", panel, build[0]);
    }

    private void showCommentAuthorSelection() {
        java.util.Map<String, ProfileListEntry> authors = new java.util.LinkedHashMap<>(NativeMessagingHooks.availableContactProfiles());
        authors.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING));
        authors.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS));
        showProfileSelection("指定作者", authors, AutomationScopePolicy.targets(repository.getString("comment_scope_users", "")),
                next -> repository.putString("comment_scope_users", String.join(",", next)),
                () -> showTargetIds("指定作者 UID", "comment_scope_users"));
    }

    private String commentMediaPreview() {
        if (!"ai".equals(repository.getString("comment_mode", "ai"))) return "未启用";
        boolean images = repository.getBoolean("comment_recognize_images", true);
        boolean video = repository.getBoolean("comment_recognize_video", false);
        return images && video ? "图片、视频" : images ? "图片" : video ? "视频" : "不识别";
    }

    private void showCommentMediaSettings(OptionRow preview) {
        LinearLayout panel = form();
        showAutomationPanel("素材识别", panel, () -> {
            addSwitch("识别图片", "", repository.getBoolean("comment_recognize_images", true), true,
                    value -> { repository.putBoolean("comment_recognize_images", value); preview.setValue(commentMediaPreview()); });
            addSwitch("识别视频", "", repository.getBoolean("comment_recognize_video", false), true,
                    value -> { repository.putBoolean("comment_recognize_video", value); preview.setValue(commentMediaPreview()); });
        });
    }

    private void addFollowListsOption() {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption("关注与粉丝名单", "", NativeFollowLists.statusText(), true,
                () -> showFollowLists(preview[0]));
    }

    private void showFollowLists(OptionRow preview) {
        LinearLayout form = form();
        Button following = formButton();
        following.setText("我关注的");
        following.setTag("settings.followLists.following");
        following.setOnClickListener(ignored -> showFollowListMembers(FollowListSnapshot.Kind.FOLLOWING));
        form.addView(MiuixFormControls.viewFor(following), matchWrap());
        Button followers = formButton();
        followers.setText("关注我的");
        followers.setTag("settings.followLists.followers");
        followers.setOnClickListener(ignored -> showFollowListMembers(FollowListSnapshot.Kind.FOLLOWERS));
        LinearLayout.LayoutParams followersParams = matchWrap();
        followersParams.topMargin = dp(8);
        form.addView(MiuixFormControls.viewFor(followers), followersParams);
        AlertDialog dialog = new MiuixDialogBuilder(context, miuixHome != null)
                .setTitle("关注与粉丝名单").setMessage(NativeFollowLists.statusText()).setView(form)
                .setNegativeButton("返回", null).setPositiveButton("刷新名单", null).create();
        showOptionDialog(dialog);
        Button refresh = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        refresh.setOnClickListener(ignored -> {
            refresh.setEnabled(false);
            boolean started = NativeFollowLists.refresh(() -> {
                if (!dialog.isShowing()) return;
                String status = NativeFollowLists.statusText();
                dialog.setMessage(status);
                preview.setValue(status);
                refresh.setEnabled(true);
            });
            if (started) {
                dialog.setMessage(NativeFollowLists.statusText());
            } else {
                repository.putString(NativeFollowLists.REFRESH_KEY, Long.toString(System.currentTimeMillis()));
                String status = "已请求刷新。请打开抖音内的模块设置查看实际名单。";
                dialog.setMessage(status);
                preview.setValue(status);
                refresh.setEnabled(true);
            }
        });
    }

    private void showFollowListMembers(FollowListSnapshot.Kind kind) {
        java.util.Map<String, String> members = NativeFollowLists.members(kind);
        FollowListSnapshot.State snapshot = FollowListSnapshot.current();
        String title = kind == FollowListSnapshot.Kind.FOLLOWING ? "我关注的" : "关注我的";
        boolean known = kind == FollowListSnapshot.Kind.FOLLOWING
                ? snapshot.followingKnown : snapshot.followersKnown;
        java.util.Set<String> ids = kind == FollowListSnapshot.Kind.FOLLOWING
                ? snapshot.following : snapshot.followers;
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuixHome != null);
        builder.setTitle(known ? title + "（" + ids.size() + "人）" : title);
        if (!snapshot.active) {
            builder.setMessage(repository.isInHost() ? "尚未读取登录账号。" : "请在抖音内查看实际名单。");
        } else if (!known) {
            builder.setMessage("名单尚未加载。\n" + NativeFollowLists.statusText());
        } else if (ids.isEmpty()) {
            builder.setMessage("列表为空。");
        } else {
            MemberListView list = new MemberListView(miuixHome != null ? rowContext() : context, miuixHome != null,
                    new ArrayList<>(NativeFollowLists.memberDetails(kind).values()), java.util.Collections.emptySet(), false);
            builder.setView(list).setNegativeButton("返回", null);
            showOptionDialog(builder.create(), list::close);
            return;
        }
        builder.setNegativeButton("返回", null);
        showOptionDialog(builder.create());
    }

    private void addPromptOption(String title, String value, ValueTextListener listener) {
        String[] current = {value};
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(title, "", promptPreview(value), true, () -> showOptionDialog(
                new PromptEditorDialog(context, title, current[0], candidate -> {
                    listener.onValue(candidate);
                    current[0] = candidate;
                    preview[0].setValue(promptPreview(candidate));
                }, miuixHome != null)));
        preview[0] = row;
    }

    private static String promptPreview(String value) {
        if (value.isEmpty()) return "未设置";
        int points = value.codePointCount(0, value.length());
        return value.substring(0, value.offsetByCodePoints(0, Math.min(240, points)));
    }

    private void addUnavailable(String detail) {
        TextView notice = text(detail, 14, MUTED, false);
        notice.setPadding(dp(8), dp(18), dp(8), dp(18));
        content.addView(notice, matchWrap());
    }

    private void addUnavailableSwitch(String title, String summary) {
        addSwitch(title, summary, false, false, value -> { });
    }

    private void addLocalSwitch(String title, String summary, String key) {
        addSwitch(title, summary, repository.getBoolean(key, false), true,
                value -> repository.putBoolean(key, value));
    }

    private void addProfileCount(String title, String key) {
        addEditableOption(title, "留空使用原始数量", repository.getString(key, ""),
                true, true, false, value -> repository.putString(key, value));
    }

    private void addColorOption() {
        String value = repository.getString("recall_background", "#2051BA55");
        OptionRow[] colorRow = new OptionRow[1];
        OptionRow row = addOption("撤回的背景色", "", null, true, () -> {
            LinearLayout form = form();
            LinearLayout colors = new LinearLayout(context);
            for (String color : new String[]{"#2051BA55", "#2066A3FF", "#20F6C344", "#20F06A7D", "#00000000"}) {
                View swatch = new View(context);
                swatch.setBackground(makeCircle(Color.parseColor(color)));
                swatch.setContentDescription(color);
                swatch.setTooltipText(color);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
                params.rightMargin = colors.getChildCount() < 4 ? dp(8) : 0;
                colors.addView(swatch, params);
                swatch.setOnClickListener(ignored -> {
                    repository.putString("recall_background", color);
                    colorRow[0].setColor(Color.parseColor(color));
                });
            }
            form.addView(colors, matchWrap());
            EditText input = formInput(form, "颜色值", repository.getString("recall_background", "#2051BA55"), false);
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("撤回的背景色").setView(form)
                    .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                        try {
                            Color.parseColor(input.getText().toString().trim());
                            repository.putString("recall_background", input.getText().toString().trim());
                            colorRow[0].setColor(Color.parseColor(input.getText().toString().trim()));
                        } catch (IllegalArgumentException failure) {
                            android.widget.Toast.makeText(context, "颜色值无效", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }).create());
        });
        colorRow[0] = row;
        try { row.setColor(Color.parseColor(value)); }
        catch (IllegalArgumentException ignored) { row.setColor(Color.parseColor("#2051BA55")); }
    }

    private void addCommentLongPressOption() {
        String[] labels = {"复制评论", "保存评论图片", "复制源数据"};
        String[] keys = {"copy_comment_text", "comment_media_tools", "copy_source_data"};
        boolean[] syncing = {false};
        boolean checked = repository.getBoolean(keys[0], false)
                || repository.getBoolean(keys[1], false)
                || repository.getBoolean(keys[2], false);
        ToggleControl toggle = addSwitch("长按操作", "包含复制评论、保存评论图片和复制源数据。",
                checked, true, false, value -> {
                    if (!syncing[0]) for (String key : keys) repository.putBoolean(key, value);
                });
        toggle.setOnLongClickListener(view -> {
            boolean[] enabled = new boolean[keys.length];
            for (int index = 0; index < keys.length; index++) {
                enabled[index] = repository.getBoolean(keys[index], false);
            }
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("长按操作")
                    .setMultiChoiceItems(labels, enabled, (dialog, which, value) ->
                            repository.putBoolean(keys[which], value))
                    .setPositiveButton("完成", null).create(), () -> {
                        boolean anyEnabled = false;
                        for (String key : keys) anyEnabled |= repository.getBoolean(key, false);
                        syncing[0] = true;
                        toggle.setChecked(anyEnabled);
                        syncing[0] = false;
                    });
            return true;
        });
    }

    private void addUnavailableOption(String title, String summary) {
        View row = addOption(title, summary, null, false, () -> { }).view;
        row.setAlpha(0.65f);
        row.setContentDescription(title + "（未适配）");
    }

    private void showAiModelSettings() {
        LinearLayout previous = content;
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        content = panel;
        addOption("添加模型配置", "选择供应商预设", null, true, this::showAiPresetPicker);
        addOption("测试当前配置", "发送一条测试消息", null, true, this::showAiChat);
        addSectionLabel("已配置模型");
        AiModelSettings settings = aiSettings();
        java.util.Map<Button, String> activationButtons = new java.util.LinkedHashMap<>();
        if (settings.configs.isEmpty()) addUnavailable("尚未配置");
        for (AiModelConfig config : settings.configs) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(4), dp(4), dp(4));
            if (miuixHome != null) row.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(config.name, 15, TEXT, true);
            title.setTag("ai.profile." + config.id);
            TextView summary = text((config.modelName.isEmpty() ? "未选择模型" : config.modelName)
                    + " · " + protocolName(config.protocol), 12, MUTED, false);
            LinearLayout details = new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setTag("ai.edit." + config.id);
            details.addView(title, matchWrap());
            details.addView(summary, matchWrap());
            details.setOnClickListener(ignored -> showAiModelEditor(config));
            View modelDetails = details;
            if (miuixHome != null) {
                modelDetails = new MiuixSettingsViews.Option(rowContext(), config.name,
                        summary.getText().toString(), null, true, () -> showAiModelEditor(config)).getView();
                modelDetails.setTag("ai.edit." + config.id);
            }
            row.addView(modelDetails, miuixHome == null
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                    : matchWrap());
            Button activate = formButton();
            boolean active = config.id.equals(settings.activeId);
            activate.setText(active ? "已启用" : "启用");
            activate.setEnabled(!active);
            activate.setTag("ai.activate." + config.id);
            activationButtons.put(activate, config.id);
            activate.setOnClickListener(ignored -> {
                try {
                    AiModelSettings next = aiSettings().activate(config.id);
                    saveAiSettings(next);
                    for (java.util.Map.Entry<Button, String> entry : activationButtons.entrySet()) {
                        boolean selected = next.activeId.equals(entry.getValue());
                        entry.getKey().setText(selected ? "已启用" : "启用");
                        entry.getKey().setEnabled(!selected);
                    }
                } catch (RuntimeException failure) { toast(safeError(failure)); }
            });
            row.addView(MiuixFormControls.viewFor(activate), miuixHome == null
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
                    : matchWrap());
            content.addView(row, matchWrap());
            addDivider();
        }
        content = previous;
        ScrollView scroll = new ScrollView(context);
        scroll.addView(panel);
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("AI 模型设置")
                .setView(scroll).setNegativeButton("返回", null).create());
    }

    private AiModelSettings aiSettings() {
        try { return AiModelSettings.read(repository); }
        catch (RuntimeException failure) {
            toast("模型配置无法读取：" + failure.getMessage());
            return new AiModelSettings(java.util.Collections.emptyList(), "");
        }
    }

    private void saveAiSettings(AiModelSettings settings) {
        settings.save(repository);
    }

    private void showAiPresetPicker() {
        String[] labels = new String[AiProviderPresets.ALL.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = AiProviderPresets.ALL.get(i).name;
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("选择供应商")
                .setItems(labels, (dialog, index) -> {
                    AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(index);
                    try {
                        JSONObject json = new JSONObject().put("id", java.util.UUID.randomUUID().toString())
                                .put("name", preset.name).put("provider", preset.id)
                                .put("protocol", preset.protocol).put("apiEndpoint", preset.apiEndpoint);
                        AiModelConfig draft = AiModelConfig.fromJson(json);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                                () -> showAiModelEditor(draft));
                    } catch (org.json.JSONException failure) { toast("无法创建模型配置"); }
                }).setNegativeButton("取消", null).create());
    }

    private EditText formInput(LinearLayout parent, String label, String value, boolean secret) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = dp(10);
        parent.addView(heading, headingParams);
        EditText input = miuixHome == null ? new EditText(context)
                : MiuixFormControls.input(context, label, value, secret);
        input.setContentDescription(label);
        input.setText(value);
        input.setTextSize(14);
        input.setMaxLines(secret ? 1 : 4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        return form;
    }

    private ScrollView scrolling(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private void showAiModelEditor(AiModelConfig original) {
        AiModelEditor editor = new AiModelEditor(context, repository, original,
                miuixHome != null, this::showAiModelSettings);
        editor.show(this::showOptionDialog);
    }

    private static String protocolName(String protocol) {
        for (int i = 0; i < AiModelConfig.PROTOCOLS.length; i++) {
            if (AiModelConfig.PROTOCOLS[i].equals(protocol)) return AiModelConfig.PROTOCOL_LABELS[i];
        }
        return AiModelConfig.PROTOCOL_LABELS[0];
    }

    private static String safeError(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message == null ? "操作失败" : message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showAiChat() {
        try { showOptionDialog(new AiTestChatDialog(context, aiSettings().selected(), miuixHome != null)); }
        catch (RuntimeException failure) { toast(safeError(failure)); }
    }

    private void showReplyPool(boolean keyword) {
        String key = keyword ? "reply_keywords" : "reply_random";
        JSONArray saved;
        try { saved = new JSONArray(repository.getString(key, "[]")); }
        catch (org.json.JSONException ignored) { saved = new JSONArray(); }
        LinearLayout form = form();
        List<EditText[]> editors = new ArrayList<>();
        Runnable add = () -> {
            if (editors.size() >= 50) return;
            LinearLayout entry = form();
            EditText match = keyword ? formInput(entry, "匹配文字", "", false) : null;
            EditText response = formInput(entry, "回复内容", "", false);
            ImageButton remove = new ImageButton(context);
            remove.setImageResource(android.R.drawable.ic_menu_delete);
            remove.setContentDescription("删除回复");
            remove.setTooltipText("删除回复");
            EditText[] values = {match, response};
            remove.setOnClickListener(ignored -> { editors.remove(values); form.removeView(entry); });
            entry.addView(miuixHome == null ? remove : MiuixExtraControls.iconAction(rowContext(),
                    remove, android.R.drawable.ic_menu_delete), new LinearLayout.LayoutParams(dp(48), dp(48)));
            editors.add(values);
            form.addView(entry, matchWrap());
        };
        for (int index = 0; index < saved.length(); index++) {
            add.run();
            EditText[] entry = editors.get(editors.size() - 1);
            if (keyword) {
                JSONObject pair = saved.optJSONObject(index);
                if (pair != null) { entry[0].setText(pair.optString("keyword")); entry[1].setText(pair.optString("reply")); }
            } else entry[1].setText(saved.optString(index));
        }
        ImageButton addButton = new ImageButton(context);
        addButton.setImageResource(android.R.drawable.ic_input_add);
        addButton.setContentDescription("添加回复");
        addButton.setTooltipText("添加回复");
        addButton.setOnClickListener(ignored -> add.run());
        form.addView(miuixHome == null ? addButton : MiuixExtraControls.iconAction(rowContext(),
                addButton, android.R.drawable.ic_input_add), new LinearLayout.LayoutParams(dp(48), dp(48)));
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(keyword ? "关键词规则" : "随机内容")
                .setView(scrolling(form)).setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    JSONArray entries = new JSONArray();
                    for (EditText[] item : editors) {
                        String reply = item[1].getText().toString().trim();
                        if (reply.isEmpty()) continue;
                        try {
                            if (keyword) {
                                String match = item[0].getText().toString().trim();
                                if (!match.isEmpty()) entries.put(new JSONObject().put("keyword", match).put("reply", reply));
                            } else entries.put(reply);
                        } catch (org.json.JSONException ignored) { }
                    }
                    repository.putString(key, entries.toString());
                }).create());
    }

    private void addChoiceOption(String title, String key, String[] labels, String[] values, String fallback) {
        addChoiceOption(title, key, labels, values, fallback, null);
    }

    private void addChoiceOption(String title, String key, String[] labels, String[] values, String fallback, Runnable changed) {
        String selected = repository.getString(key, fallback);
        int initial = java.util.Arrays.asList(values).indexOf(selected);
        int selectedIndex = Math.max(0, initial);
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(title, "", labels[selectedIndex], true, () -> {
            int current = Math.max(0, java.util.Arrays.asList(values).indexOf(repository.getString(key, fallback)));
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title)
                    .setSingleChoiceItems(labels, current, (dialog, which) -> {
                        repository.putString(key, values[which]);
                        preview[0].setValue(labels[which]);
                        dialog.dismiss();
                        if (changed != null) changed.run();
                    }).setNegativeButton("取消", null).create());
        });
        preview[0] = row;
    }

    private void addMultiChoiceCsvOption(String title, String key, String[] labels, String[] values) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(title, "", scopeLabels(labels, values, repository.getString(key, "")), true, () -> {
            java.util.Set<String> selected = AutomationScopePolicy.targets(repository.getString(key, ""));
            boolean[] checked = new boolean[values.length];
            for (int index = 0; index < values.length; index++) checked[index] = selected.contains(values[index]);
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title)
                    .setMultiChoiceItems(labels, checked, (dialog, which, value) -> checked[which] = value)
                    .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                        List<String> next = new ArrayList<>();
                        for (int index = 0; index < values.length; index++) if (checked[index]) next.add(values[index]);
                        String saved = String.join(",", next);
                        repository.putString(key, saved);
                        preview[0].setValue(scopeLabels(labels, values, saved));
                    }).create());
        });
    }

    private static String scopeLabels(String[] labels, String[] values, String configured) {
        java.util.Set<String> selected = AutomationScopePolicy.targets(configured);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length; index++) if (selected.contains(values[index])) result.add(labels[index]);
        return result.isEmpty() ? "未选择" : String.join("、", result);
    }

    private void showTargetSelection(String title, String key) {
        java.util.Map<String, String> contacts = NativeMessagingHooks.availableContacts();
        if (contacts.isEmpty()) {
            showTargetIds(title, key);
            return;
        }
        java.util.Set<String> selected = com.douai.module.feature.ReplyPolicy.targets(repository.getString(key, ""));
        showProfileSelection(title, NativeMessagingHooks.availableContactProfiles(), selected,
                next -> repository.putString(key, String.join(",", next)), () -> showTargetIds(title, key));
    }

    private void showTargetIds(String title, String key) {
        LinearLayout form = form();
        EditText input = formInput(form, "用户 UID，以逗号分隔", repository.getString(key, ""), false);
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) ->
                        repository.putString(key, String.join(",", com.douai.module.feature.ReplyPolicy.targets(input.getText().toString())))).create());
    }

    private void showStreakWindow() {
        LinearLayout form = form();
        EditText start = formInput(form, "开始时间", repository.getString("streak_start", ""), false);
        EditText end = formInput(form, "结束时间", repository.getString("streak_end", ""), false);
        AlertDialog dialog = new MiuixDialogBuilder(context, miuixHome != null).setTitle("续火花限制时间").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        showOptionDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String first = start.getText().toString().trim();
            String last = end.getText().toString().trim();
            try {
                if (!first.isEmpty() || !last.isEmpty()) {
                    java.time.LocalTime.parse(first);
                    java.time.LocalTime.parse(last);
                }
                repository.putString("streak_start", first);
                repository.putString("streak_end", last);
                dialog.dismiss();
            } catch (java.time.format.DateTimeParseException failure) { start.setError("时间格式应为 HH:mm"); }
        });
    }

    private void confirmUnknownStreakRetry() {
        Dialog dialog = new MiuixDialogBuilder(context, miuixHome != null)
                .setTitle("重试结果未知的续火花")
                .setMessage("上次发送可能已经到达对方，只是没有收到确认。继续重试可能会重复发送。")
                .setNegativeButton("取消", null)
                .setPositiveButton("仍然重试", (ignored, which) -> repository.putString(
                        "streak_retry_unknown_token", java.util.UUID.randomUUID().toString()))
                .create();
        showOptionDialog(dialog);
    }

    private void showAudioPathEditor(String title, String key) {
        LinearLayout form = form();
        EditText path = formInput(form, "文件路径", repository.getString(key, ""), false);
        Button pick = formButton();
        pick.setText("选择音频文件");
        pick.setOnClickListener(ignored -> {
            if (!HostAudioPicker.launch(findOwnerActivity(context), repository, key, path::setText)) {
                android.widget.Toast.makeText(context, "文件选择器不可用，可填写可读取的音频路径", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        form.addView(MiuixFormControls.viewFor(pick), matchWrap());
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) ->
                        repository.putString(key, path.getText().toString().trim())).create());
    }

    private void showSpeechSettings() {
        LinearLayout previous = content;
        LinearLayout form = form();
        content = form;
        addEditableOption("语音引擎", "留空使用系统默认引擎", repository.getString("tts_engine", ""),
                false, true, false, value -> repository.putString("tts_engine", value));
        addEditableOption("语音语言", "", repository.getString("tts_language", "zh-CN"),
                false, true, false, value -> repository.putString("tts_language", value));
        addEditableOption("语速", "0.5 至 2", repository.getString("tts_rate", "1"),
                false, true, false, value -> repository.putString("tts_rate", value));
        addEditableOption("音调", "0.5 至 2", repository.getString("tts_pitch", "1"),
                false, true, false, value -> repository.putString("tts_pitch", value));
        content = previous;
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("文本转语音设置").setView(form)
                .setNegativeButton("返回", null).create());
    }

    private void showGestureSettings(String title, String prefix) {
        if (miuixHome != null) {
            String[] initial = new String[9];
            for (int region = 0; region < initial.length; region++) {
                String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
                int index = Math.max(0, java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action));
                initial[region] = FeedGestureToolsHook.ACTION_LABELS[index];
            }
            MiuixExtraControls.GestureGrid[] grid = new MiuixExtraControls.GestureGrid[1];
            grid[0] = MiuixExtraControls.gestureGrid(rowContext(), prefix, initial, region -> {
                String key = prefix + "_" + FeedGestureToolsHook.REGION_IDS[region];
                String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
                int selected = Math.max(0, java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action));
                showOptionDialog(new MiuixDialogBuilder(context, true)
                        .setTitle(title + " · " + FeedGestureToolsHook.REGION_LABELS[region])
                        .setSingleChoiceItems(FeedGestureToolsHook.ACTION_LABELS, selected, (dialog, which) -> {
                            repository.putString(key, FeedGestureToolsHook.ACTION_IDS[which]);
                            grid[0].setAction(region, FeedGestureToolsHook.ACTION_LABELS[which]);
                            dialog.dismiss();
                        }).setNegativeButton("取消", null).create());
            });
            showOptionDialog(new MiuixDialogBuilder(context, true).setTitle(title).setView(scrolling(grid[0].getView()))
                    .setNegativeButton("返回", null).create());
            return;
        }
        LinearLayout panel = form();
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setTag("settings.gesture.grid." + prefix);
        int cellHeight = dp(Math.round(96 * Math.max(1f,
                context.getResources().getConfiguration().fontScale)));
        for (int row = 0; row < 3; row++) {
            LinearLayout cells = new LinearLayout(context);
            cells.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3; column++) {
                int region = row * 3 + column;
                String key = prefix + "_" + FeedGestureToolsHook.REGION_IDS[region];
                String label = FeedGestureToolsHook.REGION_LABELS[region];
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(8), dp(4), dp(8));
                cell.setBackground(makeRounded(Color.WHITE, LINE, dp(5)));
                cell.setTag("settings.gesture." + key);
                cell.setFocusable(true);
                TextView name = text(label, 14, TEXT, true);
                name.setGravity(Gravity.CENTER);
                cell.addView(name, matchWrap());
                TextView value = text("", 13, MUTED, false);
                value.setGravity(Gravity.CENTER);
                value.setTag("settings.gesture.value." + key);
                LinearLayout.LayoutParams valueParams = matchWrap();
                valueParams.topMargin = dp(5);
                cell.addView(value, valueParams);
                updateGestureCell(cell, value, label, prefix, region);
                cell.setOnClickListener(ignored -> {
                    String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
                    int current = java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action);
                    showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title + " · " + label)
                            .setSingleChoiceItems(FeedGestureToolsHook.ACTION_LABELS, Math.max(0, current),
                                    (dialog, which) -> {
                                        repository.putString(key, FeedGestureToolsHook.ACTION_IDS[which]);
                                        updateGestureCell(cell, value, label, prefix, region);
                                        dialog.dismiss();
                                    }).setNegativeButton("取消", null).create());
                });
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, cellHeight, 1);
                if (column != 2) cellParams.rightMargin = dp(6);
                cells.addView(cell, cellParams);
            }
            LinearLayout.LayoutParams rowParams = matchWrap();
            if (row != 2) rowParams.bottomMargin = dp(6);
            grid.addView(cells, rowParams);
        }
        panel.addView(grid, matchWrap());
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(scrolling(panel))
                .setNegativeButton("返回", null).create());
    }

    private LocalOptions gestureOptions(String prefix) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        List<String> suffixes = new ArrayList<>(java.util.Arrays.asList(FeedGestureToolsHook.REGION_IDS));
        java.util.Collections.addAll(suffixes, "left", "center", "right", "action");
        for (String suffix : suffixes) {
            String key = prefix + "_" + suffix;
            String value = repository.getString(key, null);
            if (value != null) values.put(key, value);
        }
        return new LocalOptions(values);
    }

    private void updateGestureCell(View cell, TextView preview, String label, String prefix, int region) {
        String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
        int index = Math.max(0, java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action));
        String value = FeedGestureToolsHook.ACTION_LABELS[index];
        preview.setText(value);
        cell.setContentDescription(label + "，" + value);
    }

    private void showTopTabs() {
        java.util.Map<String, String> tabs = TopTabFilterHook.availableTabs();
        if (tabs.isEmpty()) {
            LinearLayout form = form();
            EditText input = formInput(form, "要隐藏的标签名称", repository.getString("hidden_top_tabs", ""), false);
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("设置顶部Tab").setView(form)
                    .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) ->
                            repository.putString("hidden_top_tabs", input.getText().toString().trim())).create());
            return;
        }
        String[] ids = tabs.keySet().toArray(new String[0]);
        String[] titles = tabs.values().toArray(new String[0]);
        java.util.Set<String> hidden = new java.util.HashSet<>();
        for (String value : repository.getString("hidden_top_tabs", "").split("[,，\\n]+")) hidden.add(value.trim());
        boolean[] checked = new boolean[ids.length];
        for (int index = 0; index < ids.length; index++) checked[index] = hidden.contains(ids[index]) || hidden.contains(titles[index]);
        AlertDialog dialog = new MiuixDialogBuilder(context, miuixHome != null).setTitle("设置顶部Tab")
                .setMultiChoiceItems(titles, checked, (ignored, which, value) -> checked[which] = value)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        showOptionDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            List<String> selected = new ArrayList<>();
            for (int index = 0; index < ids.length; index++) if (checked[index]) selected.add(ids[index]);
            if (selected.size() == ids.length) {
                android.widget.Toast.makeText(context, "至少保留一个顶部标签", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            repository.putString("hidden_top_tabs", String.join(",", selected));
            dialog.dismiss();
        });
    }

    private void addSectionLabel(String title) {
        TextView label = text(title, 13, MUTED, false);
        label.setPadding(0, dp(18), 0, dp(8));
        content.addView(label, matchWrap());
    }

    private void showAutomationSettings() {
        AutomationSettingsDialog automation = new AutomationSettingsDialog(context, repository, miuixHome != null,
                () -> settings = repository.load());
        showOptionDialog(automation.dialog(), automation::close);
    }

    private void showSystemBarSettings() {
        LinearLayout previous = content;
        LinearLayout panel = form();
        content = panel;
        addSwitch("隐藏状态栏", "", repository.getBoolean("remove_black_edges", false), true,
                value -> repository.putBoolean("remove_black_edges", value));
        addSwitch("隐藏导航栏", "", repository.getBoolean("hide_navigation_bar", false), true,
                value -> repository.putBoolean("hide_navigation_bar", value));
        content = previous;
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("沉浸式全屏").setView(panel)
                .setNegativeButton("返回", null).create());
    }

    private void showVersionSettings() {
        LinearLayout previous = content;
        LinearLayout panel = form();
        content = panel;
        addSwitch("版本信息修改", "修改抖音进程读到的版本信息。",
                repository.getBoolean("spoof_version", false), true,
                value -> repository.putBoolean("spoof_version", value));
        addEditableOption("版本名称", "", repository.getString("fake_version_name", ""),
                false, true, false, value -> repository.putString("fake_version_name", value));
        addEditableOption("版本号", "", repository.getString("fake_version_code", ""),
                true, true, false, value -> repository.putString("fake_version_code", value));
        content = previous;
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle("版本信息").setView(scrolling(panel))
                .setNegativeButton("返回", null).create());
    }

    private View addEditableOption(String title, String summary, String value, boolean numeric,
                                   boolean enabled, boolean feedControl, ValueTextListener listener) {
        String[] current = {value};
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(title, summary, value, enabled, () -> {
            EditText input = miuixHome == null ? new EditText(context)
                    : MiuixFormControls.input(context, title, current[0], false);
            input.setContentDescription(title);
            input.setTag("settings.input." + title);
            input.setText(current[0]);
            input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMaxLines(numeric ? 1 : 6);
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(400)});
            LinearLayout form = new LinearLayout(context);
            form.setPadding(dp(20), dp(8), dp(20), 0);
            form.addView(MiuixFormControls.viewFor(input), matchWrap());
            AlertDialog editor = new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(form)
                    .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            showOptionDialog(editor);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                String candidate = input.getText().toString().trim();
                try {
                    listener.onValue(candidate);
                    current[0] = candidate;
                    preview[0].setValue(candidate.isEmpty() ? "未设置" : candidate);
                    editor.dismiss();
                } catch (RuntimeException failure) { toast(safeError(failure)); }
            });
        });
        preview[0] = row;
        if (feedControl) feedFilterControls.add(row);
        return row.view;
    }

    private OptionRow addOption(String title, String summary, String value, boolean enabled, Runnable action) {
        Context rowContext = rowContext();
        if (rowContext != null) {
            MiuixSettingsViews.Option option = new MiuixSettingsViews.Option(
                    rowContext, title, summary, value, enabled, action);
            content.addView(option.getView(), matchWrap());
            return new OptionRow(option.getView(), option::setValue, option::setEnabled, option::setColor);
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setMinimumHeight(dp(58));
        row.setTag("settings.option." + title);
        row.setContentDescription(title);
        row.setEnabled(enabled);
        row.setFocusable(true);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, TEXT, false), matchWrap());
        if (summary != null && !summary.isEmpty()) {
            TextView detail = text(summary, 12, MUTED, false);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(3);
            labels.addView(detail, params);
        }
        TextView preview = null;
        if (value != null) {
            preview = text(value.isEmpty() ? "未设置" : value, 13, MUTED, false);
            preview.setTag("settings.value." + title);
            preview.setMaxLines(3);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(5);
            labels.addView(preview, params);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("›", 24, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(32)));
        row.setOnClickListener(view -> { if (view.isEnabled()) action.run(); });
        content.addView(row, matchWrap());
        addDivider();
        TextView valuePreview = preview;
        View[] colorSwatch = new View[1];
        return new OptionRow(row, newValue -> {
            if (valuePreview != null) valuePreview.setText(newValue);
        }, row::setEnabled, color -> {
            if (colorSwatch[0] == null) {
                colorSwatch[0] = new View(context);
                labels.addView(colorSwatch[0], new LinearLayout.LayoutParams(dp(24), dp(24)));
            }
            colorSwatch[0].setBackground(makeCircle(color));
        });
    }

    private void showOptionDialog(Dialog dialog) {
        showOptionDialog(dialog, null);
    }

    private void showOptionDialog(Dialog dialog, Runnable onDismiss) {
        discardDismissedOptionDialogs();
        optionDialogs.add(dialog);
        if (onDismiss != null) optionDismissActions.put(dialog, onDismiss);
        dialog.setOwnerActivity(findOwnerActivity(context));
        dialog.setOnDismissListener(ignored -> {
            int index = optionDialogs.indexOf(dialog);
            if (index >= 0) {
                for (int child = optionDialogs.size() - 1; child > index; child--) {
                    Dialog childDialog = optionDialogs.get(child);
                    finishOptionDialog(childDialog);
                    childDialog.dismiss();
                }
                optionDialogs.remove(dialog);
            }
            finishOptionDialog(dialog);
        });
        dialog.show();
    }

    private void discardDismissedOptionDialogs() {
        // Dismiss callbacks can arrive after a sibling has already been opened.
        for (int index = 0; index < optionDialogs.size(); index++) {
            if (optionDialogs.get(index).isShowing()) continue;
            List<Dialog> closedBranch = new ArrayList<>(optionDialogs.subList(index, optionDialogs.size()));
            optionDialogs.removeAll(closedBranch);
            for (Dialog child : closedBranch) {
                finishOptionDialog(child);
                if (child.isShowing()) child.dismiss();
            }
            return;
        }
    }

    private void dismissOptionDialogs() {
        for (Dialog dialog : new ArrayList<>(optionDialogs)) {
            finishOptionDialog(dialog);
            dialog.dismiss();
        }
        optionDialogs.clear();
    }

    private void finishOptionDialog(Dialog dialog) {
        Runnable action = optionDismissActions.remove(dialog);
        if (action != null) action.run();
    }

    public void dismissChildPages() {
        dismissOptionDialogs();
        if (miuixComponentHidePage != null) miuixComponentHidePage.dismiss();
        miuixComponentHidePage = null;
        if (componentHidePage != null) componentHidePage.dismiss();
        componentHidePage = null;
        if (categoryDialog != null) categoryDialog.dismiss();
        categoryDialog = null;
    }

    public void close() {
        dismissChildPages();
        if (miuixHome != null) miuixHome.close();
    }

    public void setScanStatus(ScanStatus status) {
        if (miuixHome != null) miuixHome.setScanStatus(status);
        if (scanTitle == null) return;
        scanTitle.setText(status.title);
        String detail = status.detail;
        if (status.restartRequired) detail += "\n请完全退出并重新打开抖音后生效。";
        scanDetail.setText(detail);
        scanMark.setBackground(makeCircle(status.ready ? ACCENT : Color.rgb(231, 151, 40)));
    }

    public void setFrameworkSyncStatus(boolean connected, String message, String error) {
        if (syncTitle == null) return;
        syncTitle.setText(connected ? "LSPosed 已连接" : "LSPosed 未连接");
        String detail = "本页设置保存在模块应用；抖音内设置保存在抖音应用，两者互不共享。";
        if (error != null && !error.isEmpty()) detail += "\n" + error;
        syncDetail.setText(detail);
        syncMark.setBackground(makeCircle(connected ? Color.rgb(0, 146, 117)
                : Color.rgb(198, 84, 67)));
    }

    private void addTitle(String title, String subtitle) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(title, 25, TEXT, true);
        labels.addView(heading, matchWrap());
        TextView sub = text(subtitle, 14, MUTED, false);
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(5);
        labels.addView(sub, p);
        header.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onClose != null) {
            Button close = new Button(context);
            close.setText("关闭");
            close.setOnClickListener(view -> onClose.run());
            header.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(header, matchWrap());
    }

    private void addHostSettingsNotice() {
        TextView notice = text("设置保存在抖音本地。",
                13, MUTED, false);
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(12);
        content.addView(notice, p);
    }

    private void addIndependentSettingsNotice() {
        TextView notice = text("抖音内功能设置独立保存在抖音应用，本页只显示模块应用状态。",
                14, TEXT, false);
        notice.setPadding(dp(14), dp(14), dp(14), dp(14));
        notice.setBackground(makeRounded(Color.WHITE, LINE, dp(7)));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        content.addView(notice, params);
    }

    private void addScanStatus(ScanStatus status) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(makeRounded(Color.WHITE, LINE, dp(7)));

        scanMark = new View(context);
        scanMark.setBackground(makeCircle(Color.rgb(231, 151, 40)));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(9), dp(9));
        markParams.rightMargin = dp(12);
        box.addView(scanMark, markParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        scanTitle = text("", 15, TEXT, true);
        scanDetail = text("", 13, MUTED, false);
        labels.addView(scanTitle, matchWrap());
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(3);
        labels.addView(scanDetail, detailParams);
        box.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(20);
        content.addView(box, p);
        setScanStatus(status);
    }

    private void addFrameworkSyncStatus() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        box.setBackground(makeRounded(Color.WHITE, LINE, dp(7)));

        syncMark = new View(context);
        syncMark.setBackground(makeCircle(Color.rgb(198, 84, 67)));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(9), dp(9));
        markParams.rightMargin = dp(12);
        box.addView(syncMark, markParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        syncTitle = text("配置未连接", 14, TEXT, true);
        syncDetail = text("本地设置保存在此设备。", 12, MUTED, false);
        labels.addView(syncTitle, matchWrap());
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(3);
        labels.addView(syncDetail, detailParams);
        box.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(8);
        content.addView(box, p);
    }

    private void addSwitch(String title, String summary, boolean checked, boolean enabled,
                           ValueListener listener) {
        addSwitch(title, summary, checked, enabled, false, listener);
    }

    private ToggleControl addSwitch(String title, String summary, boolean checked, boolean enabled,
                             boolean isFeedFilterControl, ValueListener listener) {
        Context rowContext = rowContext();
        if (rowContext != null) {
            MiuixSettingsViews.Toggle toggle = new MiuixSettingsViews.Toggle(
                    rowContext, title, summary, checked, enabled, listener::onValue);
            ToggleControl control = new ToggleControl(toggle::setChecked, toggle::setEnabled,
                    toggle::setOnLongClickListener, toggle::setSummary);
            content.addView(toggle.getView(), matchWrap());
            if (isFeedFilterControl) feedFilterControls.add(control);
            return control;
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 15, TEXT, false);
        labels.addView(name, matchWrap());
        TextView description = text(summary == null ? "" : summary, 12, MUTED, false);
        description.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(3);
        labels.addView(description, descriptionParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch toggle = new Switch(context);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        toggle.setTag("settings.toggle." + title);
        toggle.setContentDescription(title);
        if (isFeedFilterControl) feedFilterControls.add(toggle::setEnabled);
        toggle.setOnCheckedChangeListener((button, value) -> listener.onValue(value));
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(row, matchWrap());
        addDivider();
        return new ToggleControl(toggle::setChecked, toggle::setEnabled, toggle::setOnLongClickListener, value -> {
            description.setText(value);
            description.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void addTextSetting(String title, String value, String hint, boolean numeric,
                                boolean enabled, ValueTextListener listener) {
        addTextSetting(title, value, hint, numeric, enabled, false, listener);
    }

    private void addTextSetting(String title, String value, String hint, boolean numeric,
                                boolean enabled, boolean isFeedFilterControl,
                                ValueTextListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView label = text(title, 14, TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setContentDescription(title);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(14);
        input.setPadding(dp(10), dp(4), dp(10), dp(4));
        input.setBackground(makeRounded(Color.WHITE, LINE, dp(5)));
        input.setEnabled(enabled);
        if (isFeedFilterControl) feedFilterControls.add(input::setEnabled);
        input.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        input.setEms(numeric ? 6 : 12);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        input.setOnFocusChangeListener((view, focused) -> {
            if (!focused) listener.onValue(input.getText().toString());
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            listener.onValue(input.getText().toString());
            input.clearFocus();
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(dp(numeric ? 105 : 170), dp(42)));
        content.addView(row, matchWrap());
    }

    private void addDivider() {
        addDividerTo(content);
    }

    private void addDividerTo(LinearLayout parent) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        parent.addView(divider, p);
    }

    private static Activity findOwnerActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private void update(SettingsTransform transform) {
        settings = transform.apply(settings);
        repository.save(settings);
    }

    private void setFeedFilterControlsEnabled(boolean enabled) {
        for (EnabledControl control : feedFilterControls) control.setEnabled(enabled);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Context rowContext() {
        if (miuixHome == null) return null;
        return miuixRowContext != null ? miuixRowContext : miuixHome.getView().getContext();
    }

    private Button formButton() {
        return miuixHome == null ? new Button(context) : MiuixFormControls.button(context);
    }

    private GradientDrawable makeRounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable makeCircle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String validatePlaybackSpeed(String value) {
        final float speed;
        try { speed = Float.parseFloat(value.trim()); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("请输入 0.25 至 4 的数字"); }
        if (!Float.isFinite(speed) || speed < 0.25f || speed > 4f) {
            throw new IllegalArgumentException("倍速应为 0.25 至 4");
        }
        return value.trim();
    }

    private static String effectivePlaybackSpeed(String value) {
        try {
            float speed = Float.parseFloat(value == null ? "" : value.trim());
            if (Float.isFinite(speed) && speed >= 0.25f && speed <= 4f) return value.trim();
        } catch (NumberFormatException ignored) { }
        return "2";
    }

    private static int parseNonNegative(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static FeatureSettings copy(FeatureSettings s, Boolean download, Boolean watermark) {
        return make(download == null ? s.downloadEnabled : download,
                watermark == null ? s.removeWatermark : watermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled,
                s.minimumDurationSeconds, s.minimumLikesEnabled, s.minimumLikeCount,
                s.automationEnabled, s.autoLikeEnabled, s.autoFollowEnabled,
                s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withFeedEnabled(FeatureSettings s, boolean value) {
        return make(s.downloadEnabled, s.removeWatermark, value, s.hideAds, s.hideLives,
                s.hideImagePosts, s.hideRecommendationCards, s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword,
                s.minimumDurationEnabled, s.minimumDurationSeconds, s.minimumLikesEnabled,
                s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled, s.autoFollowEnabled,
                s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withFilter(FeatureSettings s, boolean value, Filter filter) {
        boolean ads = filter == Filter.AD ? value : s.hideAds;
        boolean lives = filter == Filter.LIVE ? value : s.hideLives;
        boolean images = filter == Filter.IMAGE ? value : s.hideImagePosts;
        boolean cards = filter == Filter.CARD ? value : s.hideRecommendationCards;
        boolean keywordEnabled = filter == Filter.KEYWORD ? value : s.keywordFilterEnabled;
        boolean durationEnabled = filter == Filter.DURATION ? value : s.minimumDurationEnabled;
        boolean likesEnabled = filter == Filter.LIKES ? value : s.minimumLikesEnabled;
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                ads, lives, images, cards, s.hideRecommendationMerchants, keywordEnabled, s.keyword, durationEnabled,
                s.minimumDurationSeconds, likesEnabled, s.minimumLikeCount,
                s.automationEnabled, s.autoLikeEnabled, s.autoFollowEnabled,
                s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withKeyword(FeatureSettings s, String value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, value, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withDuration(FeatureSettings s, int value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, value,
                s.minimumLikesEnabled, s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withLikes(FeatureSettings s, int value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, value, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withAutomation(FeatureSettings s, Boolean master, Boolean like,
                                                  Boolean follow, Boolean comment, Boolean message) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, s.minimumLikeCount,
                master == null ? s.automationEnabled : master,
                like == null ? s.autoLikeEnabled : like,
                follow == null ? s.autoFollowEnabled : follow,
                comment == null ? s.autoCommentEnabled : comment,
                message == null ? s.autoMessageEnabled : message);
    }

    private static FeatureSettings make(boolean download, boolean watermark, boolean feed,
                                       boolean ads, boolean lives, boolean images,
                                       boolean cards, boolean merchants, boolean keywordEnabled, String keyword,
                                       boolean durationEnabled, int duration, boolean likesEnabled,
                                       int likes, boolean automation, boolean autoLike,
                                       boolean autoFollow, boolean autoComment, boolean autoMessage) {
        return new FeatureSettings(download, watermark, feed, ads, lives, images, cards, merchants,
                keywordEnabled, keyword, durationEnabled, duration, likesEnabled, likes,
                automation, autoLike, autoFollow, autoComment, autoMessage);
    }

    private static FeatureSettings withRecommendationMerchants(FeatureSettings s, boolean value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards, value,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private enum Category {
        BASIC("基本功能", 3),
        INTERFACE("界面相关", 13),
        FILTER("视频筛选", 14),
        DOWNLOAD("下载相关", 5),
        AUTOPLAY("自动播放", 6),
        AUTO_REPLY("自动回复", 8),
        AUTO_COMMENT("自动评论", 6),
        COMMENT("评论相关", 7),
        STREAK("自动续火花", 7),
        COMPONENT_HIDE("组件隐藏", 24),
        MESSAGE("消息管理", 6),
        SPOOF("本地伪装", 6),
        EXTENSION("扩展功能", 3);

        final String title;
        final int itemCount;

        Category(String title, int itemCount) {
            this.title = title;
            this.itemCount = itemCount;
        }
    }

    private static final class CategoryRow {
        final Category category;
        final View view;

        CategoryRow(Category category, View view) {
            this.category = category;
            this.view = view;
        }
    }

    private interface EnabledControl { void setEnabled(boolean enabled); }

    private static final class ToggleControl implements EnabledControl {
        private final java.util.function.Consumer<Boolean> checked;
        private final java.util.function.Consumer<Boolean> enabled;
        private final java.util.function.Consumer<View.OnLongClickListener> longClick;
        private final java.util.function.Consumer<String> summary;

        ToggleControl(java.util.function.Consumer<Boolean> checked,
                      java.util.function.Consumer<Boolean> enabled,
                      java.util.function.Consumer<View.OnLongClickListener> longClick,
                      java.util.function.Consumer<String> summary) {
            this.checked = checked;
            this.enabled = enabled;
            this.longClick = longClick;
            this.summary = summary;
        }

        void setChecked(boolean value) { checked.accept(value); }
        @Override public void setEnabled(boolean value) { enabled.accept(value); }
        void setOnLongClickListener(View.OnLongClickListener listener) { longClick.accept(listener); }
        void setSummary(String value) { summary.accept(value); }
    }

    private static final class OptionRow implements EnabledControl {
        final View view;
        private final java.util.function.Consumer<String> value;
        private final java.util.function.Consumer<Boolean> enabled;
        private final java.util.function.IntConsumer color;

        OptionRow(View view, java.util.function.Consumer<String> value,
                  java.util.function.Consumer<Boolean> enabled, java.util.function.IntConsumer color) {
            this.view = view;
            this.value = value;
            this.enabled = enabled;
            this.color = color;
        }

        void setValue(String text) { value.accept(text); }
        void setColor(int newColor) { color.accept(newColor); }
        @Override public void setEnabled(boolean newEnabled) { enabled.accept(newEnabled); }
    }

    private enum Filter { AD, LIVE, IMAGE, CARD, KEYWORD, DURATION, LIKES }
    private interface ValueListener { void onValue(boolean value); }
    private interface ValueTextListener { void onValue(String value); }
    private interface SettingsTransform { FeatureSettings apply(FeatureSettings current); }
}

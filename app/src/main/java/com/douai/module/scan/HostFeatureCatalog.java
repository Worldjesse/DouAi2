package com.douai.module.scan;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Core installation contracts. Runtime reflection checks remain authoritative. */
public final class HostFeatureCatalog {
    private static final List<Feature> FEATURES = Collections.unmodifiableList(Arrays.asList(
            feature("feed_filter", "推荐流筛选", "feed_hook_installed",
                    "main_feed_api_v1", "retrofit_success_response", "retrofit_response_url",
                    "aweme_ad_flag", "aweme_is_live", "aweme_is_image", "aweme_type",
                    "main_feed_response_model", "aweme_feed_attributes", "aweme_statistics_digg_count"),
            feature("download", "原生下载扩展", "download_hook_installed",
                    "download_native_module_name", "download_native_view_factory", "download_native_click"),
            feature("top_tabs", "顶部标签", "top_tab_hook_installed",
                    "home_tab_list_provider", "home_tab_model"),
            feature("feed_ui_video", "视频组件隐藏", "feed_ui_video_hook_installed",
                    "feed_holder_page_selected", "feed_holder_resume", "feed_holder_pause", "feed_holder_main_root"),
            feature("feed_ui_image", "图文组件隐藏", "feed_ui_image_hook_installed",
                    "image_holder_page_selected", "image_holder_resume", "image_holder_pause", "image_holder_main_root"),
            feature("home_navigation", "首页导航隐藏", "home_navigation_hook_installed",
                    "component_top_navigation", "component_bottom_navigation"),
            feature("refresh_confirm", "刷新确认", "refresh_hook_installed", "home_tab_select_or_refresh"),
            feature("auto_like", "自动点赞", "auto_like_hook_installed",
                    "digg_core_presenter_bind", "digg_view_model_like", "digg_view_model_state"),
            feature("auto_follow", "自动关注", "auto_follow_hook_installed",
                    "feed_holder_selected", "feed_holder_quick_root"),
            feature("auto_play", "自动连播", "auto_play_hook_installed",
                    "feed_holder_page_selected", "feed_holder_resume", "feed_holder_pause",
                    "feed_aweme_accessor", "feed_holder_main_root", "adv_gesture_item_params",
                    "auto_slide_panel_context", "auto_slide_current_holder", "adv_player_move_next",
                    "adv_player_move_previous", "auto_slide_pager_touch", "auto_slide_scroll_state",
                    "auto_slide_comments_showing", "auto_slide_completed",
                    "component_player_playing", "component_player_pause"),
            feature("auto_comment", "自动评论", "auto_comment_hook_installed",
                    "feed_holder_selected", "image_holder_page_selected", "auto_comment_checked_request",
                    "auto_comment_work_context", "portrait_comment_aweme_resolver",
                    "auto_comment_publish_success", "auto_comment_publish_failure"),
            feature("auto_message", "私聊草稿", "auto_message_hook_installed",
                    "auto_message_editor_bind", "auto_message_editor_fields", "auto_message_editor_getter",
                    "auto_message_text_api", "auto_message_session_id", "auto_message_input_logic",
                    "auto_message_plain_text_mode", "auto_message_single_chat_module"),
            feature("comment_tools", "评论工具", "comment_tools_hook_installed",
                    "comment_longpress_bind", "comment_longpress_item_model", "comment_panel_params", "comment_model"),
            feature("remove_splash_ads", "启动广告", "feature.remove_splash_ads.installed", "adv_ui_splash_gate"),
            feature("remove_teen_dialog", "青少年提示", "feature.remove_teen_dialog.installed", "adv_ui_teen_guide"),
            feature("block_long_press_speed", "长按倍速拦截", "feature.block_long_press_speed.installed", "adv_ui_long_press_speed"),
            feature("spoof_profile", "本地数量显示", "feature.spoof_profile.installed",
                    "adv_profile_render", "adv_profile_own", "adv_profile_root"),
            feature("recall_background", "撤回背景色", "feature.recall_background.installed",
                    "adv_im_cell_bind", "adv_im_cell_message", "adv_im_message_uuid",
                    "media_account_service", "media_current_user"),
            feature("comment_enhancements", "评论后缀及颜色", "feature.comment_enhancements.installed", "comment_request_factory"),
            feature("comment_voice", "评论语音替换", "feature.comment_voice.installed",
                    "comment_audio_upload", "comment_uploader_path", "comment_uploader_listener", "comment_audio_notify")
    ));

    private HostFeatureCatalog() { }

    public static List<Feature> getFeatures() { return FEATURES; }

    private static Feature feature(String id, String label, String installedKey, String... rules) {
        return new Feature(id, label, installedKey, rules);
    }

    public static final class Feature {
        public final String id;
        public final String label;
        public final String installedKey;
        public final List<String> rules;

        private Feature(String id, String label, String installedKey, String[] rules) {
            this.id = id;
            this.label = label;
            this.installedKey = installedKey;
            this.rules = Collections.unmodifiableList(Arrays.asList(rules.clone()));
        }
    }
}

package com.douai.module.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit transferable settings, separate from operational commands, consent and runtime data. */
final class ConfigKeys {
    static final Map<String, String> TYPES;
    static {
        Map<String, String> keys = new LinkedHashMap<>();
        add(keys, "boolean", "download_enabled remove_watermark feed_filter_enabled hide_ads hide_lives hide_image_posts "
                + "hide_recommendation_cards hide_recommendation_merchants keyword_filter_enabled minimum_duration_enabled "
                + "minimum_likes_enabled automation_enabled auto_like_enabled auto_follow_enabled auto_comment_enabled auto_message_enabled "
                + "hide_interface hide_top_tabs remove_black_edges hide_navigation_bar auto_play player_gestures copy_comment_text "
                + "comment_media_tools copy_source_data prevent_message_recall confirm_refresh spoof_version auto_speed "
                + "pause_auto_on_comments smart_swipe comment_append_suffix comment_voice_replace comment_tts colored_comment "
                + "streak_enabled streak_tip recall_self recall_other dm_voice_replace dm_tts spoof_profile download_custom_name "
                + "remove_download_limits filter_interrupt hide_mini_games hide_long_videos native_auto_reply native_auto_comment "
                + "reply_voice comment_voice reply_private_chats reply_group_chats reply_global comment_recognize_images "
                + "comment_recognize_video reply_tip comment_tip reply_save_history comment_save_history "
                + "toolbar_enabled intercept_native_long_press disable_double_tap_like block_long_press_speed "
                + "hide_camera_plus hide_video_black_edges prevent_insert_video prevent_skip_hidden remove_splash_ads "
                + "remove_teen_dialog remove_update_dialog");
        add(keys, "int", "minimum_duration_seconds minimum_like_count");
        add(keys, "string", "keyword video_right_alpha hidden_top_tabs fake_version_name fake_version_code auto_comment_text "
                + "auto_message_text playback_speed comment_suffix colored_comment_type comment_voice_path dm_voice_path "
                + "streak_content streak_content_api streak_start streak_end streak_targets recall_background "
                + "profile_like_count profile_following_count profile_follower_count profile_mutual_count download_name_template "
                + "download_directory_uri maximum_duration_seconds reply_mode reply_prompt reply_keywords reply_random "
                + "reply_targets reply_group_members reply_group_mode reply_group_scope_mode reply_group_targets "
                + "comment_mode comment_prompt comment_scope_users ai_model_settings_v2 "
                + "download_method third_party_get_template third_party_headers third_party_app_id third_party_app_secret "
                + "third_party_signature_mode third_party_signature_header third_party_download_fields third_party_naming_fields "
                + "tts_provider tts_engine tts_language tts_rate tts_pitch tts_get_template tts_headers tts_audio_field "
                + "tts_voice_get_template tts_voice_headers tts_voice_list_path tts_voice_id_path tts_voice_name_path "
                + "tts_selected_voice tts_selected_voice_name component_hide_custom_views");
        for (String prefix : Arrays.asList("reply", "comment")) add(keys, "string", prefix + "_daily_limit "
                + prefix + "_user_cooldown_minutes " + prefix + "_delay_seconds " + prefix + "_notice_seconds "
                + prefix + "_scope_mode " + prefix + "_scope_relations " + prefix + "_scope_keywords "
                + prefix + "_scope_fields " + prefix + "_filter_mode " + prefix + "_filter_keywords " + prefix + "_filter_fields");
        add(keys, "boolean", "component_hide_enabled component_hide_uniform_enabled component_hide_on_switch "
                + "component_hide_open_comments_on_show component_hide_show_on_pause");
        add(keys, "string", "component_hide_uniform_alpha");
        for (ComponentHideSettings.Component component : ComponentHideSettings.Component.values()) keys.put(component.key, "string");
        for (String prefix : Arrays.asList("long_press", "double_tap")) {
            for (String region : com.douai.module.feature.FeedGestureToolsHook.REGION_IDS) keys.put(prefix + "_" + region, "string");
            for (String legacy : Arrays.asList("left", "center", "right")) keys.put(prefix + "_" + legacy, "string");
        }
        TYPES = Collections.unmodifiableMap(keys);
    }
    private static void add(Map<String, String> keys, String type, String names) {
        for (String key : names.split(" ")) keys.put(key, type);
    }
    static void validate(String key, Object value) {
        String expected = TYPES.get(key);
        if (expected == null || !("boolean".equals(expected) && value instanceof Boolean
                || "string".equals(expected) && value instanceof String
                || "int".equals(expected) && value instanceof Integer)) throw ConfigJson.invalid();
        if (value instanceof String && ((String) value).length() > ConfigTransfer.MAX_BYTES) throw ConfigJson.invalid();
    }
    private ConfigKeys() { }
}

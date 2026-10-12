package com.douai.module.config;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Component opacity uses -2 (gone), -1 (invisible), or the 0..255 alpha range. */
public final class ComponentHideSettings {
    public static final String ENABLED = "component_hide_enabled";
    public static final String UNIFORM_ENABLED = "component_hide_uniform_enabled";
    public static final String UNIFORM_ALPHA = "component_hide_uniform_alpha";
    public static final String HIDE_ON_SWITCH = "component_hide_on_switch";
    public static final String OPEN_COMMENTS_ON_SHOW = "component_hide_open_comments_on_show";
    public static final String SHOW_ON_PAUSE = "component_hide_show_on_pause";
    public static final String CUSTOM_VIEWS = CustomViewLocator.SETTINGS_KEY;

    public enum Component {
        AVATAR("avatar", "头像透明度"),
        LIKE("like", "点赞透明度"),
        COMMENT("comment", "评论透明度"),
        COLLECT("collect", "收藏透明度"),
        SHARE("share", "分享透明度"),
        MUSIC_DISC("music_disc", "音乐碟片透明度"),
        CARD("card", "卡片透明度"),
        AUTHOR("author", "作者昵称透明度"),
        CO_CREATION("co_creation", "共创透明度"),
        DESCRIPTION("description", "视频描述透明度"),
        MUSIC_INFO("music_info", "音乐信息透明度"),
        FULLSCREEN("fullscreen", "全屏透明度"),
        TOP_NAV("top_nav", "顶部导航透明度"),
        BOTTOM_NAV("bottom_nav", "底部导航透明度"),
        FEEDBACK("feedback", "轻反馈组件透明度"),
        PROGRESS("progress", "进度条透明度"),
        BOTTOM_TOOLS("bottom_tools", "底部工具透明度"),
        TIME_LOCATION("time_location", "时间属地透明度");

        public final String key;
        public final String title;

        Component(String name, String title) {
            this.key = "component_alpha_" + name;
            this.title = title;
        }
    }

    public final boolean configured;
    public final boolean enabled;
    public final boolean uniformEnabled;
    public final int uniformAlpha;
    public final boolean hideOnSwitch;
    public final boolean openCommentsOnShow;
    public final boolean showOnPause;
    public final List<CustomViewLocator> customViews;
    private final Map<Component, Integer> alphas;

    private ComponentHideSettings(Values values) {
        configured = values.hasMaster();
        boolean legacyHide = values.bool("hide_interface", false);
        int legacyRight = 100;
        try { legacyRight = Math.max(0, Math.min(100, Integer.parseInt(values.string("video_right_alpha", "100")))); }
        catch (RuntimeException ignored) { }
        enabled = values.bool(ENABLED, legacyHide || legacyRight < 100);
        uniformEnabled = values.bool(UNIFORM_ENABLED, false);
        uniformAlpha = parseAlpha(values.string(UNIFORM_ALPHA, "255"), 255);
        hideOnSwitch = values.bool(HIDE_ON_SWITCH, true);
        openCommentsOnShow = values.bool(OPEN_COMMENTS_ON_SHOW, false);
        showOnPause = values.bool(SHOW_ON_PAUSE, true);
        customViews = CustomViewLocator.parse(values.string(CUSTOM_VIEWS, ""));
        EnumMap<Component, Integer> result = new EnumMap<>(Component.class);
        for (Component component : Component.values()) {
            int fallback = 255;
            if (!configured && component != Component.TOP_NAV && component != Component.BOTTOM_NAV) {
                if (legacyHide) fallback = -1;
                else if (component == Component.AVATAR || component == Component.LIKE
                        || component == Component.COMMENT || component == Component.COLLECT
                        || component == Component.SHARE) fallback = Math.round(legacyRight * 255f / 100);
            }
            result.put(component, parseAlpha(values.string(component.key, Integer.toString(fallback)), fallback));
        }
        alphas = Collections.unmodifiableMap(result);
    }

    public int alpha(Component component) {
        return uniformEnabled ? uniformAlpha : individualAlpha(component);
    }

    public int individualAlpha(Component component) {
        return alphas.get(component);
    }

    public static int parseAlpha(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= -2 && parsed <= 255 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static boolean isBooleanKey(String key) {
        return ENABLED.equals(key) || UNIFORM_ENABLED.equals(key) || HIDE_ON_SWITCH.equals(key)
                || OPEN_COMMENTS_ON_SHOW.equals(key) || SHOW_ON_PAUSE.equals(key);
    }

    public static boolean isAlphaKey(String key) {
        if (UNIFORM_ALPHA.equals(key)) return true;
        for (Component component : Component.values()) if (component.key.equals(key)) return true;
        return false;
    }

    public static void initialize(SettingsStore store) {
        ComponentHideSettings current = readFrom(store);
        if (current.configured) return;
        for (Component component : Component.values()) {
            if (store.getString(component.key, null) == null) {
                store.putString(component.key, Integer.toString(current.individualAlpha(component)));
            }
        }
        store.putBoolean(ENABLED, current.enabled);
    }

    public static ComponentHideSettings readFrom(SharedPreferences preferences) {
        return new ComponentHideSettings(new Values() {
            public boolean bool(String key, boolean fallback) { return preferences.getBoolean(key, fallback); }
            public String string(String key, String fallback) { return preferences.getString(key, fallback); }
            public boolean hasMaster() { return preferences.contains(ENABLED); }
        });
    }

    public static ComponentHideSettings readFrom(SettingsStore store) {
        return new ComponentHideSettings(new Values() {
            public boolean bool(String key, boolean fallback) { return store.getBoolean(key, fallback); }
            public String string(String key, String fallback) { return store.getString(key, fallback); }
            public boolean hasMaster() { return store.getBoolean(ENABLED, false) == store.getBoolean(ENABLED, true); }
        });
    }

    private interface Values {
        boolean bool(String key, boolean fallback);
        String string(String key, String fallback);
        boolean hasMaster();
    }
}

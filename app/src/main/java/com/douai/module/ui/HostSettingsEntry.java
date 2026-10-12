package com.douai.module.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Injects one entry into the uniquely identified host settings scroll content. */
final class HostSettingsEntry {
    private static final String TAG = "DouAi.HostSettingsEntry";

    private HostSettingsEntry() { }

    static View attach(Activity activity, Class<?> scrollClass, Runnable openSettings) {
        View content = activity.findViewById(android.R.id.content);
        List<ViewGroup> scrolls = new ArrayList<>();
        findScrolls(content, scrollClass, scrolls);
        if (scrolls.size() != 1) return null;
        ViewGroup scroll = scrolls.get(0);
        if (scroll.getChildCount() != 1 || !(scroll.getChildAt(0) instanceof LinearLayout)) return null;
        LinearLayout container = (LinearLayout) scroll.getChildAt(0);
        if (container.getOrientation() != LinearLayout.VERTICAL) return null;
        for (int index = 0; index < container.getChildCount(); index++) {
            View existing = container.getChildAt(index);
            if (TAG.equals(existing.getTag())) return existing;
        }

        TextView entry = new TextView(activity);
        entry.setTag(TAG);
        entry.setText("DouAi 模块设置");
        entry.setTextSize(16);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        entry.setMinHeight(dp(activity, 54));
        entry.setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_preferences,
                0, 0, 0);
        entry.setCompoundDrawablePadding(dp(activity, 12));
        entry.setOnClickListener(ignored -> openSettings.run());
        entry.setFocusable(true);
        container.addView(entry, Math.min(1, container.getChildCount()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        return entry;
    }

    private static void findScrolls(View view, Class<?> scrollClass, List<ViewGroup> matches) {
        if (view == null) return;
        if (scrollClass.isInstance(view) && view instanceof ViewGroup) {
            matches.add((ViewGroup) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                findScrolls(group.getChildAt(index), scrollClass, matches);
            }
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

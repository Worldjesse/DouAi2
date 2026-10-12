package com.douai.module.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.douai.module.config.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ModuleSettingsIsolationTest {
    @Test
    public void standaloneModuleScreenExplainsThatHostSettingsAreIndependent() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity,
                new SettingsRepository(activity));
        View root = screen.createView();
        assertTrue(countText(root, "抖音内功能设置独立保存在抖音应用，本页只显示模块应用状态。"));
        assertFalse(countText(root, "组件隐藏"));
        assertFalse(countText(root, "推荐流筛选"));
        activity.finish();
    }

    private static boolean countText(View root, String expected) {
        if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (countText(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }
}

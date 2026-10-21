package com.douai.module.feature;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HomeNavigationHideControllerTest {
    public static final class RenamedBottomBar extends FrameLayout {
        public RenamedBottomBar(android.content.Context context) { super(context); }
    }

    @Test public void scannedBottomBarSurvivesAnObfuscatedClassRenameAndRestoresOnClose() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = app.getSharedPreferences("navigation-rename", 0);
        prefs.edit().clear().putBoolean(com.douai.module.config.ComponentHideSettings.ENABLED, true)
                .putString("component_alpha_bottom_nav", "-2").commit();
        com.ss.android.ugc.aweme.main.MainActivity activity = Robolectric
                .buildActivity(com.ss.android.ugc.aweme.main.MainActivity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        RenamedBottomBar bottom = new RenamedBottomBar(activity);
        root.addView(bottom);
        activity.setContentView(root);
        HomeNavigationHideController controller = HomeNavigationHideController.install(app,
                com.douai.module.config.ExtendedSettings.readFrom(prefs),
                com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar.class,
                RenamedBottomBar.class, null);
        try {
            controller.onActivityResumed(activity);
            assertEquals(View.GONE, bottom.getVisibility());
        } finally { controller.close(); }
        assertEquals(View.VISIBLE, bottom.getVisibility());
    }
    @Test
    public void uniqueNavigationTargetRefusesAnAmbiguousLayout() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        TextView first = new TextView(root.getContext());
        root.addView(first);
        assertSame(first, HomeNavigationHideController.uniqueTarget(root, TextView.class.getName()));
        root.addView(new TextView(root.getContext()));
        assertNull(HomeNavigationHideController.uniqueTarget(root, TextView.class.getName()));
        assertNull(HomeNavigationHideController.uniqueTarget(root, "missing.Navigation"));
    }

    @Test
    public void goneInvisibleAndAlphaRestoreOriginalInteractionAndVisibility() {
        View view = new View(RuntimeEnvironment.getApplication());
        view.setAlpha(0.8f);
        view.setClickable(true);
        view.setFocusable(false);
        HomeNavigationHideController.Original original = new HomeNavigationHideController.Original(view);
        original.apply(view, -2);
        assertEquals(View.GONE, view.getVisibility());
        assertFalse(view.isClickable());
        original.apply(view, -1);
        assertEquals(View.INVISIBLE, view.getVisibility());
        original.apply(view, 70);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(0.8f * 70 / 255f, view.getAlpha(), 0.001f);
        assertTrue(view.isClickable());
        original.apply(view, 0);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertTrue(view.isClickable());
        original.restore(view);
        assertEquals(0.8f, view.getAlpha(), 0.001f);
        assertFalse(view.isFocusable());
    }
}

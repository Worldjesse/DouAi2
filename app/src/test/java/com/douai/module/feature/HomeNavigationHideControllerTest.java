package com.douai.module.feature;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HomeNavigationHideControllerTest {
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

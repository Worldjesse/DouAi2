package com.douai.module.feature;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import com.douai.module.config.ExtendedSettings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ImmersiveModeControllerTest {
    @Test
    public void statusAndNavigationBarsCanBeHiddenIndependentlyAndRestored() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int originalVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        int originalWindowFlags = activity.getWindow().getAttributes().flags;
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), settings(false, false), ignored -> { });
        controller.onActivityResumed(activity);

        controller.setSettings(settings(false, true));
        int navigationVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        assertTrue((navigationVisibility & 2) != 0);
        assertTrue((navigationVisibility & 4096) != 0);
        assertEquals(originalWindowFlags & WindowManager.LayoutParams.FLAG_FULLSCREEN,
                activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN);

        controller.setSettings(settings(true, false));
        int statusVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        assertTrue((statusVisibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0);
        assertFalse((statusVisibility & 2) != 0);
        assertTrue((activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);

        controller.setSettings(settings(false, false));
        assertEquals(originalVisibility, activity.getWindow().getDecorView().getSystemUiVisibility());
        assertEquals(originalWindowFlags, activity.getWindow().getAttributes().flags);
        controller.close();
        activity.finish();
    }

    @Test
    public void backgroundConfigurationAppliesOnMainAndLateUpdatesAfterCloseAreIgnored() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int original = activity.getWindow().getDecorView().getSystemUiVisibility();
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), settings(false, false), ignored -> { });
        controller.onActivityResumed(activity);
        ExtendedSettings hidden = settings(false, true);
        Thread worker = new Thread(() -> controller.setSettings(hidden));
        worker.start();
        worker.join();
        Object looperShadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        looperShadow.getClass().getMethod("idle").invoke(looperShadow);
        assertTrue((activity.getWindow().getDecorView().getSystemUiVisibility() & 2) != 0);
        controller.close();
        controller.setSettings(hidden);
        looperShadow.getClass().getMethod("idle").invoke(looperShadow);
        assertEquals(original, activity.getWindow().getDecorView().getSystemUiVisibility());
        activity.finish();
    }

    private static ExtendedSettings settings(boolean hideStatusBar, boolean hideNavigationBar) {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("immersive_controller_test", 0);
        preferences.edit().putBoolean("remove_black_edges", hideStatusBar)
                .putBoolean("hide_navigation_bar", hideNavigationBar).commit();
        return ExtendedSettings.readFrom(preferences);
    }
}

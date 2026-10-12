package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.WindowInsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h640dp-mdpi")
public final class HostDialogBoundsTest {
    @Test public void centeredWindowLeavesHostVisibleOnEverySide() { verifyBounds(); }
    @Test @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    public void shortLandscapeWindowKeepsMargins() { verifyBounds(); }
    @Test @Config(qualifiers = "w1200dp-h900dp-land-mdpi")
    public void tabletWindowHasALimitedWidthAndHeight() { verifyBounds(); }

    @Test public void keyboardInsetsShrinkWindowAndRestoreItsOriginalBounds() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Dialog dialog = new Dialog(activity);
        dialog.show();
        HostDialogBounds.install(activity, dialog, false);
        int original = dialog.getWindow().getAttributes().height;
        WindowInsets keyboard = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 20))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 280)).build();
        dialog.getWindow().getDecorView().dispatchApplyWindowInsets(keyboard);
        assertTrue(dialog.getWindow().getAttributes().height < original);
        assertTrue(dialog.getWindow().getAttributes().height <= 640 - 24 - 280 - 48);
        dialog.getWindow().getDecorView().dispatchApplyWindowInsets(new WindowInsets.Builder().build());
        assertEquals(original, dialog.getWindow().getAttributes().height);
        dialog.dismiss();
        activity.finish();
    }

    private void verifyBounds() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Dialog dialog = new Dialog(activity);
        dialog.show();
        HostDialogBounds.install(activity, dialog, false);
        android.view.WindowManager.LayoutParams bounds = dialog.getWindow().getAttributes();
        assertEquals(Gravity.CENTER, bounds.gravity);
        assertTrue(bounds.width > 0 && bounds.width <= 560);
        assertTrue(bounds.height > 0 && bounds.height <= 720);
        assertTrue(bounds.width < activity.getResources().getDisplayMetrics().widthPixels);
        assertTrue(bounds.height < activity.getResources().getDisplayMetrics().heightPixels);
        dialog.dismiss();
        activity.finish();
    }
}

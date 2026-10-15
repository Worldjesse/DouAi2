package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.ui.miuix.MiuixDialogBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ModuleThemeTest {
    @Test public void existingEditsAndOriginalPaletteSurviveDarkLightRoundTrip() {
        Context context = ModuleTheme.themedContext(RuntimeEnvironment.getApplication());
        LinearLayout root = new LinearLayout(context);
        root.setBackgroundColor(Color.WHITE);
        Drawable original = root.getBackground();
        EditText input = new EditText(context);
        input.setText("尚未保存的配置");
        input.setSelection(2, 5);
        input.setTextColor(0xFF1F272D);
        root.addView(input);
        ModuleTheme.bind(root);

        RuntimeEnvironment.setQualifiers("+night");
        ModuleTheme.bind(root);
        assertEquals(0xFF242424, ((ColorDrawable) root.getBackground()).getColor());
        assertEquals(0xFFF2F2F2, input.getCurrentTextColor());
        assertEquals("尚未保存的配置", input.getText().toString());
        assertEquals(2, input.getSelectionStart());
        assertEquals(5, input.getSelectionEnd());

        RuntimeEnvironment.setQualifiers("+notnight");
        ModuleTheme.bind(root);
        assertSame(original, root.getBackground());
        assertEquals(0xFF1F272D, input.getCurrentTextColor());
        assertEquals(2, input.getSelectionStart());
        assertEquals(5, input.getSelectionEnd());
    }

    @Test public void changingTextColorsAfterBindingRetainsNewStateColors() {
        TextView label = new TextView(RuntimeEnvironment.getApplication());
        ModuleTheme.bind(label);
        int[][] states = {{-android.R.attr.state_enabled}, {android.R.attr.state_pressed}, {}};
        label.setTextColor(new ColorStateList(states, new int[]{0x66445566, 0xFF123456, 0xFF356289}));
        RuntimeEnvironment.setQualifiers("+night");
        ModuleTheme.bind(label);
        assertEquals(0xFF356289, label.getTextColors().getDefaultColor());
        assertEquals(0x66445566, label.getTextColors().getColorForState(new int[]{}, 0));
        assertEquals(0xFF123456, label.getTextColors().getColorForState(
                new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed}, 0));
    }

    @Test public void dynamicChildrenFollowNightModeAndColorPreviewsKeepExactColors() {
        RuntimeEnvironment.setQualifiers("+night");
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        LinearLayout root = new LinearLayout(controller.get());
        ModuleTheme.bind(root);
        controller.get().setContentView(root);
        TextView added = new TextView(controller.get());
        added.setTextColor(0xFF1F272D);
        root.addView(added);
        View swatch = new View(controller.get());
        swatch.setBackgroundColor(Color.WHITE);
        ModuleTheme.preserveColors(swatch);
        root.addView(swatch);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(0xFFF2F2F2, added.getCurrentTextColor());
        assertEquals(Color.WHITE, ((ColorDrawable) swatch.getBackground()).getColor());
        controller.pause().stop().destroy();
    }

    @Test public void detachUnregistersCallbackAndCancelsItsPendingRefresh() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        TrackingContext context = new TrackingContext(controller.get());
        LinearLayout root = new LinearLayout(context);
        root.setBackgroundColor(Color.WHITE);
        ModuleTheme.bind(root);
        ModuleTheme.bind(root);
        controller.get().setContentView(root);
        assertEquals(1, context.callbacks.size());
        RuntimeEnvironment.setQualifiers("+night");
        context.callbacks.get(0).onConfigurationChanged(Resources.getSystem().getConfiguration());
        controller.get().setContentView(new View(controller.get()));
        assertTrue(context.callbacks.isEmpty());
        ShadowLooper.idleMainLooper();
        assertEquals(Color.WHITE, ((ColorDrawable) root.getBackground()).getColor());
        controller.get().setContentView(root);
        assertEquals(1, context.callbacks.size());
        assertEquals(0xFF242424, ((ColorDrawable) root.getBackground()).getColor());
        controller.pause().stop().destroy();
        assertTrue(context.callbacks.isEmpty());
    }

    @Test public void themedContextChangesWithoutMutatingHostThemeOrResources() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        Resources.Theme originalHostTheme = host.getTheme();
        Context module = ModuleTheme.themedContext(host);
        Resources.Theme light = module.getTheme();
        assertSame(module, ModuleTheme.themedContext(module));
        assertNotSame(originalHostTheme, light);
        RuntimeEnvironment.setQualifiers("+night");
        Configuration override = new Configuration(host.getResources().getConfiguration());
        override.uiMode = (override.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        host.getResources().updateConfiguration(override, host.getResources().getDisplayMetrics());
        assertTrue(ModuleTheme.isDark(module));
        assertNotSame(light, module.getTheme());
        assertSame(originalHostTheme, host.getTheme());
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
        controller.pause().stop().destroy();
    }

    @Test public void openNativeAlertUpdatesBackgroundTogetherWithText() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Context context = ModuleTheme.themedContext(controller.get());
        AlertDialog alert = new MiuixDialogBuilder(context, false)
                .setTitle("确认操作").setMessage("当前内容").setPositiveButton("确定", null).create();
        alert.show();
        View decor = alert.getWindow().getDecorView();
        ModuleTheme.bind(decor);
        assertTrue(Color.red(centerColor(decor.getBackground())) > 180);
        RuntimeEnvironment.setQualifiers("+night");
        ModuleTheme.bind(decor);
        TextView message = alert.findViewById(android.R.id.message);
        assertTrue(Color.red(message.getCurrentTextColor()) > 180);
        assertTrue("An open native alert must not retain its light window behind light text",
                Color.red(centerColor(decor.getBackground())) < 128);
        RuntimeEnvironment.setQualifiers("+notnight");
        ModuleTheme.bind(decor);
        assertTrue(Color.red(centerColor(decor.getBackground())) > 180);
        assertTrue(Color.red(message.getCurrentTextColor()) < 128);
        alert.dismiss();
        controller.pause().stop().destroy();
    }

    @Test public void nativeButtonAndSpinnerPopupSwitchWithAnAlreadyOpenForm() {
        Context context = ModuleTheme.themedContext(RuntimeEnvironment.getApplication());
        LinearLayout root = new LinearLayout(context);
        Button button = new Button(context);
        button.setText("读取模型");
        Spinner spinner = new Spinner(context, Spinner.MODE_DROPDOWN);
        root.addView(button);
        root.addView(spinner);
        ModuleTheme.bind(root);
        assertNotNull(button.getBackgroundTintList());
        assertTrue(Color.red(button.getBackgroundTintList().getDefaultColor()) > 128);
        assertTrue(Color.red(centerColor(spinner.getPopupBackground())) > 180);
        RuntimeEnvironment.setQualifiers("+night");
        ModuleTheme.bind(root);
        assertTrue("Native button background must follow its newly light label",
                Color.red(button.getBackgroundTintList().getDefaultColor()) < 128);
        assertTrue("A popup opened after a theme change must use its current theme",
                Color.red(centerColor(spinner.getPopupBackground())) < 128);
        ColorStateList labels = button.getTextColors();
        assertTrue("Disabled buttons must remain visually distinguishable",
                Color.alpha(labels.getColorForState(new int[]{}, 0))
                        < Color.alpha(labels.getColorForState(new int[]{android.R.attr.state_enabled}, 0)));
        RuntimeEnvironment.setQualifiers("+notnight");
        ModuleTheme.bind(root);
        assertTrue(Color.red(button.getBackgroundTintList().getDefaultColor()) > 128);
        assertTrue(Color.red(centerColor(spinner.getPopupBackground())) > 180);
    }

    private static int centerColor(Drawable drawable) {
        assertNotNull(drawable);
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        android.graphics.Rect oldBounds = new android.graphics.Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 100, 100);
        drawable.draw(new Canvas(bitmap));
        drawable.setBounds(oldBounds);
        int color = bitmap.getPixel(50, 50);
        bitmap.recycle();
        return color;
    }

    private static final class TrackingContext extends ContextWrapper {
        final List<ComponentCallbacks> callbacks = new ArrayList<>();
        TrackingContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public void registerComponentCallbacks(ComponentCallbacks callback) { callbacks.add(callback); }
        @Override public void unregisterComponentCallbacks(ComponentCallbacks callback) { callbacks.remove(callback); }
    }
}

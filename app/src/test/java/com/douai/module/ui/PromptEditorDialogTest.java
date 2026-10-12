package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w296dp-h560dp-mdpi")
public final class PromptEditorDialogTest {
    private Activity activity;
    private PromptEditorDialog dialog;
    private AtomicReference<String> saved;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        saved = new AtomicReference<>();
        dialog = new PromptEditorDialog(activity, "一级提示词", "记忆设定\n", saved::set);
        dialog.setOwnerActivity(activity);
        dialog.show();
    }

    @After public void cleanup() { dialog.dismiss(); activity.finish(); }

    @Test public void longPromptHasNoLengthFilterAndSavePreservesWhitespace() {
        EditText input = input();
        assertEquals(0, input.getFilters().length);
        String text = " 记忆属性\n".repeat(50000);
        input.setText(text);
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();
        assertEquals(text, saved.get());
        assertFalse(dialog.isShowing());
    }

    @Test public void cancellationDoesNotCommitAnEditedPrompt() {
        input().setText("不保存");
        dialog.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        assertNull(saved.get());
    }

    @Test public void keyboardInsetsResizeEditorWithoutChangingItsText() {
        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 20))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 240)).build();
        dialog.getWindow().getDecorView().dispatchApplyWindowInsets(insets);
        assertEquals(264, dialog.getWindow().getAttributes().height);
        assertEquals("记忆设定\n", input().getText().toString());
        assertTrue((dialog.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) != 0);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void narrowEditorUsesMostOfTheDialogAndButtonsRemainVisible() { renderPreview(); }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void landscapeEditorRemainsScrollableAndBounded() { renderPreview(); }

    private void renderPreview() {
        input().setText("记忆设定\n保持简洁自然的表达。\n保留身份、偏好和交流风格。\n".repeat(30));
        View decor = dialog.getWindow().getDecorView();
        WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
        decor.measure(View.MeasureSpec.makeMeasureSpec(attributes.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(attributes.height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, attributes.width, attributes.height);
        assertTrue(input().getHeight() > attributes.height / 2);
        View save = dialog.getButton(Dialog.BUTTON_POSITIVE);
        View footer = (View) save.getParent();
        View root = (View) input().getParent();
        assertTrue(input().getBottom() <= footer.getTop());
        assertTrue(footer.getTop() + save.getBottom() <= root.getHeight());
        String folder = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (folder == null) return;
        java.io.File directory = new java.io.File(folder);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(directory,
                "prompt-editor-" + metrics.widthPixels + "x" + metrics.heightPixels + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private EditText input() { return dialog.getWindow().getDecorView().findViewWithTag("settings.input.一级提示词"); }
}

package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;
import com.douai.module.ui.miuix.MiuixPromptEditorSurface;

/** Large multiline prompt editing surface; values are committed only on Save. */
final class PromptEditorDialog extends Dialog {
    private final EditText input;
    private final Button save;
    private final Button cancel;
    private MiuixPromptEditorSurface miuixSurface;

    PromptEditorDialog(Context context, String title, String value, Consumer<String> onSave) {
        this(context, title, value, onSave, false);
    }

    PromptEditorDialog(Context baseContext, String title, String value, Consumer<String> onSave, boolean useMiuix) {
        super(ModuleTheme.themedContext(baseContext), ModuleTheme.dialogTheme(baseContext));
        Context context = getContext();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle(title);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.setTag("prompt.editor");
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextSize(18);
        heading.setTextColor(Color.rgb(31, 39, 45));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(40)));

        input = new EditText(context);
        input.setTag("settings.input." + title);
        input.setContentDescription(title);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextSize(16);
        input.setTextColor(Color.rgb(31, 39, 45));
        input.setHorizontallyScrolling(false);
        input.setVerticalScrollBarEnabled(true);
        input.setText(value);
        root.addView(input, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        cancel = new Button(context);
        cancel.setText("取消");
        cancel.setTag("prompt.cancel");
        cancel.setOnClickListener(ignored -> dismiss());
        footer.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        save = new Button(context);
        save.setText("保存");
        save.setTag("prompt.save");
        save.setOnClickListener(ignored -> {
            try { onSave.accept(input.getText().toString()); dismiss(); }
            catch (RuntimeException failure) { Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show(); }
        });
        footer.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(52)));
        Activity activity = findActivity(context);
        if (useMiuix && activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            setOwnerActivity(activity);
            miuixSurface = new MiuixPromptEditorSurface(activity, title, input, save, cancel, this::dismiss);
            setContentView(miuixSurface.getView());
        } else {
            ModuleTheme.bind(root);
            setContentView(root);
        }
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.42f;
            window.setAttributes(attributes);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            window.getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                sizeWindow(insets);
                return insets;
            });
        }
    }

    Button getButton(int which) { return which == BUTTON_POSITIVE ? save : which == BUTTON_NEGATIVE ? cancel : null; }

    @Override protected void onStart() {
        super.onStart();
        if (miuixSurface != null) miuixSurface.bindBackNavigation(this);
        Window window = getWindow();
        if (window != null) sizeWindow(window.getDecorView().getRootWindowInsets());
        if (miuixSurface == null) input.requestFocus();
    }

    @Override public void dismiss() {
        if (miuixSurface != null) {
            miuixSurface.close();
            miuixSurface = null;
        }
        super.dismiss();
    }

    private void sizeWindow(WindowInsets insets) {
        Window window = getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        Activity owner = getOwnerActivity();
        if (Build.VERSION.SDK_INT >= 30 && owner != null) {
            Rect bounds = owner.getWindowManager().getCurrentWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        }
        int horizontal = 0;
        int vertical = 0;
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                horizontal = safe.left + safe.right;
                vertical = safe.top + safe.bottom;
            } else {
                horizontal = insets.getSystemWindowInsetLeft() + insets.getSystemWindowInsetRight();
                vertical = insets.getSystemWindowInsetTop() + insets.getSystemWindowInsetBottom();
            }
        }
        width = Math.max(1, Math.min(dp(560), width - horizontal - dp(32)));
        height = Math.max(1, Math.min(dp(720), Math.min(Math.round(height * 0.84f), height - vertical - dp(32))));
        window.setGravity(Gravity.CENTER);
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.width != width || attributes.height != height) window.setLayout(width, height);
    }

    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return context instanceof Activity ? (Activity) context : null;
    }
}

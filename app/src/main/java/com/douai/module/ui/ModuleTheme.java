package com.douai.module.ui;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import androidx.compose.ui.platform.AbstractComposeView;
import com.douai.module.R;

/** System theme for module-owned windows only; never changes the host application theme. */
public final class ModuleTheme {
    private ModuleTheme() { }

    public static boolean isDark(Context context) {
        // Global resources are updated before Activity-specific configuration overrides.
        int mode = Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (mode == Configuration.UI_MODE_NIGHT_UNDEFINED) {
            mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        }
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int dialogTheme(Context context) {
        return isDark(context) ? android.R.style.Theme_Material_Dialog_NoActionBar
                : android.R.style.Theme_Material_Light_Dialog_NoActionBar;
    }

    public static int alertTheme(Context context) {
        return isDark(context) ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    public static Context themedContext(Context context) {
        return context instanceof ThemedContext ? context : new ThemedContext(context);
    }

    private static final class ThemedContext extends ContextThemeWrapper {
        private Resources.Theme localTheme;
        private boolean dark;
        ThemedContext(Context base) { super(base, 0); }
        @Override public Resources.Theme getTheme() {
            boolean next = isDark(this);
            if (localTheme == null || dark != next) {
                dark = next;
                localTheme = getResources().newTheme();
                localTheme.applyStyle(dark ? android.R.style.Theme_Material
                        : android.R.style.Theme_Material_Light, true);
            }
            return localTheme;
        }
    }

    /** Translate only the module's existing palette; arbitrary user colors stay unchanged. */
    public static int color(Context context, int lightColor) {
        return isDark(context) ? darkColor(lightColor) : lightColor;
    }

    private static int darkColor(int color) {
        switch (color) {
            case 0xFFFFFFFF: return 0xFF242424;
            case 0xFF1F272D: case 0xFF20282C: case 0xFF242A30: case 0xFF000000: return 0xFFF2F2F2;
            case 0xFF69737A: case 0xFF636E74: case 0xFF778087: return 0xFFB0B6BB;
            case 0xFFE2E7E8: case 0xFFE2E8E7: case 0xFFE8EDED: case 0xFFDCE1E5: return 0xFF41474C;
            case 0xFFF8FAFA: case 0xFFF6F8F8: case 0xFFF5F6F9: return 0xFF141414;
            case 0xFFF8F9FA: case 0xFFEEEFF3: case 0xFFF2F3F7: case 0xFFE9EEF1: return 0xFF303438;
            case 0xFF009788: case 0xFF008579: case 0xFF00776C: case 0xFF009275: return 0xFF70D9C5;
            case 0xFF0085E1: return 0xFF8AC8FF;
            case 0xFFE1F3ED: case 0xFFE7F5F2: return 0xFF203D37;
            case 0xFFD2EBE3: case 0xFFC9E5DF: case 0xFFACDBD3: return 0xFF41655D;
            case 0xFFD7DCDE: return 0xFF4D555A;
            case 0xFF8A959B: return 0xFF9BA7AE;
            case 0xFFAE3139: case 0xFFC65443: case 0xFFB3261E: return 0xFFFFB4AB;
            default: return color;
        }
    }

    /** Mark color previews/content whose exact color is meaningful, before binding their root. */
    public static void preserveColors(View view) { view.setTag(R.id.module_theme_preserve, Boolean.TRUE); }

    /** Bind once, then update existing controls in place so unsaved edits and selection survive. */
    public static void bind(View root) {
        if (root == null || root instanceof AbstractComposeView) return;
        Object existing = root.getTag(R.id.module_theme_binding);
        if (existing instanceof Binding) { ((Binding) existing).refresh(); return; }
        Binding binding = new Binding(root);
        root.setTag(R.id.module_theme_binding, binding);
        root.addOnAttachStateChangeListener(binding);
        binding.refresh();
        if (root.isAttachedToWindow()) binding.onViewAttachedToWindow(root);
    }

    private static final class Binding implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener, ComponentCallbacks {
        private final View root;
        private final Context application;
        private final Runnable update = this::refresh;
        private ViewTreeObserver observer;
        private boolean attached;
        private boolean dark;
        Binding(View root) {
            this.root = root;
            Context app = root.getContext().getApplicationContext();
            application = app == null ? root.getContext() : app;
        }
        void refresh() { dark = isDark(root.getContext()); applyTree(root, dark); }
        @Override public void onViewAttachedToWindow(View view) {
            if (attached) return;
            attached = true;
            application.registerComponentCallbacks(this);
            observer = root.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
            observer.addOnPreDrawListener(this);
            refresh();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if (!attached) return;
            attached = false;
            application.unregisterComponentCallbacks(this);
            root.removeCallbacks(update);
            if (observer != null && observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(this);
                observer.removeOnPreDrawListener(this);
            }
            observer = null;
        }
        @Override public void onGlobalLayout() { refresh(); }
        @Override public boolean onPreDraw() {
            if (dark != isDark(root.getContext())) refresh();
            return true;
        }
        @Override public void onConfigurationChanged(Configuration configuration) {
            root.removeCallbacks(update);
            if (attached) root.post(update);
        }
        @Override public void onLowMemory() { }
    }

    private static void applyTree(View view, boolean dark) {
        if (Boolean.TRUE.equals(view.getTag(R.id.module_theme_preserve)) || view instanceof AbstractComposeView) return;
        if (Build.VERSION.SDK_INT >= 29 && view.isForceDarkAllowed()) view.setForceDarkAllowed(false);
        Object tag = view.getTag(R.id.module_theme_colors);
        Colors colors = tag instanceof Colors ? (Colors) tag : new Colors();
        if (tag != colors) view.setTag(R.id.module_theme_colors, colors);
        colors.apply(view, dark);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i), dark);
        }
    }

    private static final int[][] STATES = {
            {-android.R.attr.state_enabled}, {android.R.attr.state_pressed},
            {android.R.attr.state_checked}, {android.R.attr.state_selected},
            {android.R.attr.state_focused}, {}
    };

    private static ColorStateList textColors(ColorStateList source, boolean dark, boolean hint) {
        int[] colors = new int[STATES.length];
        for (int i = 0; i < colors.length; i++) {
            int[] query = i == 0 ? STATES[i] : i == STATES.length - 1
                    ? new int[]{android.R.attr.state_enabled}
                    : new int[]{android.R.attr.state_enabled, STATES[i][0]};
            int original = source.getColorForState(query, source.getDefaultColor());
            int rgb = original | 0xFF000000;
            // Framework text may have been created under a dark ContextThemeWrapper.
            // Normalize neutral framework white/black while leaving colored text intact.
            int base = rgb == Color.WHITE || rgb == Color.BLACK ? (hint ? 0xFF69737A : 0xFF242A30) : rgb;
            int mapped = dark ? darkColor(base) : base;
            colors[i] = (mapped & 0x00FFFFFF) | (original & 0xFF000000);
        }
        return new ColorStateList(STATES, colors);
    }

    private static final class TextColors {
        ColorStateList source;
        ColorStateList rendered;
        boolean dark;
        ColorStateList update(ColorStateList current, boolean nextDark, boolean hint) {
            if (source == null || current != rendered) {
                source = current;
                rendered = null;
            }
            if (rendered == null || dark != nextDark) {
                dark = nextDark;
                rendered = textColors(source, nextDark, hint);
            }
            return rendered;
        }
    }

    private static final class Colors {
        final TextColors text = new TextColors();
        final TextColors hint = new TextColors();
        final TextColors link = new TextColors();
        Drawable background;
        Drawable renderedBackground;
        boolean backgroundDark;
        Boolean controlsDark;

        void apply(View view, boolean dark) {
            Drawable current = view.getBackground();
            if (current != renderedBackground) { background = current; renderedBackground = null; }
            if (current != null && (renderedBackground == null || backgroundDark != dark)) {
                renderedBackground = mappedBackground(background, dark, view.getResources());
                backgroundDark = dark;
                if (renderedBackground != current) view.setBackground(renderedBackground);
            }
            if (view instanceof TextView) {
                TextView label = (TextView) view;
                ColorStateList next = text.update(label.getTextColors(), dark, false);
                if (label.getTextColors() != next) label.setTextColor(next);
                if (label.getHintTextColors() != null) {
                    next = hint.update(label.getHintTextColors(), dark, true);
                    if (label.getHintTextColors() != next) label.setHintTextColor(next);
                }
                if (label.getLinkTextColors() != null) {
                    next = link.update(label.getLinkTextColors(), dark, false);
                    if (label.getLinkTextColors() != next) label.setLinkTextColor(next);
                }
            }
            if (controlsDark == null || controlsDark != dark) {
                controlsDark = dark;
                int accent = dark ? 0xFF70D9C5 : 0xFF008579;
                int muted = dark ? 0xFF9BA7AE : 0xFF8A959B;
                ColorStateList control = new ColorStateList(new int[][]{
                        {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                        new int[]{dark ? 0xFF596267 : 0xFFB0B8BD, accent, muted});
                if (view instanceof Switch) {
                    ((Switch) view).setThumbTintList(control);
                    ((Switch) view).setTrackTintList(new ColorStateList(new int[][]{
                            {android.R.attr.state_checked}, {}}, new int[]{dark ? 0xFF41655D : 0xFFACDBD3,
                            dark ? 0xFF4D555A : 0xFFD7DCDE}));
                } else if (view instanceof CompoundButton) ((CompoundButton) view).setButtonTintList(control);
                if (view instanceof EditText && !(view.getBackground() instanceof GradientDrawable)) {
                    view.setBackgroundTintList(ColorStateList.valueOf(accent));
                }
                if ((view instanceof Button && !(view instanceof CompoundButton) || view instanceof ImageButton)
                        && !(view.getBackground() instanceof GradientDrawable)
                        && !(view.getBackground() instanceof ColorDrawable)) {
                    view.setBackgroundTintList(new ColorStateList(new int[][]{
                            {-android.R.attr.state_enabled}, {android.R.attr.state_pressed}, {}},
                            new int[]{dark ? 0xFF282D30 : 0xFFEBEEEE,
                                    dark ? 0xFF41655D : 0xFFACDBD3, dark ? 0xFF303438 : 0xFFE7F5F2}));
                }
                if (view instanceof ImageButton) ((ImageButton) view).setImageTintList(ColorStateList.valueOf(accent));
                if (view instanceof Spinner) {
                    ((Spinner) view).setPopupBackgroundDrawable(new ColorDrawable(dark ? 0xFF242424 : Color.WHITE));
                }
                if (view instanceof ProgressBar) {
                    ((ProgressBar) view).setProgressTintList(ColorStateList.valueOf(accent));
                    ((ProgressBar) view).setIndeterminateTintList(ColorStateList.valueOf(accent));
                }
            }
        }
    }

    private static Drawable mappedBackground(Drawable original, boolean dark, Resources resources) {
        if (!dark) return original;
        if (original instanceof ColorDrawable) {
            int color = ((ColorDrawable) original).getColor();
            int mapped = darkColor(color);
            return mapped == color ? original : new ColorDrawable(mapped);
        }
        if (original instanceof GradientDrawable && original.getConstantState() != null) {
            GradientDrawable shape = (GradientDrawable) original;
            ColorStateList fill = shape.getColor();
            if (fill != null && darkColor(fill.getDefaultColor()) != fill.getDefaultColor()) {
                GradientDrawable copy = (GradientDrawable) original.getConstantState().newDrawable(resources).mutate();
                copy.setColor(darkColor(fill.getDefaultColor()));
                // Module cards/inputs use a thin outline. Avoid the original near-white edge at night.
                copy.setStroke(Math.max(1, Math.round(resources.getDisplayMetrics().density)), 0xFF41474C);
                return copy;
            }
        }
        return original;
    }
}

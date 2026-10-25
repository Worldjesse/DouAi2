package com.douai.module.feature;

import android.graphics.Rect;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Read-only, bounded inspection of the host's native View hierarchy. */
final class ViewInspector {
    private static final int MAX_NODES = 2000;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_TEXT_LENGTH = 256;

    private ViewInspector() { }

    static boolean matches(View view, String query) {
        if (view == null) return false;
        if (query == null || query.trim().isEmpty()) return true;
        String searchable = (view.getClass().getName() + " " + resourceName(view) + " "
                + textContent(view) + " " + descriptionContent(view) + " "
                + visibility(view)).toLowerCase(Locale.ROOT);
        for (String token : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!searchable.contains(token)) return false;
        }
        return true;
    }

    static String contentSummary(View view) {
        if (view == null || isPassword(view)) return "";
        String value = textContent(view);
        if (value.trim().isEmpty()) value = descriptionContent(view);
        value = value.replaceAll("\\s+", " ").trim();
        if (value.length() <= 80) return value;
        int end = Character.isHighSurrogate(value.charAt(78)) ? 78 : 79;
        return value.substring(0, end) + "…";
    }

    static String describe(View view) {
        if (view == null) return "未选中视图";
        StringBuilder result = new StringBuilder("类名: ").append(view.getClass().getName())
                .append("\n资源 ID: ").append(resourceName(view));
        try {
            if (view.getId() != View.NO_ID) {
                result.append(" (").append(String.format(Locale.ROOT, "0x%08x", view.getId()))
                        .append(')');
            }
            if (isPassword(view)) result.append("\n文本: [密码内容已隐藏]");
            else {
                String text = textContent(view);
                String description = descriptionContent(view);
                if (!text.isEmpty()) result.append("\n文本: ").append(text);
                if (!description.isEmpty()) result.append("\n内容描述: ").append(description);
            }
            Class<?> parentClass = view.getClass().getSuperclass();
            result.append("\n父类: ").append(parentClass == null ? "无" : parentClass.getName());
            ViewParent parent = view.getParent();
            result.append("\n父视图: ").append(parent == null ? "无" : parent.getClass().getName());
            if (parent instanceof View) result.append(" · ").append(resourceName((View) parent));
            DisplayMetrics metrics = view.getResources().getDisplayMetrics();
            if (view instanceof TextView) {
                TextView textView = (TextView) view;
                float scaledDensity = metrics.scaledDensity > 0 ? metrics.scaledDensity : 1;
                float sizeSp = Build.VERSION.SDK_INT >= 34
                        ? TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, textView.getTextSize(), metrics)
                        : textView.getTextSize() / scaledDensity;
                result.append("\n字号: ").append(decimal(sizeSp)).append(" sp")
                        .append("\n文字颜色: ")
                        .append(String.format(Locale.ROOT, "#%08X", textView.getCurrentTextColor()));
            }
            float density = metrics.density;
            if (density <= 0) density = 1;
            result.append("\n尺寸: ").append(view.getWidth()).append(" × ")
                    .append(view.getHeight()).append(" px / ")
                    .append(decimal(view.getWidth() / density)).append(" × ")
                    .append(decimal(view.getHeight() / density)).append(" dp");
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            result.append("\n屏幕位置: (").append(location[0]).append(", ")
                    .append(location[1]).append(") px")
                    .append("\n可见性: ").append(visibility(view))
                    .append(" · isShown=").append(view.isShown())
                    .append("\nalpha=").append(decimal(view.getAlpha()))
                    .append(" · Z=").append(decimal(view.getZ())).append(" px")
                    .append("\nclickable=").append(view.isClickable())
                    .append(" · longClickable=").append(view.isLongClickable())
                    .append("\nenabled=").append(view.isEnabled())
                    .append(" · focusable=").append(view.isFocusable())
                    .append("\npadding (左/上/右/下): ")
                    .append(view.getPaddingLeft()).append('/').append(view.getPaddingTop())
                    .append('/').append(view.getPaddingRight()).append('/')
                    .append(view.getPaddingBottom()).append(" px");
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                result.append("\nLayoutParams: ").append(params.getClass().getName())
                        .append("\n布局尺寸: ").append(layoutSize(params.width)).append(" × ")
                        .append(layoutSize(params.height));
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    result.append("\nmargin (左/上/右/下): ").append(margins.leftMargin)
                            .append('/').append(margins.topMargin).append('/')
                            .append(margins.rightMargin).append('/').append(margins.bottomMargin)
                            .append(" px");
                }
            }
        } catch (RuntimeException ignored) {
            result.append("\n部分属性无法读取");
        }
        return result.toString();
    }

    static List<View> hitCandidates(View root, int screenX, int screenY) {
        if (root == null) return Collections.emptyList();
        ArrayList<View> result = new ArrayList<>();
        collectHits(root, root, screenX, screenY, 0, new int[]{0}, result);
        return result;
    }

    private static void collectHits(View view, View root, int x, int y, int depth,
                                    int[] visited, List<View> result) {
        if (view == null || depth > MAX_DEPTH || visited[0] >= MAX_NODES || isFinder(view)) return;
        visited[0]++;
        if (!view.isShown() || view.getAlpha() <= 0.01f) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // Copy at most the remaining budget, beginning with the last drawn siblings.
            int remaining = MAX_NODES - visited[0];
            int first = Math.max(0, group.getChildCount() - remaining);
            ArrayList<DrawChild> children = new ArrayList<>(Math.min(group.getChildCount(), remaining));
            for (int index = group.getChildCount() - 1; index >= first; index--) {
                View child = group.getChildAt(index);
                if (child != null && !isFinder(child)) children.add(new DrawChild(child, index));
            }
            children.sort((left, right) -> {
                int z = Float.compare(right.view.getZ(), left.view.getZ());
                return z != 0 ? z : Integer.compare(right.index, left.index);
            });
            for (DrawChild child : children) {
                collectHits(child.view, root, x, y, depth + 1, visited, result);
                if (visited[0] >= MAX_NODES) break;
            }
        }
        if (view != root && screenBounds(view).contains(x, y)) result.add(view);
    }

    static Rect screenBounds(View view) {
        Rect visible = new Rect();
        if (view == null) return visible;
        try {
            int depth = 0;
            for (View current = view; current != null; ) {
                if (++depth > MAX_DEPTH + 1 || current.getAlpha() <= 0.01f || isFinder(current)) {
                    return visible;
                }
                current = current.getParent() instanceof View ? (View) current.getParent() : null;
            }
            if (!view.isShown() || !view.getGlobalVisibleRect(visible)) return new Rect();
            int[] screen = new int[2];
            int[] window = new int[2];
            View root = view.getRootView();
            root.getLocationOnScreen(screen);
            root.getLocationInWindow(window);
            visible.offset(screen[0] - window[0], screen[1] - window[1]);
            return visible;
        } catch (RuntimeException ignored) {
            return new Rect();
        }
    }

    private static boolean isFinder(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).startsWith("douai.view_finder");
    }

    private static String resourceName(View view) {
        try {
            return view.getId() == View.NO_ID ? "无资源 ID" : view.getResources().getResourceName(view.getId());
        } catch (RuntimeException ignored) {
            return "无资源 ID";
        }
    }

    private static String visibility(View view) {
        if (view.getVisibility() == View.GONE) return "GONE";
        return view.getVisibility() == View.INVISIBLE ? "INVISIBLE" : "VISIBLE";
    }

    private static String textContent(View view) {
        if (!(view instanceof TextView) || isPassword(view)) return "";
        try { return safeText(((TextView) view).getText()); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static String descriptionContent(View view) {
        if (isPassword(view)) return "";
        try { return safeText(view.getContentDescription()); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static boolean isPassword(View view) {
        if (!(view instanceof TextView)) return false;
        try {
            TextView text = (TextView) view;
            if (text.getTransformationMethod() instanceof PasswordTransformationMethod) return true;
            int input = text.getInputType();
            int variation = input & InputType.TYPE_MASK_VARIATION;
            int kind = input & InputType.TYPE_MASK_CLASS;
            return (kind == InputType.TYPE_CLASS_TEXT
                    && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                    || (kind == InputType.TYPE_CLASS_NUMBER
                    && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static String safeText(CharSequence value) {
        if (value == null) return "";
        try {
            int length = value.length();
            StringBuilder result = new StringBuilder(Math.min(length, MAX_TEXT_LENGTH));
            for (int index = 0; index < Math.min(length, MAX_TEXT_LENGTH); index++) {
                result.append(value.charAt(index));
            }
            if (length > MAX_TEXT_LENGTH) result.append('…');
            return result.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String decimal(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String layoutSize(int value) {
        if (value == ViewGroup.LayoutParams.MATCH_PARENT) return "MATCH_PARENT";
        if (value == ViewGroup.LayoutParams.WRAP_CONTENT) return "WRAP_CONTENT";
        return value + " px";
    }

    private static final class DrawChild {
        final View view;
        final int index;

        DrawChild(View view, int index) {
            this.view = view;
            this.index = index;
        }
    }
}

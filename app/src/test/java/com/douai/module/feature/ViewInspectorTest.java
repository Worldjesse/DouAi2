package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Rect;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ViewInspectorTest {
    @Test public void queryMatchesAllTokensAcrossClassIdTextAndDescription() {
        Activity activity = activity();
        TextView view = new TextView(activity);
        view.setId(android.R.id.text1);
        view.setText("Favorites Count");
        view.setContentDescription("Bookmark action");
        assertTrue(ViewInspector.matches(view, "TEXTVIEW text1 favorites bookmark"));
        assertTrue(ViewInspector.matches(view, "  count\nVISIBLE "));
        assertFalse(ViewInspector.matches(view, "favorites comment"));
        view.setVisibility(View.GONE);
        assertTrue(ViewInspector.matches(view, "gone"));
        assertTrue(ViewInspector.matches(view, ""));
        activity.finish();
    }

    @Test public void propertiesContainDimensionsCoordinatesAndLayoutWithoutUnboundedText() {
        Activity activity = activity();
        FrameLayout root = new FrameLayout(activity);
        TextView view = new TextView(activity);
        view.setId(android.R.id.text1);
        view.setText("a".repeat(500));
        view.setContentDescription("description");
        view.setPadding(1, 2, 3, 4);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(100, 80);
        params.setMargins(5, 6, 7, 8);
        root.addView(view, params);
        layout(activity, root);
        String description = ViewInspector.describe(view);
        assertTrue(description.contains("android:id/text1"));
        assertTrue(description.contains("100 × 80 px"));
        assertTrue(description.contains("屏幕位置:"));
        assertTrue(description.contains("1/2/3/4 px"));
        assertTrue(description.contains("5/6/7/8 px"));
        assertTrue(description.contains("a".repeat(256) + "…"));
        assertFalse(description.contains("a".repeat(257)));
        activity.finish();
    }

    @Test public void passwordTextAndDescriptionAreNeverInspected() {
        Activity activity = activity();
        EditText password = new EditText(activity);
        password.setText("secret-password");
        password.setContentDescription("secret-password");
        int[] modes = {
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        };
        for (int mode : modes) {
            password.setInputType(mode);
            assertFalse(ViewInspector.matches(password, "secret-password"));
            assertFalse(ViewInspector.describe(password).contains("secret-password"));
            assertTrue(ViewInspector.describe(password).contains("密码内容已隐藏"));
        }
        password.setInputType(InputType.TYPE_CLASS_TEXT);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        assertFalse(ViewInspector.matches(password, "secret-password"));
        activity.finish();
    }

    @Test public void frontSiblingIsChosenBeforeDeeperViewsBehindItAndParentsRemainCandidates() {
        Activity activity = activity();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout behind = new FrameLayout(activity);
        TextView deepBehind = new TextView(activity);
        behind.addView(deepBehind, new FrameLayout.LayoutParams(100, 100));
        root.addView(behind, new FrameLayout.LayoutParams(200, 200));
        TextView front = new TextView(activity);
        root.addView(front, new FrameLayout.LayoutParams(200, 200));
        layout(activity, root);
        Rect commonBounds = ViewInspector.screenBounds(deepBehind);
        List<View> candidates = ViewInspector.hitCandidates(root, commonBounds.centerX(), commonBounds.centerY());
        assertEquals(front, candidates.get(0));
        assertTrue(candidates.indexOf(deepBehind) < candidates.indexOf(behind));
        assertFalse(candidates.contains(root));
        behind.setElevation(10);
        candidates = ViewInspector.hitCandidates(root, commonBounds.centerX(), commonBounds.centerY());
        assertEquals(deepBehind, candidates.get(0));
        assertEquals(behind, candidates.get(1));
        activity.finish();
    }

    @Test public void summaryPrefersTextFallsBackToDescriptionAndKeepsPasswordsEmpty() {
        Activity activity = activity();
        TextView text = new TextView(activity);
        text.setText("  收藏\n  123  ");
        text.setContentDescription("收藏按钮");
        assertEquals("收藏 123", ViewInspector.contentSummary(text));
        text.setText(" ");
        assertEquals("收藏按钮", ViewInspector.contentSummary(text));
        text.setText("a".repeat(100));
        String summary = ViewInspector.contentSummary(text);
        assertEquals(80, summary.length());
        assertTrue(summary.endsWith("…"));
        EditText password = new EditText(activity);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("secret-password");
        password.setContentDescription("secret-password");
        assertEquals("", ViewInspector.contentSummary(password));
        activity.finish();
    }

    @Test public void descriptionIdentifiesTextStylingAndItsParentLayout() {
        Activity activity = activity();
        FrameLayout parent = new FrameLayout(activity);
        parent.setId(android.R.id.content);
        TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setTextColor(0xFF123456);
        parent.addView(text, new FrameLayout.LayoutParams(100, 80));
        String description = ViewInspector.describe(text);
        assertTrue(description.contains("字号: 18.00 sp"));
        assertTrue(description.contains("文字颜色: #FF123456"));
        assertTrue(description.contains("父类: android.view.View"));
        assertTrue(description.contains("父视图: android.widget.FrameLayout · android:id/content"));
        assertTrue(description.contains("LayoutParams: android.widget.FrameLayout$LayoutParams"));
        activity.finish();
    }

    @Test public void finderAndTransparentAncestorsAreExcluded() {
        Activity activity = activity();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target, new FrameLayout.LayoutParams(200, 200));
        FrameLayout transparent = new FrameLayout(activity);
        TextView hidden = new TextView(activity);
        transparent.addView(hidden, new FrameLayout.LayoutParams(200, 200));
        transparent.setAlpha(0);
        root.addView(transparent, new FrameLayout.LayoutParams(200, 200));
        FrameLayout finder = new FrameLayout(activity);
        finder.setTag("douai.view_finder.helper-test");
        TextView finderChild = new TextView(activity);
        finder.addView(finderChild, new FrameLayout.LayoutParams(200, 200));
        root.addView(finder, new FrameLayout.LayoutParams(200, 200));
        layout(activity, root);
        List<View> candidates = hits(root, target);
        assertEquals(1, candidates.size());
        assertEquals(target, candidates.get(0));
        assertTrue(ViewInspector.screenBounds(hidden).isEmpty());
        assertTrue(ViewInspector.screenBounds(finderChild).isEmpty());
        activity.finish();
    }

    @Test public void clippingLimitsHitBoundsToVisiblePartOfView() {
        Activity activity = activity();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout clipping = new FrameLayout(activity);
        TextView oversized = new TextView(activity);
        clipping.addView(oversized, new FrameLayout.LayoutParams(200, 200));
        root.addView(clipping, new FrameLayout.LayoutParams(80, 80));
        layout(activity, root);
        Rect bounds = ViewInspector.screenBounds(oversized);
        assertEquals(80, bounds.width());
        assertEquals(80, bounds.height());
        assertFalse(ViewInspector.hitCandidates(root, bounds.right + 1, bounds.top + 1).contains(oversized));
        activity.finish();
    }

    @Test public void customTextGetterFailureDoesNotBreakInspection() {
        Activity activity = activity();
        class FailingTextView extends TextView {
            boolean fail;
            FailingTextView() { super(activity); }
            @Override public CharSequence getText() {
                if (fail) throw new IllegalStateException("host text unavailable");
                return super.getText();
            }
        }
        FailingTextView view = new FailingTextView();
        view.setContentDescription("available-description");
        view.fail = true;
        assertTrue(ViewInspector.matches(view, "available-description"));
        assertEquals("available-description", ViewInspector.contentSummary(view));
        assertTrue(ViewInspector.describe(view).contains("available-description"));
        activity.finish();
    }

    @Test public void traversalKeepsFrontmostCandidatesWithinNodeBudget() {
        Activity activity = activity();
        FrameLayout root = new FrameLayout(activity);
        View first = null;
        View last = null;
        for (int index = 0; index < 2010; index++) {
            View child = new View(activity);
            root.addView(child, new FrameLayout.LayoutParams(100, 100));
            if (first == null) first = child;
            last = child;
        }
        layout(activity, root);
        List<View> candidates = hits(root, last);
        assertEquals(last, candidates.get(0));
        assertTrue(candidates.size() < 2000);
        assertFalse(candidates.contains(first));
        activity.finish();
    }

    private static Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private static void layout(Activity activity, FrameLayout root) {
        activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 640);
    }

    private static List<View> hits(View root, View target) {
        Rect bounds = ViewInspector.screenBounds(target);
        assertTrue(!bounds.isEmpty());
        return ViewInspector.hitCandidates(root, bounds.centerX(), bounds.centerY());
    }
}

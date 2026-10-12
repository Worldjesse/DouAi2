package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.douai.module.config.CustomViewLocator;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ViewFinderOverlayTest {
    @Test
    public void overlayUsesScreenCoordinatesAndConfirmsTheSelectedFeedView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        TextView target = new TextView(activity);
        target.setText("target");
        content.addView(target, new FrameLayout.LayoutParams(180, 120));
        TextView sibling = new TextView(activity);
        sibling.setText("next");
        FrameLayout.LayoutParams siblingParams = new FrameLayout.LayoutParams(140, 90);
        siblingParams.topMargin = 180;
        content.addView(sibling, siblingParams);
        activity.setContentView(content);
        content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 320, 640);
        View decor = activity.getWindow().getDecorView();
        CustomViewLocator existingComponent = CustomViewLocator.capture(decor, content, target);
        ViewFinderOverlay picker = ViewFinderOverlay.show(activity, decor, content,
                new ViewFinderOverlay.Listener() {
                    @Override public boolean onConfirm(CustomViewLocator locator) {
                        assertEquals(CustomViewLocator.Scope.FEED, locator.scope);
                        assertEquals(target.getClass().getName(), locator.viewClassName);
                        return true;
                    }
                    @Override public java.util.List<CustomViewLocator> existingCustomViews() {
                        return java.util.Collections.singletonList(existingComponent);
                    }
                });
        assertNotNull(picker);
        assertTrue(picker.getParent() == decor);
        assertEquals(View.GONE, picker.findViewWithTag("view_finder.selection_panel").getVisibility());
        String diagnostic = picker.diagnosticSummary();
        assertTrue(diagnostic.contains("views="));
        assertTrue(diagnostic.contains("visibleBounds="));
        assertTrue(diagnostic.contains("directChildren="));

        picker.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        picker.layout(0, 0, 320, 640);
        ImageButton drag = (ImageButton) picker.findViewWithTag("view_finder.drag");
        View toolbar = (View) drag.getParent().getParent();
        MotionEvent down = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 5, 5, 0);
        MotionEvent move = MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 35, 45, 0);
        MotionEvent up = MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, 35, 45, 0);
        try {
            drag.dispatchTouchEvent(down);
            drag.dispatchTouchEvent(move);
            drag.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            move.recycle();
            up.recycle();
        }
        assertTrue("The floating toolbar should move from its drag handle",
                toolbar.getTranslationY() > 0f);

        Rect bounds = ViewFinderOverlay.screenBounds(target);
        assertTrue(bounds.width() > 0);
        float x = bounds.exactCenterX();
        float y = bounds.exactCenterY();
        MotionEvent tap = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, x, y, 0);
        picker.onTouchEvent(tap);
        tap.recycle();

        View selectionPanel = picker.findViewWithTag("view_finder.selection_panel");
        assertEquals(View.VISIBLE, selectionPanel.getVisibility());
        assertEquals(View.VISIBLE, picker.findViewWithTag("view_finder.selection_callout").getVisibility());
        picker.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        picker.layout(0, 0, 320, 640);
        View selectionDrag = picker.findViewWithTag("view_finder.selection_drag");
        assertNotNull(selectionDrag);
        MotionEvent panelDown = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 8, 8, 0);
        MotionEvent panelMove = MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 8, 48, 0);
        MotionEvent panelUp = MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, 8, 48, 0);
        try {
            selectionDrag.dispatchTouchEvent(panelDown);
            selectionDrag.dispatchTouchEvent(panelMove);
            selectionDrag.dispatchTouchEvent(panelUp);
        } finally {
            panelDown.recycle();
            panelMove.recycle();
            panelUp.recycle();
        }
        assertTrue("The selection panel should move from its drag handle",
                selectionPanel.getTranslationY() > 0f);
        TextView initialDetails = (TextView) picker.findViewWithTag("view_finder.selection_details");
        String initialSelection = initialDetails.getText().toString();
        View next = picker.findViewWithTag("view_finder.下一个");
        assertNotNull(next);
        next.performClick();
        assertTrue("Next must update the selected node details", !initialSelection.equals(initialDetails.getText().toString()));

        View rules = picker.findViewWithTag("view_finder.confirm");
        assertNotNull(rules);
        rules.performClick();
        android.app.AlertDialog editor = (android.app.AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertEquals(1, countText((ViewGroup) editor.getWindow().getDecorView(), "自定义组件"));
        assertTrue(countText((ViewGroup) editor.getWindow().getDecorView(),
                existingComponent.label + "\n" + existingComponent.viewClassName) > 0);
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(picker.isClosed());
        assertEquals(0, countFinderOverlays((ViewGroup) decor));
        activity.finish();
    }

    private static int countFinderOverlays(ViewGroup parent) {
        int count = 0;
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof ViewFinderOverlay) count++;
            if (child instanceof ViewGroup) count += countFinderOverlays((ViewGroup) child);
        }
        return count;
    }

    private static int countText(View root, String value) {
        int count = root instanceof TextView && value.contentEquals(((TextView) root).getText()) ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                count += countText(group.getChildAt(index), value);
            }
        }
        return count;
    }
}

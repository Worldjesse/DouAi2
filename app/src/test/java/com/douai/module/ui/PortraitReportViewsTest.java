package com.douai.module.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PortraitReportViewsTest {
    @Test public void repeatedFramesKeepGeometryAndRenderTheSamePixels() throws Exception {
        View chart = rhythm(new ArrayList<>());
        Bitmap first = render(chart, 600, 220);
        Object originalHits = hits(chart).get(0);
        String originalDescription = chart.getContentDescription().toString();
        Bitmap latest = null;
        for (int frame = 0; frame < 100; frame++) {
            if (latest != null) latest.recycle();
            latest = render(chart, 600, 220);
            assertSame(originalHits, hits(chart).get(0));
        }
        assertTrue(first.sameAs(latest));
        assertEquals(originalDescription, chart.getContentDescription().toString());
        assertEquals(168, hits(chart).size());
        first.recycle(); latest.recycle();
    }

    @Test public void selectionChangesOverlayWithoutRebuildingGeometry() throws Exception {
        List<String> selected = new ArrayList<>();
        View chart = rhythm(selected);
        Bitmap before = render(chart, 600, 220);
        Object originalHits = hits(chart).get(0);
        assertTrue(chart.performClick());
        Bitmap after = render(chart, 600, 220);
        assertEquals(1, selected.size());
        assertTrue(selected.get(0).contains("周一"));
        assertSame(originalHits, hits(chart).get(0));
        assertFalse(before.sameAs(after));
        before.recycle(); after.recycle();
    }

    @Test public void resizeAndThemeChangeRebuildGeometryAndKeepAccessibleSelection() throws Exception {
        List<String> selected = new ArrayList<>();
        View chart = rhythm(selected);
        Bitmap initial = render(chart, 600, 220);
        Object original = hits(chart).get(0);
        Bitmap resized = render(chart, 900, 220);
        assertNotSame(original, hits(chart).get(0));
        Object resizedHit = hits(chart).get(0);
        RuntimeEnvironment.setQualifiers("night");
        Bitmap dark = render(chart, 900, 220);
        assertNotSame(resizedHit, hits(chart).get(0));
        assertFalse(resized.sameAs(dark));
        assertTrue(chart.performClick());
        assertTrue(selected.get(0).contains("周一"));
        initial.recycle(); resized.recycle(); dark.recycle();
    }

    private static View rhythm(List<String> selected) throws Exception {
        Class<?> type = Class.forName("com.douai.module.ui.PortraitReportViews$RhythmChart");
        Constructor<?> constructor = type.getDeclaredConstructor(Context.class, long[][].class, Consumer.class);
        constructor.setAccessible(true);
        long[][] values = new long[7][24];
        values[0][0] = 10;
        return (View) constructor.newInstance(RuntimeEnvironment.getApplication(), values, (Consumer<String>) selected::add);
    }

    private static List<?> hits(View chart) throws Exception {
        Field field = chart.getClass().getSuperclass().getDeclaredField("hits");
        field.setAccessible(true);
        return (List<?>) field.get(chart);
    }

    private static Bitmap render(View chart, int width, int height) {
        chart.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        chart.draw(new Canvas(bitmap));
        return bitmap;
    }
}

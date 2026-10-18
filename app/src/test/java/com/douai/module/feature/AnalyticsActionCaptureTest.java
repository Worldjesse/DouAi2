package com.douai.module.feature;

import com.douai.module.analytics.AnalyticsSnapshot;
import com.douai.module.analytics.LocalAnalytics;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AnalyticsActionCaptureTest {
    @Test public void delayedSaveCannotBorrowLaterVisitOrSurviveClear() {
        LocalAnalytics store = LocalAnalytics.get(RuntimeEnvironment.getApplication());
        try {
            store.clear(); store.setEnabled(true);
            String first = store.beginVisit(store.generation(), "one", "image", "author", "作者", List.of("话题"), false, -1, "unknown");
            AnalyticsActionCapture captured = AnalyticsActionCapture.capture(RuntimeEnvironment.getApplication(), Map.of("aid", "one"));
            store.finishVisit(first, 0, "covered_window");
            store.beginVisit(store.generation(), "two", "short_video", "author", "作者", List.of("话题"), false, 1000, "unknown");
            captured.manual("download_queued", "下载任务已加入", "accepted");
            AnalyticsSnapshot result = store.snapshot(0);
            assertEquals(1, result.totalFeatureUses);
            assertTrue("late acceptance is not evidence about the new card", result.portrait.typeActions.isEmpty());
            store.clear();
            captured.manual("download_queued", "下载任务已加入", "accepted");
            assertEquals("clear invalidates already captured actions", 0, store.snapshot(0).totalFeatureUses);
        } finally { store.setEnabled(false); store.clear(); store.close(); }
    }

    @Test public void explicitCurrentWorkLinksOnlyOnceWithoutFallbackFeatureIncrement() {
        LocalAnalytics store = LocalAnalytics.get(RuntimeEnvironment.getApplication());
        try {
            store.clear(); store.setEnabled(true);
            store.beginVisit(store.generation(), "one", "image", "author", "作者", List.of("话题"), false, -1, "unknown");
            AnalyticsActionCapture captured = AnalyticsActionCapture.capture(RuntimeEnvironment.getApplication(), Map.of("aid", "one"));
            captured.manual("copy_text", "复制作品文案", "success");
            AnalyticsSnapshot result = store.snapshot(0);
            assertEquals(1, result.totalFeatureUses);
            assertEquals(1, result.portrait.typeActions.size());
            assertEquals(1, result.portrait.typeActions.get(0).count);
        } finally { store.setEnabled(false); store.clear(); store.close(); }
    }
}

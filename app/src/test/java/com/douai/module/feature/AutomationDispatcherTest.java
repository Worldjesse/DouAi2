package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutomationDispatcherTest {
    private Activity activity;
    private AutomationDispatcher dispatcher;
    private AutomationHistoryStore history;
    private ManualExecutor worker;
    private AtomicInteger sends;

    @Before public void setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .edit().clear().commit();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        worker = new ManualExecutor();
        dispatcher = new AutomationDispatcher(activity, ignored -> { }, worker);
        dispatcher.onActivityResumed(activity);
        history = new AutomationHistoryStore(activity);
        sends = new AtomicInteger();
    }

    @After public void close() {
        dispatcher.close();
        worker.runAll();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void noNoticeDispatchesImmediatelyAndRecordsOneConfirmedOutcome() {
        dispatcher.setOptions(options(false, true));
        dispatcher.prepare("reply", "one", "u1", "用户甲", "收到消息", "生成回复", "model",
                () -> true, result -> {
                    sends.incrementAndGet();
                    result.accept("sent");
                    result.accept("failed");
                });
        worker.runAll();
        assertEquals(1, sends.get());
        assertNull(notice("one"));
        assertEquals(1, history.read("reply").size());
        assertEquals("sent", history.read("reply").get(0).status);
        assertEquals("收到消息", history.read("reply").get(0).input);
        assertEquals("生成回复", history.read("reply").get(0).output);
        prepare("reply", "one", () -> true);
        worker.runAll();
        assertEquals(1, sends.get());
    }

    @Test public void noticeTimesOutAndStillSubmitsWithoutAUserClick() {
        dispatcher.setOptions(options(true, true));
        prepare("comment", "comment1", () -> true);
        worker.runAll();
        assertNotNull(notice("comment1"));
        assertEquals(0, sends.get());
        assertEquals("pending", history.read("comment").get(0).status);
        advance(7);
        worker.runAll();
        assertEquals(0, sends.get());
        advance(1);
        worker.runAll();
        assertEquals(1, sends.get());
        assertNull(notice("comment1"));
        assertEquals("submitted", history.read("comment").get(0).status);
    }

    @Test public void longPressCancelsAndNeverSendsWhenTheOldTimeoutFires() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "cancel", () -> true);
        assertTrue(notice("cancel").performLongClick());
        advance(20);
        worker.runAll();
        assertEquals(0, sends.get());
        assertNull(notice("cancel"));
        assertEquals("cancelled", history.read("reply").get(0).status);
    }

    @Test public void openingDetailsPausesTimeoutAndExplicitSendRunsOnce() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "expand", () -> true);
        notice("expand").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("原始内容", ((TextView) dialog.findViewById(android.R.id.content)
                .findViewWithTag("automation.details.input")).getText().toString());
        assertEquals("生成内容", ((TextView) dialog.findViewById(android.R.id.content)
                .findViewWithTag("automation.details.output")).getText().toString());
        advance(90);
        worker.runAll();
        assertEquals(0, sends.get());
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        worker.runAll();
        assertEquals(1, sends.get());
        assertEquals("sent", history.read("reply").get(0).status);
    }

    @Test public void dismissingDetailsCancelsInsteadOfSending() {
        dispatcher.setOptions(options(true, true));
        prepare("comment", "dismiss", () -> true);
        notice("dismiss").performClick();
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        ShadowLooper.idleMainLooper();
        advance(20);
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("cancelled", history.read("comment").get(0).status);
    }

    @Test public void automationDisabledBeforeQueuedSendCancelsWithoutCallingNativeAction() {
        dispatcher.setOptions(options(false, true));
        prepare("reply", "disabled", () -> true);
        Map<String, Object> disabled = base(false, true);
        disabled.put("automation_enabled", false);
        dispatcher.setOptions(new LocalOptions(disabled));
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("cancelled", history.read("reply").get(0).status);
    }

    @Test public void invalidTargetAtTimeoutCancelsAndDoesNotSend() {
        dispatcher.setOptions(options(true, true));
        AtomicBoolean current = new AtomicBoolean(true);
        prepare("comment", "moved", current::get);
        current.set(false);
        advance(8);
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("cancelled", history.read("comment").get(0).status);
    }

    @Test public void disablingHistoryStopsQueuedWritesAndKeepsOldRecordsAvailable() {
        dispatcher.setOptions(options(false, true));
        prepare("reply", "old", () -> true);
        worker.runAll();
        assertEquals(1, history.read("reply").size());
        prepare("reply", "new", () -> true);
        dispatcher.setOptions(options(false, false));
        worker.runAll();
        assertEquals(2, sends.get());
        assertEquals(1, history.read("reply").size());
        assertEquals("old", history.read("reply").get(0).id);
    }

    @Test public void historyIsDisabledByDefaultAndSendFailureIsNotRecordedAsSuccess() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("automation_enabled", true);
        defaults.put("native_auto_reply", true);
        dispatcher.setOptions(new LocalOptions(defaults));
        prepare("reply", "private", () -> true);
        worker.runAll();
        assertEquals(1, sends.get());
        assertTrue(history.read("reply").isEmpty());
        dispatcher.setOptions(options(false, true));
        dispatcher.prepare("reply", "failure", "u", "用户", "内容", "生成内容", "model",
                () -> true, callback -> { throw new IllegalStateException("native failure"); });
        worker.runAll();
        assertEquals("failed", history.read("reply").get(0).status);
    }

    @Test public void cancelKindOnlyCancelsTheSelectedFeature() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "reply", () -> true);
        prepare("comment", "comment", () -> true);
        dispatcher.cancelKind("reply");
        advance(8);
        worker.runAll();
        assertEquals(1, sends.get());
        assertEquals("cancelled", history.read("reply").get(0).status);
        assertEquals("submitted", history.read("comment").get(0).status);
    }

    @Test public void pauseReleasesActivityViewsAndResumeShowsTheRemainingNotice() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "resume", () -> true);
        dispatcher.onActivityPaused(activity);
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.notices"));
        advance(3);
        dispatcher.onActivityResumed(activity);
        assertNotNull(notice("resume"));
        advance(5);
        worker.runAll();
        assertEquals(1, sends.get());
    }

    @Test public void closeCancelsPendingNoticeAndGenerationFailureCanBeReviewed() {
        dispatcher.setOptions(options(true, true));
        dispatcher.reportFailure("reply", "generation", "u", "用户", "输入", "model");
        prepare("comment", "close", () -> true);
        dispatcher.close();
        advance(8);
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("failed", history.read("reply").get(0).status);
        assertEquals("cancelled", history.read("comment").get(0).status);
    }

    private void prepare(String kind, String id, java.util.function.BooleanSupplier valid) {
        dispatcher.prepare(kind, id, "target", "目标", "原始内容", "生成内容", "model", valid,
                callback -> { sends.incrementAndGet(); callback.accept("reply".equals(kind) ? "sent" : "submitted"); });
    }

    @Test public void silentNativeCallbackBecomesSubmittedAndDoesNotClaimSuccess() {
        dispatcher.setOptions(options(false, true));
        dispatcher.prepare("reply", "unknown", "u", "用户", "消息", "回复", "model", () -> true, result -> sends.incrementAndGet());
        worker.runAll();
        assertEquals("sending", history.read("reply").get(0).status);
        advance(30); worker.runAll();
        assertEquals("submitted", history.read("reply").get(0).status);
        assertEquals(1, sends.get());
    }

    @Test public void generationFailureShowsTimedNoticeOnlyWhenItsSwitchIsEnabled() {
        dispatcher.setOptions(options(true, true));
        dispatcher.reportFailure("reply", "failure", "target", "用户", "消息", "model");
        worker.runAll();
        assertNotNull(activity.getWindow().getDecorView().findViewWithTag("automation.failure.failure"));
        assertEquals("failed", history.read("reply").get(0).status);
        advance(8); worker.runAll();
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.failure.failure"));
        assertEquals(0, sends.get());
    }

    @Test public void lateNativeFailureReplacesUnconfirmedOutcomeAndFurtherCallbacksAreIgnored() {
        dispatcher.setOptions(options(false, true));
        java.util.concurrent.atomic.AtomicReference<Consumer<String>> callback = new java.util.concurrent.atomic.AtomicReference<>();
        dispatcher.prepare("reply", "late", "u", "用户", "消息", "回复", "model", () -> true, callback::set);
        worker.runAll(); advance(30); worker.runAll();
        assertEquals("submitted", history.read("reply").get(0).status);
        callback.get().accept("failed"); worker.runAll();
        assertEquals("failed", history.read("reply").get(0).status);
        callback.get().accept("sent"); worker.runAll();
        assertEquals("failed", history.read("reply").get(0).status);
    }

    @Test @Config(qualifiers = "w296dp-h560dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void narrowNoticeHasBoundedTextAndCanRenderPreview() { layoutPreview(); }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void landscapeNoticeRemainsBoundedAndScrollable() { layoutPreview(); }

    private void layoutPreview() {
        dispatcher.setOptions(options(true, false));
        dispatcher.prepare("reply", "layout", "id", "较长的回复对象名称".repeat(5), "收到消息", "待发送的回复内容。".repeat(40),
                "model", () -> true, done -> { });
        View decor = activity.getWindow().getDecorView();
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
        View banner = notice("layout");
        assertNotNull(banner);
        assertTrue(banner.getWidth() > 0 && banner.getWidth() <= metrics.widthPixels);
        assertTrue(banner.getHeight() <= metrics.heightPixels / 3 + 20);
        assertTrue(((android.view.ViewGroup) banner.getParent()).getWidth() <= metrics.widthPixels);
        String output = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (output == null) return;
        java.io.File folder = new java.io.File(output);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(folder,
                "automation-" + metrics.widthPixels + "x" + metrics.heightPixels + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private View notice(String id) {
        View reply = activity.getWindow().getDecorView().findViewWithTag("automation.notice." + id);
        return reply != null ? reply : activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring");
    }

    private static LocalOptions options(boolean tip, boolean history) { return new LocalOptions(base(tip, history)); }

    private static Map<String, Object> base(boolean tip, boolean history) {
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true);
        values.put("native_auto_reply", true);
        values.put("native_auto_comment", true);
        values.put("reply_tip", tip);
        values.put("comment_tip", tip);
        values.put("reply_save_history", history);
        values.put("comment_save_history", history);
        return values;
    }

    private static void advance(int seconds) {
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(seconds));
        ShadowLooper.idleMainLooper();
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(tasks);
            tasks.clear();
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable action) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(action);
        }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}

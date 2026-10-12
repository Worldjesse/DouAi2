package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ListView;

import com.douai.module.config.AutomationHistoryStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutomationHistoryDialogTest {
    private Activity activity;
    private AutomationHistoryStore store;
    private ManualExecutor worker;
    private AutomationHistoryDialog page;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("douai_automation_history", Activity.MODE_PRIVATE).edit().clear().commit();
        store = new AutomationHistoryStore(activity);
        worker = new ManualExecutor();
        page = new AutomationHistoryDialog(activity, AutomationHistoryStore.REPLY, false, worker);
    }

    @After public void tearDown() {
        if (page != null) page.dialog().dismiss();
        worker.shutdownNow();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void openingReadsOffMainAndVirtualizesRowsWhilePreservingDetailsAndClear() throws Exception {
        for (int index = 0; index < 200; index++) {
            long createdAt = 1000 + index;
            store.upsert(new AutomationHistoryStore.Entry("history-" + index, "reply", "target-" + index,
                    "用户 " + index, "原消息 " + index, "生成回复 " + index, "模型", "sent",
                    createdAt, createdAt));
        }
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        ListView list = list();
        assertEquals(1, list.getAdapter().getCount());
        worker.runNextOnThread();
        assertEquals(1, list.getAdapter().getCount());
        ShadowLooper.idleMainLooper();

        assertEquals(200, list.getAdapter().getCount());
        list.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 360, 480);
        assertTrue(list.getChildCount() > 0);
        assertTrue("Only visible records should be materialized", list.getChildCount() < 200);
        View first = list.getAdapter().getView(0, null, list);
        assertEquals("automation.history.history-199", first.getTag());
        first.performClick();
        AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(containsText(detail.getWindow().getDecorView(), "原消息 199"));
        assertTrue(containsText(detail.getWindow().getDecorView(), "生成回复 199"));
        detail.dismiss();

        page.dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(1, list.getAdapter().getCount());
        assertEquals("暂无记录", ((android.widget.TextView) list.getAdapter().getView(0, null, list))
                .getText().toString());
        assertTrue(store.read(AutomationHistoryStore.REPLY).isEmpty());
    }

    @Test public void dismissedReadCannotPopulateTheClosedPage() throws Exception {
        store.upsert(new AutomationHistoryStore.Entry("late", "reply", "target", "用户", "消息", "回复",
                "模型", "sent", 1, 1));
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        ListView list = list();
        worker.runNextOnThread();
        page.dialog().dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals(1, list.getAdapter().getCount());
        assertEquals("正在读取…", ((android.widget.TextView) list.getAdapter().getView(0, null, list))
                .getText().toString());
    }

    private ListView list() {
        return page.dialog().getWindow().getDecorView().findViewWithTag("automation.history");
    }

    private static boolean containsText(View view, String needle) {
        if (view instanceof android.widget.TextView
                && ((android.widget.TextView) view).getText().toString().contains(needle)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), needle)) return true;
            }
        }
        return false;
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }

        void runNextOnThread() throws InterruptedException {
            Runnable task = tasks.poll();
            if (task == null) return;
            Thread thread = new Thread(task, "test-history-worker");
            thread.start();
            thread.join(5000);
            if (thread.isAlive()) throw new AssertionError("History work did not finish");
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}

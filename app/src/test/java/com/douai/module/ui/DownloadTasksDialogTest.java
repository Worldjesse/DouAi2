package com.douai.module.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.douai.module.feature.DownloadTasks.Snapshot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;

import androidx.activity.ComponentActivity;
import androidx.compose.ui.test.ActionsKt;
import androidx.compose.ui.test.AssertionsKt;
import androidx.compose.ui.test.FiltersKt;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DownloadTasksDialogTest {
    private Activity activity;
    private FakeTasks tasks;
    private DownloadTasksDialog page;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        tasks = new FakeTasks();
        page = new DownloadTasksDialog(activity, false, tasks);
    }

    @After public void tearDown() {
        page.close();
        page.dialog().dismiss();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void subscribesOnlyWhileVisibleAndDoesNotCancelOnClose() {
        assertEquals(0, tasks.listeners.size());
        show();
        assertEquals(1, tasks.listeners.size());
        assertTrue(containsText(row(0), "暂无下载任务"));
        Runnable late = tasks.listeners.get(0);
        page.dialog().dismiss();
        ShadowLooper.idleMainLooper();
        assertTrue(tasks.listeners.isEmpty());
        tasks.entries.add(snapshot("1", "running", 25, 100, true));
        late.run();
        ShadowLooper.idleMainLooper();
        assertTrue(containsText(row(0), "暂无下载任务"));
        assertTrue(tasks.cancelled.isEmpty());
        show();
        assertEquals(1, tasks.listeners.size());
        assertTrue(containsText(row(0), "下载中"));
    }

    @Test public void showsProgressAndCancelsOnlyTheSelectedCurrentTask() {
        tasks.entries.add(snapshot("one", "running", 25, 100, true));
        tasks.entries.add(snapshot("two", "queued", 0, -1, true));
        show();
        View first = row(0);
        assertEquals("download.task.one", first.getTag());
        assertEquals(25, find(first, ProgressBar.class).getProgress());
        Button cancel = first.findViewWithTag("download.cancel.one");
        assertTrue(cancel.performClick());
        assertEquals(Collections.singletonList("one"), tasks.cancelled);
        tasks.entries.set(0, snapshot("one", "completed", 100, 100, false));
        tasks.changed();
        ShadowLooper.idleMainLooper();
        View completed = row(0);
        assertTrue(containsText(completed, "已完成"));
        assertEquals(View.GONE, completed.findViewWithTag("download.cancel.one").getVisibility());
        cancel.performClick();
        assertEquals("Stale controls cannot cancel a completed task", 1, tasks.cancelled.size());
    }

    @Test public void unknownSizeAndTerminalStatesAreExplicitAndClosedControlsDoNothing() {
        tasks.entries.add(snapshot("one", "running", 32, -1, true));
        show();
        View first = row(0);
        assertTrue(find(first, ProgressBar.class).isIndeterminate());
        assertTrue(containsText(first, "总大小未知"));
        Button old = first.findViewWithTag("download.cancel.one");
        for (String[] state : new String[][]{{"cancelled", "已取消"}, {"timed_out", "已超时"},
                {"failed", "失败"}, {"cancelling", "正在取消"}}) {
            tasks.entries.set(0, snapshot("one", state[0], 32, -1, false));
            tasks.changed();
            ShadowLooper.idleMainLooper();
            assertTrue(containsText(row(0), state[1]));
        }
        tasks.entries.set(0, snapshot("one", "running", 32, -1, true));
        page.close(); // Also covers the parent settings page replacing the dialog's dismiss listener.
        old.performClick();
        assertTrue(tasks.cancelled.isEmpty());
        assertTrue(tasks.listeners.isEmpty());
    }

    @Test public void taskHistoryOnlyCreatesVisibleRows() {
        for (int index = 0; index < 50; index++) tasks.entries.add(snapshot("task-" + index, "completed", 100, 100, false));
        show();
        ListView list = list();
        list.measure(View.MeasureSpec.makeMeasureSpec(296, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 296, 240);
        assertEquals(50, list.getAdapter().getCount());
        assertTrue(list.getChildCount() > 0);
        assertTrue(list.getChildCount() < 50);
    }

    private void show() { page.dialog().show(); ShadowLooper.idleMainLooper(); }
    private ListView list() { return page.dialog().getWindow().getDecorView().findViewWithTag("download.tasks"); }
    private View row(int position) { return list().getAdapter().getView(position, null, list()); }
    private static Snapshot snapshot(String id, String status, long bytes, long total, boolean cancellable) {
        return new Snapshot(id, "文件-" + id + ".mp4", "保存视频", status, bytes, total, 1, 3, cancellable);
    }
    private static boolean containsText(View view, String needle) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(needle)) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            if (containsText(((ViewGroup) view).getChildAt(index), needle)) return true;
        }
        return false;
    }
    private static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            T result = find(((ViewGroup) view).getChildAt(index), type);
            if (result != null) return result;
        }
        return null;
    }
    private static final class FakeTasks implements DownloadTasksDialog.Tasks {
        final List<Snapshot> entries = new ArrayList<>();
        final List<Runnable> listeners = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        @Override public List<Snapshot> snapshots() { return new ArrayList<>(entries); }
        @Override public AutoCloseable listen(Runnable changed) {
            listeners.add(changed);
            return () -> listeners.remove(changed);
        }
        @Override public void cancel(String id) { cancelled.add(id); }
        void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    }

    @RunWith(RobolectricTestRunner.class)
    @Config(sdk = 28, qualifiers = "w296dp-h560dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public static final class MiuixPreviewTest {
        @Rule public final AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> compose =
                AndroidComposeTestRule_androidKt.createAndroidComposeRule(ComponentActivity.class, EmptyCoroutineContext.INSTANCE);
        private DownloadTasksDialog page;

        @After public void close() {
            compose.runOnUiThread(() -> {
                if (page != null) { page.close(); page.dialog().dismiss(); }
                return Unit.INSTANCE;
            });
        }

        @Test public void narrowTaskPageKeepsCancelVisible() throws Exception { verify(); }
        @Test @Config(sdk = 28, qualifiers = "w640dp-h360dp-land-mdpi")
        public void landscapeTaskPageKeepsCancelVisible() throws Exception { verify(); }

        private void verify() throws Exception {
            FakeTasks tasks = new FakeTasks();
            tasks.entries.add(snapshot("one", "running", 2500000, 10000000, true));
            tasks.entries.add(snapshot("two", "queued", 0, -1, true));
            compose.runOnUiThread(() -> {
                page = new DownloadTasksDialog(compose.getActivity(), true, tasks);
                page.dialog().show();
                return Unit.INSTANCE;
            });
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("download.cancel.one"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("download.cancel.one"), false));
            assertEquals(Collections.singletonList("one"), tasks.cancelled);
            compose.waitForIdle();
            compose.runOnUiThread(() -> {
                View decor = page.dialog().getWindow().getDecorView();
                android.view.WindowManager.LayoutParams bounds = page.dialog().getWindow().getAttributes();
                int width = bounds.width > 0 ? bounds.width : decor.getWidth();
                int height = bounds.height > 0 ? bounds.height : decor.getHeight();
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, width, height);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                try {
                    decor.draw(new Canvas(bitmap));
                    int[] pixels = new int[width * height];
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                    HashSet<Integer> colors = new HashSet<>();
                    for (int pixel : pixels) colors.add(pixel);
                    assertTrue("Download tasks must render content", colors.size() > 8);
                    String location = System.getenv("DOUAI_CHAT_SCREENSHOTS");
                    if (location != null) {
                        File directory = new File(location);
                        assertTrue(directory.isDirectory() || directory.mkdirs());
                        try (FileOutputStream stream = new FileOutputStream(new File(directory,
                                "download-tasks-" + width + "x" + height + ".png"))) {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                } finally { bitmap.recycle(); }
                page.dialog().dismiss();
                return Unit.INSTANCE;
            });
            compose.waitForIdle();
            assertTrue(tasks.listeners.isEmpty());
            assertEquals("Dismissing the page must not cancel another task", 1, tasks.cancelled.size());
        }
    }
}

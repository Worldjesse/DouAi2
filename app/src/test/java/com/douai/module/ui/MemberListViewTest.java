package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.view.View;
import com.douai.module.R;
import com.douai.module.BuildConfig;
import com.douai.module.feature.ProfileListEntry;
import com.douai.module.feature.AvatarImageLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MemberListViewTest {
    @Test public void hostPickerLoadsPlaceholderFromTheInstalledModulePackage() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertEquals(BuildConfig.APPLICATION_ID,
                activity.getResources().getResourcePackageName(R.drawable.ic_member_placeholder));
        Resources hostResources = new Resources(activity.getAssets(), activity.getResources().getDisplayMetrics(),
                activity.getResources().getConfiguration()) {
            @Override public String getResourcePackageName(int id) {
                return "com.ss.android.ugc.aweme";
            }
        };
        String[] requestedPackage = {null};
        Context hostContext = new ContextWrapper(activity) {
            @Override public Resources getResources() { return hostResources; }
            @Override public Context createPackageContext(String packageName, int flags) {
                requestedPackage[0] = packageName;
                assertEquals(Context.CONTEXT_IGNORE_SECURITY, flags);
                return activity;
            }
        };
        MemberListView members = new MemberListView(hostContext, false, Collections.emptyList(),
                Collections.emptySet(), false);
        try {
            assertEquals(BuildConfig.APPLICATION_ID, requestedPackage[0]);
        } finally { members.close(); activity.finish(); }
    }

    @Test public void rowsShowAvatarNameUidAndSelectionSurvivesSearchAndRecycling() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "Alice", ""), new ProfileListEntry("202", "Bob", "")),
                Collections.singleton("202"), true);
        try {
            activity.setContentView(members);
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            assertEquals("Alice", ((TextView) row.findViewWithTag("member.name")).getText().toString());
            assertEquals("UID: 101", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            assertNotNull(((ImageView) ((android.view.ViewGroup) row).getChildAt(0)).getDrawable());
            row.performClick();
            EditText search = members.findViewWithTag("settings.followLists.search");
            search.setText("202");
            row = list.getAdapter().getView(0, row, list);
            assertEquals("Bob", ((TextView) row.findViewWithTag("member.name")).getText().toString());
            row.performClick();
            assertEquals(Collections.singleton("101"), members.selected());
            search.setText("no match");
            assertEquals(0, list.getAdapter().getCount());
            search.setText("");
            assertEquals(2, list.getAdapter().getCount());
            members.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY));
            members.layout(0, 0, 320, 420);
            Bitmap bitmap = Bitmap.createBitmap(320, 420, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE); members.draw(canvas);
            write(bitmap, "members.png");
        } finally { members.close(); activity.finish(); }
    }

    @Test public void adaptiveIconHasVisiblePixelsAndMonochromeVariant() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Drawable icon = activity.getDrawable(R.mipmap.ic_launcher);
        assertNotNull(icon);
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 256, 256);
        icon.draw(new Canvas(bitmap));
        int first = bitmap.getPixel(128, 128);
        boolean varied = false;
        for (int y = 40; y < 216; y += 4) for (int x = 40; x < 216; x += 4) {
            varied |= bitmap.getPixel(x, y) != first;
        }
        assertTrue(varied);
        assertNotNull(activity.getDrawable(R.drawable.ic_douai_monochrome));
        write(bitmap, "douai-icon.png");
        activity.finish();
    }

    @Test public void nicknameOnlySelectionDoesNotInventAUserUid() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = new MemberListView(activity, false, Collections.singletonList(ProfileListEntry.named("Alice")),
                Collections.singleton("Alice"), true);
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            assertEquals("UID: 未读取", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            row.performClick();
            assertTrue(members.selected().isEmpty());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void sharedAvatarRequestUpdatesOnlyItsBoundRowsAndCacheAvoidsAnotherLoad() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        QueuedExecutor images = new QueuedExecutor();
        int[] loads = {0}, dataChanges = {0};
        Bitmap avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        String url = "https://p3.douyinpic.com/shared.png";
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "Alice", url), new ProfileListEntry("202", "Bob", url)),
                Collections.emptySet(), false, images, (source, request) -> { loads[0]++; return avatar; });
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            list.getAdapter().registerDataSetObserver(new DataSetObserver() {
                @Override public void onChanged() { dataChanges[0]++; }
            });
            View first = list.getAdapter().getView(0, null, list);
            View second = list.getAdapter().getView(1, null, list);
            images.runAll();
            ShadowLooper.idleMainLooper();
            assertEquals(1, loads[0]);
            assertSame(avatar, ((BitmapDrawable) avatar(first).getDrawable()).getBitmap());
            assertSame(avatar, ((BitmapDrawable) avatar(second).getDrawable()).getBitmap());
            assertEquals(0, dataChanges[0]);
            View cached = list.getAdapter().getView(0, null, list);
            images.runAll();
            assertEquals(1, loads[0]);
            assertSame(avatar, ((BitmapDrawable) avatar(cached).getDrawable()).getBitmap());
        } finally { members.close(); avatar.recycle(); activity.finish(); }
    }

    @Test public void recycledRowCancelsItsUnusedQueuedRequestAndReceivesOnlyTheNewAvatar() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        QueuedExecutor images = new QueuedExecutor();
        List<String> loaded = new ArrayList<>();
        Bitmap avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        String firstUrl = "https://p3.douyinpic.com/alice.png", secondUrl = "https://p3.douyinpic.com/bob.png";
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "Alice", firstUrl), new ProfileListEntry("202", "Bob", secondUrl)),
                Collections.emptySet(), false, images, (source, request) -> { loaded.add(source); return avatar; });
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            row = list.getAdapter().getView(1, row, list);
            images.runAll();
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList(secondUrl), loaded);
            assertEquals(secondUrl, avatar(row).getTag());
            assertSame(avatar, ((BitmapDrawable) avatar(row).getDrawable()).getBitmap());
        } finally { members.close(); avatar.recycle(); activity.finish(); }
    }

    @Test public void closingCancelsRequestsAndRecyclesResultsAlreadyWaitingForMainThread() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        QueuedExecutor images = new QueuedExecutor();
        AvatarImageLoader.Request[] request = {null};
        Bitmap avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        String url = "https://p3.douyinpic.com/alice.png";
        MemberListView members = new MemberListView(activity, false,
                Collections.singletonList(new ProfileListEntry("101", "Alice", url)),
                Collections.emptySet(), false, images, (source, pending) -> { request[0] = pending; return avatar; });
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            Drawable placeholder = avatar(row).getDrawable();
            images.runAll();
            members.close();
            assertTrue(images.isShutdown());
            try { request[0].load(url); fail("Closed member picker must cancel its request"); }
            catch (CancellationException expected) { }
            ShadowLooper.idleMainLooper();
            assertSame(placeholder, avatar(row).getDrawable());
            assertTrue(avatar.isRecycled());
        } finally { members.close(); if (!avatar.isRecycled()) avatar.recycle(); activity.finish(); }
    }

    private static ImageView avatar(View row) { return (ImageView) ((android.view.ViewGroup) row).getChildAt(0); }

    private static final class QueuedExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> commands = new ArrayDeque<>();
        boolean stopped;
        void runAll() { while (!commands.isEmpty()) commands.remove().run(); }
        @Override public void execute(Runnable command) {
            if (stopped) throw new java.util.concurrent.RejectedExecutionException();
            commands.add(command);
        }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() {
            stopped = true; List<Runnable> remaining = new ArrayList<>(commands); commands.clear(); return remaining;
        }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && commands.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private static void write(Bitmap bitmap, String name) {
        String target = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (target == null) return;
        File directory = new File(target); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}

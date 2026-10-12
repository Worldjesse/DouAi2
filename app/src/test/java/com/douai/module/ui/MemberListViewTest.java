package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.view.View;
import com.douai.module.R;
import com.douai.module.BuildConfig;
import com.douai.module.feature.ProfileListEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
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

    private static void write(Bitmap bitmap, String name) {
        String target = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (target == null) return;
        File directory = new File(target); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}

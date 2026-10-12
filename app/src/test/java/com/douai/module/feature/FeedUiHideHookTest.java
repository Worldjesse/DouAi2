package com.douai.module.feature;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class FeedUiHideHookTest {
    public static final class FeedHolderFixture {
        public FrameLayout w;
        public FrameLayout f;
        public String aid = "current";
        public AwemeFixture getAweme() { return new AwemeFixture(aid); }

        public void onVideoPageSelected() { }
        public void onImagePageSelected() { }
        public void onVideoResume() { }
        public void onVideoPause() { }
        public void onImageResume() { }
        public void onImagePause() { }
    }

    public static final class AwemeFixture {
        private final String aid;
        AwemeFixture(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }

    public static final class PlayerFixture {
        public void onPause(String aid) { }
        public void onPlaying(String aid) { }
    }

    @Test
    public void matchesThe40_6FeedActionAndAuthorViewIds() {
        int[] resourceIds = {
                2131374336, 2131374348, 2131374349, 2131374369,
                2131371282, 2131371297, 2131371374, 2131371600,
                2131401927, 2131405160, 2131405374,
                2131415727, 2131415074, 2131415061, 2131379757, 2131379745,
                2131370690, 2131386250, 2131380047, 2131380048,
                2131398470, 2131401779, 2131410329
        };

        for (int resourceId : resourceIds) {
            assertTrue("Expected feed control resource ID " + resourceId,
                    FeedUiHideHook.isHiddenResourceId(resourceId));
        }
    }

    @Test
    public void leavesUnrelatedFeedResourcesVisible() {
        assertFalse(FeedUiHideHook.isHiddenResourceId(0x7f0a9734));
        assertFalse(FeedUiHideHook.isHiddenResourceId(0));
    }

    @Test
    public void matchesAccessibleFeedActionsWithoutDependingOnResourceNames() {
        assertTrue(FeedUiHideHook.matchesDescription("点赞 2.4 万"));
        assertTrue(FeedUiHideHook.matchesDescription("评论, 149"));
        assertTrue(FeedUiHideHook.matchesDescription("收藏"));
        assertTrue(FeedUiHideHook.matchesDescription("分享"));
        assertFalse(FeedUiHideHook.matchesDescription("Demon 发布了新作品"));
        assertFalse(FeedUiHideHook.matchesDescription("视频播放"));
    }

    @Test
    public void hidesVideoAndImageActionsAgainAfterHostRebindAndRestoresOnClose() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView digg = new TextView(activity);
        digg.setId(2131374336);
        digg.setVisibility(View.VISIBLE);
        root.addView(digg, new FrameLayout.LayoutParams(120, 48));
        activity.setContentView(root);

        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), enabledSettings(), ignored -> { });

        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, digg.getVisibility());

        digg.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, digg.getVisibility());
        assertTrue(hook.getPreDrawRechecks() > 0);
        assertEquals(1, hook.getHostStateResets());

        digg.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, digg.getVisibility());
        assertEquals(2, hook.getHostStateResets());

        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, digg.getVisibility());
        activity.finish();
    }

    @Test
    public void appliesDyydsStyleRightActionOpacityAndRestoresAtFullOpacity() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView digg = new TextView(activity);
        digg.setId(2131374336);
        root.addView(digg, new FrameLayout.LayoutParams(120, 48));
        activity.setContentView(root);

        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), settings(false, 35), ignored -> { });
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();

        assertEquals(View.VISIBLE, digg.getVisibility());
        assertEquals(0.35f, digg.getAlpha(), 0.001f);

        hook.setSettings(settings(false, 0));
        idleMainLooper();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, digg.getVisibility());
        assertEquals(0.0f, digg.getAlpha(), 0.001f);

        hook.setSettings(settings(false, 100));
        idleMainLooper();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(1.0f, digg.getAlpha(), 0.001f);
        hook.close();
        activity.finish();
    }

    @Test
    public void hidesAnonymousWidgetChildrenAndAuthorButKeepsMediaVisible() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View media = new View(activity);
        root.addView(media);
        FrameLayout widgets = new FrameLayout(activity);
        widgets.setId(2131362267);
        widgets.setAlpha(0.8f);
        widgets.setClickable(true);
        widgets.setFocusable(false);
        root.addView(widgets);
        widgets.addView(new View(activity));
        widgets.addView(new TextView(activity));
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402155);
        widgets.addView(right);
        right.addView(new View(activity));
        TextView author = new TextView(activity);
        author.setId(2131398470);
        author.setAlpha(0.7f);
        author.setClickable(false);
        author.setFocusable(true);
        root.addView(author);
        activity.setContentView(root);
        FeedHolderFixture holder = holder(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());

        intercept(hooks.get("DouAi.FeedUi.ImageResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, widgets.getVisibility());
        assertFalse(widgets.isClickable());
        assertFalse(widgets.isFocusable());
        assertEquals(View.INVISIBLE, author.getVisibility());
        assertFalse(author.isFocusable());
        assertEquals(View.VISIBLE, media.getVisibility());
        assertEquals(View.VISIBLE, root.getVisibility());

        hook.setSettings(settings(false, 35));
        idleMainLooper();
        assertEquals(View.VISIBLE, widgets.getVisibility());
        assertEquals(0.8f, widgets.getAlpha(), 0.001f);
        assertEquals(0.35f, right.getAlpha(), 0.001f);
        assertEquals(View.VISIBLE, author.getVisibility());
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertTrue(author.isFocusable());

        hook.setSettings(settings(false, 100));
        idleMainLooper();
        assertEquals(View.VISIBLE, widgets.getVisibility());
        assertEquals(0.8f, widgets.getAlpha(), 0.001f);
        assertEquals(1f, right.getAlpha(), 0.001f);
        assertTrue(widgets.isClickable());
        assertFalse(widgets.isFocusable());
        assertEquals(View.VISIBLE, author.getVisibility());
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertFalse(author.isClickable());
        assertTrue(author.isFocusable());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void appliesOpacityOnceToRightRegionWithoutChangingAuthorOrChildAlpha() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402155);
        right.setAlpha(0.6f);
        root.addView(right);
        TextView star = new TextView(activity);
        star.setId(2131374349);
        star.setAlpha(0.8f);
        right.addView(star);
        right.addView(new View(activity));
        TextView author = new TextView(activity);
        author.setId(2131398470);
        author.setAlpha(0.7f);
        root.addView(author);
        root.setClickable(true);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 35));
        FeedHolderFixture holder = holder(root);

        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(0.21f, right.getAlpha(), 0.001f);
        assertEquals(0.8f, star.getAlpha(), 0.001f);
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertEquals(1.0f, root.getAlpha(), 0.001f);
        assertEquals(1, hook.getHiddenViews());

        right.setAlpha(1f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(0.21f, right.getAlpha(), 0.001f);
        assertEquals(0.8f, star.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(0.6f, right.getAlpha(), 0.001f);
        assertTrue(root.isClickable());
        activity.finish();
    }

    @Test
    public void pauseRestoresStateAndResumeStartsMonitoringAgain() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402155);
        right.setAlpha(0.75f);
        right.setClickable(true);
        right.setFocusable(true);
        root.addView(right);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(true, 40));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertFalse(right.isClickable());

        right.setVisibility(View.VISIBLE);
        right.setAlpha(1f);
        right.setClickable(true);
        right.setFocusable(true);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertEquals(0.3f, right.getAlpha(), 0.001f);
        assertFalse(right.isClickable());
        assertFalse(right.isFocusable());

        intercept(hooks.get("DouAi.FeedUi.VideoPause"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, right.getVisibility());
        assertEquals(0.75f, right.getAlpha(), 0.001f);
        assertTrue(right.isClickable());
        assertTrue(right.isFocusable());
        int rechecks = hook.getPreDrawRechecks();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(rechecks, hook.getPreDrawRechecks());
        hook.setSettings(settings(true, 20));
        idleMainLooper();
        assertEquals(View.VISIBLE, right.getVisibility());

        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertEquals(0.15f, right.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void liveEnableWorksOnSelectedCardAndDisableRemovesDrawObserver() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View button = new View(activity);
        button.setId(2131374336);
        root.addView(button);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 100));
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, button.getVisibility());

        hook.setSettings(enabledSettings());
        idleMainLooper();
        assertEquals(View.INVISIBLE, button.getVisibility());
        root.getViewTreeObserver().dispatchOnPreDraw();
        int rechecks = hook.getPreDrawRechecks();
        hook.setSettings(settings(false, 100));
        idleMainLooper();
        assertEquals(View.VISIBLE, button.getVisibility());
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(rechecks, hook.getPreDrawRechecks());

        hook.setSettings(settings(false, 60));
        idleMainLooper();
        assertEquals(0.6f, button.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(1f, button.getAlpha(), 0.001f);
        activity.finish();
    }

    @Test
    public void closedHookIgnoresQueuedAndLateLifecycleCallbacks() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View button = new View(activity);
        button.setId(2131374336);
        root.addView(button);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        hook.close();
        idleMainLooper();
        assertEquals(0, hook.getCardScans());
        assertEquals(View.VISIBLE, button.getVisibility());
        int callbacks = hook.getSelectedCallbacks();
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        hook.setSettings(enabledSettings());
        idleMainLooper();
        assertEquals(callbacks, hook.getSelectedCallbacks());
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, button.getVisibility());
        activity.finish();
    }

    @Test
    public void componentsHaveIndependentValuesAndDoNotHideArbitraryText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View avatar = new View(activity);
        avatar.setId(2131366221);
        avatar.setAlpha(0.8f);
        root.addView(avatar);
        View like = new View(activity);
        like.setId(2131374356);
        like.setClickable(true);
        root.addView(like);
        TextView description = new TextView(activity);
        description.setText("点赞，评论和分享都是视频内容");
        root.addView(description);
        View author = new View(activity);
        author.setId(2131398470);
        author.setVisibility(View.GONE);
        root.addView(author);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.AVATAR.key, "128")
                .putString(Component.LIKE.key, "-2").putString(Component.AUTHOR.key, "128").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(0.8f * 128 / 255f, avatar.getAlpha(), 0.001f);
        assertEquals(View.GONE, like.getVisibility());
        assertFalse(like.isClickable());
        assertEquals(View.GONE, author.getVisibility());
        assertEquals(View.VISIBLE, description.getVisibility());
        assertEquals(View.VISIBLE, root.getVisibility());
        like.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, like.getVisibility());

        preferences.edit().putString(Component.LIKE.key, "255")
                .putBoolean(ComponentHideSettings.ENABLED, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertTrue(like.isClickable());
        assertEquals(0.8f, avatar.getAlpha(), 0.001f);
        assertEquals(View.GONE, author.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void independentHidePreservesProgressInsideAnotherComponentContainer() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout bottomTools = new FrameLayout(activity);
        bottomTools.setId(2131367345);
        root.addView(bottomTools);
        View tool = new View(activity);
        bottomTools.addView(tool);
        View progress = new View(activity);
        progress.setId(2131378120);
        progress.setAlpha(0.7f);
        progress.setClickable(true);
        bottomTools.addView(progress);
        View like = new View(activity);
        like.setId(2131374356);
        root.addView(like);
        View comment = new View(activity);
        comment.setId(2131371412);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putString(Component.COMMENT.key, "-2")
                .putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();

        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(View.GONE, tool.getVisibility());
        assertTrue(progress.isShown());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        assertTrue(progress.isClickable());
        preferences.edit().putString(Component.PROGRESS.key, "128").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertTrue(progress.isShown());
        assertEquals(0.7f * 128 / 255f, progress.getAlpha(), 0.001f);
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "-2").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, bottomTools.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false)
                .putString(Component.PROGRESS.key, "255").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, bottomTools.getVisibility());
        assertEquals(View.GONE, tool.getVisibility());
        assertTrue(progress.isShown());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, tool.getVisibility());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        activity.finish();
    }

    @Test
    public void allMatchingControlsAreHiddenWhenHostDuplicatesAResourceId() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View first = new View(activity);
        first.setId(2131374356);
        root.addView(first);
        View second = new View(activity);
        second.setId(2131374356);
        root.addView(second);
        View progress = new View(activity);
        progress.setId(2131378120);
        root.addView(progress);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, first.getVisibility());
        assertEquals(View.GONE, second.getVisibility());
        assertTrue(progress.isShown());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, first.getVisibility());
        assertEquals(View.VISIBLE, second.getVisibility());
        activity.finish();
    }

    @Test
    public void uniformOpacityOverridesIndividualsAndZeroRemainsClickable() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374356);
        like.setClickable(true);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "0").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(0f, like.getAlpha(), 0.001f);
        assertTrue(like.isClickable());
        preferences.edit().putString(ComponentHideSettings.UNIFORM_ALPHA, "-1").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.INVISIBLE, like.getVisibility());
        assertFalse(like.isClickable());
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertTrue(like.isClickable());
        activity.finish();
    }

    @Test
    public void collectionSettingDoesNotAffectTheAvatarFollowButton() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View collect = new View(activity);
        collect.setId(2131371071);
        root.addView(collect);
        View follow = new View(activity);
        follow.setId(2131379757);
        root.addView(follow);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COLLECT.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, collect.getVisibility());
        assertEquals(View.VISIBLE, follow.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void playerPauseRequiresCurrentAidAndOpensCommentsOnlyOncePerShowTransition() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View comment = new View(activity);
        comment.setId(2131371412);
        int[] clicks = {0};
        comment.setOnClickListener(view -> clicks[0]++);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, true).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "old");
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(0, clicks[0]);
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals(1, clicks[0]);
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(1, clicks[0]);
        intercept(hooks.get("DouAi.FeedUi.PlayerPlaying"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        holder.aid = "changed-before-queued-callback";
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(1, clicks[0]);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void gestureComponentToggleWorksWithoutPausePolicy() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View comment = new View(activity);
        comment.setId(2131371412);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        hook.setComponentsTemporarilyShown(true);
        idleMainLooper();
        assertTrue(hook.areComponentsTemporarilyShown());
        assertEquals(View.VISIBLE, comment.getVisibility());
        hook.setComponentsTemporarilyShown(false);
        idleMainLooper();
        assertFalse(hook.areComponentsTemporarilyShown());
        assertEquals(View.GONE, comment.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void switchOptionControlsWhetherPauseRevealCarriesToTheNextCard() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374356);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-1")
                .putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, false).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        holder.aid = "next";
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, true).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        holder.aid = "third";
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, like.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void disablingPauseRevealReappliesHidingImmediately() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374356);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertTrue(hook.areComponentsTemporarilyShown());
        assertEquals(View.VISIBLE, like.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertFalse(hook.areComponentsTemporarilyShown());
        assertEquals(View.GONE, like.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void customViewIsHiddenAndRestoredWhenItsPersistedRuleIsDeleted() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView custom = new TextView(activity);
        custom.setText("custom control");
        custom.setClickable(true);
        custom.setFocusable(true);
        root.addView(custom);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), custom);
        assertNotNull(locator);

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, custom.getVisibility());
        assertFalse(custom.isClickable());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.emptyList())).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, custom.getVisibility());
        assertTrue(custom.isClickable());
        assertTrue(custom.isFocusable());

        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void windowCustomRuleSurvivesFeedPauseAndDoesNotReplaceFixedComponentRule() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        FrameLayout feed = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374356);
        feed.addView(like);
        content.addView(feed);
        TextView windowControl = new TextView(activity);
        windowControl.setText("window control");
        content.addView(windowControl);
        activity.setContentView(content);

        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), feed, windowControl);
        assertNotNull(locator);
        assertEquals(CustomViewLocator.Scope.WINDOW, locator.scope);

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putString(ComponentHideSettings.CUSTOM_VIEWS,
                        CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(feed);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.GONE, windowControl.getVisibility());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.emptyList())).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.VISIBLE, windowControl.getVisibility());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, windowControl.getVisibility());

        intercept(hooks.get("DouAi.FeedUi.VideoPause"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.GONE, windowControl.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, windowControl.getVisibility());
        activity.finish();
    }

    @Test
    public void customRuleNeverHidesFinderOverlayOrItsChildren() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout overlay = new FrameLayout(activity);
        View overlayButton = new View(activity);
        overlay.addView(overlayButton);
        root.addView(overlay);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), root, overlayButton);
        assertNotNull(locator);
        overlay.setTag("douai.view_finder");

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(View.VISIBLE, overlayButton.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    private static SharedPreferences componentPreferences() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("feed_ui_test", 0);
        preferences.edit().clear().putBoolean(ComponentHideSettings.ENABLED, true).commit();
        return preferences;
    }

    private static FeedHolderFixture holder(FrameLayout root) {
        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        return holder;
    }

    private FeedUiHideHook install(Map<String, XposedInterface.Hooker> hooks,
                                   ExtendedSettings settings) throws Exception {
        return FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), settings, ignored -> { });
    }

    private static ExtendedSettings enabledSettings() {
        return settings(true, 100);
    }

    private static ExtendedSettings settings(boolean hideInterface, int actionAlpha) {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("feed_ui_test", 0);
        preferences.edit().putBoolean("hide_interface", hideInterface)
                .remove(ComponentHideSettings.ENABLED)
                .putString("video_right_alpha", Integer.toString(actionAlpha)).commit();
        return ExtendedSettings.readFrom(preferences);
    }

    private static void idleMainLooper() throws ReflectiveOperationException {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idle").invoke(shadow);
    }

    private static void intercept(XposedInterface.Hooker hook, Object holder, Object... arguments) throws Throwable {
        assertNotNull(hook);
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if ("proceed".equals(method.getName())) return null;
                    if ("getThisObject".equals(method.getName())) return holder;
                    if ("getArg".equals(method.getName())) return arguments[(Integer) args[0]];
                    throw new UnsupportedOperationException(method.getName());
                });
        hook.intercept(chain);
    }

    private static HostScanReport createReport() throws Exception {
        String owner = descriptor(FeedHolderFixture.class);
        List<RuleScanResult> results = Arrays.asList(
                methodResult("feed_holder_page_selected", owner, "onVideoPageSelected"),
                methodResult("feed_holder_resume", owner, "onVideoResume"),
                methodResult("feed_holder_pause", owner, "onVideoPause"),
                classResult("feed_holder_main_root", owner),
                methodResult("image_holder_page_selected", owner, "onImagePageSelected"),
                methodResult("image_holder_resume", owner, "onImageResume"),
                methodResult("image_holder_pause", owner, "onImagePause"),
                classResult("image_holder_main_root", owner),
                new RuleScanResult("component_player_pause", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", descriptor(PlayerFixture.class),
                                "onPause", "(Ljava/lang/String;)V"))),
                new RuleScanResult("component_player_playing", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", descriptor(PlayerFixture.class),
                                "onPlaying", "(Ljava/lang/String;)V"))));
        return new HostScanReport("test-host", results, false);
    }

    private static RuleScanResult methodResult(String id, String owner, String method) throws Exception {
        return new RuleScanResult(id, "method_scan", Collections.singletonList(
                new HostCandidate("test.apk", "classes.dex", owner, method, "()V")));
    }

    private static RuleScanResult classResult(String id, String owner) {
        return new RuleScanResult(id, "method_scan", Collections.singletonList(
                new HostCandidate("test.apk", "classes.dex", owner, "", "")));
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static XposedInterface createFramework(Map<String, XposedInterface.Hooker> hooks) {
        return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (frameworkProxy, frameworkMethod, frameworkArgs) -> {
                    if (!"hook".equals(frameworkMethod.getName())) return null;
                    String[] id = {""};
                    XposedInterface.HookBuilder[] builder = new XposedInterface.HookBuilder[1];
                    builder[0] = (XposedInterface.HookBuilder) Proxy.newProxyInstance(
                            XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builderProxy, builderMethod, builderArgs) -> {
                                if ("setId".equals(builderMethod.getName())) {
                                    id[0] = (String) builderArgs[0];
                                    return builder[0];
                                }
                                if ("intercept".equals(builderMethod.getName())) {
                                    hooks.put(id[0], (XposedInterface.Hooker) builderArgs[0]);
                                    return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                            new Class<?>[]{XposedInterface.HookHandle.class},
                                            (handleProxy, handleMethod, handleArgs) -> null);
                                }
                                return builder[0];
                            });
                    return builder[0];
                });
    }
}

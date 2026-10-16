package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class HostBehaviorViewTest {
    public static final class Panel {
        static final int TOP_SPACE_ID = 2131410054;
        static final int BOTTOM_SPACE_ID = 2131364845;
        public View i = new View(RuntimeEnvironment.getApplication());
        public View j = new View(RuntimeEnvironment.getApplication());
        public int createdRoots, spaceUpdates;
        public void spaces() { spaceUpdates++; }
        public void onViewCreated(View root, Bundle state) {
            createdRoots++;
            i = root.findViewById(TOP_SPACE_ID);
            j = root.findViewById(BOTTOM_SPACE_ID);
        }
    }

    public static final class Profile {
        public final TextView y;
        public final TextView w;
        public final TextView x;
        public final TextView F;
        public final LinearLayout root;
        public boolean own;
        public boolean failOwn;
        public Profile(Context context) {
            root = new LinearLayout(context);
            y = new TextView(context); w = new TextView(context);
            x = new TextView(context); F = new TextView(context);
            root.addView(y); root.addView(w); root.addView(x); root.addView(F);
            y.setText("12"); w.setText("34"); x.setText("56"); F.setText("78");
        }
        public void bind(Object user) { }
        public boolean own() {
            if (failOwn) throw new IllegalStateException("Profile fixture unavailable");
            return own;
        }
        public View root() { return root; }
    }

    public static final class RecallCell {
        public String uid = "100";
        public boolean failMessage;
        public void bind(RecallHolder holder, int index) { }
        public RecallMessage message(int index) {
            if (failMessage) throw new IllegalStateException("Recall fixture unavailable");
            return new RecallMessage();
        }
        public static RecallCell account() { return new RecallCell(); }
        public RecallCell user() { return this; }
    }

    public static final class RecallMessage {
        public String uuid() { return "message"; }
    }

    public static final class RecallHolder {
        public final View itemView;
        public RecallHolder(Context context) { itemView = new View(context); }
    }

    @Test public void blackSpacesApplyLiveAndRestoreOwnedVisibility() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Collections.emptyMap()), ignored -> { });
        Panel panel = new Panel();
        panel.j.setVisibility(View.INVISIBLE);
        harness.invoke(spaces, panel);
        assertEquals(1, harness.proceeded);
        assertEquals(View.VISIBLE, panel.i.getVisibility());
        hooks.setOptions(new LocalOptions(Map.of("hide_video_black_edges", true)));
        assertEquals(View.GONE, panel.i.getVisibility());
        assertEquals(View.GONE, panel.j.getVisibility());
        hooks.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals(View.VISIBLE, panel.i.getVisibility());
        assertEquals(View.INVISIBLE, panel.j.getVisibility());
        hooks.close();
    }

    @Test public void restorationPreservesANewHostVisibility() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { });
        Panel panel = new Panel();
        harness.invoke(spaces, panel);
        panel.i.setVisibility(View.INVISIBLE);
        hooks.close();
        assertEquals(View.INVISIBLE, panel.i.getVisibility());
        assertEquals(View.VISIBLE, panel.j.getVisibility());
    }

    @Test public void blackSpacesRecoverWhenTheSameFeedRootAndBottomViewReattachWithoutActivityResume() throws Throwable {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            FrameLayout content = new FrameLayout(activity.get());
            FrameLayout feedRoot = new FrameLayout(activity.get());
            content.addView(feedRoot);
            activity.get().setContentView(content);
            Panel panel = new Panel();
            panel.i = new View(activity.get());
            panel.j = new View(activity.get());
            panel.j.setBackgroundColor(Color.BLACK);
            feedRoot.addView(panel.i, new FrameLayout.LayoutParams(-1, 24));
            feedRoot.addView(panel.j, new FrameLayout.LayoutParams(-1, 56));
            Harness harness = new Harness();
            Method spaces = Panel.class.getMethod("spaces");
            HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                    "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                    new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { });
            try {
                assertTrue(feedRoot.isAttachedToWindow());
                harness.invoke(spaces, panel);
                assertEquals(View.GONE, panel.j.getVisibility());

                content.removeView(feedRoot);
                assertFalse(feedRoot.isAttachedToWindow());
                panel.j.setVisibility(View.VISIBLE);
                content.addView(feedRoot);
                assertTrue(feedRoot.isAttachedToWindow());

                // No settings refresh, panel hook invocation, Activity resume, or private apply.
                // Recovery must come from the attached tree's actual rendering callback.
                feedRoot.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("the reused feed's bottom black space must be hidden after reattachment",
                        View.GONE, panel.j.getVisibility());
                assertEquals("switch recovery must not depend on rerunning handleTopAndBottomSpace", 1, harness.proceeded);
            } finally {
                hooks.close();
            }
        }
    }

    @Test public void blackSpacesBindRecreatedPanelRootBeforeItsFirstSpaceUpdate() throws Throwable {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            FrameLayout content = new FrameLayout(activity.get());
            activity.get().setContentView(content);
            Harness harness = new Harness();
            Method spaces = Panel.class.getMethod("spaces");
            Method created = Panel.class.getMethod("onViewCreated", View.class, Bundle.class);
            HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                    "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                    new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { });
            Panel panel = new Panel();
            try {
                LinearLayout originalRoot = panelRoot(activity.get());
                content.addView(originalRoot);
                harness.invokeHost(created, panel, originalRoot, null);
                harness.invokeHost(spaces, panel);
                View originalBottom = panel.j;
                assertEquals(View.GONE, originalBottom.getVisibility());

                // Host 40.6.0 onViewCreated resolves the replacement root's i/j fields.
                // Dispatch that real lifecycle method without a second LLLFF or Activity resume.
                content.removeView(originalRoot);
                LinearLayout replacement = panelRoot(activity.get());
                content.addView(replacement);
                harness.invokeHost(created, panel, replacement, null);
                assertSame(replacement.findViewById(Panel.BOTTOM_SPACE_ID), panel.j);
                assertFalse(originalBottom == panel.j);
                assertEquals(2, panel.createdRoots);
                assertEquals(1, panel.spaceUpdates);
                replacement.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("onViewCreated must bind the replacement root before LLLFF happens again",
                        View.GONE, panel.j.getVisibility());
            } finally { hooks.close(); }
        }
    }

    @Test public void disablingOrClosingBlackSpaceHooksLeavesNewPanelRootsHostOwned() throws Throwable {
        for (boolean close : new boolean[]{false, true}) {
            try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
                FrameLayout content = new FrameLayout(activity.get());
                activity.get().setContentView(content);
                Harness harness = new Harness();
                Method spaces = Panel.class.getMethod("spaces");
                Method created = Panel.class.getMethod("onViewCreated", View.class, Bundle.class);
                HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                        "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                        new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { });
                try {
                    Panel panel = new Panel();
                    LinearLayout first = panelRoot(activity.get());
                    content.addView(first);
                    harness.invokeHost(created, panel, first, null);
                    harness.invokeHost(spaces, panel);
                    assertEquals(View.GONE, panel.j.getVisibility());
                    if (close) hooks.close(); else hooks.setOptions(new LocalOptions(Collections.emptyMap()));
                    content.removeView(first);
                    LinearLayout replacement = panelRoot(activity.get());
                    content.addView(replacement);
                    harness.invokeHost(created, panel, replacement, null);
                    replacement.getViewTreeObserver().dispatchOnPreDraw();
                    assertEquals(View.VISIBLE, panel.i.getVisibility());
                    assertEquals(View.VISIBLE, panel.j.getVisibility());
                } finally { hooks.close(); }
            }
        }
    }

    @Test public void blackSpacePreDrawRecoveryConvergesThroughTheNextNormalLinearLayout() throws Throwable {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            LinearLayout column = panelRoot(activity.get());
            activity.get().setContentView(column);
            Harness harness = new Harness();
            Method spaces = Panel.class.getMethod("spaces");
            Method created = Panel.class.getMethod("onViewCreated", View.class, Bundle.class);
            HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                    "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                    new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { });
            try {
                Panel panel = new Panel();
                harness.invokeHost(created, panel, column, null);
                harness.invokeHost(spaces, panel);
                measureColumn(column);
                assertEquals(200, column.getMeasuredHeight());

                panel.j.setVisibility(View.VISIBLE);
                measureColumn(column);
                assertEquals(256, column.getMeasuredHeight());
                assertEquals(56, panel.j.getHeight());
                column.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(View.GONE, panel.j.getVisibility());
                // Visibility and measured geometry have different lifetimes. Let the next normal
                // measure/layout finish; do not prescribe a preDraw return value or reject frames.
                measureColumn(column);
                assertEquals("the next normal layout must remove the recovered bottom space", 200, column.getMeasuredHeight());
                assertEquals(200, column.getHeight());
            } finally { hooks.close(); }
        }
    }

    private static LinearLayout panelRoot(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        View top = new View(context);
        top.setId(Panel.TOP_SPACE_ID);
        root.addView(top, new LinearLayout.LayoutParams(-1, 24));
        root.addView(new View(context), new LinearLayout.LayoutParams(-1, 200));
        View bottom = new View(context);
        bottom.setId(Panel.BOTTOM_SPACE_ID);
        bottom.setBackgroundColor(Color.BLACK);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, 56));
        return root;
    }

    private static void measureColumn(LinearLayout column) {
        column.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        column.layout(0, 0, column.getMeasuredWidth(), column.getMeasuredHeight());
    }

    @Test public void profileSpoofOnlyChangesOwnProfileAndRestoresText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Profile profile = new Profile(activity);
        activity.setContentView(profile.root);
        Harness harness = new Harness();
        Method bind = Profile.class.getMethod("bind", Object.class);
        ProfileSpoofHook hook = ProfileSpoofHook.install(harness.framework, report(Map.of(
                "adv_profile_render", bind, "adv_profile_own", Profile.class.getMethod("own"),
                "adv_profile_root", Profile.class.getMethod("root"))), getClass().getClassLoader(),
                activity, new LocalOptions(Map.of("spoof_profile", true, "profile_like_count", "9001",
                        "profile_following_count", "9002", "profile_follower_count", "9003",
                        "profile_mutual_count", "9004")), ignored -> { });
        harness.invoke(bind, profile);
        assertEquals("12", profile.y.getText().toString());
        profile.own = true;
        harness.invoke(bind, profile);
        assertEquals("9001", profile.y.getText().toString());
        assertEquals("9002", profile.w.getText().toString());
        assertEquals("9003", profile.x.getText().toString());
        assertEquals("9004", profile.F.getText().toString());
        hook.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals("12", profile.y.getText().toString());
        assertEquals("34", profile.w.getText().toString());
        assertEquals("56", profile.x.getText().toString());
        assertEquals("78", profile.F.getText().toString());
        hook.close();
        activity.finish();
    }

    @Test public void profileReflectionFailureRevokesRegistrationAndRestoresDisplayedCounts() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Profile profile = new Profile(activity);
        profile.own = true;
        activity.setContentView(profile.root);
        Harness harness = new Harness();
        Method bind = Profile.class.getMethod("bind", Object.class);
        ProfileSpoofHook hook = ProfileSpoofHook.install(harness.framework, report(Map.of(
                "adv_profile_render", bind, "adv_profile_own", Profile.class.getMethod("own"),
                "adv_profile_root", Profile.class.getMethod("root"))), getClass().getClassLoader(),
                activity, new LocalOptions(Map.of("spoof_profile", true, "profile_like_count", "9001")),
                ignored -> { });
        try {
            assertTrue(hook.isActive());
            harness.invoke(bind, profile);
            assertEquals("9001", profile.y.getText().toString());
            profile.failOwn = true;
            harness.invoke(bind, profile);
            assertFalse(hook.isActive());
            assertFalse(harness.interceptors.containsKey(bind));
            assertEquals("12", profile.y.getText().toString());
            assertEquals("34", profile.w.getText().toString());
            assertEquals("56", profile.x.getText().toString());
            assertEquals("78", profile.F.getText().toString());
        } finally { hook.close(); activity.finish(); }
    }

    @Test public void recallReflectionFailureRevokesRegistrationAndRestoresHighlightedBackground() throws Throwable {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("recall.100.message", true).commit();
        Harness harness = new Harness();
        Method bind = RecallCell.class.getMethod("bind", RecallHolder.class, int.class);
        RecallHighlightHook hook = RecallHighlightHook.install(harness.framework, report(Map.of(
                "adv_im_cell_bind", bind, "adv_im_cell_message", RecallCell.class.getMethod("message", int.class),
                "adv_im_message_uuid", RecallMessage.class.getMethod("uuid"),
                "media_account_service", RecallCell.class.getMethod("account"),
                "media_current_user", RecallCell.class.getMethod("user"))), getClass().getClassLoader(), context,
                new LocalOptions(Map.of("prevent_message_recall", true, "recall_background", "#2051BA55")),
                ignored -> { });
        RecallCell cell = new RecallCell();
        RecallHolder holder = new RecallHolder(context);
        GradientDrawable original = new GradientDrawable();
        original.setColor(Color.BLUE);
        holder.itemView.setBackground(original);
        RecallHolder failingHolder = new RecallHolder(context);
        GradientDrawable failingOriginal = new GradientDrawable();
        failingOriginal.setColor(Color.RED);
        failingHolder.itemView.setBackground(failingOriginal);
        try {
            assertTrue(hook.isActive());
            harness.invoke(bind, cell, holder, 0);
            assertEquals(Color.parseColor("#2051BA55"), ((ColorDrawable) holder.itemView.getBackground()).getColor());
            cell.failMessage = true;
            harness.invoke(bind, cell, failingHolder, 0);
            assertFalse(hook.isActive());
            assertFalse(harness.interceptors.containsKey(bind));
            assertSame(original, holder.itemView.getBackground());
            assertSame(failingOriginal, failingHolder.itemView.getBackground());
        } finally { hook.close(); }
    }

    private static HostScanReport report(Map<String, Method> methods) {
        java.util.ArrayList<RuleScanResult> results = new java.util.ArrayList<>();
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            Method method = entry.getValue();
            results.add(new RuleScanResult(entry.getKey(), "fixture", Collections.singletonList(
                    new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                            method.getName(), HostTargets.descriptor(method)))));
        }
        return new HostScanReport("fixture", results, false);
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new LinkedHashMap<>();
        int proceeded;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { XposedInterface.class }, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class }, (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class }, (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        void invoke(Method target, Object owner, Object... arguments) throws Throwable {
            invoke(target, owner, arguments, false);
        }

        void invokeHost(Method target, Object owner, Object... arguments) throws Throwable {
            invoke(target, owner, arguments, true);
        }

        private void invoke(Method target, Object owner, Object[] arguments, boolean executeOriginal) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getThisObject")) return owner;
                        if (method.getName().equals("getExecutable")) return target;
                        if (method.getName().equals("getArgs")) return Arrays.asList(arguments);
                        if (method.getName().equals("getArg")) return arguments[(Integer) args[0]];
                        if (method.getName().startsWith("proceed")) {
                            proceeded++;
                            return executeOriginal ? target.invoke(owner, arguments) : null;
                        }
                        return null;
                    });
            XposedInterface.Hooker interceptor = interceptors.get(target);
            if (interceptor == null && executeOriginal) {
                proceeded++;
                target.invoke(owner, arguments);
            } else {
                interceptor.intercept(chain);
            }
        }
    }
}

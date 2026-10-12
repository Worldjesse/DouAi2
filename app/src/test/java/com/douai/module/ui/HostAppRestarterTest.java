package com.douai.module.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.os.ResultReceiver;
import com.douai.module.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.ss.android.ugc.aweme")
public final class HostAppRestarterTest {
    private static final String IDENTITY = "a".repeat(64);

    @Test public void restartUsesForegroundRelayAndWaitsForItsAcknowledgment() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        registerHostLauncher(activity);
        registerRelay(activity);
        List<String> order = new ArrayList<>();
        HostAppRestarter restarter = new HostAppRestarter(new HostAppRestarter.AndroidScheduler(
                ignored -> order.add("terminate")));

        HostAppRestarter.Result result = restarter.restart(activity, IDENTITY, writer(order, true));
        assertTrue(result.isSuccessful());
        assertEquals(Arrays.asList("commit"), order);
        ShadowActivity shadowActivity = Shadow.extract(activity);
        Intent handoff = shadowActivity.getNextStartedActivity();
        assertNotNull(handoff);
        assertEquals(new ComponentName(BuildConfig.APPLICATION_ID, "com.douai.module.ui.HostRestartActivity"),
                handoff.getComponent());
        assertEquals(BuildConfig.APPLICATION_ID + ".action.RESTART_HOST", handoff.getAction());
        assertEquals(activity.getPackageName(),
                handoff.getStringExtra(HostAppRestarter.EXTRA_TARGET_PACKAGE));

        ResultReceiver receiver = handoff.getParcelableExtra(HostAppRestarter.EXTRA_RESULT_RECEIVER,
                ResultReceiver.class);
        assertNotNull(receiver);
        receiver.send(0, null);
        idleMainLooper();
        assertEquals(Arrays.asList("commit", "terminate"), order);
        activity.finish();
    }

    @Test public void registeredRelayIsRequiredBeforeCommitOrTermination() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        registerHostLauncher(activity);
        registerRelay(activity);
        List<String> order = new ArrayList<>();
        HostAppRestarter restarter = new HostAppRestarter(new HostAppRestarter.AndroidScheduler(
                ignored -> order.add("terminate")));
        HostAppRestarter.Result result = restarter.restart(activity, IDENTITY, writer(order, true));
        assertTrue(result.isSuccessful());
        assertEquals(Arrays.asList("commit"), order);
        activity.finish();
    }

    @Test public void failedAcknowledgmentDoesNotTerminateHost() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        registerHostLauncher(activity);
        registerRelay(activity);
        List<String> order = new ArrayList<>();
        HostAppRestarter restarter = new HostAppRestarter(new HostAppRestarter.AndroidScheduler(
                ignored -> order.add("terminate")));
        HostAppRestarter.Result result = restarter.restart(activity, IDENTITY, writer(order, false));
        assertFalse(result.isSuccessful());
        assertEquals(Arrays.asList("commit"), order);
        activity.finish();
    }

    @Test public void relayRejectsArbitraryLaunchWithoutTargetAndIdentity() {
        Intent intent = new Intent().setComponent(new ComponentName(BuildConfig.APPLICATION_ID,
                "com.douai.module.ui.HostRestartActivity"));
        HostRestartActivity activity = Robolectric.buildActivity(HostRestartActivity.class, intent)
                .create().get();
        assertTrue(activity.isFinishing());
    }

    @Test @Config(sdk = {28, 35})
    public void relayConfirmsForegroundThenLaunchesHostAfterTerminationWindow() throws Exception {
        Activity owner = Robolectric.buildActivity(Activity.class).setup().get();
        ComponentName launcher = registerHostLauncher(owner, HostAppRestarter.HOST_PACKAGE);
        assertNotNull(owner.getPackageManager().getLaunchIntentForPackage(HostAppRestarter.HOST_PACKAGE));
        int[] ready = {-1};
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int resultCode, android.os.Bundle data) {
                ready[0] = resultCode;
            }
        };
        Intent intent = new Intent(HostAppRestarter.ACTION_RESTART_RELAY)
                .setComponent(new ComponentName(BuildConfig.APPLICATION_ID,
                        "com.douai.module.ui.HostRestartActivity"))
                .putExtra(HostAppRestarter.EXTRA_TARGET_PACKAGE, HostAppRestarter.HOST_PACKAGE)
                .putExtra(HostAppRestarter.EXTRA_IDENTITY, IDENTITY)
                .putExtra(HostAppRestarter.EXTRA_RESULT_RECEIVER, receiver);
        HostRestartActivity relay = Robolectric.buildActivity(HostRestartActivity.class, intent)
                .setup().get();

        assertEquals(0, ready[0]);
        ShadowActivity shadowRelay = Shadow.extract(relay);
        assertNull("The old host must terminate before its launcher starts", shadowRelay.getNextStartedActivity());
        idleMainLooperFor(Duration.ofMillis(800));
        assertEquals(launcher, shadowRelay.getNextStartedActivity().getComponent());
        relay.finish();
        owner.finish();
    }

    private static void registerRelay(Activity activity) {
        ShadowPackageManager manager = Shadow.extract(activity.getPackageManager());
        ComponentName relay = new ComponentName(BuildConfig.APPLICATION_ID, "com.douai.module.ui.HostRestartActivity");
        manager.addActivityIfNotPresent(relay).exported = true;
    }

    private static ComponentName registerHostLauncher(Activity activity) {
        return registerHostLauncher(activity, activity.getPackageName());
    }

    private static ComponentName registerHostLauncher(Activity activity, String packageName) {
        ComponentName launcher = new ComponentName(packageName, "HostLauncher");
        ShadowPackageManager manager = Shadow.extract(activity.getPackageManager());
        ActivityInfo info = manager.addActivityIfNotPresent(launcher);
        info.exported = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_LAUNCHER);
        manager.addIntentFilterForActivity(launcher, filter);
        return launcher;
    }

    private static HostAppRestarter.AcknowledgmentWriter writer(List<String> order, boolean succeeds) {
        return new HostAppRestarter.AcknowledgmentWriter() {
            @Override public boolean commit(String identity) {
                assertEquals(IDENTITY, identity);
                order.add("commit");
                return succeeds;
            }
            @Override public void rollback(String identity) { order.add("rollback"); }
        };
    }

    private static void idleMainLooper() throws Exception {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idle").invoke(shadow);
    }

    private static void idleMainLooperFor(Duration duration) throws Exception {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idleFor", Duration.class).invoke(shadow, duration);
    }
}

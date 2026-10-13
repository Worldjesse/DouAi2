package com.douai.module.feature;

import android.content.SharedPreferences;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.Arrays;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ShortVideoProgressHookTest {
    @Test public void scannedLegacyAndProPredicatesDriveVisibleNativeDefaultsWithoutChangingNegativeValues() throws Throwable {
        SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("short_progress_native", 0);
        preferences.edit().clear().putBoolean(ComponentHideSettings.ENABLED, true)
                .putString(Component.PROGRESS.key, "124").commit();
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        Method legacy = Predicates.class.getMethod("legacy", Work.class, Integer.class);
        Method pro = Predicates.class.getMethod("pro", Work.class);
        HostScanReport report = new HostScanReport("fixture", Arrays.asList(
                result("component_short_progress_legacy", legacy), result("component_short_progress_pro", pro)), false);
        ShortVideoProgressHook hook = ShortVideoProgressHook.install(framework.api, report,
                getClass().getClassLoader(), ExtendedSettings.readFrom(preferences), ignored -> { });
        Work work = new Work(20_000);
        assertTrue(hook.isLegacyInstalled());
        assertTrue(hook.isProInstalled());
        assertEquals(false, framework.invoke(legacy, null, work, 20_000));
        assertEquals(false, framework.invoke(pro, null, work));
        preferences.edit().putString(Component.PROGRESS.key, "-2").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        assertEquals(true, framework.invoke(pro, null, work));
        hook.close();
        assertEquals(true, framework.invoke(pro, null, work));
    }

    private static RuleScanResult result(String id, Method method) {
        return new RuleScanResult(id, "method_scan", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                method.getName(), HostTargets.descriptor(method))));
    }

    public static final class Predicates {
        public static boolean legacy(Work work, Integer duration) { return true; }
        public static boolean pro(Work work) { return true; }
    }

    @Test public void retainedShortVideoUsesVisibleNativeDefaultAndAllValuesKeepTheirMeaning() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("short_progress", 0);
        preferences.edit().clear().putBoolean(ComponentHideSettings.ENABLED, true)
                .putString(Component.PROGRESS.key, "124").commit();
        ShortVideoProgressHook hook = ShortVideoProgressHook.install(null,
                new HostScanReport("fixture", Collections.emptyList(), false), getClass().getClassLoader(),
                ExtendedSettings.readFrom(preferences), ignored -> { });
        Work shortWork = new Work(20_000);
        assertFalse("Playing short videos must receive the visible native default", hook.overrideDecision(true, shortWork, null));
        for (int alpha : new int[]{255, 64, 1}) {
            preferences.edit().putString(Component.PROGRESS.key, Integer.toString(alpha)).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            assertFalse(hook.overrideDecision(true, shortWork, 20_000));
        }
        for (int alpha : new int[]{0, -1, -2}) {
            preferences.edit().putString(Component.PROGRESS.key, Integer.toString(alpha)).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            assertTrue(hook.overrideDecision(true, shortWork, null));
        }
        preferences.edit().putString(Component.PROGRESS.key, "124")
                .putBoolean(ComponentHideSettings.ENABLED, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        assertTrue(hook.overrideDecision(true, shortWork, null));
        hook.close();
    }

    @Test public void adsPhotosLongVideosAndMissingDurationKeepTheirNativeDecision() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("short_progress_scope", 0);
        preferences.edit().clear().putBoolean(ComponentHideSettings.ENABLED, true)
                .putString(Component.PROGRESS.key, "124").commit();
        ShortVideoProgressHook hook = ShortVideoProgressHook.install(null,
                new HostScanReport("fixture", Collections.emptyList(), false), getClass().getClassLoader(),
                ExtendedSettings.readFrom(preferences), ignored -> { });
        Work work = new Work(30_000);
        assertFalse(hook.overrideDecision(true, work, null));
        assertFalse(hook.overrideDecision(false, work, null));
        work.video.duration = 30_001;
        assertTrue(hook.overrideDecision(true, work, null));
        work.video.duration = 0;
        assertTrue(hook.overrideDecision(true, work, null));
        work.video.duration = 10_000;
        work.ad = true;
        assertTrue(hook.overrideDecision(true, work, null));
        work.ad = false;
        work.type = 68;
        assertTrue(hook.overrideDecision(true, work, null));
        work.type = 0;
        hook.close();
        assertTrue(hook.overrideDecision(true, work, null));
    }

    public static final class Work {
        final Video video;
        boolean ad;
        int type;
        Work(int duration) { video = new Video(duration); }
        public Video getVideo() { return video; }
        public boolean getAd() { return ad; }
        public int getAwemeType() { return type; }
    }

    public static final class Video {
        int duration;
        Video(int duration) { this.duration = duration; }
        public int getDuration() { return duration; }
    }
}

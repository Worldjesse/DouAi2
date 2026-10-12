package com.douai.module.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import androidx.test.core.app.ApplicationProvider;
import com.douai.module.BuildConfig;
import com.douai.module.R;
import com.douai.module.diagnostics.ScanDiagnosticsChannel;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class LauncherVisibilityTest {
    @Test public void renamedInstallPackageDeclaresItsSignatureDiagnosticsPermission() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals(BuildConfig.APPLICATION_ID, context.getPackageName());
        PackageInfo info = context.getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID,
                PackageManager.GET_PERMISSIONS);
        PermissionInfo permission = Arrays.stream(info.permissions)
                .filter(item -> ScanDiagnosticsChannel.PERMISSION.equals(item.name))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE,
                permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
        assertTrue(Arrays.asList(info.requestedPermissions).contains(ScanDiagnosticsChannel.PERMISSION));
    }

    @Test public void hidesOnlyLauncherAliasAndCanRestoreIt() {
        Context context = ApplicationProvider.getApplicationContext();
        ComponentName main = new ComponentName(context, "com.douai.module.MainActivity");
        int initial = context.getPackageManager().getComponentEnabledSetting(main);
        assertEquals("DouAi", context.getString(R.string.app_name));
        LauncherVisibility.setHidden(context, false);
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.getPackageName());
        Intent settings = new Intent(Intent.ACTION_MAIN).addCategory("de.robv.android.xposed.category.MODULE_SETTINGS")
                .setPackage(context.getPackageName());
        assertEquals("com.douai.module.Launcher", context.getPackageManager().queryIntentActivities(launcher, 0).get(0).activityInfo.name);
        assertEquals(BuildConfig.APPLICATION_ID,
                context.getPackageManager().queryIntentActivities(launcher, 0).get(0).activityInfo.packageName);
        LauncherVisibility.setHidden(context, true);
        assertTrue(LauncherVisibility.isHidden(context));
        assertEquals(initial, context.getPackageManager().getComponentEnabledSetting(main));
        assertTrue(context.getPackageManager().queryIntentActivities(launcher, 0).isEmpty());
        assertEquals("com.douai.module.MainActivity", context.getPackageManager().queryIntentActivities(settings, 0).get(0).activityInfo.name);
        assertEquals(BuildConfig.APPLICATION_ID,
                context.getPackageManager().queryIntentActivities(settings, 0).get(0).activityInfo.packageName);
        LauncherVisibility.setHidden(context, false);
        assertFalse(LauncherVisibility.isHidden(context));
        assertEquals(initial, context.getPackageManager().getComponentEnabledSetting(main));
    }
}

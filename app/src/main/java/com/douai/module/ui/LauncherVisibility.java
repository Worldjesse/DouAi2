package com.douai.module.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Changes only the launcher alias; the exported module settings activity remains enabled. */
public final class LauncherVisibility {
    private LauncherVisibility() { }
    private static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(), "com.douai.module.Launcher");
    }
    public static boolean isHidden(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(alias(context))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
    public static void setHidden(Context context, boolean hidden) {
        context.getPackageManager().setComponentEnabledSetting(alias(context), hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }
}

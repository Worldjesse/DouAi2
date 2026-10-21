package com.douai.module.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;

/** Test fixtures select the same original installed profile as the production bridge. */
public final class HostProfileFixture {
    private HostProfileFixture() { }
    public static HostAdaptation.Profile profile(Context context) {
        HostAdaptation.Profile profile = HostAdaptation.currentProfile();
        if (profile != null) return profile;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            profile = HostAdaptation.forVersion(context.getPackageName(), info.versionName, info.getLongVersionCode());
            if (profile == null) throw new AssertionError("Fixture requires a supported original host profile");
            return profile;
        } catch (android.content.pm.PackageManager.NameNotFoundException failure) { throw new AssertionError(failure); }
    }
    public static int id(Context context, int canonical) { return HostResourceProfile.id(profile(context), canonical); }
}

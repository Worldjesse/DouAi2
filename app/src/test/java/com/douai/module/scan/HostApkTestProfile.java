package com.douai.module.scan;

import java.io.File;
import java.io.IOException;

/** Selects original-APK contracts from the binary Manifest, never from the file name. */
public final class HostApkTestProfile {
    private HostApkTestProfile() { }

    public static HostAdaptation.Profile profile(File apk) throws IOException {
        HostAdaptation.Profile profile = HostApkProfile.read(apk);
        if (profile == null) {
            HostApkProfile.Identity identity = HostApkProfile.readIdentity(apk);
            throw new AssertionError("No verified profile for original APK " + identity.packageName
                    + " " + identity.versionName + " (" + identity.versionCode + ")");
        }
        return profile;
    }

    public static boolean is406(File apk) throws IOException {
        return "40.6.0".equals(profile(apk).versionName);
    }

    public static File rulesFile(File apk) throws IOException {
        return assetFile(profile(apk).rulesAsset);
    }

    public static File assetFile(String asset) {
        File file = new File("src/main/assets/" + asset);
        if (!file.isFile()) file = new File("app/src/main/assets/" + asset);
        if (!file.isFile()) throw new AssertionError("Missing host rules asset: " + asset);
        return file;
    }
}
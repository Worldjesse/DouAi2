package com.douai.module.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Static APK evidence; matching this profile does not establish runtime feature success. */
public final class HostAdaptation {
    public static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    public static final String RULES_ASSET = "douyin-host-rules.tsv";
    public static final String VERIFIED_VERSION_NAME = "40.6.0";
    public static final long VERIFIED_VERSION_CODE = 400601L;
    public static final String VERIFIED_RULES_FINGERPRINT =
            "12039abcf939f2afe473416551cf75076ed18d978cb0bbcf3418908c121f48b9";
    private static final Map<Integer, String> FEED_RESOURCES = feedResources();

    private HostAdaptation() { }

    public static boolean matchesVersion(String packageName, String versionName, long versionCode) {
        return TARGET_PACKAGE.equals(packageName) && VERIFIED_VERSION_NAME.equals(versionName)
                && versionCode == VERIFIED_VERSION_CODE;
    }

    public static boolean matchesRules(String fingerprint) {
        return VERIFIED_RULES_FINGERPRINT.equals(fingerprint);
    }

    public static Map<Integer, String> getFeedResources() { return FEED_RESOURCES; }

    private static Map<Integer, String> feedResources() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131366221, "b1g");
        values.put(2131367345, "cmm");
        values.put(2131362267, "i6");
        values.put(2131370690, "em6");
        values.put(2131374336, "gw+");
        values.put(2131374348, "gxl");
        values.put(2131374349, "gxm");
        values.put(2131374369, "gx6");
        values.put(2131374356, "gxt");
        values.put(2131379757, "j_l");
        values.put(2131379745, "j=+");
        values.put(2131371282, "comment_container");
        values.put(2131371297, "e17");
        values.put(2131371374, "e32");
        values.put(2131371600, "e9l");
        values.put(2131371412, "comment_layout");
        values.put(2131371071, "ewk");
        values.put(2131370900, "esb");
        values.put(2131370902, "esd");
        values.put(2131371905, "fc7");
        values.put(2131378315, "jb1");
        values.put(2131378359, "jc5");
        values.put(2131378361, "jc7");
        values.put(2131378120, "i=8");
        values.put(2131369826, "d5s");
        values.put(2131378145, "i_t");
        values.put(2131380047, "ket");
        values.put(2131380048, "keu");
        values.put(2131386250, "n80");
        values.put(2131388599, "pm6");
        values.put(2131393288, "si1");
        values.put(2131393289, "si2");
        values.put(2131394023, "s1c");
        values.put(2131394026, "s1f");
        values.put(2131394663, "tdx");
        values.put(2131394225, "s6v");
        values.put(2131394230, "s60");
        values.put(2131398470, "title");
        values.put(2131400515, "w05");
        values.put(2131401927, "share_container");
        values.put(2131405160, "zu2");
        values.put(2131405374, "z0a");
        values.put(2131402155, "x1c");
        values.put(2131401779, "xr6");
        values.put(2131410329, "22i");
        values.put(2131415074, "5zq");
        values.put(2131415061, "5zd");
        values.put(2131415727, "user_avatar");
        values.put(2131415983, "6h0");
        values.put(2131416121, "6ld");
        values.put(2131416122, "6le");
        return Collections.unmodifiableMap(values);
    }
}

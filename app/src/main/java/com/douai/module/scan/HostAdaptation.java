package com.douai.module.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Static APK evidence; matching this profile does not establish runtime feature success. */
public final class HostAdaptation {
    public static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    public static final String RULES_ASSET = "douyin-host-rules.tsv";
    public static final String VERIFIED_VERSION_NAME = "40.8.0";
    public static final long VERIFIED_VERSION_CODE = 400801L;
    public static final long VERIFIED_UPDATE_VERSION_CODE = 40809900L;
    public static final long UPDATE_VERSION_CODE_405 = 40509900L;
    public static final String VERIFIED_RULES_FINGERPRINT =
            "16170513f5cd610971e1daab6f80f33ee4499b59170187dc70ae604366db4c75";
    private static final Map<Integer, String> FEED_RESOURCES = feedResources();

    private HostAdaptation() { }

    public static final Profile PROFILE_406 = new Profile("40.6.0", 400601L,
            "douyin-host-rules-40.6.0.tsv",
            "99f7257bb5f1e533a9b99565b019d5ab978c9061bb77002db13ee2dd0d25e36f", legacyFeedResources());
    public static final Profile PROFILE_405 = new Profile("40.5.0", 400501L,
            "douyin-host-rules-40.5.0.tsv",
            "92eeb9b0939ff2d8cd24fdec91e77cca21b9b8c0fff3d6fc5989dbaebd8a9edf", feedResources405());
    public static final Profile PROFILE_408 = new Profile("40.8.0", 400801L,
            RULES_ASSET, VERIFIED_RULES_FINGERPRINT, FEED_RESOURCES);
    /** Every supported host build; selection always uses the real host identity. */
    public static final Profile[] PROFILES = {PROFILE_405, PROFILE_406, PROFILE_408};
    private static volatile Profile currentProfile;

    public static final class Profile {
        public final String versionName;
        public final long versionCode;
        public final String rulesAsset;
        public final String fingerprint;
        private final Map<Integer, String> feedResources;

        private Profile(String versionName, long versionCode, String rulesAsset,
                        String fingerprint, Map<Integer, String> feedResources) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.rulesAsset = rulesAsset;
            this.fingerprint = fingerprint;
            this.feedResources = feedResources;
        }

        public Map<Integer, String> getFeedResources() { return feedResources; }
        public boolean matchesRules(String value) { return fingerprint.equals(value); }
    }

    public static Profile forVersion(String packageName, String versionName, long versionCode) {
        return TARGET_PACKAGE.equals(packageName) ? forVersion(versionName, versionCode) : null;
    }

    public static Profile forVersion(String versionName, long versionCode) {
        for (Profile profile : PROFILES) {
            if (profile.versionName.equals(versionName) && profile.versionCode == versionCode) return profile;
        }
        return null;
    }

    public static Profile currentProfile() { return currentProfile; }
    public static void setCurrentProfile(Profile profile) { currentProfile = profile; }

    public static boolean matchesVersion(String packageName, String versionName, long versionCode) {
        return forVersion(packageName, versionName, versionCode) != null;
    }

    public static boolean matchesRules(String fingerprint) {
        for (Profile profile : PROFILES) {
            if (profile.matchesRules(fingerprint)) return true;
        }
        return false;
    }

    /** Latest profile for source/API callers; runtime selection must use the real host identity. */
    public static Map<Integer, String> getFeedResources() { return FEED_RESOURCES; }

    private static Map<Integer, String> legacyFeedResources() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131362267, "i6");
        values.put(2131366221, "b1g");
        values.put(2131367345, "cmm");
        values.put(2131369826, "d5s");
        values.put(2131370690, "em6");
        values.put(2131370900, "esb");
        values.put(2131370902, "esd");
        values.put(2131371071, "ewk");
        values.put(2131371282, "comment_container");
        values.put(2131371297, "e17");
        values.put(2131371374, "e32");
        values.put(2131371412, "comment_layout");
        values.put(2131371600, "e9l");
        values.put(2131371905, "fc7");
        values.put(2131374336, "gw+");
        values.put(2131374348, "gxl");
        values.put(2131374349, "gxm");
        values.put(2131374356, "gxt");
        values.put(2131374369, "gx6");
        values.put(2131378120, "i=8");
        values.put(2131378145, "i_t");
        values.put(2131378315, "jb1");
        values.put(2131378359, "jc5");
        values.put(2131378361, "jc7");
        values.put(2131379745, "j=+");
        values.put(2131379757, "j_l");
        values.put(2131380047, "ket");
        values.put(2131380048, "keu");
        values.put(2131386250, "n80");
        values.put(2131388599, "pm6");
        values.put(2131393288, "si1");
        values.put(2131393289, "si2");
        values.put(2131394023, "s1c");
        values.put(2131394026, "s1f");
        values.put(2131394225, "s6v");
        values.put(2131394230, "s60");
        values.put(2131394663, "tdx");
        values.put(2131398470, "title");
        values.put(2131400515, "w05");
        values.put(2131401779, "xr6");
        values.put(2131401927, "share_container");
        values.put(2131402155, "x1c");
        values.put(2131405160, "zu2");
        values.put(2131405374, "z0a");
        values.put(2131410329, "22i");
        values.put(2131415061, "5zd");
        values.put(2131415074, "5zq");
        values.put(2131415727, "user_avatar");
        values.put(2131415983, "6h0");
        values.put(2131416121, "6ld");
        values.put(2131416122, "6le");
        return Collections.unmodifiableMap(values);
    }

    /** Feed resource names for the 40.5.0 build, parsed from its resources.arsc. */
    private static Map<Integer, String> feedResources405() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131362266, "i6");
        values.put(2131366217, "b1g");
        values.put(2131367340, "cmm");
        values.put(2131369821, "d5s");
        values.put(2131370683, "em6");
        values.put(2131370894, "esb");
        values.put(2131370896, "esd");
        values.put(2131371065, "ewk");
        values.put(2131371290, "e17");
        values.put(2131371332, "comment_container");
        values.put(2131371367, "e32");
        values.put(2131371463, "comment_layout");
        values.put(2131371594, "e9l");
        values.put(2131371899, "fc7");
        values.put(2131374329, "gw+");
        values.put(2131374341, "gxl");
        values.put(2131374342, "gxm");
        values.put(2131374349, "gxt");
        values.put(2131374363, "gx6");
        values.put(2131378114, "i=8");
        values.put(2131378139, "i_t");
        values.put(2131378309, "jb1");
        values.put(2131378353, "jc5");
        values.put(2131378355, "jc7");
        values.put(2131379739, "j=+");
        values.put(2131379751, "j_l");
        values.put(2131380041, "ket");
        values.put(2131380042, "keu");
        values.put(2131386244, "n80");
        values.put(2131388592, "pm6");
        values.put(2131393281, "si1");
        values.put(2131393282, "si2");
        values.put(2131394011, "s1c");
        values.put(2131394014, "s1f");
        values.put(2131394219, "s6v");
        values.put(2131394224, "s60");
        values.put(2131394654, "tdx");
        values.put(2131398470, "title");
        values.put(2131401772, "xr6");
        values.put(2131401927, "share_container");
        values.put(2131402145, "x1c");
        values.put(2131405153, "zu2");
        values.put(2131405367, "z0a");
        values.put(2131410321, "22i");
        values.put(2131415053, "5zd");
        values.put(2131415066, "5zq");
        values.put(2131415727, "user_avatar");
        values.put(2131415974, "6h0");
        values.put(2131416113, "6ld");
        values.put(2131416114, "6le");
        return Collections.unmodifiableMap(values);
    }

    private static Map<Integer, String> feedResources() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131366240, "b1z");
        values.put(2131367357, "cmx");
        values.put(2131362267, "i6");
        values.put(2131370692, "em5");
        values.put(2131374327, "gw1");
        values.put(2131374339, "gw+");
        values.put(2131374340, "gxa");
        values.put(2131374360, "gxu");
        values.put(2131374347, "gxh");
        values.put(2131379772, "j_x");
        values.put(2131379761, "j_m");
        values.put(2131371285, "comment_container");
        values.put(2131371299, "e16");
        values.put(2131371376, "e31");
        values.put(2131371605, "e9n");
        values.put(2131371414, "comment_layout");
        values.put(2131371073, "ewj");
        values.put(2131370902, "esa");
        values.put(2131370904, "esc");
        values.put(2131371909, "fc8");
        values.put(2131378332, "jcb");
        values.put(2131378377, "jdf");
        values.put(2131378379, "jdh");
        values.put(2131378139, "i_k");
        values.put(2131369833, "d5w");
        values.put(2131378163, "i_8");
        values.put(2131380062, "ke6");
        values.put(2131380063, "ke7");
        values.put(2131386238, "n8l");
        values.put(2131388590, "pmu");
        values.put(2131393278, "sin");
        values.put(2131393279, "sio");
        values.put(2131394011, "s01");
        values.put(2131394015, "s05");
        values.put(2131394653, "tdk");
        values.put(2131394215, "s6i");
        values.put(2131394220, "s6n");
        values.put(2131398470, "title");
        values.put(2131400515, "w01");
        values.put(2131401927, "share_container");
        values.put(2131405175, "zu+");
        values.put(2131405391, "z0n");
        values.put(2131402182, "x1y");
        values.put(2131401805, "xso");
        values.put(2131410333, "22h");
        values.put(2131415055, "5y5");
        values.put(2131415033, "5yj");
        values.put(2131415727, "user_avatar");
        values.put(2131415966, "6hd");
        values.put(2131416104, "6ku");
        values.put(2131416105, "6kv");
        return Collections.unmodifiableMap(values);
    }
}

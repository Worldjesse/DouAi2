package com.douai.module.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable semantic resource keys and UI aliases for the two verified original host builds. */
public final class HostResourceProfile {
    private static final Map<Integer, Integer> LEGACY_IDS = legacyIds();
    private static final Map<Integer, Integer> CANONICAL_IDS = canonicalIds();
    private static final Map<Integer, Integer> IDS_405 = ids405();
    private static final Map<Integer, Integer> CANONICAL_IDS_405 = invert(IDS_405);
    /**
     * Obfuscated 40.5.0 names resolved from the real 400501/40509900 APK. Unresolved keys fall back to
     * {@link #UNRESOLVED_405} so callers fail as "not adapted" instead of binding another build's class.
     */
    private static final Map<String, String> CLASS_NAMES_405 = classNames405();
    private static final String UNRESOLVED_405 = "X.DouAiUnadapted405";

    private HostResourceProfile() { }

    public static boolean legacy(HostAdaptation.Profile profile) {
        if (profile == null) throw new IllegalArgumentException("Host profile is required");
        return profile.versionCode == 400601L;
    }

    public static boolean build405(HostAdaptation.Profile profile) {
        if (profile == null) throw new IllegalArgumentException("Host profile is required");
        return profile.versionCode == 400501L;
    }

    public static int id(HostAdaptation.Profile profile, int canonicalId) {
        if (legacy(profile)) return LEGACY_IDS.getOrDefault(canonicalId, canonicalId);
        if (build405(profile)) return IDS_405.getOrDefault(canonicalId, canonicalId);
        return canonicalId;
    }

    public static int canonicalId(HostAdaptation.Profile profile, int actualId) {
        if (legacy(profile)) return CANONICAL_IDS.getOrDefault(actualId, actualId);
        if (build405(profile)) return CANONICAL_IDS_405.getOrDefault(actualId, actualId);
        return actualId;
    }

    public static String className(HostAdaptation.Profile profile, String latestName) {
        if (build405(profile)) return CLASS_NAMES_405.getOrDefault(latestName, UNRESOLVED_405);
        boolean previous = legacy(profile);
        // The keys stay stable for callers. The latest targets are from build 40809900.
        switch (latestName) {
            case "X.0YVw": return previous ? "X.0s7o" : "X.0TzN";
            case "X.0vJL": return previous ? "X.0sBR" : "X.1Czy";
            case "X.0YVa": return previous ? "X.0ryb" : "X.0Tqi";
            case "X.0vMA": return previous ? "X.1bWb" : "X.1hFv";
            case "X.0uuG": return previous ? "X.0qn3" : "X.0URm";
            case "X.0vK4": return previous ? "X.0sNm" : "X.14XH";
            case "X.0Yjd": return previous ? "X.0sMT" : "X.0UGJ";
            case "X.0XZk": return previous ? "X.0sWV" : "X.0TQx";
            case "X.0uok": return previous ? "X.0sSQ" : "X.1Cni";
            case "X.0vM9": return previous ? "X.1aLM" : "X.1fyy";
            case "X.1UOy": return previous ? "X.131D" : "X.1Cd0";
            case "X.1U1O": return previous ? "X.133H" : "X.1Cue";
            case "X.1U1r": return previous ? "X.133I" : "X.1CuX";
            case "X.1U1z": return previous ? "X.134V" : "X.1CuH";
            case "X.1U2g": return previous ? "X.134d" : "X.1Cur";
            case "X.0vUA": return previous ? "X.12w3" : "X.1Ccz";
            case "X.0vTt": return previous ? "X.12vu" : "X.1Ct9";
            case "X.1UWt": return previous ? "X.06Dh" : "X.1Czx";
            default: return latestName;
        }
    }

    public static String viewFactory(HostAdaptation.Profile profile) {
        if (build405(profile)) return CLASS_NAMES_405.getOrDefault("viewFactory", UNRESOLVED_405);
        return legacy(profile) ? "LJJIIJ" : "LJJIIJZLJL";
    }

    public static String avatarLoader(HostAdaptation.Profile profile) {
        if (build405(profile)) return CLASS_NAMES_405.getOrDefault("avatarLoader", UNRESOLVED_405);
        return legacy(profile) ? "LJIIIZ" : "LJIIJ";
    }

    /**
     * Only names proven against the 40.5.0 APK belong here. An empty table is the honest state: native
     * chat, avatar loading and the album picker then report "not adapted" on that build.
     */
    private static Map<String, String> classNames405() {
        Map<String, String> values = new LinkedHashMap<>();
        // Both names are unique in the 400501/40509900 APK with the exact reflective signatures used here.
        values.put("viewFactory", "LJJ");
        values.put("avatarLoader", "LJIIIZ");
        return Collections.unmodifiableMap(values);
    }

    private static Map<Integer, Integer> ids405() {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        values.put(0x7f0a01db, 0x7f0a01da); // i6
        values.put(0x7f0a1160, 0x7f0a1149); // b1g
        values.put(0x7f0a15bd, 0x7f0a15ac); // cmm
        values.put(0x7f0a1f69, 0x7f0a1f5d); // d5s
        values.put(0x7f0a22c4, 0x7f0a22bb); // em6
        values.put(0x7f0a2396, 0x7f0a238e); // esb
        values.put(0x7f0a2398, 0x7f0a2390); // esd
        values.put(0x7f0a2441, 0x7f0a2439); // ewk
        values.put(0x7f0a2515, 0x7f0a2544); // comment_container
        values.put(0x7f0a2523, 0x7f0a251a); // e17
        values.put(0x7f0a2570, 0x7f0a2567); // e32
        values.put(0x7f0a2596, 0x7f0a25c7); // comment_layout
        values.put(0x7f0a2655, 0x7f0a264a); // e9l
        values.put(0x7f0a2785, 0x7f0a277b); // fc7
        values.put(0x7f0a30f7, 0x7f0a30f9); // gw+
        values.put(0x7f0a3103, 0x7f0a3105); // gxl
        values.put(0x7f0a3104, 0x7f0a3106); // gxm
        values.put(0x7f0a310b, 0x7f0a310d); // gxt
        values.put(0x7f0a3118, 0x7f0a311b); // gx6
        values.put(0x7f0a3fdb, 0x7f0a3fc2); // i=8
        values.put(0x7f0a3ff3, 0x7f0a3fdb); // i_t
        values.put(0x7f0a409c, 0x7f0a4085); // jb1
        values.put(0x7f0a40c9, 0x7f0a40b1); // jc5
        values.put(0x7f0a40cb, 0x7f0a40b3); // jc7
        values.put(0x7f0a4631, 0x7f0a461b); // j=+
        values.put(0x7f0a463c, 0x7f0a4627); // j_l
        values.put(0x7f0a475e, 0x7f0a4749); // ket
        values.put(0x7f0a475f, 0x7f0a474a); // keu
        values.put(0x7f0a5f7e, 0x7f0a5f84); // n80
        values.put(0x7f0a68ae, 0x7f0a68b0); // pm6
        values.put(0x7f0a7afe, 0x7f0a7b01); // si1
        values.put(0x7f0a7aff, 0x7f0a7b02); // si2
        values.put(0x7f0a7ddb, 0x7f0a7ddb); // s1c
        values.put(0x7f0a7ddf, 0x7f0a7dde); // s1f
        values.put(0x7f0a7ea7, 0x7f0a7eab); // s6v
        values.put(0x7f0a7eac, 0x7f0a7eb0); // s60
        values.put(0x7f0a805d, 0x7f0a805e); // tdx
        values.put(0x7f0a8f46, 0x7f0a8f46); // title
        values.put(0x7f0a9c4d, 0x7f0a9c2c); // xr6
        values.put(0x7f0a9cc7, 0x7f0a9cc7); // share_container
        values.put(0x7f0a9dc6, 0x7f0a9da1); // x1c
        values.put(0x7f0aa977, 0x7f0aa961); // zu2
        values.put(0x7f0aaa4f, 0x7f0aaa37); // z0a
        values.put(0x7f0abd9d, 0x7f0abd91); // 22i
        values.put(0x7f0acff9, 0x7f0ad00d); // 5zd
        values.put(0x7f0ad00f, 0x7f0ad01a); // 5zq
        values.put(0x7f0ad2af, 0x7f0ad2af); // user_avatar
        values.put(0x7f0ad39e, 0x7f0ad3a6); // 6h0
        values.put(0x7f0ad428, 0x7f0ad431); // 6ld
        values.put(0x7f0ad429, 0x7f0ad432); // 6le
        return Collections.unmodifiableMap(values);
    }

    private static Map<Integer, Integer> legacyIds() {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        values.put(0x7f0d1b57, 0x7f0d1b61);
        values.put(0x7f0a577c, 0x7f0a5774);
        values.put(0x7f0a6a1e, 0x7f0a6a27);
        values.put(0x7f0a9dcc, 0x7f0a9daf);
        values.put(0x7f083b0a, 0x7f083ab1);
        values.put(0x7f083b0d, 0x7f083ab4);
        values.put(0x7f08415e, 0x7f084101);
        values.put(0x7f08430a, 0x7f0842a7);
        values.put(0x7f08439b, 0x7f084338);
        values.put(0x7f084486, 0x7f084422);
        values.put(0x7f0844a9, 0x7f084445);
        values.put(0x7f0844b1, 0x7f08444d);
        values.put(0x7f0845cd, 0x7f08456a);
        values.put(0x7f0845d2, 0x7f08456f);
        values.put(0x7f0a0f00, 0x7f0a0ef3);
        values.put(0x7f0a10e6, 0x7f0a10d9);
        values.put(0x7f0a1160, 0x7f0a114d);
        values.put(0x7f0a15bd, 0x7f0a15b1);
        values.put(0x7f0a1f69, 0x7f0a1f62);
        values.put(0x7f0a202d, 0x7f0a2026);
        values.put(0x7f0a2042, 0x7f0a203b);
        values.put(0x7f0a205b, 0x7f0a2054);
        values.put(0x7f0a2063, 0x7f0a205c);
        values.put(0x7f0a22c4, 0x7f0a22c2);
        values.put(0x7f0a2396, 0x7f0a2394);
        values.put(0x7f0a2398, 0x7f0a2396);
        values.put(0x7f0a2441, 0x7f0a243f);
        values.put(0x7f0a2515, 0x7f0a2512);
        values.put(0x7f0a2523, 0x7f0a2521);
        values.put(0x7f0a2570, 0x7f0a256e);
        values.put(0x7f0a2596, 0x7f0a2594);
        values.put(0x7f0a2655, 0x7f0a2650);
        values.put(0x7f0a2785, 0x7f0a2781);
        values.put(0x7f0a30f7, 0x7f0a3100);
        values.put(0x7f0a3103, 0x7f0a310c);
        values.put(0x7f0a3104, 0x7f0a310d);
        values.put(0x7f0a310b, 0x7f0a3114);
        values.put(0x7f0a3118, 0x7f0a3121);
        values.put(0x7f0a3fd6, 0x7f0a3fc5);
        values.put(0x7f0a3fdb, 0x7f0a3fc8);
        values.put(0x7f0a3ff3, 0x7f0a3fe1);
        values.put(0x7f0a409c, 0x7f0a408b);
        values.put(0x7f0a40c9, 0x7f0a40b7);
        values.put(0x7f0a40cb, 0x7f0a40b9);
        values.put(0x7f0a4258, 0x7f0a4246);
        values.put(0x7f0a4631, 0x7f0a4621);
        values.put(0x7f0a463c, 0x7f0a462d);
        values.put(0x7f0a475e, 0x7f0a474f);
        values.put(0x7f0a475f, 0x7f0a4750);
        values.put(0x7f0a5146, 0x7f0a5158);
        values.put(0x7f0a5f7e, 0x7f0a5f8a);
        values.put(0x7f0a68ae, 0x7f0a68b7);
        values.put(0x7f0a6a13, 0x7f0a6a1c);
        values.put(0x7f0a7afe, 0x7f0a7b08);
        values.put(0x7f0a7aff, 0x7f0a7b09);
        values.put(0x7f0a7d27, 0x7f0a7d32);
        values.put(0x7f0a7ddb, 0x7f0a7de7);
        values.put(0x7f0a7ddf, 0x7f0a7dea);
        values.put(0x7f0a7ea7, 0x7f0a7eb1);
        values.put(0x7f0a7eac, 0x7f0a7eb6);
        values.put(0x7f0a805d, 0x7f0a8067);
        values.put(0x7f0a8189, 0x7f0a8192);
        values.put(0x7f0a9c4d, 0x7f0a9c33);
        values.put(0x7f0a9dbe, 0x7f0a9da1);
        values.put(0x7f0a9dc6, 0x7f0a9dab);
        values.put(0x7f0aa977, 0x7f0aa968);
        values.put(0x7f0aaa4f, 0x7f0aaa3e);
        values.put(0x7f0ab9a8, 0x7f0ab99c);
        values.put(0x7f0abd9d, 0x7f0abd99);
        values.put(0x7f0acff9, 0x7f0ad015);
        values.put(0x7f0ad00f, 0x7f0ad022);
        values.put(0x7f0ad39e, 0x7f0ad3af);
        values.put(0x7f0ad428, 0x7f0ad439);
        values.put(0x7f0ad429, 0x7f0ad43a);
        values.put(0x7f0d194a, 0x7f0d1954);
        values.put(0x7f0d194b, 0x7f0d1955);
        values.put(0x7f0d194c, 0x7f0d1956);
        return Collections.unmodifiableMap(values);
    }

    private static Map<Integer, Integer> canonicalIds() { return invert(LEGACY_IDS); }

    private static Map<Integer, Integer> invert(Map<Integer, Integer> source) {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : source.entrySet()) {
            if (values.put(entry.getValue(), entry.getKey()) != null) {
                throw new IllegalStateException("Duplicate host resource identifier");
            }
        }
        return Collections.unmodifiableMap(values);
    }
}

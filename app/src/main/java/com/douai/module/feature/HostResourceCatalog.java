package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.view.View;

import com.douai.module.scan.HostAdaptation;

import java.util.Map;

/** Resource identifiers verified against the Douyin 40.6.0 resource table. */
final class HostResourceCatalog {
    static final String VERIFIED_PACKAGE = HostAdaptation.TARGET_PACKAGE;
    static final String VERIFIED_VERSION_NAME = HostAdaptation.VERIFIED_VERSION_NAME;
    private static final Map<Integer, String> NAMES = HostAdaptation.getFeedResources();

    private HostResourceCatalog() { }

    static boolean matches(int id, Resources resources) {
        if (resources == null) return false;
        String expected = NAMES.get(id);
        if (expected == null) return false;
        try {
            return matches(id, resources.getResourcePackageName(id), resources.getResourceEntryName(id));
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
    }

    static boolean matches(int id, String resourceName) {
        return resourceName != null && resourceName.equals(NAMES.get(id));
    }

    static boolean matches(int id, String packageName, String resourceName) {
        return VERIFIED_PACKAGE.equals(packageName) && matches(id, resourceName);
    }

    static String expectedName(int id) { return NAMES.get(id); }

    @SuppressLint("ResourceType")
    static View findVerifiedView(View root, int id) {
        if (root == null) return null;
        View view = root.findViewById(id);
        return view != null && matches(id, view.getResources()) ? view : null;
    }

}

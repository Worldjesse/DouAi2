package com.douai.module.feature;

import org.junit.Test;

import static org.junit.Assert.*;

public final class HostResourceCatalogTest {
    @Test public void expected40_6ResourceNamesConfirmTheirIdentifiers() {
        assertTrue(HostResourceCatalog.matches(2131369826, HostResourceCatalog.VERIFIED_PACKAGE, "d5s"));
        assertFalse(HostResourceCatalog.matches(2131369826, "com.douai.module", "d5s"));
        assertTrue(HostResourceCatalog.matches(2131374336, "gw+"));
        assertTrue(HostResourceCatalog.matches(2131371282, "comment_container"));
        assertTrue(HostResourceCatalog.matches(2131401927, "share_container"));
        assertTrue(HostResourceCatalog.matches(2131415727, "user_avatar"));
        assertTrue(HostResourceCatalog.matches(2131378315, HostResourceCatalog.VERIFIED_PACKAGE, "jb1"));
        assertTrue(HostResourceCatalog.matches(2131378359, HostResourceCatalog.VERIFIED_PACKAGE, "jc5"));
        assertTrue(HostResourceCatalog.matches(2131378361, HostResourceCatalog.VERIFIED_PACKAGE, "jc7"));
        assertFalse(HostResourceCatalog.matches(2131378315, "com.douai.module", "jb1"));
        assertFalse(HostResourceCatalog.matches(2131374336, "com.douai.module", "gw+"));
        assertTrue(HostResourceCatalog.matches(2131374336, HostResourceCatalog.VERIFIED_PACKAGE, "gw+"));
    }

    @Test public void changedOrUnknownResourceNamesCannotConfirmAnIdentifier() {
        assertFalse(HostResourceCatalog.matches(2131374336, "digg"));
        assertFalse(HostResourceCatalog.matches(2131374336, (String) null));
        assertFalse(HostResourceCatalog.matches(0x7f0a9734, "like"));
    }
}

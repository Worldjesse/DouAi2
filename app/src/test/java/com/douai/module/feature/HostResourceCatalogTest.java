package com.douai.module.feature;

import org.junit.Test;

import static org.junit.Assert.*;

public final class HostResourceCatalogTest {
    @Test public void expected40_6ResourceNamesConfirmTheirIdentifiers() {
        assertTrue(HostResourceCatalog.matches(2131374336, "gw+"));
        assertTrue(HostResourceCatalog.matches(2131371282, "comment_container"));
        assertTrue(HostResourceCatalog.matches(2131401927, "share_container"));
        assertTrue(HostResourceCatalog.matches(2131415727, "user_avatar"));
        assertFalse(HostResourceCatalog.matches(2131374336, "com.douai.module", "gw+"));
        assertTrue(HostResourceCatalog.matches(2131374336, HostResourceCatalog.VERIFIED_PACKAGE, "gw+"));
    }

    @Test public void changedOrUnknownResourceNamesCannotConfirmAnIdentifier() {
        assertFalse(HostResourceCatalog.matches(2131374336, "digg"));
        assertFalse(HostResourceCatalog.matches(2131374336, (String) null));
        assertFalse(HostResourceCatalog.matches(0x7f0a9734, "like"));
    }
}

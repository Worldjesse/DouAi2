package com.douai.module.feature;

import org.junit.Test;

import static org.junit.Assert.*;

public final class HostResourceCatalogTest {
    @Test public void expected40_8ResourceNamesConfirmTheirIdentifiers() {
        assertTrue(HostResourceCatalog.matches(2131369833, HostResourceCatalog.VERIFIED_PACKAGE, "d5w"));
        assertFalse(HostResourceCatalog.matches(2131369833, "com.douai.module", "d5w"));
        assertTrue(HostResourceCatalog.matches(2131374327, "gw1"));
        assertTrue(HostResourceCatalog.matches(2131371285, "comment_container"));
        assertTrue(HostResourceCatalog.matches(2131401927, "share_container"));
        assertTrue(HostResourceCatalog.matches(2131415727, "user_avatar"));
        assertTrue(HostResourceCatalog.matches(2131378332, HostResourceCatalog.VERIFIED_PACKAGE, "jcb"));
        assertTrue(HostResourceCatalog.matches(2131378377, HostResourceCatalog.VERIFIED_PACKAGE, "jdf"));
        assertTrue(HostResourceCatalog.matches(2131378379, HostResourceCatalog.VERIFIED_PACKAGE, "jdh"));
        assertFalse(HostResourceCatalog.matches(2131378332, "com.douai.module", "jcb"));
        assertFalse(HostResourceCatalog.matches(2131374327, "com.douai.module", "gw1"));
        assertTrue(HostResourceCatalog.matches(2131374327, HostResourceCatalog.VERIFIED_PACKAGE, "gw1"));
    }

    @Test public void changedOrUnknownResourceNamesCannotConfirmAnIdentifier() {
        assertFalse(HostResourceCatalog.matches(2131374327, "digg"));
        assertFalse(HostResourceCatalog.matches(2131374327, (String) null));
        assertFalse(HostResourceCatalog.matches(0x7f0a9734, "like"));
    }

    @Test public void oldHostIdentifiersAndOldNamesCannotSelectTheMigratedActions() {
        assertFalse(HostResourceCatalog.matches(2131374336, "gw+"));
        assertFalse(HostResourceCatalog.matches(2131379757, "j_l"));
        assertFalse(HostResourceCatalog.matches(2131374327, "gw+"));
        assertFalse(HostResourceCatalog.matches(2131379772, "j_l"));
    }
}

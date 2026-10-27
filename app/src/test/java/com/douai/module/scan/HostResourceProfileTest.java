package com.douai.module.scan;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostResourceProfileTest {
    @Test public void build405UsesItsOwnResourceTableAndRefusesUnverifiedHostClasses() {
        assertEquals(0x7f0a1f5d, HostResourceProfile.id(HostAdaptation.PROFILE_405, 0x7f0a1f69));
        assertEquals(0x7f0a1f69, HostResourceProfile.canonicalId(HostAdaptation.PROFILE_405, 0x7f0a1f5d));
        // Entries without 40.5.0 evidence stay identity-mapped instead of borrowing another build's id.
        assertEquals(0x7f0a3fd6, HostResourceProfile.id(HostAdaptation.PROFILE_405, 0x7f0a3fd6));
        assertEquals("LJJ", HostResourceProfile.viewFactory(HostAdaptation.PROFILE_405));
        assertEquals("LJIIIZ", HostResourceProfile.avatarLoader(HostAdaptation.PROFILE_405));
        // Unresolved native-UI class names must never borrow another build's obfuscated name.
        assertEquals("X.DouAiUnadapted405", HostResourceProfile.className(HostAdaptation.PROFILE_405, "X.0YVw"));
        assertNotEquals(HostResourceProfile.className(HostAdaptation.PROFILE_406, "X.0YVw"),
                HostResourceProfile.className(HostAdaptation.PROFILE_405, "X.0YVw"));
    }

    @Test public void semanticIdentifiersResolveSeparatelyForBothOriginalResourceTables() {
        assertEquals(0x7f0a462d, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0a463c));
        assertEquals(0x7f0a463c, HostResourceProfile.id(HostAdaptation.PROFILE_408, 0x7f0a463c));
        assertEquals(0x7f0a463c, HostResourceProfile.canonicalId(HostAdaptation.PROFILE_406, 0x7f0a462d));
        assertEquals(0x7f0a2394, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0a2396));
        assertEquals(0x7f0a2398, HostResourceProfile.canonicalId(HostAdaptation.PROFILE_406, 0x7f0a2396));
        assertEquals(0x7f0d1954, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0d194a));
        assertEquals(0x7f08444d, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0844b1));
        assertEquals(0x7f060094, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f060094));
    }

    @Test public void reflectionAliasesAreBoundToTheSelectedOriginalHost() {
        assertEquals("X.0s7o", HostResourceProfile.className(HostAdaptation.PROFILE_406, "X.0YVw"));
        assertEquals("X.0TzN", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.0YVw"));
        assertEquals("X.131D", HostResourceProfile.className(HostAdaptation.PROFILE_406, "X.1UOy"));
        assertEquals("X.1Cd0", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.1UOy"));
        assertEquals("X.1fyy", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.0vM9"));
        assertEquals("X.133H", HostResourceProfile.className(HostAdaptation.PROFILE_406, "X.1U1O"));
        assertEquals("X.1Cue", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.1U1O"));
        assertEquals("LJJIIJ", HostResourceProfile.viewFactory(HostAdaptation.PROFILE_406));
        assertEquals("LJJIIJZLJL", HostResourceProfile.viewFactory(HostAdaptation.PROFILE_408));
        assertEquals("LJIIIZ", HostResourceProfile.avatarLoader(HostAdaptation.PROFILE_406));
        assertEquals("LJIIJ", HostResourceProfile.avatarLoader(HostAdaptation.PROFILE_408));
        assertThrows(IllegalArgumentException.class, () -> HostResourceProfile.id(null, 0x7f0a463c));
    }
}

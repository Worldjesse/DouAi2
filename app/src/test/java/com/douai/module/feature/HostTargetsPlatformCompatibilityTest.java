package com.douai.module.feature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {28, 33})
public final class HostTargetsPlatformCompatibilityTest {
    public static final class HostModel {
        public String state = "before";
        public String getState() { return state; }
    }

    @Test public void resolvesFieldsMethodsAndMissingMembersOnTheSupportedOlderPlatforms() throws Exception {
        HostModel owner = new HostModel();
        assertEquals("before", HostTargets.read(owner, "state"));
        assertEquals("before", HostTargets.invoke(owner, "getState"));
        owner.state = "after";
        assertEquals("after", HostTargets.field(HostModel.class, "state").get(owner));
        assertEquals("after", HostTargets.invoke(owner, "getState"));
        assertNull(HostTargets.read(owner, "absent"));
        assertNull(HostTargets.invoke(owner, "absent"));
        assertThrows(NoSuchFieldException.class, () -> HostTargets.field(HostModel.class, "absent"));
    }
}

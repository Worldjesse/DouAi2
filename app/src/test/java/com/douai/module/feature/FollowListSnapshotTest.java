package com.douai.module.feature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FollowListSnapshotTest {
    @Before public void setUp() { FollowListSnapshot.reset(); }
    @After public void tearDown() { FollowListSnapshot.reset(); }

    @Test public void eachListBecomesKnownOnlyWhenItsCompletedSetIsPublished() {
        FollowListSnapshot.activateAccount("42");
        FollowListSnapshot.Refresh following = FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING);
        FollowListSnapshot.Refresh followers = FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWERS);
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertFalse(FollowListSnapshot.current().followersKnown);
        assertTrue(FollowListSnapshot.publish(following, Arrays.asList("100", "101")));
        FollowListSnapshot.State first = FollowListSnapshot.current();
        assertTrue(first.followingKnown);
        assertFalse(first.followersKnown);
        assertEquals(2, first.following.size());
        assertTrue(first.followingUpdatedAt > 0);
        assertEquals(0, first.followersUpdatedAt);
        assertTrue(FollowListSnapshot.publish(followers, Collections.singleton("200")));
        assertTrue(FollowListSnapshot.current().followersKnown);
        assertTrue(FollowListSnapshot.current().followersUpdatedAt > 0);
        assertFalse(first.followersKnown);
    }

    @Test public void successfulEmptyListIsKnownAndDefensivelyCopied() {
        FollowListSnapshot.activateAccount("42");
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWERS),
                Collections.emptyList()));
        assertTrue(FollowListSnapshot.current().followersKnown);
        List<String> uids = new ArrayList<>(Arrays.asList(" 100 ", "100", "101"));
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING), uids));
        uids.clear();
        assertEquals(2, FollowListSnapshot.current().following.size());
        try {
            FollowListSnapshot.current().following.add("102");
            fail("Published lists must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void switchingAccountsInvalidatesBothListsAndOldAccountFetches() {
        FollowListSnapshot.activateAccount("42");
        FollowListSnapshot.Refresh inFlight = FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWERS);
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("100")));
        long oldEpoch = FollowListSnapshot.current().accountEpoch;
        FollowListSnapshot.activateAccount("43");
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertFalse(FollowListSnapshot.current().followersKnown);
        assertTrue(FollowListSnapshot.current().following.isEmpty());
        assertTrue(FollowListSnapshot.current().accountEpoch > oldEpoch);
        assertFalse(FollowListSnapshot.publish(inFlight, Collections.singleton("200")));
        FollowListSnapshot.activateAccount("42");
        assertFalse(FollowListSnapshot.publish(inFlight, Collections.singleton("200")));
    }

    @Test public void activatingSameAccountRetainsTheCompletedLists() {
        FollowListSnapshot.activateAccount("42");
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("100")));
        FollowListSnapshot.State before = FollowListSnapshot.current();
        assertSame(before, FollowListSnapshot.activateAccount(" 42 "));
    }

    @Test public void latestRefreshWinsAndFailedRefreshRetainsPreviousCompletedSnapshot() {
        FollowListSnapshot.activateAccount("42");
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("100")));
        FollowListSnapshot.State before = FollowListSnapshot.current();
        FollowListSnapshot.Refresh older = FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING);
        FollowListSnapshot.Refresh newer = FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING);
        assertSame(before, FollowListSnapshot.current());
        assertFalse(FollowListSnapshot.publish(older, Collections.singleton("101")));
        assertFalse(FollowListSnapshot.publish(newer, Arrays.asList("101", "not-a-uid")));
        assertSame(before, FollowListSnapshot.current());
        assertTrue(FollowListSnapshot.publish(newer, Collections.singleton("102")));
        assertEquals(Collections.singleton("102"), FollowListSnapshot.current().following);
        assertFalse(FollowListSnapshot.publish(newer, Collections.singleton("103")));
    }

    @Test public void invalidatingOneListPreservesTheOtherAndRejectsInFlightRefresh() {
        FollowListSnapshot.activateAccount("42");
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("100")));
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWERS),
                Collections.singleton("200")));
        FollowListSnapshot.Refresh refresh = FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING);
        FollowListSnapshot.invalidate(FollowListSnapshot.Kind.FOLLOWING);
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertTrue(FollowListSnapshot.current().followersKnown);
        assertEquals(Collections.singleton("200"), FollowListSnapshot.current().followers);
        assertFalse(FollowListSnapshot.publish(refresh, Collections.singleton("101")));
    }

    @Test public void unsignedOrWrongAccountCannotBeginOrPublishRefresh() {
        assertNull(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING));
        FollowListSnapshot.activateAccount("42");
        assertNull(FollowListSnapshot.beginRefresh("43", FollowListSnapshot.Kind.FOLLOWING));
        FollowListSnapshot.activateAccount("0");
        assertTrue(FollowListSnapshot.current().active);
        assertEquals("", FollowListSnapshot.current().accountUid);
        assertNull(FollowListSnapshot.beginRefresh("0", FollowListSnapshot.Kind.FOLLOWING));
        assertFalse(FollowListSnapshot.publish(null, Collections.emptyList()));
    }
}

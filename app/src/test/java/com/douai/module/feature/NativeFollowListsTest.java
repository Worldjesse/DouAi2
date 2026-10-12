package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class NativeFollowListsTest {
    private final AtomicReference<NativeFollowLists.Account> account = new AtomicReference<>();
    private final List<FollowListPager.Kind> requests = new ArrayList<>();
    private NativeFollowLists service;

    @Before public void setup() {
        FollowListSnapshot.reset();
        account.set(new NativeFollowLists.Account("42", "sec42"));
    }

    @After public void cleanup() {
        if (service != null) service.close();
        FollowListSnapshot.reset();
    }

    private void service(LocalOptions options, FollowListPager.Fetcher source) throws Exception {
        service = new NativeFollowLists(account::get, (kind, query) -> {
            requests.add(kind);
            return source.fetch(kind, query);
        }, ignored -> { });
        HostTargets.field(NativeFollowLists.class, "active").set(null, service);
        HostTargets.field(NativeFollowLists.class, "options").set(service, options);
    }

    @Test public void selectedFollowingLoadsActualUsersAndReusesCompletedList() throws Exception {
        service(options("comment_scope_mode", "whitelist", "comment_scope_relations", "following"),
                (kind, query) -> new Page(new User("100", "Alice", 1, 0)));
        service.refreshNow(false);
        assertEquals(Collections.singletonList(FollowListPager.Kind.FOLLOWING), requests);
        assertEquals(Collections.singleton("100"), FollowListSnapshot.current().following);
        assertFalse(FollowListSnapshot.current().followersKnown);
        assertEquals("Alice", NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).get("100"));
        assertEquals("Alice", AutomationScopePolicy.cachedName("100"));
        assertTrue(AutomationScopePolicy.allows("comment", options("comment_scope_mode", "whitelist",
                "comment_scope_relations", "following"), "100", "", "", "", null));
        service.refreshNow(false);
        assertEquals(1, requests.size());
    }

    @Test public void strangersRequireBothListsAndManualRefreshAlsoLoadsBoth() throws Exception {
        service(options("reply_scope_mode", "blacklist", "reply_scope_relations", "strangers"),
                (kind, query) -> new Page(new User(kind == FollowListPager.Kind.FOLLOWING ? "100" : "200", "Name", 1, 1)));
        service.refreshNow(false);
        assertEquals(Arrays.asList(FollowListPager.Kind.FOLLOWING, FollowListPager.Kind.FOLLOWERS), requests);
        assertTrue(AutomationScopePolicy.cached("300").stranger);
        service.refreshNow(true);
        assertEquals(4, requests.size());
        assertTrue(NativeFollowLists.statusText().contains("1 人"));
    }

    @Test public void failedFollowerFetchDoesNotInvalidateSuccessfullyLoadedFollowing() throws Exception {
        service(options("comment_scope_mode", "whitelist", "comment_scope_relations", "strangers"),
                (kind, query) -> {
                    if (kind == FollowListPager.Kind.FOLLOWERS) throw new IllegalStateException("network");
                    return new Page(new User("100", "Alice", 1, 0));
                });
        service.refreshNow(false);
        assertTrue(FollowListSnapshot.current().followingKnown);
        assertFalse(FollowListSnapshot.current().followersKnown);
        assertFalse(AutomationScopePolicy.cached("300").stranger);
        assertTrue(NativeFollowLists.statusText().contains("拉取失败"));
        service.refreshNow(false);
        assertEquals(2, requests.size());
    }

    @Test public void switchedAccountDuringFetchCannotPublishOrReuseOldMembership() throws Exception {
        service(options("comment_scope_mode", "whitelist", "comment_scope_relations", "following"),
                (kind, query) -> {
                    account.set(new NativeFollowLists.Account("43", "sec43"));
                    NativeFollowLists.ensureAccount("43");
                    return new Page(new User("100", "Previous account", 1, 0));
                });
        service.refreshNow(false);
        assertEquals("43", FollowListSnapshot.current().accountUid);
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertTrue(NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).isEmpty());
    }

    @Test public void logoutClearsLoadedNamesAndListsImmediately() throws Exception {
        service(options(), (kind, query) -> new Page(new User("100", "Alice", 1, 1)));
        service.refreshNow(true);
        account.set(new NativeFollowLists.Account("", ""));
        NativeFollowLists.ensureAccount("0");
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertTrue(NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).isEmpty());
        assertEquals("未登录抖音账号", NativeFollowLists.statusText());
    }

    @Test public void staleMessagingAccountCannotChangeWhichAccountOwnsTheLists() throws Exception {
        service(options(), (kind, query) -> new Page(new User("100", "Alice", 1, 1)));
        service.refreshNow(true);
        assertFalse(NativeFollowLists.ensureAccount("41"));
        assertEquals("42", FollowListSnapshot.current().accountUid);
        assertTrue(FollowListSnapshot.current().followingKnown);
        assertTrue(NativeFollowLists.ensureAccount("42"));
    }

    @Test public void expiredListBecomesUnknownUntilRefetched() throws Exception {
        service(options("comment_scope_mode", "blacklist", "comment_scope_relations", "following"),
                (kind, query) -> new Page(new User("100", "Alice", 1, 0)));
        service.refreshNow(false);
        long expiry = FollowListSnapshot.current().followingUpdatedAt + NativeFollowLists.TTL_MS;
        java.lang.reflect.Method expire = NativeFollowLists.class.getDeclaredMethod("expire", long.class);
        expire.setAccessible(true);
        expire.invoke(service, expiry);
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertFalse(AutomationScopePolicy.allows("comment", options("comment_scope_mode", "blacklist",
                "comment_scope_relations", "following"), "100", "", "", "", null));
        service.refreshNow(true);
        assertTrue(FollowListSnapshot.current().followingKnown);
    }

    @Test public void freshCompleteSnapshotIsPreservedOnManualRefreshFailure() throws Exception {
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        service(options(), (kind, query) -> {
            if (fail.get()) throw new IllegalStateException("network");
            return new Page(new User("100", "Alice", 1, 1));
        });
        service.refreshNow(true);
        fail.set(true);
        service.refreshNow(true);
        assertTrue(FollowListSnapshot.current().followingKnown);
        assertEquals("Alice", NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).get("100"));
        assertTrue(NativeFollowLists.statusText().contains("拉取失败"));
    }

    private static LocalOptions options(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return new LocalOptions(values);
    }

    static final class Page {
        public int status_code;
        public int error_code;
        public boolean hasMore;
        public List<User> items;
        Page(User... users) { items = Arrays.asList(users); }
    }

    static final class User {
        public String uid;
        public String nickname;
        public int followStatus;
        public int followerStatus;
        User(String uid, String name, int follow, int follower) {
            this.uid = uid; nickname = name; followStatus = follow; followerStatus = follower;
        }
    }
}

package com.douai.module.feature;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Complete relationship lists for the current signed-in host account. */
public final class FollowListSnapshot {
    public enum Kind { FOLLOWING, FOLLOWERS }

    private static long accountEpoch;
    private static long followingRevision;
    private static long followersRevision;
    private static volatile State state = empty("", 0, false);

    private FollowListSnapshot() { }

    public static synchronized State activateAccount(String accountUid) {
        String uid = normalizeUid(accountUid);
        if (!state.active || !state.accountUid.equals(uid)) {
            state = empty(uid, ++accountEpoch, true);
            followingRevision++;
            followersRevision++;
        }
        return state;
    }

    public static State current() { return state; }

    /** A failed fetch leaves the previous complete list intact. */
    public static synchronized Refresh beginRefresh(String accountUid, Kind kind) {
        String uid = normalizeUid(accountUid);
        if (!state.active || uid.isEmpty() || !state.accountUid.equals(uid) || kind == null) return null;
        long revision = kind == Kind.FOLLOWING ? ++followingRevision : ++followersRevision;
        return new Refresh(uid, state.accountEpoch, kind, revision);
    }

    /** Call only after every page was fetched successfully. Stale fetches cannot publish. */
    public static synchronized boolean publish(Refresh refresh, Collection<String> allUids) {
        if (!isCurrent(refresh) || allUids == null) return false;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : allUids) {
            String uid = normalizeUid(value);
            if (uid.isEmpty()) return false;
            normalized.add(uid);
        }
        Set<String> complete = Collections.unmodifiableSet(normalized);
        long now = System.currentTimeMillis();
        State old = state;
        if (refresh.kind == Kind.FOLLOWING) {
            state = new State(old.accountUid, old.accountEpoch, true, complete, old.followers,
                    true, old.followersKnown, now, old.followersUpdatedAt);
            followingRevision++;
        } else {
            state = new State(old.accountUid, old.accountEpoch, true, old.following, complete,
                    old.followingKnown, true, old.followingUpdatedAt, now);
            followersRevision++;
        }
        return true;
    }

    public static synchronized void invalidate(Kind kind) {
        State old = state;
        if (kind == Kind.FOLLOWING) {
            followingRevision++;
            state = new State(old.accountUid, old.accountEpoch, old.active, Collections.emptySet(), old.followers,
                    false, old.followersKnown, 0, old.followersUpdatedAt);
        } else if (kind == Kind.FOLLOWERS) {
            followersRevision++;
            state = new State(old.accountUid, old.accountEpoch, old.active, old.following, Collections.emptySet(),
                    old.followingKnown, false, old.followingUpdatedAt, 0);
        }
    }

    public static synchronized void reset() {
        state = empty("", ++accountEpoch, false);
        followingRevision++;
        followersRevision++;
    }

    static String normalizeUid(String value) {
        String uid = value == null ? "" : value.trim();
        return uid.matches("[1-9][0-9]{0,19}") ? uid : "";
    }

    private static boolean isCurrent(Refresh refresh) {
        return refresh != null && state.active && refresh.accountEpoch == state.accountEpoch
                && refresh.accountUid.equals(state.accountUid)
                && refresh.revision == (refresh.kind == Kind.FOLLOWING ? followingRevision : followersRevision);
    }

    private static State empty(String uid, long epoch, boolean active) {
        return new State(uid, epoch, active, Collections.emptySet(), Collections.emptySet(), false, false, 0, 0);
    }

    public static final class Refresh {
        public final String accountUid;
        public final Kind kind;
        private final long accountEpoch;
        private final long revision;

        private Refresh(String accountUid, long accountEpoch, Kind kind, long revision) {
            this.accountUid = accountUid;
            this.accountEpoch = accountEpoch;
            this.kind = kind;
            this.revision = revision;
        }
    }

    public static final class State {
        public final String accountUid;
        public final long accountEpoch;
        public final boolean active;
        public final Set<String> following;
        public final Set<String> followers;
        public final boolean followingKnown;
        public final boolean followersKnown;
        public final long followingUpdatedAt;
        public final long followersUpdatedAt;

        private State(String accountUid, long accountEpoch, boolean active, Set<String> following,
                      Set<String> followers, boolean followingKnown, boolean followersKnown,
                      long followingUpdatedAt, long followersUpdatedAt) {
            this.accountUid = accountUid;
            this.accountEpoch = accountEpoch;
            this.active = active;
            this.following = following;
            this.followers = followers;
            this.followingKnown = followingKnown;
            this.followersKnown = followersKnown;
            this.followingUpdatedAt = followingUpdatedAt;
            this.followersUpdatedAt = followersUpdatedAt;
        }
    }
}

package com.douai.module.feature;

import android.content.ContentValues;
import com.douai.module.config.LocalOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared allow/deny scope rules for captured works and incoming message authors. */
public final class AutomationScopePolicy {
    public static final String ALL = "all";
    public static final String WHITELIST = "whitelist";
    public static final String BLACKLIST = "blacklist";
    public static final String FOLLOWING = "following";
    public static final String FOLLOWERS = "followers";
    public static final String STRANGERS = "strangers";
    public static final String AUTHOR = "author";
    public static final String CAPTION = "caption";
    public static final String TITLE = "title";
    private static final int MAX_CACHED_USERS = 2000;
    private static final long CACHE_TTL_MS = 6L * 60 * 60 * 1000;
    private static final Map<String, CachedRelation> RELATIONS = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedRelation>(256, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CachedRelation> entry) {
                    return size() > MAX_CACHED_USERS;
                }
            });

    private AutomationScopePolicy() { }

    public static boolean allows(String prefix, LocalOptions options, String uid, String author,
                                 String caption, String title, Relation relation) {
        String mode = options.text(prefix + "_scope_mode", "").trim();
        if (mode.isEmpty()) {
            if ("reply".equals(prefix)) mode = options.enabled("reply_global") ? ALL : WHITELIST;
            else mode = ALL;
        }
        if (ALL.equals(mode)) return true;
        if (!WHITELIST.equals(mode) && !BLACKLIST.equals(mode)) return false;

        String usersKey = "reply".equals(prefix) ? "reply_targets" : prefix + "_scope_users";
        boolean match = targets(options.text(usersKey, "")).contains(normalizeUid(uid));
        match |= keywordMatch(options.text(prefix + "_scope_keywords", ""),
                options.text(prefix + "_scope_fields", ""), author, caption, title);
        if (match) return WHITELIST.equals(mode);
        Set<String> selectedRelations = tokens(options.text(prefix + "_scope_relations", ""));
        FollowListSnapshot.State lists = FollowListSnapshot.current();
        Relation value = lists.active ? fromLists(lists, uid)
                : relation == null ? Relation.unknown() : relation;
        match |= selectedRelations.contains(FOLLOWING) && value.following;
        match |= selectedRelations.contains(FOLLOWERS) && value.followsMe;
        match |= selectedRelations.contains(STRANGERS) && value.stranger;
        if (!match && (selectedRelations.contains(FOLLOWING) && !value.followingKnown
                || selectedRelations.contains(FOLLOWERS) && !value.followersKnown
                || selectedRelations.contains(STRANGERS) && !value.known)) return false;
        return WHITELIST.equals(mode) ? match : !match;
    }

    public static Relation fromUser(Object user) {
        if (user == null) return Relation.unknown();
        FollowListSnapshot.State lists = FollowListSnapshot.current();
        if (lists.active) return fromLists(lists, String.valueOf(modelValue(user, "uid", "getUid")));
        Object followValue = modelValue(user, "follow_status", "getFollowStatus");
        Object followerValue = modelValue(user, "follower_status", "getFollowerStatus");
        if (!(followValue instanceof Number) || !(followerValue instanceof Number)) return Relation.unknown();
        int follow = ((Number) followValue).intValue();
        int follower = ((Number) followerValue).intValue();
        if (follow < 0 || follow > 2 || follower < 0 || follower > 1) return Relation.unknown();
        return new Relation(true, follow == 1 || follow == 2,
                follower == 1 || follow == 2, follow == 0 && follower == 0);
    }

    public static Relation fromIncoming(ContentValues values, long sender) {
        FollowListSnapshot.State lists = FollowListSnapshot.current();
        if (lists.active) return fromLists(lists, Long.toString(sender));
        if (values != null) {
            Integer follow = integer(values, "follow_status");
            Integer follower = integer(values, "follower_status");
            if (follow != null && follower != null) return relation(follow, follower);
            String ext = values.getAsString("ext");
            if (ext != null) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(ext);
                    follow = json.has("follow_status") ? json.optInt("follow_status", -1) : null;
                    follower = json.has("follower_status") ? json.optInt("follower_status", -1) : null;
                    if (follow != null && follower != null) return relation(follow, follower);
                } catch (org.json.JSONException | RuntimeException ignored) { }
            }
        }
        return cached(Long.toString(sender));
    }

    public static void rememberUser(Object user) {
        if (user == null) return;
        FollowListSnapshot.State lists = FollowListSnapshot.current();
        String uid = normalizeUid(String.valueOf(modelValue(user, "uid", "getUid")));
        Relation relation = fromUser(user);
        String name = string(modelValue(user, "nickname", "getNickname"));
        if (!uid.isEmpty()) {
            RELATIONS.put(uid, new CachedRelation(relation, name, System.currentTimeMillis(),
                    lists.accountEpoch));
        }
    }

    public static String cachedName(String uid) {
        String listed = NativeFollowLists.memberName(uid);
        if (!listed.isEmpty()) return listed;
        CachedRelation value = cachedEntry(uid);
        return value == null ? "" : value.name;
    }

    public static Relation cached(String uid) {
        FollowListSnapshot.State lists = FollowListSnapshot.current();
        if (lists.active) return fromLists(lists, uid);
        CachedRelation value = cachedEntry(uid);
        return value == null ? Relation.unknown() : value.relation;
    }

    public static Set<String> targets(String value) { return tokens(value); }

    public static List<String> keywords(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null) return Collections.emptyList();
        for (String item : value.split(",", -1)) {
            String word = item.trim();
            if (!word.isEmpty()) result.add(word);
            if (result.size() == 100) break;
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    static boolean keywordMatch(String rawKeywords, String rawFields,
                                String author, String caption, String title) {
        List<String> words = keywords(rawKeywords);
        if (words.isEmpty()) return false;
        Set<String> fields = tokens(rawFields);
        for (String word : words) {
            String needle = word.toLowerCase(Locale.ROOT);
            if (fields.contains(AUTHOR) && contains(author, needle)
                    || fields.contains(CAPTION) && contains(caption, needle)
                    || fields.contains(TITLE) && contains(title, needle)) return true;
        }
        return false;
    }

    private static Relation relation(int follow, int follower) {
        if (follow < 0 || follow > 2 || follower < 0 || follower > 1) return Relation.unknown();
        return new Relation(true, follow == 1 || follow == 2,
                follower == 1 || follow == 2, follow == 0 && follower == 0);
    }

    private static Relation fromLists(FollowListSnapshot.State lists, String uid) {
        String key = normalizeUid(uid);
        if (key.isEmpty() || lists.accountUid.isEmpty()) return Relation.unknown();
        return new Relation(lists, key);
    }

    private static Integer integer(ContentValues values, String key) {
        try { return values.containsKey(key) ? values.getAsInteger(key) : null; }
        catch (RuntimeException ignored) { return null; }
    }

    private static CachedRelation cachedEntry(String uid) {
        String key = normalizeUid(uid);
        if (key.isEmpty()) return null;
        synchronized (RELATIONS) {
            CachedRelation value = RELATIONS.get(key);
            if (value != null && (value.accountEpoch != FollowListSnapshot.current().accountEpoch
                    || System.currentTimeMillis() - value.createdAt > CACHE_TTL_MS)) {
                RELATIONS.remove(key);
                return null;
            }
            return value;
        }
    }

    private static Object modelValue(Object model, String field, String getter) {
        Object value = DownloadActionHook.jsonValue(model, field);
        return value == null ? HostTargets.invoke(model, getter) : value;
    }

    private static Set<String> tokens(String configured) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (configured == null) return result;
        for (String item : configured.split("[,，\\s]+")) {
            String token = item.trim().toLowerCase(Locale.ROOT);
            if (!token.isEmpty()) result.add(token);
        }
        return result;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String normalizeUid(String value) {
        return FollowListSnapshot.normalizeUid(value);
    }

    private static String string(Object value) { return value instanceof String ? (String) value : ""; }

    private static final class CachedRelation {
        final Relation relation;
        final String name;
        final long createdAt;
        final long accountEpoch;
        CachedRelation(Relation relation, String name, long createdAt, long accountEpoch) {
            this.relation = relation; this.name = name; this.createdAt = createdAt; this.accountEpoch = accountEpoch;
        }
    }

    public static final class Relation {
        public final boolean known;
        public final boolean followingKnown;
        public final boolean followersKnown;
        public final boolean following;
        public final boolean followsMe;
        public final boolean stranger;
        Relation(boolean known, boolean following, boolean followsMe, boolean stranger) {
            this.known = known; this.followingKnown = known; this.followersKnown = known;
            this.following = known && following; this.followsMe = known && followsMe; this.stranger = known && stranger;
        }
        private Relation(FollowListSnapshot.State lists, String uid) {
            this.known = lists.followingKnown && lists.followersKnown;
            this.followingKnown = lists.followingKnown; this.followersKnown = lists.followersKnown;
            this.following = followingKnown && lists.following.contains(uid);
            this.followsMe = followersKnown && lists.followers.contains(uid);
            this.stranger = known && !this.following && !this.followsMe;
        }
        public static Relation unknown() { return new Relation(false, false, false, false); }
    }
}

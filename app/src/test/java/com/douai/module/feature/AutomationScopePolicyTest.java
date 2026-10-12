package com.douai.module.feature;

import android.content.ContentValues;
import com.douai.module.config.LocalOptions;
import com.google.gson.annotations.SerializedName;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AutomationScopePolicyTest {
    @Before public void setUp() { FollowListSnapshot.reset(); }
    @After public void tearDown() { FollowListSnapshot.reset(); }

    private static LocalOptions options(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return new LocalOptions(result);
    }

    @Test public void allScopeKeepsExistingCommentAndReplyBehavior() {
        LocalOptions value = options("comment_scope_mode", "all", "reply_scope_mode", "all");
        assertTrue(AutomationScopePolicy.allows("comment", value, "100", "A", "tea", "title", null));
        assertTrue(AutomationScopePolicy.allows("reply", value, "200", "B", "hello", "", null));
    }

    @Test public void whitelistMatchesSelectedRelationsOrExplicitUsersAndUnknownIsNotStranger() {
        LocalOptions value = options("comment_scope_mode", "whitelist", "comment_scope_relations", "following,strangers",
                "comment_scope_users", "777");
        assertTrue(AutomationScopePolicy.allows("comment", value, "100", "A", "", "",
                new AutomationScopePolicy.Relation(true, true, false, false)));
        assertTrue(AutomationScopePolicy.allows("comment", value, "101", "B", "", "",
                new AutomationScopePolicy.Relation(true, false, false, true)));
        assertFalse(AutomationScopePolicy.allows("comment", value, "102", "C", "", "",
                AutomationScopePolicy.Relation.unknown()));
        assertTrue(AutomationScopePolicy.allows("comment", value, "777", "D", "", "",
                AutomationScopePolicy.Relation.unknown()));
    }

    @Test public void blacklistRejectsAnySelectedRelationUserOrKeyword() {
        LocalOptions value = options("reply_scope_mode", "blacklist", "reply_scope_relations", "followers",
                "reply_scope_fields", "caption", "reply_scope_keywords", "spam,广告", "reply_targets", "300");
        assertFalse(AutomationScopePolicy.allows("reply", value, "200", "A", "spam text", "",
                AutomationScopePolicy.Relation.unknown()));
        assertFalse(AutomationScopePolicy.allows("reply", value, "201", "B", "normal", "",
                new AutomationScopePolicy.Relation(true, false, true, false)));
        assertFalse(AutomationScopePolicy.allows("reply", value, "300", "C", "normal", "",
                AutomationScopePolicy.Relation.unknown()));
        assertFalse(AutomationScopePolicy.allows("reply", value, "202", "D", "normal", "",
                AutomationScopePolicy.Relation.unknown()));
    }

    @Test public void keywordTermsMatchAnySelectedFieldAndCommaWhitespaceIsTrimmed() {
        LocalOptions value = options("comment_scope_mode", "whitelist", "comment_scope_fields", "author,title",
                "comment_scope_keywords", " tea , 旅行, ,猫 ");
        assertTrue(AutomationScopePolicy.allows("comment", value, "1", "白茶日记", "无", "春日旅行",
                AutomationScopePolicy.Relation.unknown()));
        assertFalse(AutomationScopePolicy.allows("comment", value, "2", "作者", "普通文案", "普通标题",
                AutomationScopePolicy.Relation.unknown()));
        assertFalse(AutomationScopePolicy.keywordMatch("tea,猫", "caption", "tea author", "普通文案", "普通标题"));
        assertTrue(AutomationScopePolicy.keywordMatch("tea,猫", "caption", "author", "猫与茶", "普通标题"));
    }

    @Test public void legacyReplyGlobalAndUidWhitelistArePreservedUntilNewScopeIsSaved() {
        LocalOptions global = options("reply_global", true, "reply_targets", "200");
        assertTrue(AutomationScopePolicy.allows("reply", global, "999", "name", "text", "",
                AutomationScopePolicy.Relation.unknown()));
        LocalOptions selected = options("reply_global", false, "reply_targets", "200, 201");
        assertTrue(AutomationScopePolicy.allows("reply", selected, "201", "name", "text", "",
                AutomationScopePolicy.Relation.unknown()));
        assertFalse(AutomationScopePolicy.allows("reply", selected, "999", "name", "text", "",
                AutomationScopePolicy.Relation.unknown()));
    }

    @Test public void hostFollowAndFollowerStatusAreClassifiedAndCachedForPrivateReplyAuthors() {
        ProfileUser friend = new ProfileUser("9001", "朋友", 2, 1);
        AutomationScopePolicy.Relation relation = AutomationScopePolicy.fromUser(friend);
        assertTrue(relation.following);
        assertTrue(relation.followsMe);
        assertFalse(relation.stranger);
        AutomationScopePolicy.rememberUser(friend);
        assertTrue(AutomationScopePolicy.fromIncoming(new ContentValues(), 9001).following);
        assertEquals("朋友", AutomationScopePolicy.cachedName("9001"));
        ProfileUser stranger = new ProfileUser("9002", "路人", 0, 0);
        assertTrue(AutomationScopePolicy.fromUser(stranger).stranger);
        AutomationScopePolicy.Relation followerOnly = AutomationScopePolicy.fromUser(
                new ProfileUser("9004", "关注我的人", 0, 1));
        assertFalse(followerOnly.following);
        assertTrue(followerOnly.followsMe);
        assertFalse(followerOnly.stranger);
    }

    @Test public void incomingRelationColumnsOverrideCacheAndMissingRelationsStayUnknown() {
        ContentValues values = new ContentValues();
        values.put("follow_status", 1);
        values.put("follower_status", 0);
        AutomationScopePolicy.Relation relation = AutomationScopePolicy.fromIncoming(values, 9003);
        assertTrue(relation.known);
        assertTrue(relation.following);
        assertFalse(relation.followsMe);
        assertFalse(AutomationScopePolicy.fromIncoming(new ContentValues(), 999999).known);
    }

    @Test public void followingScopeUsesTheCompleteListAndIgnoresRecentHostStatuses() {
        FollowListSnapshot.activateAccount("42");
        ProfileUser hostSaysFollowing = new ProfileUser("9001", "author", 2, 1);
        AutomationScopePolicy.rememberUser(hostSaysFollowing);
        LocalOptions value = options("comment_scope_mode", "whitelist", "comment_scope_relations", "following");
        assertFalse(AutomationScopePolicy.allows("comment", value, "9001", "author", "", "",
                new AutomationScopePolicy.Relation(true, true, true, false)));
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("9002")));
        assertFalse(AutomationScopePolicy.fromUser(hostSaysFollowing).following);
        assertTrue(AutomationScopePolicy.allows("comment", value, "9002", "author", "", "", null));
        assertFalse(AutomationScopePolicy.allows("comment", value, "9001", "author", "", "", null));
        assertTrue(AutomationScopePolicy.cached("9002").following);
    }

    @Test public void followingAndFollowerScopesWorkIndependentlyWhileStrangersRequireBothLists() {
        FollowListSnapshot.activateAccount("42");
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("100")));
        AutomationScopePolicy.Relation following = AutomationScopePolicy.fromIncoming(new ContentValues(), 100);
        assertTrue(following.followingKnown);
        assertFalse(following.followersKnown);
        assertFalse(following.known);
        assertFalse(following.stranger);
        LocalOptions strangers = options("reply_scope_mode", "whitelist", "reply_scope_relations", "strangers");
        assertFalse(AutomationScopePolicy.allows("reply", strangers, "300", "", "", "", null));
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWERS),
                Collections.singleton("200")));
        assertTrue(AutomationScopePolicy.allows("reply", strangers, "300", "", "", "", null));
        assertFalse(AutomationScopePolicy.allows("reply", strangers, "100", "", "", "", null));
        assertFalse(AutomationScopePolicy.allows("reply", strangers, "200", "", "", "", null));
        FollowListSnapshot.invalidate(FollowListSnapshot.Kind.FOLLOWING);
        LocalOptions followers = options("reply_scope_mode", "whitelist", "reply_scope_relations", "followers");
        assertTrue(AutomationScopePolicy.allows("reply", followers, "200", "", "", "", null));
        assertFalse(AutomationScopePolicy.allows("reply", strangers, "300", "", "", "", null));
    }

    @Test public void blacklistFailsClosedOnlyForAnUnloadedSelectedList() {
        FollowListSnapshot.activateAccount("42");
        LocalOptions following = options("reply_scope_mode", "blacklist", "reply_scope_relations", "following");
        LocalOptions followers = options("reply_scope_mode", "blacklist", "reply_scope_relations", "followers");
        assertFalse(AutomationScopePolicy.allows("reply", following, "200", "", "", "", null));
        assertTrue(FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("100")));
        assertTrue(AutomationScopePolicy.allows("reply", following, "200", "", "", "", null));
        assertFalse(AutomationScopePolicy.allows("reply", following, "100", "", "", "", null));
        assertFalse(AutomationScopePolicy.allows("reply", followers, "200", "", "", "", null));
    }

    @Test public void explicitUidAndKeywordRulesStillDecideWhenRelationshipListsAreUnknown() {
        FollowListSnapshot.activateAccount("42");
        LocalOptions whitelist = options("comment_scope_mode", "whitelist", "comment_scope_relations", "following",
                "comment_scope_users", "100", "comment_scope_fields", "caption", "comment_scope_keywords", "tea");
        assertTrue(AutomationScopePolicy.allows("comment", whitelist, "100", "", "", "", null));
        assertTrue(AutomationScopePolicy.allows("comment", whitelist, "200", "", "tea time", "", null));
        LocalOptions blacklist = options("reply_scope_mode", "blacklist", "reply_scope_fields", "caption",
                "reply_scope_keywords", "spam");
        assertFalse(AutomationScopePolicy.allows("reply", blacklist, "200", "", "spam", "", null));
        assertTrue(AutomationScopePolicy.allows("reply", blacklist, "200", "", "ordinary message", "", null));
    }

    @Test public void activeAccountIgnoresIncomingRelationColumnsAndAccountSwitchClearsCachedNames() {
        FollowListSnapshot.activateAccount("42");
        AutomationScopePolicy.rememberUser(new ProfileUser("9001", "previous account contact", 2, 1));
        assertEquals("previous account contact", AutomationScopePolicy.cachedName("9001"));
        ContentValues values = new ContentValues();
        values.put("follow_status", 2);
        values.put("follower_status", 1);
        assertFalse(AutomationScopePolicy.fromIncoming(values, 9001).known);
        FollowListSnapshot.activateAccount("43");
        assertEquals("", AutomationScopePolicy.cachedName("9001"));
        assertFalse(AutomationScopePolicy.cached("9001").known);
        FollowListSnapshot.activateAccount("");
        assertFalse(AutomationScopePolicy.fromIncoming(values, 9001).known);
    }

    static final class ProfileUser {
        @SerializedName("uid") final String uid;
        @SerializedName("nickname") final String nickname;
        @SerializedName("follow_status") final int followStatus;
        @SerializedName("follower_status") final int followerStatus;
        ProfileUser(String uid, String nickname, int followStatus, int followerStatus) {
            this.uid = uid; this.nickname = nickname; this.followStatus = followStatus; this.followerStatus = followerStatus;
        }
    }
}

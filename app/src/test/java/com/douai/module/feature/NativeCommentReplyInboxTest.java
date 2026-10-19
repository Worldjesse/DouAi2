package com.douai.module.feature;

import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Fake host contracts exercise actual reflected calls and model identity checks. */
public final class NativeCommentReplyInboxTest {
    private final List<String> diagnostics = new ArrayList<>();
    private Api implementation;
    private NativeCommentReplyInbox inbox;

    @Before public void setUp() throws Exception {
        FakeGroupResolver.group = 44;
        FakeGroupResolver.failure = null;
        FakeGroupResolver.calls = 0;
        implementation = new Api();
        Provider.b = implementation;
        AccountFactory.service = new Account();
        AccountFactory.service.user = new User("100");
        inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add);
    }

    @Test public void sparseNoticeRecoversFromVerifiedOwnRootAndTheActualIncomingThreadModel() throws Exception {
        Notice sparse = sparseNotice("sparse", "402", "200", 220);
        Comment raw = sparse.interactiveGeneralNoticeStruct.additionalInfo.comment.a;
        raw.awemeId = null;
        Notice complete = notice("complete", "402", "200", "101", 180);
        Comment fullIncoming = complete.commentNotice.comment;
        fullIncoming.text = "full incoming body";
        fullIncoming.imageList = Collections.singletonList(new CommentImage("https://p3.douyinpic.com/from-thread.jpg"));
        ThreadProbe probe = new ThreadProbe();
        probe.resolve = hints -> Collections.singletonList(new NativeCommentThreadLookup.Match(
                hints.get(0), complete.commentNotice.aweme, fullIncoming, complete.commentNotice.replyComment));
        inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add, probe);
        implementation.pages.add(page(false, 0, 0, sparse));

        List<CommentReplyEvent> events = inbox.scan(100, 210, () -> true);

        assertEquals(1, events.size());
        CommentReplyEvent event = events.get(0);
        assertEquals(1, probe.calls);
        assertEquals("402", probe.hints.get(0).incomingId);
        assertSame(fullIncoming, event.targetComment);
        assertSame(complete.commentNotice.aweme, event.work);
        assertEquals("101", event.rootId);
        assertEquals("own original", event.originalText);
        assertEquals("full incoming body", event.text);
        assertEquals("The notice arrival remains the eligibility time", 220, event.createdAt);
        assertTrue(event.nativeWorkBound);
        assertEquals(220, inbox.scannedThrough());
        assertEquals("0", raw.replyId);
        assertEquals("", raw.replyToReplyId);
        assertNull(sparse.interactiveGeneralNoticeStruct.additionalInfo.comment.e);
    }

    @Test public void threadLookupDoesNotMoveTheWatermarkPastAnUnresolvedNotice() throws Exception {
        ThreadProbe probe = new ThreadProbe();
        Notice complete = notice("complete", "402", "200", "101", 220);
        probe.resolve = hints -> Collections.singletonList(new NativeCommentThreadLookup.Match(
                hints.get(0), complete.commentNotice.aweme, complete.commentNotice.comment, complete.commentNotice.replyComment));
        inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add, probe);
        implementation.pages.add(page(false, 0, 0,
                sparseNotice("first", "402", "200", 220), sparseNotice("later", "403", "201", 221)));

        assertEquals(1, inbox.scan(100, 200, () -> true).size());
        assertEquals("One unresolved arrival at 221 must remain discoverable", 220, inbox.scannedThrough());
    }

    @Test public void threadLookupFailureCannotAcknowledgeTheSparseScanAsCompleted() throws Exception {
        ThreadProbe probe = new ThreadProbe();
        probe.failure = new IllegalStateException("private network payload");
        inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add, probe);
        implementation.pages.add(page(false, 0, 0, sparseNotice("failure", "402", "200", 220)));

        NativeCommentReplyInbox.ScanFailure failure = assertThrows(NativeCommentReplyInbox.ScanFailure.class,
                () -> inbox.scan(100, 200, () -> true));

        assertTrue(NativeCommentReplyInbox.failureDiagnostic(failure).contains("thread_lookup"));
        assertFalse(NativeCommentReplyInbox.failureDiagnostic(failure).contains("private"));
        assertEquals(0, inbox.scannedThrough());
    }

    @Test public void threadLookupFailureKeepsAnAlreadyProvenCanonicalReplyAndHoldsOnlyTheUnresolvedRange() throws Exception {
        ThreadProbe probe = new ThreadProbe();
        probe.failure = new IllegalStateException("private network payload");
        inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add, probe);
        implementation.pages.add(page(false, 0, 0,
                sparseNotice("pending", "402", "200", 220), notice("proven", "403", "201", "102", 230)));

        List<CommentReplyEvent> events = inbox.scan(100, 200, () -> true);

        assertEquals(1, events.size());
        assertEquals("403", events.get(0).incomingId);
        assertEquals(219, inbox.scannedThrough());
        assertTrue(diagnostics.stream().anyMatch(value -> value.contains("reason=thread_lookup")));
        assertFalse(diagnostics.stream().anyMatch(value -> value.contains("private network payload")));
    }

    @Test public void threadLookupRejectsWrongRootDirectionIdentityAndExplicitRecipient() throws Exception {
        for (String conflict : new String[]{"root", "direct", "author", "sender", "recipient", "work", "incoming", "root-is-child", "empty-original"}) {
            Notice complete = notice("complete", "402", "200", "101", 220);
            Comment target = complete.commentNotice.comment;
            Comment own = complete.commentNotice.replyComment;
            if ("root".equals(conflict)) target.replyId = "another-root";
            if ("direct".equals(conflict)) target.replyToReplyId = "somebody-else-child";
            if ("author".equals(conflict)) own.user.uid = "300";
            if ("sender".equals(conflict)) target.user.uid = "100";
            if ("recipient".equals(conflict)) target.replyToUserId = "300";
            if ("work".equals(conflict)) complete.commentNotice.aweme.aid = "another-work";
            if ("incoming".equals(conflict)) target.cid = "another-incoming";
            if ("root-is-child".equals(conflict)) own.replyId = "somebody-else-root";
            if ("empty-original".equals(conflict)) own.text = " ";
            ThreadProbe probe = new ThreadProbe();
            probe.resolve = hints -> Collections.singletonList(new NativeCommentThreadLookup.Match(
                    hints.get(0), complete.commentNotice.aweme, target, own));
            inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add, probe);
            implementation.pages.add(page(false, 0, 0, sparseNotice("conflict", "402", "200", 220)));
            assertTrue(conflict, inbox.scan(100, 200, () -> true).isEmpty());
            assertTrue(conflict, inbox.scannedThrough() < 220);
        }
    }

    @Test public void oldOrExplicitlyOtherSparseNoticesNeverStartAThreadLookup() throws Exception {
        ThreadProbe probe = new ThreadProbe();
        inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add, probe);
        Notice other = sparseNotice("other", "403", "201", 221);
        other.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyToUserId = "300";
        implementation.pages.add(page(false, 0, 0, sparseNotice("old", "402", "200", 199), other));

        assertTrue(inbox.scan(100, 200, () -> true).isEmpty());
        assertEquals(0, probe.calls);
        assertEquals(221, inbox.scannedThrough());
    }

    @Test public void nativeEffectiveCommentUserProvidesOwnAuthorEvidenceWithoutTheRawUserField() throws Exception {
        Notice notice = detailedNotice("effective-user", "402", "200", "101", 220);
        Comment own = notice.interactiveGeneralNoticeStruct.additionalInfo.comment.e;
        own.commentUser = own.user;
        own.user = null;
        Comment target = notice.interactiveGeneralNoticeStruct.additionalInfo.comment.a;
        target.commentUser = target.user;
        target.user = null;
        assertNotNull(inbox.extract(notice, 100));
    }

    @Test public void reflectedReadOnlyCallUsesBothNativeCursorsAndReturnsRepliesInTimeOrder() throws Exception {
        Notice newest = notice("latest", "302", "200", "101", 210);
        Notice older = notice("older", "301", "201", "101", 190);
        implementation.pages.add(page(true, 220, 200, newest));
        implementation.pages.add(page(false, 220, 180, older));
        List<CommentReplyEvent> events = inbox.scan(100, 180, () -> true);
        assertEquals(2, events.size());
        assertEquals("301", events.get(0).incomingId);
        assertEquals("302", events.get(1).incomingId);
        assertEquals(0L, implementation.calls.get(0)[0]);
        assertEquals(0L, implementation.calls.get(0)[1]);
        assertEquals(220L, implementation.calls.get(1)[0]);
        assertEquals(200L, implementation.calls.get(1)[1]);
        Object[] arguments = implementation.calls.get(0);
        assertEquals(NativeCommentReplyInbox.PAGE_SIZE, arguments[2]);
        assertEquals(FakeGroupResolver.group, arguments[3]);
        assertEquals(0, arguments[5]); // is_mark_read, deliberately different from old wrapper.
        assertEquals(1, arguments[8]);
        assertEquals(-1, arguments[12]);
        assertNull(arguments[21]);
        assertEquals(0, arguments[22]); // Do not accidentally omit reply subtypes.
        assertSame(newest.commentNotice.comment, events.get(1).targetComment);
        assertSame(newest.commentNotice.aweme, events.get(1).work);
        assertEquals("101", events.get(1).rootId);
        assertEquals("own original", events.get(1).originalText);
        assertEquals(210, events.get(1).createdAt);
        assertEquals(210, inbox.scannedThrough());
    }

    @Test public void resolvedReceivedGroupMakesTheBaselineAndNewReplyPagesVisible() throws Exception {
        implementation.expectedGroup = 44;
        implementation.pages.add(page(false, 0, 0, notice("old-notice", "baseline-old", "200", "101", 210)));

        assertEquals("The UI primary category must not select an unrelated empty native group",
                210, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("baseline-old"), inbox.baselineCommentIds());
        assertEquals(1, implementation.calls.size());
        assertEquals(44, implementation.calls.get(0)[3]);

        implementation.pages.add(page(true, 0, 220, notice("new-notice", "received-new", "201", "101", 220)));
        implementation.pages.add(page(false, 0, 200));
        List<CommentReplyEvent> events = inbox.scan(100, 210, () -> true);
        assertEquals(1, events.size());
        assertEquals("received-new", events.get(0).incomingId);
        assertEquals(220, inbox.scannedThrough());
        assertEquals(2, FakeGroupResolver.calls);
        assertEquals(3, implementation.calls.size());
        for (Object[] arguments : implementation.calls) {
            assertEquals(44, arguments[3]);
            assertEquals(0, arguments[5]);
        }
    }

    @Test public void receivedGroupIsResolvedForEachReadAndPinnedAcrossItsPages() throws Exception {
        implementation.expectedGroup = 44;
        implementation.pages.add(page(false, 0, 0, notice("old-notice", "baseline-old", "200", "101", 210)));
        assertEquals(210, inbox.baseline(100, () -> true));

        FakeGroupResolver.group = 87;
        implementation.expectedGroup = 87;
        implementation.duringFetch = () -> FakeGroupResolver.group = 99;
        implementation.pages.add(page(true, 0, 220, notice("remapped-notice", "remapped-reply", "201", "101", 220)));
        implementation.pages.add(page(false, 0, 200));
        List<CommentReplyEvent> events = inbox.scan(100, 210, () -> true);

        assertEquals(1, events.size());
        assertEquals("remapped-reply", events.get(0).incomingId);
        assertEquals(99, FakeGroupResolver.group);
        assertEquals("One host route snapshot is required for each complete native read", 2, FakeGroupResolver.calls);
        assertEquals(3, implementation.calls.size());
        assertEquals(44, implementation.calls.get(0)[3]);
        assertEquals(87, implementation.calls.get(1)[3]);
        assertEquals(87, implementation.calls.get(2)[3]);
        for (Object[] arguments : implementation.calls) assertEquals(0, arguments[5]);
    }

    @Test public void routeResolutionFailureNeverInvokesTheNativeNoticeApi() throws Exception {
        FakeGroupResolver.failure = new IllegalStateException("fixture route is unavailable");
        implementation.pages.add(page(false, 0, 0, notice("old-notice", "baseline-old", "200", "101", 210)));

        assertThrows(IllegalStateException.class, () -> inbox.baseline(100, () -> true));
        assertThrows(IllegalStateException.class, () -> inbox.scan(100, 210, () -> true));

        assertEquals(2, FakeGroupResolver.calls);
        assertTrue(implementation.calls.isEmpty());
        assertEquals(1, implementation.pages.size());
        assertTrue(inbox.baselineCommentIds().isEmpty());
        assertEquals(0, inbox.scannedThrough());
    }

    @Test public void nonPositiveResolvedGroupIsRejectedBeforeAnyNativeRequest() throws Exception {
        for (int invalid : new int[]{0, -1}) {
            FakeGroupResolver.group = invalid;
            implementation.expectedGroup = invalid;
            implementation.pages.add(page(false, 0, 0, notice("old-" + invalid, "old-id-" + invalid, "200", "101", 210)));

            assertThrows(IllegalStateException.class, () -> inbox.baseline(100, () -> true));
            assertThrows(IllegalStateException.class, () -> inbox.scan(100, 210, () -> true));
            assertTrue(implementation.calls.isEmpty());
        }
        assertEquals(4, FakeGroupResolver.calls);
        assertTrue(inbox.baselineCommentIds().isEmpty());
        assertEquals(0, inbox.scannedThrough());
    }

    @Test public void hostShapedNestedJsonSeedsExistingIdsAndExtractsANewSameSecondReply() throws Exception {
        implementation.expectedGroup = 44;
        Response existingPage = nestedResponseFromJson("existing-notice", "301", 0, 210);
        Notice existing = existingPage.items.get(0);
        assertNull("The host JSON stores this payload under interactive_notice, not the legacy field", existing.commentNotice);
        assertNotNull(existing.interactiveGeneralNoticeStruct.comment);
        implementation.pages.add(existingPage);

        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("301"), inbox.baselineCommentIds());

        Response freshPage = nestedResponseFromJson("fresh-notice", "302", 0, 210);
        Notice fresh = freshPage.items.get(0);
        implementation.pages.add(freshPage);
        List<CommentReplyEvent> events = inbox.scan(100, 210, () -> true);

        assertEquals(1, events.size());
        CommentReplyEvent event = events.get(0);
        assertEquals("fresh-notice", event.noticeId);
        assertEquals("302", event.incomingId);
        assertEquals("work", event.aid);
        assertEquals("101", event.rootId);
        assertEquals("200", event.senderUid);
        assertEquals("incoming reply", event.text);
        assertEquals("own original", event.originalText);
        assertEquals(210, event.createdAt);
        assertEquals(210, inbox.scannedThrough());
        assertFalse(inbox.baselineCommentIds().contains(event.incomingId));
        assertSame(fresh.interactiveGeneralNoticeStruct.comment.comment, event.targetComment);
        assertSame(fresh.interactiveGeneralNoticeStruct.comment.aweme, event.work);
        for (Object[] arguments : implementation.calls) assertEquals(0, arguments[5]);
    }

    @Test public void nestedCommentPayloadWinsAndAnEmptyNestedPayloadFallsBackToLegacy() throws Exception {
        Notice value = nestedNotice("nested-notice", "302", "201", "101", 210);
        CommentNotice nested = value.interactiveGeneralNoticeStruct.comment;
        value.commentNotice = notice("legacy-notice", "301", "200", "101", 210).commentNotice;

        CommentReplyEvent preferred = inbox.extract(value, 100);
        assertNotNull(preferred);
        assertEquals("302", preferred.incomingId);
        assertEquals("201", preferred.senderUid);
        assertSame(nested.comment, preferred.targetComment);

        value.interactiveGeneralNoticeStruct.comment = null;
        CommentReplyEvent fallback = inbox.extract(value, 100);
        assertNotNull(fallback);
        assertEquals("301", fallback.incomingId);
        assertSame(value.commentNotice.comment, fallback.targetComment);
        value.interactiveGeneralNoticeStruct = null;
        assertEquals("301", inbox.extract(value, 100).incomingId);
    }

    @Test public void nestedCommentIdsProvideProgressWhenNoticeIdsAndCursorPairsCannot() throws Exception {
        for (int index = 0; index < 4; index++) {
            Notice value = nestedNotice("", "nested-id-" + index, "200", "101", 210);
            implementation.pages.add(page(index < 3, 0, 0, value));
        }

        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Arrays.asList("nested-id-0", "nested-id-1", "nested-id-2", "nested-id-3"), inbox.baselineCommentIds());
        assertEquals(4, implementation.calls.size());
    }

    @Test public void hostShapedReplyDetailJsonSeedsBaselineAndExtractsANewSameSecondReply() throws Exception {
        implementation.expectedGroup = 44;
        Response oldPage = detailedResponseFromJson("existing-detail", "401", 0, 210);
        Notice old = oldPage.items.get(0);
        assertNull(old.commentNotice);
        assertNull(old.interactiveGeneralNoticeStruct.comment);
        assertNotNull(old.interactiveGeneralNoticeStruct.additionalInfo.comment.a);
        implementation.pages.add(oldPage);

        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("401"), inbox.baselineCommentIds());

        Response newPage = detailedResponseFromJson("new-detail", "402", 0, 210);
        Notice fresh = newPage.items.get(0);
        implementation.pages.add(newPage);
        List<CommentReplyEvent> events = inbox.scan(100, 210, () -> true);

        assertEquals(1, events.size());
        CommentReplyEvent event = events.get(0);
        assertEquals("new-detail", event.noticeId);
        assertEquals("402", event.incomingId);
        assertEquals("work", event.aid);
        assertEquals("101", event.rootId);
        assertEquals("200", event.senderUid);
        assertEquals("incoming reply", event.text);
        assertEquals("own original", event.originalText);
        assertEquals(210, event.createdAt);
        assertEquals(210, inbox.scannedThrough());
        assertSame(fresh.interactiveGeneralNoticeStruct.additionalInfo.comment.a, event.targetComment);
        assertSame(fresh.interactiveGeneralNoticeStruct.aweme, event.work);
        assertFalse(inbox.baselineCommentIds().contains(event.incomingId));
        for (Object[] arguments : implementation.calls) assertEquals(0, arguments[5]);
    }

    @Test public void directedParentlessReplyJsonUsesASeparateTargetWithoutMutatingTheHostModel() throws Exception {
        Response replay = new Gson().fromJson("""
                {
                  "status_code": 0, "has_more": false, "max_time": 0, "min_time": 0,
                  "notice_list_v2": [{
                    "nid": "directed-notice", "create_time": 210,
                    "interactive_notice": {
                      "aweme": {"aweme_id": "work"},
                      "additional_info": {"comment": {
                        "is_reply": false, "parent_id": "unrelated-metadata",
                        "current_comment": {
                          "cid": "402", "aweme_id": "work", "text": "incoming reply",
                          "reply_id": "0", "reply_to_reply_id": "", "reply_to_userid": "100", "create_time": 205,
                          "user": {"uid": "200", "nickname": "sender"},
                          "image_list": [{"origin_url": {"url_list": ["https://p3.douyinpic.com/incoming.jpg"]}}]
                        },
                        "level1_comment": {
                          "cid": "101", "aweme_id": "work", "text": "own original",
                          "reply_id": "0", "create_time": 100,
                          "user": {"uid": "100", "nickname": "me"}
                        }
                      }}
                    }
                  }]
                }
                """, Response.class);
        Notice notice = replay.items.get(0);
        Comment originalTarget = notice.interactiveGeneralNoticeStruct.additionalInfo.comment.a;
        assertEquals("100", originalTarget.replyToUserId);

        CommentReplyEvent event = inbox.extract(notice, 100);

        assertNotNull(event);
        assertEquals("101", event.rootId);
        assertEquals("own original", event.originalText);
        assertEquals("402", event.incomingId);
        assertSame(notice.interactiveGeneralNoticeStruct.aweme, event.work);
        assertNotSame(originalTarget, event.targetComment);
        Comment target = (Comment) event.targetComment;
        assertEquals("101", target.replyId);
        assertEquals("0", target.replyToReplyId);
        assertEquals(originalTarget.cid, target.cid);
        assertEquals(originalTarget.text, target.text);
        assertEquals(originalTarget.awemeId, target.awemeId);
        assertEquals(originalTarget.createTime, target.createTime);
        assertEquals(originalTarget.replyToUserId, target.replyToUserId);
        assertEquals(originalTarget.user.uid, target.user.uid);
        assertEquals(originalTarget.user.nickname, target.user.nickname);
        assertEquals(AutomationReplyMedia.commentImageUrls(originalTarget), AutomationReplyMedia.commentImageUrls(target));
        assertEquals("0", originalTarget.replyId);
        assertEquals("", originalTarget.replyToReplyId);
        assertSame(originalTarget, notice.interactiveGeneralNoticeStruct.additionalInfo.comment.a);
    }

    @Test public void parentlessReplyRequiresBothOwnRecipientAndACompleteOwnOriginalComment() throws Exception {
        for (String recipient : new String[]{null, "", "0", "300"}) {
            Notice notice = parentlessDirectedNotice("recipient", "402", "200", "101", 210);
            notice.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyToUserId = recipient;
            assertNull("Missing or conflicting recipients cannot authorize inference", inbox.extract(notice, 100));
        }
        Notice missingOriginal = parentlessDirectedNotice("no-original", "402", "200", "101", 210);
        missingOriginal.interactiveGeneralNoticeStruct.additionalInfo.comment.e = null;
        assertNull(inbox.extract(missingOriginal, 100));
        Notice wrongOwner = parentlessDirectedNotice("wrong-owner", "402", "200", "101", 210);
        wrongOwner.interactiveGeneralNoticeStruct.additionalInfo.comment.e.user.uid = "300";
        assertNull(inbox.extract(wrongOwner, 100));
        Notice noAuthor = parentlessDirectedNotice("no-author", "402", "200", "101", 210);
        noAuthor.interactiveGeneralNoticeStruct.additionalInfo.comment.e.user = null;
        assertNull(inbox.extract(noAuthor, 100));
        Notice emptyCid = parentlessDirectedNotice("empty-cid", "402", "200", "101", 210);
        emptyCid.interactiveGeneralNoticeStruct.additionalInfo.comment.e.cid = "";
        assertNull(inbox.extract(emptyCid, 100));
        Notice sameCid = parentlessDirectedNotice("same-cid", "402", "200", "101", 210);
        sameCid.interactiveGeneralNoticeStruct.additionalInfo.comment.e.cid = "402";
        assertNull(inbox.extract(sameCid, 100));
        Notice emptyText = parentlessDirectedNotice("empty-original", "402", "200", "101", 210);
        emptyText.interactiveGeneralNoticeStruct.additionalInfo.comment.e.text = " ";
        assertNull(inbox.extract(emptyText, 100));
        Notice conflictingOriginalWork = parentlessDirectedNotice("original-work", "402", "200", "101", 210);
        conflictingOriginalWork.interactiveGeneralNoticeStruct.additionalInfo.comment.e.awemeId = "other-work";
        assertNull(inbox.extract(conflictingOriginalWork, 100));
        Notice conflictingTargetWork = parentlessDirectedNotice("target-work", "402", "200", "101", 210);
        conflictingTargetWork.interactiveGeneralNoticeStruct.additionalInfo.comment.a.awemeId = "other-work";
        assertNull(inbox.extract(conflictingTargetWork, 100));
        Notice originalIsChild = parentlessDirectedNotice("original-is-child", "402", "200", "101", 210);
        originalIsChild.interactiveGeneralNoticeStruct.additionalInfo.comment.e.replyId = "another-root";
        assertNull("An own child comment CID cannot become the root of a reconstructed reply", inbox.extract(originalIsChild, 100));
        Notice originalRepliesToChild = parentlessDirectedNotice("original-replies-to-child", "402", "200", "101", 210);
        originalRepliesToChild.interactiveGeneralNoticeStruct.additionalInfo.comment.e.replyToReplyId = "another-child";
        assertNull(inbox.extract(originalRepliesToChild, 100));
        Notice invisible = parentlessDirectedNotice("invisible", "402", "200", "101", 210);
        invisible.interactiveGeneralNoticeStruct.additionalInfo.comment.b = 1;
        assertNull(inbox.extract(invisible, 100));
        Notice metadataOnly = parentlessDirectedNotice("metadata-only", "402", "200", "101", 210);
        metadataOnly.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyToUserId = null;
        metadataOnly.interactiveGeneralNoticeStruct.additionalInfo.comment.d = true;
        metadataOnly.interactiveGeneralNoticeStruct.additionalInfo.comment.f = "101";
        assertNull(inbox.extract(metadataOnly, 100));
        Notice legacyOnly = notice("legacy-parentless", "402", "200", "101", 210);
        legacyOnly.commentNotice.comment.replyId = "0";
        legacyOnly.commentNotice.comment.replyToUserId = "100";
        assertNull("This compatibility inference requires the selected native detail model", inbox.extract(legacyOnly, 100));
    }

    @Test public void explicitOtherRecipientIsRejectedEvenWhenCanonicalParentMatchesOwnComment() throws Exception {
        Notice detailed = detailedNotice("canonical-detail", "402", "200", "101", 210);
        detailed.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyToUserId = "300";
        assertNull(inbox.extract(detailed, 100));
        Notice legacy = notice("canonical-legacy", "402", "200", "101", 210);
        legacy.commentNotice.comment.replyToUserId = "300";
        assertNull(inbox.extract(legacy, 100));
    }

    @Test public void invalidOrFailingNativeCloneNeverAuthorizesAParentlessReply() throws Exception {
        for (Comment cloneBehavior : new Comment[]{new SameInstanceCloneComment(), new WrongCidCloneComment(), new ThrowingCloneComment()}) {
            Notice notice = parentlessDirectedNotice("bad-clone", "402", "200", "101", 210);
            ReplyDetail detail = notice.interactiveGeneralNoticeStruct.additionalInfo.comment;
            Comment original = detail.a;
            cloneBehavior.cid = original.cid;
            cloneBehavior.text = original.text;
            cloneBehavior.awemeId = original.awemeId;
            cloneBehavior.replyId = original.replyId;
            cloneBehavior.replyToReplyId = original.replyToReplyId;
            cloneBehavior.replyToUserId = original.replyToUserId;
            cloneBehavior.createTime = original.createTime;
            cloneBehavior.user = original.user;
            cloneBehavior.imageList = original.imageList;
            detail.a = cloneBehavior;

            assertNull(inbox.extract(notice, 100));
            assertEquals("0", cloneBehavior.replyId);
            assertEquals("", cloneBehavior.replyToReplyId);
            assertEquals("402", cloneBehavior.cid);
        }
    }

    @Test public void validNativeCloneMayUseAnotherUserInstanceOnlyWhenItsUidMatches() throws Exception {
        for (String clonedUid : new String[]{"200", "300"}) {
            Notice notice = parentlessDirectedNotice("user-clone", "402", "200", "101", 210);
            ReplyDetail detail = notice.interactiveGeneralNoticeStruct.additionalInfo.comment;
            Comment original = detail.a;
            ReplacedUserCloneComment target = new ReplacedUserCloneComment();
            target.cid = original.cid;
            target.text = original.text;
            target.awemeId = original.awemeId;
            target.replyId = original.replyId;
            target.replyToReplyId = original.replyToReplyId;
            target.replyToUserId = original.replyToUserId;
            target.createTime = original.createTime;
            target.user = original.user;
            target.imageList = original.imageList;
            target.clonedUid = clonedUid;
            detail.a = target;

            CommentReplyEvent event = inbox.extract(notice, 100);

            if ("200".equals(clonedUid)) {
                assertNotNull(event);
                Comment copy = (Comment) event.targetComment;
                assertNotSame(target, copy);
                assertNotSame(target.user, copy.user);
                assertEquals(target.user.uid, copy.user.uid);
                assertEquals("101", copy.replyId);
            } else assertNull(event);
            assertEquals("200", target.user.uid);
            assertEquals("0", target.replyId);
            assertEquals("", target.replyToReplyId);
        }
    }

    @Test public void parentlessScanSummaryReportsOnlyNumericRecipientAndOriginalStates() throws Exception {
        Notice own = parentlessDirectedNotice("private-notice-own", "private-incoming-own", "private-sender-own", "private-root-own", 210);
        Notice other = parentlessDirectedNotice("private-notice-other", "private-incoming-other", "private-sender-other", "private-root-other", 211);
        other.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyToUserId = "private-recipient-other";
        other.interactiveGeneralNoticeStruct.additionalInfo.comment.e.user.uid = "private-original-author-other";
        Notice missing = parentlessDirectedNotice("private-notice-missing", "private-incoming-missing", "private-sender-missing", "private-root-missing", 212);
        missing.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyToUserId = null;
        missing.interactiveGeneralNoticeStruct.additionalInfo.comment.e = null;
        for (Notice notice : new Notice[]{own, other, missing}) {
            notice.interactiveGeneralNoticeStruct.additionalInfo.comment.a.text = "private-incoming-body";
            Comment original = notice.interactiveGeneralNoticeStruct.additionalInfo.comment.e;
            if (original != null) original.text = "private-original-body";
        }
        implementation.pages.add(page(false, 0, 0, own, other, missing));

        assertEquals(1, inbox.scan(100, 200, () -> true).size());

        List<String> summaries = diagnostics.stream().filter(value -> value.startsWith("Comment reply inbox scan complete:"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(1, summaries.size());
        String summary = summaries.get(0);
        for (String expected : new String[]{"notices=3;", "replies=1;", "detail=3;", "no_target=0;", "parent_missing=3;",
                "recipient_own=1;", "recipient_other=1;", "recipient_missing=1;", "level1_own=1;", "level1_other=1;",
                "level1_missing=1;", "parent_context_ready=1;", "diagnostic_errors=0"}) {
            assertTrue("Missing safe numeric state: " + expected, summary.contains(expected));
        }
        assertTrue(summary.matches("Comment reply inbox scan complete: [a-z0-9_]+=\\d+(; [a-z0-9_]+=\\d+)*"));
        assertFalse(summary.contains("private-"));
        assertFalse(summary.contains("100"));
    }

    @Test public void currentCommentInReplyDetailWinsAndAnAbsentCurrentCommentFallsBackToNestedF() throws Exception {
        Notice value = detailedNotice("detail-notice", "402", "200", "101", 210);
        ReplyDetail detail = value.interactiveGeneralNoticeStruct.additionalInfo.comment;
        CommentNotice nested = notice("nested-notice", "302", "201", "101", 210).commentNotice;
        value.interactiveGeneralNoticeStruct.comment = nested;

        CommentReplyEvent preferred = inbox.extract(value, 100);
        assertNotNull(preferred);
        assertEquals("402", preferred.incomingId);
        assertEquals("200", preferred.senderUid);
        assertSame(detail.a, preferred.targetComment);

        detail.a = null;
        CommentReplyEvent fallback = inbox.extract(value, 100);
        assertNotNull(fallback);
        assertEquals("302", fallback.incomingId);
        assertSame(nested.comment, fallback.targetComment);
    }

    @Test public void replyDetailRequiresOwnMatchingParentAndVisibleIncomingIdentity() throws Exception {
        Notice wrongOwner = detailedNotice("wrong-owner", "402", "200", "101", 210);
        wrongOwner.interactiveGeneralNoticeStruct.additionalInfo.comment.e.user.uid = "300";
        assertNull(inbox.extract(wrongOwner, 100));
        Notice invisible = detailedNotice("invisible", "402", "200", "101", 210);
        invisible.interactiveGeneralNoticeStruct.additionalInfo.comment.b = 1;
        assertNull(inbox.extract(invisible, 100));
        Notice wrongParent = detailedNotice("wrong-parent", "402", "200", "101", 210);
        wrongParent.interactiveGeneralNoticeStruct.additionalInfo.comment.e.cid = "999";
        assertNull(inbox.extract(wrongParent, 100));
        Notice notReply = detailedNotice("not-reply", "402", "200", "101", 210);
        notReply.interactiveGeneralNoticeStruct.additionalInfo.comment.d = false;
        assertNotNull("The native UI label does not replace actual parent and author evidence", inbox.extract(notReply, 100));
        notReply.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyId = "0";
        notReply.interactiveGeneralNoticeStruct.additionalInfo.comment.a.replyToReplyId = "0";
        assertNull(inbox.extract(notReply, 100));
        Notice wrongWork = detailedNotice("wrong-work", "402", "200", "101", 210);
        wrongWork.interactiveGeneralNoticeStruct.aweme.aid = "another-work";
        assertNull(inbox.extract(wrongWork, 100));
        Notice self = detailedNotice("self", "402", "100", "101", 210);
        assertNull(inbox.extract(self, 100));

        Notice childReply = detailedNotice("child", "402", "200", "101", 210);
        ReplyDetail child = childReply.interactiveGeneralNoticeStruct.additionalInfo.comment;
        child.a.replyToReplyId = "202";
        assertNull("An own root does not prove that the referenced child belongs to the current account",
                inbox.extract(childReply, 100));
        child.e.cid = "202";
        assertNotNull(inbox.extract(childReply, 100));

        Notice missingNativeParent = detailedNotice("detail-parent", "402", "200", "101", 210);
        ReplyDetail parent = missingNativeParent.interactiveGeneralNoticeStruct.additionalInfo.comment;
        parent.a.replyId = "0";
        parent.a.replyToReplyId = "0";
        assertNull("Parent metadata alone cannot replace the actual target's reply identifiers",
                inbox.extract(missingNativeParent, 100));
        parent.f = "999";
        assertNull(inbox.extract(missingNativeParent, 100));
        parent.f = "101";
        parent.e.user.uid = "300";
        assertNull(inbox.extract(missingNativeParent, 100));
    }

    @Test public void generalNoticeWorkBindsNewModelsWithoutAnOptionalDuplicateTargetAid() throws Exception {
        Notice nested = nestedNotice("nested-work", "402", "200", "101", 210);
        nested.interactiveGeneralNoticeStruct.comment.comment.awemeId = null;
        CommentReplyEvent nestedEvent = inbox.extract(nested, 100);
        assertNotNull(nestedEvent);
        assertTrue(nestedEvent.nativeWorkBound);
        nested.interactiveGeneralNoticeStruct.comment.comment.awemeId = "other-work";
        assertNull(inbox.extract(nested, 100));

        Notice detailed = detailedNotice("detail-work", "403", "201", "102", 210);
        detailed.interactiveGeneralNoticeStruct.additionalInfo.comment.a.awemeId = null;
        CommentReplyEvent detailedEvent = inbox.extract(detailed, 100);
        assertNotNull(detailedEvent);
        assertTrue(detailedEvent.nativeWorkBound);
        detailed.interactiveGeneralNoticeStruct.additionalInfo.comment.a.awemeId = "other-work";
        assertNull(inbox.extract(detailed, 100));

        Notice legacy = notice("legacy-work", "404", "202", "103", 210);
        assertFalse(inbox.extract(legacy, 100).nativeWorkBound);
        legacy.commentNotice.comment.awemeId = null;
        assertNull(inbox.extract(legacy, 100));
    }

    @Test public void currentCommentIdsInReplyDetailProvideProgressWithoutNoticeIdsOrCursorChanges() throws Exception {
        for (int index = 0; index < 4; index++) {
            implementation.pages.add(page(index < 3, 0, 0,
                    detailedNotice("", "detail-id-" + index, "200", "101", 210)));
        }

        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Arrays.asList("detail-id-0", "detail-id-1", "detail-id-2", "detail-id-3"), inbox.baselineCommentIds());
        assertEquals(4, implementation.calls.size());
    }

    @Test public void replyToReplyMustReferenceOwnChildInsteadOfAnotherPersonsChildUnderOwnRoot() throws Exception {
        Notice good = notice("1", "400", "200", "101", 210);
        good.commentNotice.comment.replyToReplyId = "202";
        good.commentNotice.replyComment.cid = "202";
        good.commentNotice.level1Comment = comment("101", "300", "0", "", 100);
        assertNotNull(inbox.extract(good, 100));

        Notice otherChild = notice("2", "401", "200", "101", 210);
        otherChild.commentNotice.comment.replyToReplyId = "202";
        otherChild.commentNotice.replyComment = comment("202", "300", "101", "", 190);
        otherChild.commentNotice.level1Comment = comment("101", "100", "0", "own root", 100);
        assertNull(inbox.extract(otherChild, 100));
        assertTrue(diagnostics.stream().anyMatch(value -> value.contains("author is not current account")));
    }

    @Test public void rejectsWrongDirectionSelfSenderMentionAndIncompleteIdentityWithoutGuessing() throws Exception {
        Notice self = notice("self", "302", "100", "101", 210);
        assertNull(inbox.extract(self, 100));
        Notice reversed = notice("reversed", "302", "200", "101", 210);
        Comment swap = reversed.commentNotice.comment;
        reversed.commentNotice.comment = reversed.commentNotice.replyComment;
        reversed.commentNotice.replyComment = swap;
        assertNull(inbox.extract(reversed, 100));
        Notice wrongOriginal = notice("wrong", "302", "200", "101", 210);
        wrongOriginal.commentNotice.replyComment.user.uid = "300";
        assertNull(inbox.extract(wrongOriginal, 100));
        Notice mismatchedParent = notice("parent", "302", "200", "101", 210);
        mismatchedParent.commentNotice.replyComment.cid = "999";
        assertNull(inbox.extract(mismatchedParent, 100));
        Notice missingSender = notice("sender", "302", "200", "101", 210);
        missingSender.commentNotice.comment.user = null;
        assertNull(inbox.extract(missingSender, 100));
        Notice wrongWork = notice("work", "302", "200", "101", 210);
        wrongWork.commentNotice.aweme.aid = "different";
        assertNull(inbox.extract(wrongWork, 100));
        Notice mention = new Notice();
        mention.nid = "mention";
        assertNull(inbox.extract(mention, 100));
    }

    @Test public void baselineSeedsSameSecondIdsReturnsCopyAndNeverExtractsOrMarksRead() throws Exception {
        Notice existing = notice("existing", "302", "200", "101", 210);
        implementation.pages.add(page(true, 210, 190, existing));
        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("302"), inbox.baselineCommentIds());
        inbox.baselineCommentIds().clear();
        assertEquals(Collections.singletonList("302"), inbox.baselineCommentIds());
        assertEquals(1, implementation.calls.size());
        assertEquals(0, implementation.calls.get(0)[5]);
        implementation.pages.add(page(false, 210, 200, existing, notice("new", "303", "201", "101", 210)));
        assertEquals(2, inbox.scan(100, 210, () -> true).size());
    }

    @Test public void zeroMaxCursorDoesNotPreventBaselineOrDropNewReplies() throws Exception {
        Notice existing = notice("existing", "302", "200", "101", 210);
        implementation.pages.add(page(true, 0, 210, existing));
        implementation.pages.add(page(false, 0, 200, existing));
        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("302"), inbox.baselineCommentIds());
        assertEquals(0L, implementation.calls.get(1)[0]);
        assertEquals(210L, implementation.calls.get(1)[1]);

        Notice fresh = notice("fresh", "303", "201", "101", 220);
        implementation.pages.add(page(true, 0, 220, fresh));
        implementation.pages.add(page(false, 0, 200, existing));
        List<CommentReplyEvent> events = inbox.scan(100, 210, () -> true);
        assertEquals(Arrays.asList("302", "303"), events.stream().map(event -> event.incomingId)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(220, inbox.scannedThrough());
        assertEquals(0L, implementation.calls.get(3)[0]);
        assertEquals(220L, implementation.calls.get(3)[1]);
    }

    @Test public void changingCursorPairCanIncludeNewerNoticesDuringPagination() throws Exception {
        implementation.pages.add(page(true, 230, 210, notice("first", "302", "200", "101", 210)));
        implementation.pages.add(page(true, 240, 220, notice("newer", "303", "201", "101", 220)));
        implementation.pages.add(page(false, 240, 200, notice("older", "304", "202", "101", 200)));
        assertEquals(220, inbox.baseline(100, () -> true));
        assertEquals(Arrays.asList("302", "303", "304"), inbox.baselineCommentIds());
        assertEquals(240L, implementation.calls.get(2)[0]);
        assertEquals(220L, implementation.calls.get(2)[1]);
    }

    @Test public void emptyPagesAndZeroMinCursorCanStillReachACompleteBaselineAndNewReplies() throws Exception {
        Notice existing = notice("existing", "302", "200", "101", 210);
        implementation.pages.add(page(true, 0, 0));
        implementation.pages.add(page(true, 230, 0));
        implementation.pages.add(page(true, 220, 0, existing));
        implementation.pages.add(page(false, 210, 0, existing));
        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("302"), inbox.baselineCommentIds());
        assertEquals(0L, implementation.calls.get(1)[0]);
        assertEquals(0L, implementation.calls.get(1)[1]);
        assertEquals(230L, implementation.calls.get(2)[0]);
        assertEquals(0L, implementation.calls.get(2)[1]);

        implementation.pages.add(page(true, 240, 0));
        implementation.pages.add(page(false, 230, 0, notice("fresh", "303", "201", "101", 220)));
        List<CommentReplyEvent> events = inbox.scan(100, 210, () -> true);
        assertEquals(Collections.singletonList("303"), events.stream().map(event -> event.incomingId)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(220, inbox.scannedThrough());
    }

    @Test public void repeatedCursorWithNewNoticesStillCollectsTheEntireBaselineSecond() throws Exception {
        implementation.pages.add(page(true, 230, 210, notice("first", "302", "200", "101", 210)));
        implementation.pages.add(page(true, 230, 210, notice("second", "303", "201", "101", 210)));
        implementation.pages.add(page(false, 230, 200, notice("last", "304", "202", "101", 210)));
        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Arrays.asList("302", "303", "304"), inbox.baselineCommentIds());
        assertEquals(3, implementation.calls.size());
    }

    @Test public void opaqueCursorValuesArePassedBackWithoutSignAssumptions() throws Exception {
        implementation.pages.add(page(true, -1, 0, notice("first", "302", "200", "101", 210)));
        implementation.pages.add(page(false, -2, 0, notice("second", "303", "201", "101", 210)));
        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Arrays.asList("302", "303"), inbox.baselineCommentIds());
        assertEquals(-1L, implementation.calls.get(1)[0]);
        assertEquals(0L, implementation.calls.get(1)[1]);
    }

    @Test public void duplicatePagesAreDeduplicatedAndIncomingTimeDoesNotBecomeOriginalTime() throws Exception {
        Notice reply = notice("1", "302", "200", "101", 210);
        reply.commentNotice.replyComment.createTime = 1;
        implementation.pages.add(page(true, 220, 205, reply));
        implementation.pages.add(page(false, 220, 190, reply));
        List<CommentReplyEvent> result = inbox.scan(100, 190, () -> true);
        assertEquals(1, result.size());
        assertEquals(210, result.get(0).createdAt);
    }

    @Test public void delayedReplyUsesNoticeArrivalForFilteringAndWatermarkInsteadOfCommentPublication() throws Exception {
        Notice delayed = notice("delayed", "302", "200", "101", 100);
        delayed.createTime = 210;
        implementation.pages.add(page(false, 220, 190, delayed));
        List<CommentReplyEvent> events = inbox.scan(100, 200, () -> true);
        assertEquals(1, events.size());
        assertEquals(210, events.get(0).createdAt);
        assertEquals(210, inbox.scannedThrough());
    }

    @Test public void baselineUsesActualNoticeTimesAndDoesNotTreatCursorOrCommentTimeAsArrivalTime() throws Exception {
        Notice existing = notice("baseline", "302", "200", "101", 900);
        existing.createTime = 210;
        implementation.pages.add(page(true, 1000, 190, existing));
        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("302"), inbox.baselineCommentIds());
        existing.createTime = 0;
        implementation.pages.add(page(false, 0, 0, existing));
        assertEquals(900, inbox.baseline(100, () -> true));
    }

    @Test public void baselineCollectsOlderIdsAcrossTheWholeNewestSecondAndLeavesNewSameSecondIdEligible() throws Exception {
        Notice first = notice("first", "302", "200", "101", 210);
        Notice second = notice("second", "303", "201", "101", 210);
        implementation.pages.add(page(true, 230, 210, first));
        implementation.pages.add(page(false, 220, 210, second));
        assertEquals(210, inbox.baseline(100, () -> true));
        assertEquals(Arrays.asList("302", "303"), inbox.baselineCommentIds());
        assertEquals(230L, implementation.calls.get(1)[0]);
        assertEquals(210L, implementation.calls.get(1)[1]);
        implementation.pages.add(page(false, 230, 200, first, second, notice("new", "304", "202", "101", 210)));
        List<CommentReplyEvent> result = inbox.scan(100, 210, () -> true);
        List<String> ignored = inbox.baselineCommentIds();
        assertEquals(Collections.singletonList("304"), result.stream().map(event -> event.incomingId)
                .filter(id -> !ignored.contains(id)).collect(java.util.stream.Collectors.toList()));
    }

    @Test public void failedSameSecondBaselineCannotPublishPartiallyCollectedIds() throws Exception {
        Notice first = notice("first", "302", "200", "101", 210);
        for (int index = 0; index <= NativeCommentReplyInbox.MAX_STALLED_PAGES; index++) {
            implementation.pages.add(page(true, 230, 210, first));
        }
        NativeCommentReplyInbox.ScanFailure failure = assertThrows(NativeCommentReplyInbox.ScanFailure.class,
                () -> inbox.baseline(100, () -> true));
        assertEquals(NativeCommentReplyInbox.FailureReason.PAGINATION_NO_PROGRESS, failure.reason);
        assertTrue(inbox.baselineCommentIds().isEmpty());
        assertEquals(1 + NativeCommentReplyInbox.MAX_STALLED_PAGES, implementation.calls.size());
        String diagnostic = NativeCommentReplyInbox.failureDiagnostic(failure);
        assertTrue(diagnostic.contains("page=4; items=1; max_sign=1; min_sign=1"));
        assertTrue(diagnostic.contains("new_cursor=false; new_notices=0; stalled=3"));
        assertFalse(diagnostic.contains("first"));
        assertFalse(diagnostic.contains("302"));
        assertFalse(diagnostic.contains("incoming"));
    }

    @Test public void concurrentNewerNoticeRaisesBaselineAndKeepsEveryAlreadyObservedId() throws Exception {
        implementation.pages.add(page(true, 230, 210, notice("first", "302", "200", "101", 210)));
        implementation.pages.add(page(true, 240, 210, notice("newer", "303", "201", "101", 220)));
        assertEquals(220, inbox.baseline(100, () -> true));
        assertEquals(Arrays.asList("302", "303"), inbox.baselineCommentIds());
        assertEquals(2, implementation.calls.size());
    }

    @Test public void normalizesHostMillisecondsButPreservesNativeCursorValues() throws Exception {
        long now = 1_780_000_210L;
        Notice first = notice("first", "302", "200", "101", now);
        first.createTime = now * 1000;
        implementation.pages.add(page(true, now * 1000, (now - 5) * 1000, first));
        implementation.pages.add(page(false, now * 1000, (now - 20) * 1000));
        List<CommentReplyEvent> result = inbox.scan(100, now - 10, () -> true);
        assertEquals(now, result.get(0).createdAt);
        assertEquals(now, inbox.scannedThrough());
        assertEquals(now * 1000, implementation.calls.get(1)[0]);
        assertEquals((now - 5) * 1000, implementation.calls.get(1)[1]);
        implementation.pages.add(page(false, now * 1000, (now - 20) * 1000, first));
        assertEquals(now, inbox.baseline(100, () -> true));
    }

    @Test public void missingApiRejectedResponseBrokenPaginationAndAccountChangeThrowInsteadOfEmpty() throws Exception {
        Provider.b = null;
        assertThrows(IllegalStateException.class, () -> inbox.scan(100, 180, () -> true));
        Provider.b = implementation;
        Response failure = page(false, 0, 0);
        failure.status_code = 4;
        implementation.pages.add(failure);
        assertThrows(IllegalStateException.class, () -> inbox.scan(100, 180, () -> true));
        for (int index = 0; index <= NativeCommentReplyInbox.MAX_STALLED_PAGES; index++) {
            implementation.pages.add(page(true, 220, 200, notice("1", "302", "200", "101", 210)));
        }
        NativeCommentReplyInbox.ScanFailure stalled = assertThrows(NativeCommentReplyInbox.ScanFailure.class,
                () -> inbox.scan(100, 180, () -> true));
        assertEquals(NativeCommentReplyInbox.FailureReason.PAGINATION_NO_PROGRESS, stalled.reason);
        AccountFactory.service.user.uid = "200";
        assertThrows(java.util.concurrent.CancellationException.class, () -> inbox.scan(100, 180, () -> true));
    }

    @Test public void pageLimitAndFutureFailureNeverReturnPartialSuccess() throws Exception {
        for (int index = 0; index < NativeCommentReplyInbox.MAX_PAGES; index++) {
            implementation.pages.add(page(true, 1000, 900 - index,
                    notice("n" + index, "c" + index, "200", "101", 950 - index)));
        }
        assertThrows(IllegalStateException.class, () -> inbox.scan(100, 1, () -> true));
        implementation.next = CompletableFuture.failedFuture(new IllegalStateException("offline"));
        NativeCommentReplyInbox.ScanFailure failure = assertThrows(NativeCommentReplyInbox.ScanFailure.class,
                () -> inbox.scan(100, 1, () -> true));
        assertEquals(NativeCommentReplyInbox.FailureReason.REQUEST_WAIT, failure.reason);
        assertTrue(failure.getCause() instanceof java.util.concurrent.ExecutionException);
    }

    @Test public void cancellationDuringFutureWaitCancelsNativeReadAndPushOnlySignalsCommentPayloads() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicBoolean cancelled = new AtomicBoolean();
        implementation.next = new Future<Response>() {
            public boolean cancel(boolean interrupt) { cancelled.set(true); return true; }
            public boolean isCancelled() { return cancelled.get(); }
            public boolean isDone() { return false; }
            public Response get() { throw new AssertionError("must use timed wait"); }
            public Response get(long timeout, TimeUnit unit) throws TimeoutException {
                valid.set(false);
                throw new TimeoutException();
            }
        };
        assertThrows(java.util.concurrent.CancellationException.class, () -> inbox.scan(100, 180, valid::get));
        assertTrue(cancelled.get());
        assertTrue(inbox.isCommentPush(new Inner("comment")));
        assertFalse(inbox.isCommentPush(new Inner("mention")));
        assertFalse(inbox.isCommentPush(new Inner(null)));
        inbox.close();
        assertThrows(java.util.concurrent.CancellationException.class, () -> inbox.scan(100, 180, () -> true));
    }

    @Test public void disabledAndContentlessCommentsFailClosedWithReasonAndEmptyResponseIsSuccessful() throws Exception {
        Notice disabled = notice("1", "302", "200", "101", 210);
        disabled.noticeDisable = true;
        assertNull(inbox.extract(disabled, 100));
        Notice noText = notice("2", "303", "200", "101", 210);
        noText.commentNotice.comment.text = "";
        assertNull(inbox.extract(noText, 100));
        implementation.pages.add(page(false, 0, 0));
        assertTrue(inbox.scan(100, 180, () -> true).isEmpty());
    }

    @Test public void imageOnlyIncomingReplyUsesTheRealSerializedImageListAndPreservesTheVerifiedTarget() throws Exception {
        Notice imageOnly = imageOnlyNotice("image-only", "302", "200", "101");
        CommentReplyEvent event = inbox.extract(imageOnly, 100);
        assertNotNull(event);
        assertEquals("", event.text);
        assertEquals("own original", event.originalText);
        assertEquals("302", event.incomingId);
        assertEquals("101", event.rootId);
        assertEquals("200", event.senderUid);
        assertSame(imageOnly.commentNotice.comment, event.targetComment);
        assertSame(imageOnly.commentNotice.aweme, event.work);
        assertEquals(Collections.singletonList("https://p3.douyinpic.com/incoming.jpg"),
                AutomationReplyMedia.commentImageUrls(event.targetComment));
    }

    @Test public void imageOnlyReplyStillRequiresTextFromTheReferencedOwnComment() throws Exception {
        Notice imageOnly = imageOnlyNotice("missing-original-text", "302", "200", "101");
        imageOnly.commentNotice.replyComment.text = " ";
        imageOnly.commentNotice.replyComment.imageList = imageOnly.commentNotice.comment.imageList;
        assertNull(inbox.extract(imageOnly, 100));
        imageOnly.commentNotice.replyComment.text = null;
        assertNull(inbox.extract(imageOnly, 100));
        assertTrue(diagnostics.stream().anyMatch(value -> value.contains("own comment context")));
    }

    @Test public void originalCommentImagesCannotMakeAnEmptyIncomingReplyEligible() throws Exception {
        Notice empty = notice("empty", "302", "200", "101", 210);
        empty.commentNotice.comment.text = "";
        empty.commentNotice.replyComment.imageList = Collections.singletonList(new CommentImage("https://p3.douyinpic.com/own.jpg"));
        assertNull(inbox.extract(empty, 100));
        empty.commentNotice.comment.imageList = Collections.emptyList();
        assertNull(inbox.extract(empty, 100));
    }

    @Test public void imageOnlyRepliesDoNotWeakenAuthorReferenceDirectionOrWorkChecks() throws Exception {
        Notice self = imageOnlyNotice("self-image", "302", "100", "101");
        assertNull(inbox.extract(self, 100));
        Notice otherAuthor = imageOnlyNotice("other-author", "302", "200", "101");
        otherAuthor.commentNotice.replyComment.user.uid = "300";
        assertNull(inbox.extract(otherAuthor, 100));
        Notice wrongReference = imageOnlyNotice("wrong-parent", "302", "200", "101");
        wrongReference.commentNotice.replyComment.cid = "999";
        assertNull(inbox.extract(wrongReference, 100));
        Notice wrongWork = imageOnlyNotice("wrong-work", "302", "200", "101");
        wrongWork.commentNotice.comment.awemeId = "another-work";
        assertNull(inbox.extract(wrongWork, 100));
        Notice anotherChild = imageOnlyNotice("other-child", "302", "200", "101");
        anotherChild.commentNotice.comment.replyToReplyId = "202";
        anotherChild.commentNotice.replyComment = comment("202", "300", "101", "other original", 190);
        anotherChild.commentNotice.level1Comment = comment("101", "100", "0", "own root", 100);
        assertNull(inbox.extract(anotherChild, 100));
        Notice ownChild = imageOnlyNotice("own-child", "303", "200", "101");
        ownChild.commentNotice.comment.replyToReplyId = "202";
        ownChild.commentNotice.replyComment.cid = "202";
        ownChild.commentNotice.level1Comment = comment("101", "300", "0", "other root", 100);
        assertNotNull(inbox.extract(ownChild, 100));
    }

    @Test public void successfulFilteredPagesAdvanceWatermarkButFailedPartialScanDoesNot() throws Exception {
        Notice filtered = notice("old", "302", "100", "101", 210);
        implementation.pages.add(page(false, 220, 190, filtered));
        assertTrue(inbox.scan(100, 180, () -> true).isEmpty());
        assertEquals(210, inbox.scannedThrough());
        for (int index = 0; index <= NativeCommentReplyInbox.MAX_STALLED_PAGES; index++) {
            implementation.pages.add(page(true, 240, 230, notice("new", "303", "200", "101", 235)));
        }
        NativeCommentReplyInbox.ScanFailure stalled = assertThrows(NativeCommentReplyInbox.ScanFailure.class,
                () -> inbox.scan(100, 180, () -> true));
        assertEquals(NativeCommentReplyInbox.FailureReason.PAGINATION_NO_PROGRESS, stalled.reason);
        assertEquals(210, inbox.scannedThrough());
    }

    private static Comment comment(String id, String sender, String root, String text, long time) {
        Comment value = new RuntimeComment();
        value.cid = id; value.user = new User(sender); value.replyId = root; value.text = text;
        value.createTime = time; value.awemeId = "work"; return value;
    }
    static Notice notice(String nid, String id, String sender, String root, long time) {
        Notice value = new Notice(); value.nid = nid; value.createTime = time;
        value.commentNotice = new CommentNotice(); value.commentNotice.aweme = new Work();
        value.commentNotice.comment = comment(id, sender, root, "incoming", time);
        value.commentNotice.replyComment = comment(root, "100", "0", "own original", time - 100);
        return value;
    }
    static Notice nestedNotice(String nid, String id, String sender, String root, long time) {
        Notice value = notice(nid, id, sender, root, time);
        value.interactiveGeneralNoticeStruct = new NestedNotice();
        value.interactiveGeneralNoticeStruct.comment = value.commentNotice;
        value.commentNotice = null;
        return value;
    }
    static Notice detailedNotice(String nid, String id, String sender, String root, long time) {
        Notice value = notice(nid, id, sender, root, time);
        CommentNotice legacy = value.commentNotice;
        value.interactiveGeneralNoticeStruct = new NestedNotice();
        value.interactiveGeneralNoticeStruct.aweme = legacy.aweme;
        value.interactiveGeneralNoticeStruct.additionalInfo = new AdditionalInfo();
        ReplyDetail detail = new ReplyDetail();
        detail.a = legacy.comment;
        detail.d = true;
        detail.e = legacy.replyComment;
        detail.f = root;
        value.interactiveGeneralNoticeStruct.additionalInfo.comment = detail;
        value.commentNotice = null;
        return value;
    }

    static Notice sparseNotice(String nid, String id, String sender, long arrival) {
        Notice notice = detailedNotice(nid, id, sender, "101", arrival);
        ReplyDetail detail = notice.interactiveGeneralNoticeStruct.additionalInfo.comment;
        detail.a.replyId = "0";
        detail.a.replyToReplyId = "";
        detail.a.replyToUserId = null;
        detail.e = null;
        return notice;
    }

    static final class ThreadProbe implements NativeCommentReplyInbox.ThreadLookup {
        int calls;
        List<NativeCommentThreadLookup.Hint> hints = Collections.emptyList();
        java.util.function.Function<List<NativeCommentThreadLookup.Hint>, List<NativeCommentThreadLookup.Match>> resolve = ignored -> Collections.emptyList();
        Exception failure;
        @Override public List<NativeCommentThreadLookup.Match> resolve(long account, List<NativeCommentThreadLookup.Hint> hints,
                                                                      java.util.function.BooleanSupplier valid) throws Exception {
            calls++;
            this.hints = new ArrayList<>(hints);
            if (failure != null) throw failure;
            return resolve.apply(hints);
        }
    }
    static Notice parentlessDirectedNotice(String nid, String id, String sender, String root, long time) {
        Notice value = detailedNotice(nid, id, sender, root, time);
        ReplyDetail detail = value.interactiveGeneralNoticeStruct.additionalInfo.comment;
        detail.a.replyId = "0";
        detail.a.replyToReplyId = "";
        detail.a.replyToUserId = "100";
        return value;
    }
    private static Response detailedResponseFromJson(String nid, String incomingId, long arrivalTime, long commentTime) {
        return new Gson().fromJson("""
                {
                  "status_code": 0, "has_more": false, "max_time": 0, "min_time": 0,
                  "notice_list_v2": [{
                    "nid": "%s", "create_time": %d,
                    "interactive_notice": {
                      "aweme": {"aweme_id": "work"},
                      "additional_info": {
                        "comment": {
                          "is_reply": true, "comment_unvisible": 0, "parent_id": "101",
                          "current_comment": {
                            "cid": "%s", "aweme_id": "work", "text": "incoming reply",
                            "reply_id": "101", "reply_to_reply_id": "0", "create_time": %d,
                            "user": {"uid": "200", "nickname": "sender"}
                          },
                          "level1_comment": {
                            "cid": "101", "aweme_id": "work", "text": "own original",
                            "reply_id": "0", "create_time": 100,
                            "user": {"uid": "100", "nickname": "me"}
                          }
                        }
                      }
                    }
                  }]
                }
                """.formatted(nid, arrivalTime, incomingId, commentTime), Response.class);
    }
    private static Response nestedResponseFromJson(String nid, String incomingId, long arrivalTime, long commentTime) {
        return new Gson().fromJson("""
                {
                  "status_code": 0,
                  "has_more": false,
                  "max_time": 0,
                  "min_time": 0,
                  "notice_list_v2": [{
                    "nid": "%s",
                    "create_time": %d,
                    "interactive_notice": {
                      "comment": {
                        "aweme": {"aweme_id": "work"},
                        "comment": {
                          "cid": "%s", "aweme_id": "work", "text": "incoming reply",
                          "reply_id": "101", "reply_to_reply_id": "0", "create_time": %d,
                          "user": {"uid": "200", "nickname": "sender"}
                        },
                        "reply_comment": {
                          "cid": "101", "aweme_id": "work", "text": "own original",
                          "reply_id": "0", "create_time": 100,
                          "user": {"uid": "100", "nickname": "me"}
                        }
                      }
                    }
                  }]
                }
                """.formatted(nid, arrivalTime, incomingId, commentTime), Response.class);
    }
    private static Notice imageOnlyNotice(String nid, String id, String sender, String root) {
        Notice value = notice(nid, id, sender, root, 210);
        value.commentNotice.comment.text = "";
        value.commentNotice.comment.imageList = Collections.singletonList(new CommentImage("https://p3.douyinpic.com/incoming.jpg"));
        return value;
    }
    static Response page(boolean more, long max, long min, Notice... notices) {
        Response value = new RuntimeResponse(); value.hasMore = more; value.maxTime = max; value.minTime = min;
        value.items = Arrays.asList(notices); return value;
    }
    static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "comment_reply_notice_provider", Provider.class, null);
        add(rules, "comment_reply_notice_fetch", Fetch.class, Fetch.class.getDeclaredMethods()[0]);
        add(rules, "comment_reply_received_group", FakeGroupResolver.class, FakeGroupResolver.class.getMethod("receivedGroup"));
        add(rules, "comment_reply_notice_comment_model", FakeNoticeCommentResolver.class,
                FakeNoticeCommentResolver.class.getMethod("getCommentNotice", Notice.class));
        add(rules, "comment_reply_notice_incoming", FakeNoticeCommentResolver.class,
                FakeNoticeCommentResolver.class.getMethod("getIncomingComment", Notice.class));
        add(rules, "comment_reply_notice_reply_detail", FakeNoticeCommentResolver.class,
                FakeNoticeCommentResolver.class.getMethod("getReplyDetail", Notice.class));
        add(rules, "comment_reply_reply_detail_model", ReplyDetail.class, null);
        add(rules, "comment_reply_notice_response", Response.class, null);
        add(rules, "comment_reply_base_notice", Notice.class, null);
        add(rules, "comment_reply_comment_notice", CommentNotice.class, null);
        add(rules, "comment_reply_comment_model", Comment.class, null);
        add(rules, "comment_reply_comment_clone", Comment.class, Comment.class.getMethod("clone"));
        add(rules, "comment_reply_comment_user", Comment.class, Comment.class.getMethod("getEffectiveUser"));
        add(rules, "comment_reply_user_model", User.class, null);
        add(rules, "comment_reply_work_model", Work.class, null);
        add(rules, "media_account_service", AccountFactory.class, AccountFactory.class.getMethod("userService"));
        add(rules, "media_current_user", Account.class, Account.class.getMethod("getCurUser"));
        add(rules, "comment_reply_push_parse", Parser.class, Parser.class.getMethod("parse", Inner.class));
        add(rules, "comment_reply_push_model", Push.class, null);
        return new HostScanReport("fake", rules, false);
    }
    private static void add(List<RuleScanResult> rules, String id, Class<?> type, Method method) {
        rules.add(new RuleScanResult(id, "comment_reply", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(type), method == null ? "" : method.getName(),
                method == null ? "" : HostTargets.descriptor(method)))));
    }
    public interface Fetch {
        Future<Response> fetchNotice(long max, long min, int count, int group, Integer top, int mark,
                                    Integer contacts, Integer gps, int isNew, String avatar, String cover,
                                    String pin, int category, String scene, int disable, String tops,
                                    int unread, int currentUnread, String deleted, int preload, int official,
                                    Integer type, int filter);
    }
    public static final class Provider { public static Fetch b; }
    public static final class FakeNoticeCommentResolver {
        public static CommentNotice getCommentNotice(Notice notice) {
            if (notice == null) return null;
            NestedNotice nested = notice.interactiveGeneralNoticeStruct;
            return nested != null && nested.comment != null ? nested.comment : notice.commentNotice;
        }
        public static Comment getIncomingComment(Notice notice) {
            ReplyDetail detail = getReplyDetail(notice);
            if (detail != null && detail.a != null) return detail.a;
            CommentNotice payload = getCommentNotice(notice);
            return payload == null ? null : payload.comment;
        }
        public static ReplyDetail getReplyDetail(Notice notice) {
            NestedNotice nested = notice == null ? null : notice.interactiveGeneralNoticeStruct;
            AdditionalInfo additional = nested == null ? null : nested.additionalInfo;
            return additional == null ? null : additional.comment;
        }
    }
    public static final class FakeGroupResolver {
        static int group = 44;
        static RuntimeException failure;
        static int calls;
        public static int receivedGroup() {
            calls++;
            if (failure != null) throw failure;
            return group;
        }
    }
    public static final class Api implements Fetch {
        final Queue<Response> pages = new ArrayDeque<>(); final List<Object[]> calls = new ArrayList<>();
        Future<Response> next;
        Integer expectedGroup;
        Runnable duringFetch;
        public Future<Response> fetchNotice(long max, long min, int count, int group, Integer top, int mark,
                                           Integer contacts, Integer gps, int isNew, String avatar, String cover,
                                           String pin, int category, String scene, int disable, String tops,
                                           int unread, int currentUnread, String deleted, int preload, int official,
                                           Integer type, int filter) {
            calls.add(new Object[]{max, min, count, group, top, mark, contacts, gps, isNew, avatar, cover,
                    pin, category, scene, disable, tops, unread, currentUnread, deleted, preload, official, type, filter});
            Runnable callback = duringFetch;
            duringFetch = null;
            if (callback != null) callback.run();
            if (expectedGroup != null && group != expectedGroup) {
                return CompletableFuture.completedFuture(page(false, 0, 0));
            }
            if (next != null) { Future<Response> value = next; next = null; return value; }
            if (pages.isEmpty()) throw new IllegalStateException("Unexpected request");
            return CompletableFuture.completedFuture(pages.remove());
        }
    }
    public static class BaseResponse { public int status_code; }
    public static class Response extends BaseResponse {
        @SerializedName("notice_list_v2") public List<Notice> items;
        @SerializedName("has_more") public boolean hasMore;
        @SerializedName("max_time") public long maxTime;
        @SerializedName("min_time") public long minTime;
    }
    public static final class RuntimeResponse extends Response { }
    public static class Notice {
        public String nid;
        @SerializedName("create_time") public long createTime;
        @SerializedName("notice_disable") public boolean noticeDisable;
        @SerializedName("item_disable") public boolean itemDisable;
        @SerializedName("comment") public CommentNotice commentNotice;
        @SerializedName("interactive_notice") public NestedNotice interactiveGeneralNoticeStruct;
    }
    public static final class NestedNotice {
        @SerializedName("comment") public CommentNotice comment;
        @SerializedName("aweme") public Work aweme;
        @SerializedName("additional_info") public AdditionalInfo additionalInfo;
        public Work getAweme() { return aweme; }
    }
    public static final class AdditionalInfo {
        @SerializedName("comment") public ReplyDetail comment;
    }
    public static final class ReplyDetail {
        @SerializedName("current_comment") public Comment a;
        @SerializedName("comment_unvisible") public int b;
        @SerializedName("is_reply") public boolean d;
        @SerializedName("level1_comment") public Comment e;
        @SerializedName("parent_id") public String f;
    }
    public static class CommentNotice {
        public Comment comment;
        @SerializedName("reply_comment") public Comment replyComment;
        @SerializedName("level1_comment") public Comment level1Comment;
        public Work aweme;
        @SerializedName("comment_unvisible") public int commentUnvisible;
    }
    public static class Comment implements Cloneable {
        public String cid, text;
        @SerializedName("aweme_id") public String awemeId;
        @SerializedName("reply_id") public String replyId;
        @SerializedName("reply_to_reply_id") public String replyToReplyId;
        @SerializedName("reply_to_userid") public String replyToUserId;
        @SerializedName("create_time") public long createTime;
        public User user;
        public User commentUser;
        public User getEffectiveUser() { return commentUser != null ? commentUser : user; }
        @SerializedName("image_list") public List<CommentImage> imageList;
        @Override public Comment clone() {
            try { return (Comment) super.clone(); }
            catch (CloneNotSupportedException impossible) { throw new AssertionError(impossible); }
        }
    }
    public static final class SameInstanceCloneComment extends Comment {
        @Override public Comment clone() { return this; }
    }
    public static final class WrongCidCloneComment extends Comment {
        @Override public Comment clone() {
            Comment copy = super.clone();
            copy.cid = "another-id";
            return copy;
        }
    }
    public static final class ThrowingCloneComment extends Comment {
        @Override public Comment clone() { throw new IllegalStateException("fixture clone failure"); }
    }
    public static final class ReplacedUserCloneComment extends Comment {
        String clonedUid;
        @Override public Comment clone() {
            Comment copy = super.clone();
            copy.user = new User(clonedUid);
            copy.user.nickname = user.nickname;
            return copy;
        }
    }
    public static final class CommentImage {
        @SerializedName("origin_url") public ImageAddress originUrl;
        CommentImage(String url) { originUrl = new ImageAddress(url); }
    }
    public static final class ImageAddress {
        @SerializedName("url_list") public List<String> urlList;
        ImageAddress(String url) { urlList = Collections.singletonList(url); }
    }
    public static final class RuntimeComment extends Comment { }
    public static class Work { @SerializedName("aweme_id") public String aid = "work"; }
    public static class User { public String uid, nickname = "sender"; User(String id) { uid = id; } }
    public static final class AccountFactory { static Account service; public static Account userService() { return service; } }
    public static class Account { User user; public User getCurUser() { return user; } }
    public static class Inner { final String type; Inner(String type) { this.type = type; } }
    public static class Push { public String aggrNoticeType; }
    public static final class Parser { public static Push parse(Inner source) { Push value = new Push(); value.aggrNoticeType = source.type; return value; } }
}

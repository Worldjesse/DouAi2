package com.douai.module.feature;

import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
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
        implementation = new Api();
        Provider.b = implementation;
        AccountFactory.service = new Account();
        AccountFactory.service.user = new User("100");
        inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), diagnostics::add);
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
        assertEquals(3, arguments[3]);
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
        implementation.pages.add(page(true, 230, 210, first));
        implementation.pages.add(page(true, 230, 210, first));
        assertThrows(IllegalStateException.class, () -> inbox.baseline(100, () -> true));
        assertTrue(inbox.baselineCommentIds().isEmpty());
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
        implementation.pages.add(page(true, 220, 200, notice("1", "302", "200", "101", 210)));
        implementation.pages.add(page(true, 220, 200, notice("1", "302", "200", "101", 210)));
        assertThrows(IllegalStateException.class, () -> inbox.scan(100, 180, () -> true));
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
        assertThrows(java.util.concurrent.ExecutionException.class, () -> inbox.scan(100, 1, () -> true));
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
        implementation.pages.add(page(true, 240, 230, notice("new", "303", "200", "101", 235)));
        implementation.pages.add(page(true, 240, 230, notice("new", "303", "200", "101", 235)));
        assertThrows(IllegalStateException.class, () -> inbox.scan(100, 180, () -> true));
        assertEquals(210, inbox.scannedThrough());
    }

    private static Comment comment(String id, String sender, String root, String text, long time) {
        Comment value = new RuntimeComment();
        value.cid = id; value.user = new User(sender); value.replyId = root; value.text = text;
        value.createTime = time; value.awemeId = "work"; return value;
    }
    private static Notice notice(String nid, String id, String sender, String root, long time) {
        Notice value = new Notice(); value.nid = nid; value.createTime = time;
        value.commentNotice = new CommentNotice(); value.commentNotice.aweme = new Work();
        value.commentNotice.comment = comment(id, sender, root, "incoming", time);
        value.commentNotice.replyComment = comment(root, "100", "0", "own original", time - 100);
        return value;
    }
    private static Notice imageOnlyNotice(String nid, String id, String sender, String root) {
        Notice value = notice(nid, id, sender, root, 210);
        value.commentNotice.comment.text = "";
        value.commentNotice.comment.imageList = Collections.singletonList(new CommentImage("https://p3.douyinpic.com/incoming.jpg"));
        return value;
    }
    private static Response page(boolean more, long max, long min, Notice... notices) {
        Response value = new RuntimeResponse(); value.hasMore = more; value.maxTime = max; value.minTime = min;
        value.items = Arrays.asList(notices); return value;
    }
    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "comment_reply_notice_provider", Provider.class, null);
        add(rules, "comment_reply_notice_fetch", Fetch.class, Fetch.class.getDeclaredMethods()[0]);
        add(rules, "comment_reply_notice_response", Response.class, null);
        add(rules, "comment_reply_base_notice", Notice.class, null);
        add(rules, "comment_reply_comment_notice", CommentNotice.class, null);
        add(rules, "comment_reply_comment_model", Comment.class, null);
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
    public static final class Api implements Fetch {
        final Queue<Response> pages = new ArrayDeque<>(); final List<Object[]> calls = new ArrayList<>();
        Future<Response> next;
        public Future<Response> fetchNotice(long max, long min, int count, int group, Integer top, int mark,
                                           Integer contacts, Integer gps, int isNew, String avatar, String cover,
                                           String pin, int category, String scene, int disable, String tops,
                                           int unread, int currentUnread, String deleted, int preload, int official,
                                           Integer type, int filter) {
            calls.add(new Object[]{max, min, count, group, top, mark, contacts, gps, isNew, avatar, cover,
                    pin, category, scene, disable, tops, unread, currentUnread, deleted, preload, official, type, filter});
            if (next != null) { Future<Response> value = next; next = null; return value; }
            if (pages.isEmpty()) throw new IllegalStateException("Unexpected request");
            return CompletableFuture.completedFuture(pages.remove());
        }
    }
    public static class BaseResponse { public int status_code; }
    public static class Response extends BaseResponse { public List<Notice> items; public boolean hasMore; public long maxTime, minTime; }
    public static final class RuntimeResponse extends Response { }
    public static class Notice { public String nid; public long createTime; public boolean noticeDisable, itemDisable; public CommentNotice commentNotice; }
    public static class CommentNotice { public Comment comment, replyComment, level1Comment; public Work aweme; public int commentUnvisible; }
    public static class Comment {
        public String cid, awemeId, text, replyId, replyToReplyId;
        public long createTime;
        public User user;
        @SerializedName("image_list") public List<CommentImage> imageList;
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
    public static class Work { public String aid = "work"; }
    public static class User { public String uid, nickname = "sender"; User(String id) { uid = id; } }
    public static final class AccountFactory { static Account service; public static Account userService() { return service; } }
    public static class Account { User user; public User getCurUser() { return user; } }
    public static class Inner { final String type; Inner(String type) { this.type = type; } }
    public static class Push { public String aggrNoticeType; }
    public static final class Parser { public static Push parse(Inner source) { Push value = new Push(); value.aggrNoticeType = source.type; return value; } }
}

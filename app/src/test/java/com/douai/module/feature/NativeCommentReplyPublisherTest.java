package com.douai.module.feature;

import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class NativeCommentReplyPublisherTest {
    private Framework framework;
    private NativeCommentPublisher publisher;
    private final List<NativeCommentPublisher> publishers = new ArrayList<>();
    private final List<File> audioFiles = new ArrayList<>();

    @Before public void install() throws Exception {
        framework = new Framework();
        Request.models.clear();
        Request.instances.clear();
        Request.framework = framework;
        Request.uploadAudio = false;
        Request.foreignUpload = false;
        publisher = NativeCommentPublisher.install(framework.api, report(), getClass().getClassLoader(),
                ignored -> { }, "DouAi.CommentReply");
        publishers.add(publisher);
    }

    @After public void close() {
        for (NativeCommentPublisher installed : publishers) installed.close();
        ShadowLooper.idleMainLooper();
        for (File audio : audioFiles) audio.delete();
    }

    @Test public void sendsTheCapturedReplyModelThroughTheHostAndConfirmsTheExactNestedTarget() throws Throwable {
        CommentReplyEvent event = event("incoming", "root");
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        assertTrue(outcomes.isEmpty());
        Model model = Request.models.get(0);
        assertSame(event.targetComment, model.p);
        assertSame(event.work, model.y.work);
        assertEquals("正文", model.a);
        assertEquals("douai_comment_reply", model.n);
        assertTrue(publisher.ownsPublish(model));
        complete(0, response("work", "root", "incoming"));
        assertEquals(Collections.singletonList("sent"), outcomes);
        model.a = "已被更改";
        assertFalse(publisher.ownsPublish(model));
    }

    @Test public void legacyTargetsWithoutARepeatedWorkIdStillFailBeforeNativeSubmission() {
        for (String missingAid : new String[]{null, ""}) {
            CommentReplyEvent event = event("incoming", "root");
            assertFalse(event.nativeWorkBound);
            ((Comment) event.targetComment).awemeId = missingAid;
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event, "正文", () -> true,
                    () -> { fail("A legacy target without its work ID cannot reserve a send"); return true; },
                    () -> fail("A legacy target without its work ID cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void nativeWorkBoundTargetsWithoutARepeatedWorkIdUseCapturedWorkAndRequireAnExactAck() throws Throwable {
        int index = 0;
        for (String missingAid : new String[]{null, ""}) {
            CommentReplyEvent event = nativeWorkBoundEvent("incoming-" + index, "root");
            ((Comment) event.targetComment).awemeId = missingAid;
            List<String> outcomes = new ArrayList<>();
            AtomicInteger accepted = new AtomicInteger();
            publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals("The verified native work binding must permit a missing repeated target work ID", 1, accepted.get());
            assertTrue(outcomes.isEmpty());
            Model model = Request.models.get(index);
            assertSame(event.targetComment, model.p);
            assertSame(event.work, model.y.work);
            assertEquals(missingAid, ((Comment) event.targetComment).awemeId);
            complete(index, response("work", "root", event.incomingId));
            assertEquals(Collections.singletonList("sent"), outcomes);
            index++;
        }
        CommentReplyEvent wrongAck = nativeWorkBoundEvent("incoming-wrong-ack", "root");
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.sendReply(wrongAck, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        assertTrue(outcomes.isEmpty());
        complete(index, response("work", "root", "different-incoming"));
        assertEquals(Collections.singletonList("submitted"), outcomes);
    }

    @Test public void aStrictlyConvertedParentlessInboxReplyPublishesItsPrivateTargetAndChecksTheExactAck() throws Throwable {
        try (NativeCommentReplyInbox inbox = new NativeCommentReplyInbox(NativeCommentReplyInboxTest.report(),
                getClass().getClassLoader(), ignored -> { })) {
            int index = 0;
            for (boolean exactAck : new boolean[]{true, false}) {
                String incomingId = "directed-incoming-" + index;
                NativeCommentReplyInboxTest.Notice notice = NativeCommentReplyInboxTest.parentlessDirectedNotice(
                        "directed-notice-" + index, incomingId, "200", "101", 210);
                notice.interactiveGeneralNoticeStruct.aweme = new Work();
                NativeCommentReplyInboxTest.ReplyDetail detail = notice.interactiveGeneralNoticeStruct.additionalInfo.comment;
                detail.d = false;
                detail.f = "unrelated-ui-metadata";
                NativeCommentReplyInboxTest.Comment raw = detail.a;
                raw.awemeId = null;

                CommentReplyEvent event = inbox.extract(notice, 100);

                assertNotNull(event);
                assertTrue(event.nativeWorkBound);
                assertEquals(incomingId, event.incomingId);
                assertEquals("101", event.rootId);
                assertNotSame(raw, event.targetComment);
                NativeCommentReplyInboxTest.Comment captured = (NativeCommentReplyInboxTest.Comment) event.targetComment;
                assertEquals(incomingId, captured.cid);
                assertEquals("101", captured.replyId);
                assertEquals("0", captured.replyToReplyId);
                assertNull(captured.awemeId);
                assertSame(raw, detail.a);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);

                List<String> outcomes = new ArrayList<>();
                AtomicInteger accepted = new AtomicInteger();
                publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
                ShadowLooper.idleMainLooper();

                assertEquals(1, accepted.get());
                assertTrue(outcomes.isEmpty());
                Model model = Request.models.get(index);
                assertSame(captured, model.p);
                assertSame(event.work, model.y.work);
                assertEquals("101", model.p.replyId);
                assertEquals("0", model.p.replyToReplyId);
                assertEquals(incomingId, model.p.cid);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);
                complete(index, response("work", "101", exactAck ? incomingId : "different-incoming"));
                assertEquals(Collections.singletonList(exactAck ? "sent" : "submitted"), outcomes);
                index++;
            }
            assertEquals(2, Request.models.size());
        }
    }

    @Test public void realThreadLookupRecoveredInboxEventsKeepTheServerTargetAndRequireExactPublishAcks() throws Throwable {
        NativeCommentReplyInboxTest.FakeGroupResolver.group = 44;
        NativeCommentReplyInboxTest.FakeGroupResolver.failure = null;
        NativeCommentReplyInboxTest.AccountFactory.service = new NativeCommentReplyInboxTest.Account();
        NativeCommentReplyInboxTest.AccountFactory.service.user = new NativeCommentReplyInboxTest.User("100");
        for (int index = 0; index < 2; index++) {
            String incomingId = "thread-publish-" + index;
            NativeCommentThreadLookupTest.LookupHarness host = NativeCommentThreadLookupTest.harness("101", incomingId, 900);
            host.incoming.replyToReplyId = "101";
            NativeCommentReplyInboxTest.Notice sparse = NativeCommentReplyInboxTest.sparseNotice(
                    "thread-notice-" + index, incomingId, "200", 2000);
            NativeCommentReplyInboxTest.Comment raw = sparse.interactiveGeneralNoticeStruct.additionalInfo.comment.a;
            raw.awemeId = null;
            NativeCommentReplyInboxTest.Api api = new NativeCommentReplyInboxTest.Api();
            NativeCommentReplyInboxTest.Provider.b = api;
            api.pages.add(NativeCommentReplyInboxTest.page(false, 0, 0, sparse));
            try (NativeCommentReplyInbox inbox = new NativeCommentReplyInbox(NativeCommentReplyInboxTest.report(),
                    getClass().getClassLoader(), ignored -> { }, host.lookup)) {
                List<CommentReplyEvent> events = inbox.scan(100, 1000, () -> true);
                assertEquals(1, events.size());
                CommentReplyEvent event = events.get(0);
                assertSame(host.incoming, event.targetComment);
                assertSame(host.work, event.work);
                assertTrue(event.nativeWorkBound);
                assertEquals("101", event.rootId);
                assertEquals(2000, event.createdAt);
                assertNotSame(raw, event.targetComment);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);
                assertNull(raw.awemeId);

                AtomicInteger accepted = new AtomicInteger();
                List<String> outcomes = new ArrayList<>();
                publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
                ShadowLooper.idleMainLooper();

                assertEquals(1, accepted.get());
                assertTrue(outcomes.isEmpty());
                Model model = Request.models.get(index);
                assertSame("Publishing must use the complete raw server comment, without reconstructing it", host.incoming, model.p);
                assertSame(host.work, model.y.work);
                assertEquals(incomingId, model.p.cid);
                assertEquals("101", model.p.replyId);
                assertEquals("101", model.p.replyToReplyId);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);
                assertNull(raw.awemeId);
                complete(index, response("work", "101", index == 0 ? incomingId : "unrelated-incoming"));
                assertEquals(Collections.singletonList(index == 0 ? "sent" : "submitted"), outcomes);
            }
        }
        assertEquals(2, Request.models.size());
    }

    @Test public void nativeWorkBindingCannotOverrideConflictingOrChangedWorkCommentAndRootIdentity() {
        List<CommentReplyEvent> events = new ArrayList<>();
        CommentReplyEvent conflictingAid = nativeWorkBoundEvent("incoming", "root");
        ((Comment) conflictingAid.targetComment).awemeId = "another-work";
        events.add(conflictingAid);
        CommentReplyEvent changedWork = nativeWorkBoundEvent("incoming", "root");
        ((Work) changedWork.work).aid = "another-work";
        events.add(changedWork);
        CommentReplyEvent changedComment = nativeWorkBoundEvent("incoming", "root");
        ((Comment) changedComment.targetComment).cid = "another-comment";
        events.add(changedComment);
        CommentReplyEvent changedRoot = nativeWorkBoundEvent("incoming", "root");
        ((Comment) changedRoot.targetComment).replyId = "another-root";
        events.add(changedRoot);
        events.add(new CommentReplyEvent("notice", "work", "incoming", "root", "2", "对方", "回复", "原评",
                1000, new Work(), new Object(), true));
        for (CommentReplyEvent event : events) {
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event, "正文", () -> true,
                    () -> { fail("Changed identity cannot reserve a native send"); return true; },
                    () -> fail("Changed identity cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void nativeWorkBoundIdentityIsRecheckedAfterTheNativeReservation() {
        for (String change : new String[]{"work", "comment", "root"}) {
            CommentReplyEvent event = nativeWorkBoundEvent("incoming", "root");
            List<String> outcomes = new ArrayList<>();
            AtomicInteger reservations = new AtomicInteger();
            publisher.sendReply(event, "正文", () -> true, () -> {
                reservations.incrementAndGet();
                if ("work".equals(change)) ((Work) event.work).aid = "changed-work";
                if ("comment".equals(change)) ((Comment) event.targetComment).cid = "changed-comment";
                if ("root".equals(change)) ((Comment) event.targetComment).replyId = "changed-root";
                return true;
            }, () -> fail("Identity changed during reservation cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals("The initially verified native target should reach reservation", 1, reservations.get());
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void voiceAttachmentAndCapturedNestedReplyTargetCoexistAndCallbackStillChecksTheTarget() throws Throwable {
        SpeechPreparation.Audio audio = audio();
        CommentReplyEvent event = event("incoming", "root");
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.sendReply(event, "生成的语音正文", audio, () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        Model model = Request.models.get(0);
        assertSame(event.targetComment, model.p);
        assertSame(event.work, model.y.work);
        assertEquals("", model.a);
        assertNotNull(model.A);
        assertSame(audio.file, model.A.audio.localFile);
        assertEquals(audio.duration, model.A.audio.duration.longValue());
        assertEquals(Collections.emptyList(), model.A.audio.waveList);
        assertEquals("", model.A.text.toString());
        assertEquals(Collections.emptyList(), model.d);
        assertEquals("douai_comment_reply", model.n);
        assertTrue(publisher.ownsPublish(model));
        complete(0, response("work", "different-root", "incoming"));
        assertEquals(Collections.singletonList("submitted"), outcomes);
    }

    @Test public void checkedFalseAfterOwnedVoiceUploadIsAcceptedAndStaysSubmittedUntilAnExactAck() throws Throwable {
        Request.uploadAudio = true;
        SpeechPreparation.Audio audio = audio();
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        // Empty text is valid for an audio-only host comment, while the reply target remains mandatory.
        publisher.sendReply(event("incoming", "root"), "", audio, () -> true, () -> true,
                accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        assertEquals(1, Request.models.size());
        assertTrue(outcomes.isEmpty());
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(31));
        assertEquals(Collections.singletonList("submitted"), outcomes);
        assertEquals(1, Request.models.size());
        complete(0, response("work", "root", "incoming"));
        assertEquals(Arrays.asList("submitted", "sent"), outcomes);
    }

    @Test public void foreignVoiceUploadDoesNotMakeARejectedReplyLookAccepted() throws Exception {
        Request.uploadAudio = true;
        Request.foreignUpload = true;
        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event("incoming", "root"), "正文", audio(), () -> true, () -> true,
                () -> fail("Foreign upload cannot accept this reply"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
    }

    @Test public void invalidVoiceAttachmentFailsBeforeReservationOrNativeReplySubmission() throws Exception {
        SpeechPreparation.Audio audio = audio();
        assertTrue(audio.file.delete());
        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event("incoming", "root"), "正文", audio, () -> true,
                () -> { fail("Invalid audio cannot reserve a native send"); return true; },
                () -> fail("Invalid audio cannot be accepted"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
        assertTrue(Request.models.isEmpty());
    }

    @Test public void incompleteOrMisroutedCallbacksRemainUnknownWhileExplicitErrorsFail() throws Throwable {
        for (int index = 0; index < 4; index++) {
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event("incoming", "root"), "正文", () -> true, () -> true, () -> { }, outcomes::add);
            ShadowLooper.idleMainLooper();
            Response response = response("work", "root", "incoming");
            if (index == 0) response.comment.replyId = "other-root";
            if (index == 1) response.comment.replyToReplyId = null;
            if (index == 2) response.comment.awemeId = null;
            if (index == 3) response.status_code = 12;
            complete(index, response);
            assertEquals(Collections.singletonList(index == 3 ? "failed" : "submitted"), outcomes);
        }
    }

    @Test public void rejectsChangedWorkCommentRootAndForeignModelTypesBeforeTheHostSend() {
        List<CommentReplyEvent> events = new ArrayList<>();
        CommentReplyEvent changedComment = event("incoming", "root");
        ((Comment) changedComment.targetComment).cid = "another";
        events.add(changedComment);
        CommentReplyEvent changedAid = event("incoming", "root");
        ((Comment) changedAid.targetComment).awemeId = "another-work";
        events.add(changedAid);
        CommentReplyEvent changedRoot = event("incoming", "root");
        ((Comment) changedRoot.targetComment).replyId = "another-root";
        events.add(changedRoot);
        CommentReplyEvent changedWork = event("incoming", "root");
        ((Work) changedWork.work).aid = "another-work";
        events.add(changedWork);
        events.add(new CommentReplyEvent("notice", "work", "incoming", "root", "2", "对方", "回复", "原评",
                1000, new Work(), new Object()));
        for (CommentReplyEvent event : events) {
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event, "正文", () -> true, () -> true, () -> fail("Rejected reply was accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void rechecksReplyIdentityAfterTheNativeReservationAndHonorsCancellation() {
        CommentReplyEvent event = event("incoming", "root");
        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event, "正文", () -> true, () -> {
            ((Comment) event.targetComment).cid = "changed-during-reservation";
            return true;
        }, () -> fail("Changed reply was accepted"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
        publisher.sendReply(event("incoming", "root"), "正文", () -> false, () -> true,
                () -> fail("Cancelled reply was accepted"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("failed", "cancelled"), outcomes);
        assertTrue(Request.models.isEmpty());
    }

    @Test public void independentNamespacesKeepOldWorkCommentsAndRepliesOwnedByTheirSenders() throws Throwable {
        NativeCommentPublisher old = NativeCommentPublisher.install(framework.api, report(), getClass().getClassLoader(), ignored -> { });
        publishers.add(old);
        assertEquals(6, framework.ids.size());
        assertTrue(framework.ids.contains("DouAi.AutoComment.auto_comment_publish_success"));
        assertTrue(framework.ids.contains("DouAi.CommentReply.auto_comment_publish_success"));
        List<String> oldResults = new ArrayList<>(), replyResults = new ArrayList<>();
        old.send(new Work(), "work", "作品评论", () -> true, () -> true, () -> { }, oldResults::add);
        publisher.sendReply(event("root", "root"), "评论回复", () -> true, () -> true, () -> { }, replyResults::add);
        ShadowLooper.idleMainLooper();
        assertNull(Request.models.get(0).p);
        assertEquals("douai_auto", Request.models.get(0).n);
        complete(1, response("work", "root", null));
        assertTrue(oldResults.isEmpty());
        assertEquals(Collections.singletonList("sent"), replyResults);
        complete(0, response("work", null, null));
        assertEquals(Collections.singletonList("sent"), oldResults);
        assertEquals(Collections.singletonList("sent"), replyResults);
    }

    private void complete(int index, Response response) throws Throwable {
        framework.invoke(Request.class.getMethod("success", Response.class), Request.instances.get(index), response);
        ShadowLooper.idleMainLooper();
    }

    private SpeechPreparation.Audio audio() throws Exception {
        File file = Files.createTempFile("douai-comment-reply-", ".wav").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
        audioFiles.add(file);
        return new SpeechPreparation.Audio(file, 1400);
    }

    private static CommentReplyEvent event(String incoming, String root) {
        Comment target = new Comment();
        target.cid = incoming;
        target.awemeId = "work";
        target.replyId = incoming.equals(root) ? "0" : root;
        return new CommentReplyEvent("notice-" + incoming, "work", incoming, root, "2", "对方", "收到回复", "我的原评论",
                1000, new Work(), target);
    }

    private static CommentReplyEvent nativeWorkBoundEvent(String incoming, String root) {
        Comment target = new Comment();
        target.cid = incoming;
        target.replyId = incoming.equals(root) ? "0" : root;
        return new CommentReplyEvent("notice-" + incoming, "work", incoming, root, "2", "对方", "收到回复", "我的原评论",
                1000, new Work(), target, true);
    }

    private static Response response(String aid, String root, String incoming) {
        Response response = new Response();
        response.comment.awemeId = aid;
        response.comment.replyId = root;
        response.comment.replyToReplyId = incoming;
        return response;
    }

    public static final class Work extends NativeCommentReplyInboxTest.Work {
        public String getAid() { return aid; }
    }
    public static final class Comment extends NativeCommentReplyInboxTest.Comment {
        public Comment() { cid = "published"; }
    }
    public static final class Response { public int status_code; public Comment comment = new Comment(); }
    public static final class State { public NativeCommentReplyInboxTest.Work work; }
    public static final class Resolver {
        public static State context(NativeCommentReplyInboxTest.Work work) { State state = new State(); state.work = work; return state; }
        public static NativeCommentReplyInboxTest.Work work(State state) { return state.work; }
    }
    public static final class Model {
        public String a, n;
        public State y;
        public Attachment A;
        public NativeCommentReplyInboxTest.Comment p;
        public List<?> d;
    }
    public static final class Attachment { public Audio audio; public CharSequence text; }
    public static final class Audio {
        public final File localFile;
        public final List<?> waveList;
        public final Long duration;
        public Audio(File file, List<?> waves, Long duration) { localFile = file; waveList = waves; this.duration = duration; }
    }
    public static final class UploadCallback {
        public final Object l0, l1;
        UploadCallback(Object model, Object owner) { l0 = model; l1 = owner; }
    }
    public static final class Uploader { public static void upload(Object state, String path, Object callback, Object listener) { } }
    public static final class Request {
        static final List<Model> models = new ArrayList<>();
        static final List<Request> instances = new ArrayList<>();
        static Framework framework;
        static boolean uploadAudio, foreignUpload;
        public boolean checked(Model model) throws Throwable {
            models.add(model);
            instances.add(this);
            if (!uploadAudio) return true;
            framework.invoke(Uploader.class.getMethod("upload", Object.class, String.class, Object.class, Object.class), null,
                    model.y, model.A.audio.localFile.getAbsolutePath(),
                    new UploadCallback(foreignUpload ? new Model() : model, this), new Object());
            return false;
        }
        public void success(Response response) { }
        public void failed(Exception failure) { }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "portrait_comment_aweme_resolver", Resolver.class.getMethod("work", State.class));
        add(rules, "auto_comment_work_context", Resolver.class.getMethod("context", NativeCommentReplyInboxTest.Work.class));
        add(rules, "auto_comment_checked_request", Request.class.getMethod("checked", Model.class));
        add(rules, "auto_comment_publish_success", Request.class.getMethod("success", Response.class));
        add(rules, "auto_comment_publish_failure", Request.class.getMethod("failed", Exception.class));
        add(rules, "auto_comment_audio_upload", Uploader.class.getMethod("upload", Object.class, String.class, Object.class, Object.class));
        rules.add(new RuleScanResult("comment_audio_model", "comment", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(Audio.class), "", ""))));
        return new HostScanReport("fixture", rules, false);
    }

    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "comment", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }

    private static final class Framework {
        final Map<Executable, List<XposedInterface.Hooker>> hooks = new HashMap<>();
        final Set<String> ids = new HashSet<>();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!"hook".equals(method.getName())) return null;
                    Executable target = (Executable) arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if ("setId".equals(call.getName())) {
                                    assertTrue("Duplicate hook ID: " + values[0], ids.add((String) values[0]));
                                }
                                if (!"intercept".equals(call.getName())) return builder;
                                hooks.computeIfAbsent(target, ignored -> new ArrayList<>()).add((XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, unused) -> null);
                            });
                });

        Object invoke(Method target, Object receiver, Object... input) throws Throwable { return invokeAt(target, receiver, input, 0); }

        private Object invokeAt(Method target, Object receiver, Object[] input, int index) throws Throwable {
            List<XposedInterface.Hooker> entries = hooks.getOrDefault(target, Collections.emptyList());
            if (index >= entries.size()) return target.invoke(receiver, input);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getThisObject": return receiver;
                            case "getArg": return input[(Integer) arguments[0]];
                            case "getArgs": return Arrays.asList(input);
                            case "proceed": return invokeAt(target, receiver,
                                    arguments == null || arguments.length == 0 ? input : (Object[]) arguments[0], index + 1);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return entries.get(index).intercept(chain);
        }
    }
}

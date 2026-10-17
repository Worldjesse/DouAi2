package com.douai.module.feature;

import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Read-only notice pagination. No notification read state or host UI state is changed. */
final class NativeCommentReplyInbox implements AutoCloseable {
    static final int PAGE_SIZE = 20;
    static final int MAX_PAGES = 50;
    private static final long REQUEST_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int COMMENT_GROUP = 3;
    private final Consumer<String> log;
    private final Method fetch, accountService, currentUser, parsePush;
    private final Field api, responseStatus, responseItems, responseMore, responseMax, responseMin;
    private final Field noticeComment, noticeId, noticeTime, noticeDisabled, itemDisabled;
    private final Field incoming, originalReply, levelOne, noticeWork, invisible;
    private final Field cid, commentAid, commentUser, commentText, commentTime, replyId, replyToReplyId;
    private final Field uid, nickname, workAid, pushType;
    private final Class<?> responseClass, noticeClass, commentNoticeClass, commentClass, userClass, workClass;
    private XposedInterface.HookHandle handle;
    private volatile boolean closed;
    private volatile long scannedThrough;
    private List<String> baselineIds = java.util.Collections.emptyList();

    NativeCommentReplyInbox(HostScanReport report, ClassLoader loader, Consumer<String> log) throws Exception {
        this.log = log;
        fetch = HostTargets.method(report, "comment_reply_notice_fetch", loader);
        Class<?> provider = HostTargets.type(report, "comment_reply_notice_provider", loader);
        api = HostTargets.field(provider, "b");
        Class<?>[] expected = {long.class, long.class, int.class, int.class, Integer.class, int.class,
                Integer.class, Integer.class, int.class, String.class, String.class, String.class,
                int.class, String.class, int.class, String.class, int.class, int.class,
                String.class, int.class, int.class, Integer.class, int.class};
        if (!java.util.Arrays.equals(expected, fetch.getParameterTypes())
                || !Future.class.isAssignableFrom(fetch.getReturnType())
                || !Modifier.isStatic(api.getModifiers()) || api.getType() != fetch.getDeclaringClass()) {
            throw new IllegalStateException("Comment reply notice API contract changed");
        }
        responseClass = HostTargets.type(report, "comment_reply_notice_response", loader);
        noticeClass = HostTargets.type(report, "comment_reply_base_notice", loader);
        commentNoticeClass = HostTargets.type(report, "comment_reply_comment_notice", loader);
        commentClass = HostTargets.type(report, "comment_reply_comment_model", loader);
        userClass = HostTargets.type(report, "comment_reply_user_model", loader);
        workClass = HostTargets.type(report, "comment_reply_work_model", loader);
        responseStatus = field(responseClass, "status_code", int.class);
        responseItems = field(responseClass, "items", List.class);
        responseMore = field(responseClass, "hasMore", boolean.class);
        responseMax = field(responseClass, "maxTime", long.class);
        responseMin = field(responseClass, "minTime", long.class);
        noticeComment = field(noticeClass, "commentNotice", commentNoticeClass);
        noticeId = field(noticeClass, "nid", String.class);
        noticeTime = field(noticeClass, "createTime", long.class);
        noticeDisabled = field(noticeClass, "noticeDisable", boolean.class);
        itemDisabled = field(noticeClass, "itemDisable", boolean.class);
        incoming = field(commentNoticeClass, "comment", commentClass);
        originalReply = field(commentNoticeClass, "replyComment", commentClass);
        levelOne = field(commentNoticeClass, "level1Comment", commentClass);
        noticeWork = field(commentNoticeClass, "aweme", workClass);
        invisible = field(commentNoticeClass, "commentUnvisible", int.class);
        cid = field(commentClass, "cid", String.class);
        commentAid = field(commentClass, "awemeId", String.class);
        commentUser = field(commentClass, "user", userClass);
        commentText = field(commentClass, "text", String.class);
        commentTime = field(commentClass, "createTime", long.class);
        replyId = field(commentClass, "replyId", String.class);
        replyToReplyId = field(commentClass, "replyToReplyId", String.class);
        uid = field(userClass, "uid", String.class);
        nickname = field(userClass, "nickname", String.class);
        workAid = field(workClass, "aid", String.class);
        accountService = HostTargets.method(report, "media_account_service", loader);
        currentUser = HostTargets.method(report, "media_current_user", loader);
        if (!Modifier.isStatic(accountService.getModifiers()) || currentUser.getReturnType() != userClass) {
            throw new IllegalStateException("Comment reply account contract changed");
        }
        parsePush = HostTargets.method(report, "comment_reply_push_parse", loader);
        Class<?> pushClass = HostTargets.type(report, "comment_reply_push_model", loader);
        pushType = field(pushClass, "aggrNoticeType", String.class);
        if (!Modifier.isStatic(parsePush.getModifiers()) || parsePush.getReturnType() != pushClass) {
            throw new IllegalStateException("Comment reply push contract changed");
        }
    }

    static NativeCommentReplyInbox install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, Consumer<String> log,
                                          Runnable incomingSignal) throws Exception {
        NativeCommentReplyInbox inbox = new NativeCommentReplyInbox(report, loader, log);
        try {
            Method receive = HostTargets.method(report, "comment_reply_push_receive", loader);
            if (receive.getParameterTypes().length != 2
                    || receive.getParameterTypes()[0] != inbox.parsePush.getParameterTypes()[0]
                    || receive.getParameterTypes()[1] != boolean.class) {
                throw new IllegalStateException("Comment reply push receiver contract changed");
            }
            inbox.handle = framework.hook(receive).setId("DouAi.CommentReply.Incoming")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        try {
                            if (!inbox.closed && inbox.isCommentPush(chain.getArg(0))) incomingSignal.run();
                        } catch (Exception failure) {
                            inbox.safeLog("Comment reply push observation failed: " + failure.getClass().getSimpleName());
                        }
                        return chain.proceed();
                    });
            if (inbox.handle == null) throw new IllegalStateException("Empty comment reply push hook");
            return inbox;
        } catch (Exception failure) { inbox.close(); throw failure; }
    }

    boolean isCommentPush(Object message) throws Exception {
        Object parsed = invoke(parsePush, null, message);
        return parsed != null && "comment".equals(pushType.get(parsed));
    }

    synchronized long baseline(long account, BooleanSupplier valid) throws Exception {
        long newest = 0, max = 0, min = 0;
        Set<String> ids = new LinkedHashSet<>();
        Set<String> cursors = new HashSet<>();
        for (int index = 0; index < MAX_PAGES; index++) {
            Page page = page(account, max, min, valid);
            for (Object notice : page.items) {
                if (!noticeClass.isInstance(notice)) throw new IllegalStateException("Unexpected notice model");
                newest = Math.max(newest, eventTime(notice));
                Object model = noticeComment.get(notice);
                Object comment = model == null ? null : incoming.get(model);
                if (comment != null) {
                    String value = id(cid.get(comment));
                    if (!value.isEmpty()) ids.add(value);
                }
            }
            if (newest <= 0 && !page.items.isEmpty()) throw new IllegalStateException("Notice baseline lacks timestamps");
            // Only finish collecting the newest second, never traverse the full historical inbox.
            if (!page.more || seconds(page.min) > 0 && seconds(page.min) < newest) {
                verifyAccount(account, valid);
                baselineIds = new ArrayList<>(ids);
                return newest > 0 ? newest : System.currentTimeMillis() / 1000;
            }
            ensureProgress(page, min, cursors);
            max = page.max;
            min = page.min;
        }
        throw new IllegalStateException("Comment reply baseline limit reached before collecting its entire second");
    }

    synchronized List<String> baselineCommentIds() { return new ArrayList<>(baselineIds); }
    long scannedThrough() { return scannedThrough; }

    synchronized List<CommentReplyEvent> scan(long account, long sinceSeconds, BooleanSupplier valid) throws Exception {
        if (sinceSeconds <= 0) throw new IllegalArgumentException("Comment reply scan requires an established baseline");
        Map<String, CommentReplyEvent> events = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        long max = 0, min = 0, newestNotice = 0;
        for (int index = 0; index < MAX_PAGES; index++) {
            Page page = page(account, max, min, valid);
            for (Object notice : page.items) {
                if (!noticeClass.isInstance(notice)) throw new IllegalStateException("Unexpected notice model");
                newestNotice = Math.max(newestNotice, eventTime(notice));
                CommentReplyEvent event = extract(notice, account);
                if (event != null && event.createdAt >= sinceSeconds) events.putIfAbsent(event.incomingId, event);
            }
            // The host retains both cursors: 0dHG:339-351 -> 0dHH:318/322/540.
            if (!page.more || seconds(page.min) > 0 && seconds(page.min) < sinceSeconds) {
                verifyAccount(account, valid);
                List<CommentReplyEvent> ordered = new ArrayList<>(events.values());
                ordered.sort(Comparator.comparingLong((CommentReplyEvent event) -> event.createdAt)
                        .thenComparing(event -> event.incomingId));
                scannedThrough = newestNotice;
                return ordered;
            }
            ensureProgress(page, min, cursors);
            max = page.max;
            min = page.min;
        }
        throw new IllegalStateException("Comment reply pagination limit reached before baseline; watermark must not advance");
    }

    private Page page(long account, long max, long min, BooleanSupplier valid) throws Exception {
        verifyAccount(account, valid);
        Object service;
        try { service = api.get(null); }
        catch (LinkageError unavailable) { throw new IllegalStateException("Host notice API initialization failed", unavailable); }
        if (service == null || !fetch.getDeclaringClass().isInstance(service)) {
            throw new IllegalStateException("Host notice API instance is not ready");
        }
        // No subtype filter: the native comment tab combines received comments and replies.
        Object value = invoke(fetch, service, max, min, PAGE_SIZE, COMMENT_GROUP, Integer.valueOf(0), 0,
                Integer.valueOf(0), Integer.valueOf(0), 1, "", "", "", -1, "", 0, "", 0, 0, "", 0, 1, null, 0);
        if (!(value instanceof Future<?>)) throw new IllegalStateException("Host notice request returned no Future");
        Future<?> future = (Future<?>) value;
        Object response;
        long deadline = System.nanoTime() + REQUEST_TIMEOUT_NANOS;
        try {
            while (true) {
                checkValid(valid);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Comment reply notice request timed out");
                try { response = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(500)), TimeUnit.NANOSECONDS); break; }
                catch (TimeoutException waiting) { if (System.nanoTime() >= deadline) throw waiting; }
            }
        } catch (Exception failure) {
            future.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
        verifyAccount(account, valid);
        if (!responseClass.isInstance(response) || responseStatus.getInt(response) != 0) {
            throw new IllegalStateException("Comment reply notice response rejected or unrecognized");
        }
        Object list = responseItems.get(response);
        boolean more = responseMore.getBoolean(response);
        if (list == null && more || list != null && !(list instanceof List<?>)) {
            throw new IllegalStateException("Comment reply notice response lacks a page");
        }
        return new Page(list == null ? java.util.Collections.emptyList() : new ArrayList<>((List<?>) list),
                more, responseMax.getLong(response), responseMin.getLong(response));
    }

    CommentReplyEvent extract(Object notice, long account) throws Exception {
        if (!noticeClass.isInstance(notice)) throw new IllegalStateException("Unexpected notice model");
        String nid = string(noticeId.get(notice));
        Object model = noticeComment.get(notice);
        if (model == null) return skip(nid, "unsupported or missing comment notice payload");
        if (noticeDisabled.getBoolean(notice) || itemDisabled.getBoolean(notice) || invisible.getInt(model) != 0) {
            return skip(nid, "disabled or invisible comment");
        }
        // 0dN0:219-227 passes CommentNotice.comment to the host reply action's 0dMx.a.
        Object target = incoming.get(model);
        Object work = noticeWork.get(model);
        if (target == null || work == null) return skip(nid, "missing incoming comment or work");
        String incomingId = id(cid.get(target));
        String aid = id(workAid.get(work));
        String originAid = id(commentAid.get(target));
        Object sender = commentUser.get(target);
        String senderId = sender == null ? "" : id(uid.get(sender));
        if (incomingId.isEmpty() || aid.isEmpty() || !aid.equals(originAid) || senderId.isEmpty()) {
            return skip(nid, "incomplete or inconsistent incoming identity");
        }
        String me = Long.toString(account);
        if (me.equals(senderId)) return skip(nid, "incoming comment is own message");
        String parent = id(replyToReplyId.get(target));
        String root = id(replyId.get(target));
        if (parent.isEmpty()) parent = root;
        if (parent.isEmpty()) return skip(nid, "incoming comment is not a reply");
        Object original = null;
        for (Object candidate : new Object[]{originalReply.get(model), levelOne.get(model)}) {
            if (candidate == null || !parent.equals(id(cid.get(candidate)))) continue;
            Object author = commentUser.get(candidate);
            if (author == null || !me.equals(id(uid.get(author)))) {
                return skip(nid, "referenced comment author is not current account");
            }
            String candidateAid = id(commentAid.get(candidate));
            if (!candidateAid.isEmpty() && !aid.equals(candidateAid)) return skip(nid, "referenced work differs");
            original = candidate;
        }
        if (original == null) return skip(nid, "cannot prove reply references own comment");
        String text = string(commentText.get(target)).trim();
        String originalText = string(commentText.get(original)).trim();
        if (originalText.isEmpty() || text.isEmpty() && !AutomationReplyMedia.hasCommentImages(target)) {
            return skip(nid, "missing reply content or own comment context");
        }
        long created = eventTime(notice);
        if (created <= 0) return skip(nid, "incoming reply lacks timestamp");
        return new CommentReplyEvent(nid, aid, incomingId, root.isEmpty() ? incomingId : root,
                senderId, string(nickname.get(sender)), text, originalText, created, work, target);
    }

    /** Watermarks describe notice arrival, including replies published earlier but delivered later. */
    private long eventTime(Object notice) throws Exception {
        long created = noticeTime.getLong(notice);
        if (created > 0) return seconds(created);
        Object model = noticeComment.get(notice);
        Object comment = model == null ? null : incoming.get(model);
        return comment == null ? 0 : seconds(commentTime.getLong(comment));
    }

    private static void ensureProgress(Page page, long previousMin, Set<String> cursors) {
        if (page.items.isEmpty() || page.min <= 0 || seconds(page.max) < seconds(page.min)
                || previousMin > 0 && seconds(page.min) > seconds(previousMin)
                || !cursors.add(page.max + ":" + page.min)) {
            throw new IllegalStateException("Comment reply pagination made no progress");
        }
    }

    // InteractiveSessionInteract:34-77/304-317 accepts either seconds or milliseconds at this threshold.
    private static long seconds(long value) { return value >= 10_000_000_000L ? value / 1000 : value; }

    private void verifyAccount(long account, BooleanSupplier valid) throws Exception {
        checkValid(valid);
        Object service = invoke(accountService, null);
        if (account <= 0 || !currentUser.getDeclaringClass().isInstance(service)) {
            throw new IllegalStateException("Comment reply account service is not ready");
        }
        Object user = invoke(currentUser, service);
        if (!userClass.isInstance(user) || !Long.toString(account).equals(uid.get(user))) {
            throw new CancellationException("Comment reply account changed");
        }
    }

    private void checkValid(BooleanSupplier valid) {
        if (closed || Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) {
            throw new CancellationException("Comment reply scan no longer valid");
        }
    }

    private CommentReplyEvent skip(String nid, String reason) { safeLog("Comment reply notice skipped; nid=" + nid + "; " + reason); return null; }
    private void safeLog(String message) { try { log.accept(message); } catch (RuntimeException ignored) { } }
    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
    private static String id(Object value) { String text = string(value); return "0".equals(text) ? "" : text; }
    private static Field field(Class<?> owner, String name, Class<?> expected) throws Exception {
        Field result = HostTargets.field(owner, name);
        if (result.getType() != expected) throw new IllegalStateException("Comment reply model changed: " + name);
        return result;
    }
    private static Object invoke(Method method, Object owner, Object... arguments) throws Exception {
        try { return method.invoke(owner, arguments); }
        catch (LinkageError unavailable) { throw new IllegalStateException("Host notice target is unavailable", unavailable); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Host notice invocation failed", cause);
        }
    }
    @Override public void close() {
        closed = true;
        if (handle != null) { try { handle.unhook(); } catch (RuntimeException ignored) { } handle = null; }
    }
    private static final class Page {
        final List<?> items;
        final boolean more;
        final long max, min;
        Page(List<?> items, boolean more, long max, long min) { this.items = items; this.more = more; this.max = max; this.min = min; }
    }
}

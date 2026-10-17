package com.douai.module.feature;

/** A verified incoming reply and its captured host publish target. */
final class CommentReplyEvent {
    final String noticeId, aid, incomingId, rootId, senderUid, senderName, text, originalText;
    final long createdAt;
    final Object work, targetComment;

    CommentReplyEvent(String noticeId, String aid, String incomingId, String rootId,
                      String senderUid, String senderName, String text, String originalText,
                      long createdAt, Object work, Object targetComment) {
        this.noticeId = noticeId;
        this.aid = aid;
        this.incomingId = incomingId;
        this.rootId = rootId;
        this.senderUid = senderUid;
        this.senderName = senderName;
        this.text = text;
        this.originalText = originalText;
        this.createdAt = createdAt;
        this.work = work;
        this.targetComment = targetComment;
    }
}

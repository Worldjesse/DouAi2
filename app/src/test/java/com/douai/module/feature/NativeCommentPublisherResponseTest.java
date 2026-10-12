package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeCommentPublisherResponseTest {
    @Test public void successRequiresStatusAndARealCommentIdForTheOriginalWork() {
        Response response = new Response();
        response.comment = new Comment();
        response.comment.cid = "published";
        response.comment.awemeId = "origin";
        assertTrue(NativeCommentPublisher.validResponse(response, "origin"));
        response.status_code = 1;
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        response.status_code = 0;
        response.comment.awemeId = "another";
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        response.comment.awemeId = "origin";
        response.comment.cid = "";
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        response.comment = null;
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        assertFalse(NativeCommentPublisher.validResponse(null, "origin"));
    }

    public static final class Response { public int status_code; public Comment comment; }
    public static final class Comment { public String cid; public String awemeId; }
}

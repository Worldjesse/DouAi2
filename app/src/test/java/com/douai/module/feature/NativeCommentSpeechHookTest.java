package com.douai.module.feature;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NativeCommentSpeechHookTest {
    public static final class Attachment {
        public Object audio;
        public Object emoji;
        public Object video;
        public Object cameraStruct;
        public Object groupChat;
        public List<Object> imageList = Collections.emptyList();
    }

    @Test public void convertsOnlyPlainTextAndPreservesOtherNativeAttachments() {
        Attachment input = new Attachment();
        assertTrue(NativeCommentSpeechHook.isTextOnly(input, "评论"));
        assertFalse(NativeCommentSpeechHook.isTextOnly(input, "  "));
        input.audio = new Object();
        assertFalse(NativeCommentSpeechHook.isTextOnly(input, "评论"));
        input.audio = null;
        input.imageList = Collections.singletonList(new Object());
        assertFalse(NativeCommentSpeechHook.isTextOnly(input, "评论"));
        input.imageList = Collections.emptyList();
        input.emoji = new Object();
        assertFalse(NativeCommentSpeechHook.isTextOnly(input, "评论"));
    }
}

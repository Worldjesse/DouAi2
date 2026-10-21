package com.douai.module.feature;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class NativeCommentSpeechHookTest {
    public static final class Attachment {
        public Object audio;
        public Object emoji;
        public Object video;
        public Object cameraStruct;
        public Object groupChat;
        public List<Object> imageList = Collections.emptyList();
        public Attachment copy() { throw new AssertionError("Rejected long press must not copy a model"); }
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

    @Test public void longPressAttachmentIsConsumedAndNeverFallsBackToNativeText() throws Throwable {
        for (String field : new String[]{"audio", "emoji", "video", "cameraStruct", "groupChat", "imageList"}) {
            Model model = new Model();
            Attachment.class.getField(field).set(model.A, field.equals("imageList")
                    ? Collections.singletonList(new Object()) : new Object());
            NativeCommentSpeechHook hook = hook();
            assertTrue(hook.armLongPress(gesture(() -> true)));
            AtomicInteger proceeded = new AtomicInteger();
            assertNull(hook.intercept(chain(model, proceeded), Publisher.class.getMethod("submit", Model.class)));
            assertEquals(0, proceeded.get());
            assertEquals("#voice", model.a);
            // Clearing attachments can be followed by a new deliberate long press of the same draft.
            assertTrue(hook.armLongPress(gesture(() -> true)));
            hook.close();
        }
    }

    @Test public void deferredConfirmationSurvivesTheOriginalClickAndRejectsDuplicates() throws Throwable {
        NativeCommentSpeechHook hook = hook();
        HashVoiceGesture token = gesture(() -> true);
        assertTrue(hook.armLongPress(token));
        AtomicInteger proceeded = new AtomicInteger();
        // There is deliberately no ThreadLocal around these later model callbacks.
        assertNull(hook.intercept(chain(new Model(), proceeded), Publisher.class.getMethod("submit", Model.class)));
        assertNull(hook.intercept(chain(new Model(), proceeded), Publisher.class.getMethod("submit", Model.class)));
        assertFalse(token.claim());
        assertEquals(0, proceeded.get());
        hook.close();
    }

    @Test public void disabledOrChangedContextStillConsumesItsLateNativeConfirmation() throws Throwable {
        for (boolean disable : new boolean[]{false, true}) {
            NativeCommentSpeechHook hook = hook();
            AtomicBoolean current = new AtomicBoolean(true);
            assertTrue(hook.armLongPress(gesture(current::get)));
            if (disable) hook.setOptions(new LocalOptions(Collections.emptyMap()));
            else current.set(false);
            AtomicInteger proceeded = new AtomicInteger();
            assertNull(hook.intercept(chain(new Model(), proceeded), Publisher.class.getMethod("submit", Model.class)));
            assertEquals(0, proceeded.get());
            hook.close();
        }
    }

    @Test public void unrelatedReplyTargetsAndAutomaticPublishingRemainNative() throws Throwable {
        NativeCommentSpeechHook hook = hook();
        assertTrue(hook.armLongPress(gesture(() -> true)));
        AtomicInteger proceeded = new AtomicInteger();
        Model other = new Model();
        other.p = new Reply("other-reply");
        hook.intercept(chain(other, proceeded), Publisher.class.getMethod("submit", Model.class));
        hook.setAutomaticPublishGuard(ignored -> true);
        hook.intercept(chain(new Model(), proceeded), Publisher.class.getMethod("submit", Model.class));
        assertEquals(2, proceeded.get());
        hook.close();
    }

    @Test public void missingTargetMetadataOnAnOwnedDraftFailsClosed() throws Throwable {
        NativeCommentSpeechHook hook = hook();
        assertTrue(hook.armLongPress(gesture(() -> true)));
        Model model = new Model(); model.y = null;
        AtomicInteger proceeded = new AtomicInteger();
        assertNull(hook.intercept(chain(model, proceeded), Publisher.class.getMethod("submit", Model.class)));
        assertEquals(0, proceeded.get());
        hook.close();
    }

    @Test public void oldCommentOptionDoesNotEnableLongPressAndLongPressDoesNotEnableOldClick() {
        LocalOptions longOnly = new LocalOptions(Collections.singletonMap("hash_long_press_voice", true));
        LocalOptions oldOnly = new LocalOptions(Collections.singletonMap("comment_tts", true));
        assertTrue(NativeCommentSpeechHook.speechEnabled(longOnly, true));
        assertFalse(NativeCommentSpeechHook.speechEnabled(longOnly, false));
        assertFalse(NativeCommentSpeechHook.speechEnabled(oldOnly, true));
        assertTrue(NativeCommentSpeechHook.speechEnabled(oldOnly, false));
    }

    public static final class Work { public String groupId = "work"; public String serviceId = "service"; }
    public static final class Reply {
        final String id;
        Reply(String id) { this.id = id; }
        public String getCid() { return id; }
    }
    public static final class Model {
        public String a = "#voice";
        public Attachment A = new Attachment();
        public Work y = new Work();
        public Reply p = new Reply("reply");
        public Model copy() { throw new AssertionError("Rejected long press must not copy a model"); }
    }
    public static final class Audio { public Audio(File file, List<?> wave, Long duration) { } }
    public static final class Publisher { public void submit(Model model) { throw new AssertionError("Native text leaked"); } }

    private static NativeCommentSpeechHook hook() throws Exception {
        return new NativeCommentSpeechHook(Model.class, Attachment.class, Audio.class,
                Model.class.getMethod("copy"), Attachment.class.getMethod("copy"),
                new LocalOptions(Collections.singletonMap("hash_long_press_voice", true)), ignored -> { });
    }
    private static HashVoiceGesture gesture(java.util.function.BooleanSupplier current) {
        return new HashVoiceGesture("#voice", "work", "service", "reply", current, () -> true, () -> { });
    }
    private static XposedInterface.Chain chain(Model model, AtomicInteger proceeded) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(NativeCommentSpeechHookTest.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getArg")) return model;
                    if (method.getName().equals("proceed")) { proceeded.incrementAndGet(); return null; }
                    throw new AssertionError(method.getName());
                });
    }
}

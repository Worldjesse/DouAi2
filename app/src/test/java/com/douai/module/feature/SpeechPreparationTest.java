package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

public final class SpeechPreparationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final LocalOptions options = new LocalOptions(Collections.emptyMap());

    @Test public void resultDeliveredAfterCancellationIsDeletedByTheCallbackOwner() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicReference<BiConsumer<LocalSpeechSynthesis.Result, String>> callback = new AtomicReference<>();
        assertThrows(CancellationException.class, () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                valid::get, (context, text, settings, completed) -> {
                    callback.set(completed);
                    valid.set(false);
                    return () -> { };
                }, (source, context) -> { throw new AssertionError("Cancelled request must not encode"); }));
        File late = temporary.newFile("late.wav");
        callback.get().accept(new LocalSpeechSynthesis.Result(late, 1000), null);
        assertFalse(late.exists());
    }

    @Test public void synchronousCloseCallbackIsDeletedAfterPreparationHasStopped() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        File late = temporary.newFile("close.wav");
        assertThrows(CancellationException.class, () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                valid::get, (context, text, settings, completed) -> {
                    valid.set(false);
                    return () -> completed.accept(new LocalSpeechSynthesis.Result(late, 1000), null);
                }, (source, context) -> { throw new AssertionError("Cancelled request must not encode"); }));
        assertFalse(late.exists());
    }

    @Test public void cancellationDuringEncodingDeletesBothSourceAndEncodedFiles() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        File source = temporary.newFile("source.wav");
        File encoded = temporary.newFile("encoded.m4a");
        assertThrows(CancellationException.class, () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                valid::get, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 1000), null);
                    return () -> { };
                }, (input, context) -> {
                    assertEquals(source, input);
                    valid.set(false);
                    return new DirectMessageSpeechHook.AudioFile(encoded, 1000);
                }));
        assertFalse(source.exists());
        assertFalse(encoded.exists());
    }

    @Test public void successfulPreparationTransfersOnlyTheEncodedFileToTheSender() throws Exception {
        File source = temporary.newFile("source.wav");
        File encoded = temporary.newFile("encoded.m4a");
        SpeechPreparation.Audio audio = SpeechPreparation.prepare(null, "hello", options, 30_000,
                () -> true, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 1000), null);
                    return () -> { };
                }, (input, context) -> new DirectMessageSpeechHook.AudioFile(encoded, 950));
        assertFalse(source.exists());
        assertTrue(encoded.exists());
        assertEquals(encoded, audio.file);
        assertEquals(950, audio.duration);
        audio.delete();
        assertFalse(encoded.exists());
    }

    @Test public void overlongSourceIsRemovedBeforeEncoding() throws Exception {
        File source = temporary.newFile("source.wav");
        SpeechPreparation.Failure failure = assertThrows(SpeechPreparation.Failure.class,
                () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                () -> true, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 30_001), null);
                    return () -> { };
                }, (input, context) -> { throw new AssertionError("Overlong request must not encode"); }));
        assertTrue(failure.diagnostic().contains("reason=duration_limit"));
        assertTrue(failure.diagnostic().contains("sourceMillis=30001"));
        assertFalse(source.exists());
    }

    @Test public void unlimitedCommentKeepsTheFullSourceAndEncodedDuration() throws Exception {
        File source = temporary.newFile("long-source.wav");
        File encoded = temporary.newFile("long-encoded.m4a");
        SpeechPreparation.Audio audio = SpeechPreparation.prepare(null, "hello", options, Long.MAX_VALUE,
                () -> true, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 180_000), null);
                    return () -> { };
                }, (input, context) -> new DirectMessageSpeechHook.AudioFile(encoded, 180_010));
        assertFalse(source.exists());
        assertEquals(180_010, audio.duration);
        assertTrue(encoded.exists());
        audio.delete();
    }

    @Test public void commentTransfersTheOriginalFileWithoutImEncodingOrDurationClipping() throws Exception {
        File source = temporary.newFile("comment.mp3");
        AtomicReference<BiConsumer<LocalSpeechSynthesis.Result, String>> callback = new AtomicReference<>();
        SpeechPreparation.Audio audio = SpeechPreparation.prepareComment(null, "hello", options, Long.MAX_VALUE,
                () -> true, (context, text, settings, completed) -> {
                    callback.set(completed);
                    completed.accept(new LocalSpeechSynthesis.Result(source, 180_001), null);
                    return () -> completed.accept(new LocalSpeechSynthesis.Result(source, 180_001), null);
                });
        assertEquals(source, audio.file);
        assertEquals(180_001, audio.duration);
        assertTrue(source.exists());
        callback.get().accept(new LocalSpeechSynthesis.Result(source, 180_001), null);
        assertTrue(source.exists());
        File late = temporary.newFile("unexpected.mp3");
        callback.get().accept(new LocalSpeechSynthesis.Result(late, 1000), null);
        assertFalse(late.exists());
        audio.delete();
        assertFalse(source.exists());
    }

    @Test public void commentCancellationDeletesLateSynthesisAndNeverTransfersIt() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicReference<BiConsumer<LocalSpeechSynthesis.Result, String>> callback = new AtomicReference<>();
        assertThrows(CancellationException.class,
                () -> SpeechPreparation.prepareComment(null, "hello", options, Long.MAX_VALUE, valid::get,
                        (context, text, settings, completed) -> {
                            callback.set(completed);
                            valid.set(false);
                            return () -> { };
                        }));
        File late = temporary.newFile("late-comment.mp3");
        callback.get().accept(new LocalSpeechSynthesis.Result(late, 180_001), null);
        assertFalse(late.exists());
    }

    @Test public void speechDiagnosticsIdentifyTheStageWithoutProviderTextOrCredentials() throws Exception {
        SpeechPreparation.Failure synthesis = assertThrows(SpeechPreparation.Failure.class,
                () -> SpeechPreparation.prepareComment(null, "hello", options, Long.MAX_VALUE, () -> true,
                        (context, text, settings, completed) -> {
                            completed.accept(null, "secret-text https://provider/?key=private-key");
                            return () -> { };
                        }));
        assertTrue(synthesis.diagnostic().contains("stage=synthesis"));
        assertFalse(synthesis.diagnostic().contains("secret"));
        assertFalse(synthesis.diagnostic().contains("provider/"));
        assertFalse(synthesis.diagnostic().contains("private-key"));
        File source = temporary.newFile("pcm-source.wav");
        SpeechPreparation.Failure encoding = assertThrows(SpeechPreparation.Failure.class,
                () -> SpeechPreparation.prepare(null, "hello", options, 120_000, () -> true,
                        (context, text, settings, completed) -> {
                            completed.accept(new LocalSpeechSynthesis.Result(source, 25_000), null);
                            return () -> { };
                        }, (input, context) -> { throw new IllegalArgumentException("解码后的 PCM 格式不受支持"); }));
        assertTrue(encoding.diagnostic().contains("stage=encoding"));
        assertTrue(encoding.diagnostic().contains("reason=unsupported_pcm"));
        assertTrue(encoding.diagnostic().contains("sourceMillis=25000"));
        assertFalse(source.exists());
    }
}

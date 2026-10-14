package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DirectMessageSpeechFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cancelledQueuedEncodingCannotRunAndItsOwnedSourceIsRemoved() throws Exception {
        DirectMessageSpeechHook.Pending pending = pending();
        File source = temporary.newFile("source.wav");
        AtomicInteger executions = new AtomicInteger();
        FutureTask<Void> task = new FutureTask<>(() -> { executions.incrementAndGet(); return null; });
        assertTrue(pending.adoptSource(source));
        assertTrue(pending.installEncoding(task));
        pending.cancel();
        task.run();
        assertTrue(task.isCancelled());
        assertEquals(0, executions.get());
        assertFalse(source.exists());
    }

    @Test public void cancellationDeletesBothUntransferredFilesAndAnyLateResult() throws Exception {
        DirectMessageSpeechHook.Pending pending = pending();
        File source = temporary.newFile("source.wav");
        File encoded = temporary.newFile("encoded.m4a");
        assertTrue(pending.adoptSource(source));
        assertTrue(pending.adoptEncoded(new DirectMessageSpeechHook.AudioFile(encoded, 1000)));
        pending.cancel();
        assertFalse(source.exists());
        assertFalse(encoded.exists());
        File lateSource = temporary.newFile("late.wav");
        File lateEncoded = temporary.newFile("late.m4a");
        assertFalse(pending.adoptSource(lateSource));
        assertFalse(pending.adoptEncoded(new DirectMessageSpeechHook.AudioFile(lateEncoded, 1000)));
        assertFalse(lateSource.exists());
        assertFalse(lateEncoded.exists());
    }

    @Test public void rejectedWorkerOrPreSendValidationFailureCleansThePendingFiles() throws Exception {
        DirectMessageSpeechHook.Pending pending = pending();
        File source = temporary.newFile("source.wav");
        File encoded = temporary.newFile("encoded.m4a");
        FutureTask<Void> task = new FutureTask<>(() -> null);
        pending.adoptSource(source);
        pending.adoptEncoded(new DirectMessageSpeechHook.AudioFile(encoded, 1000));
        pending.installEncoding(task);
        pending.cleanup();
        assertTrue(task.isCancelled());
        assertFalse(source.exists());
        assertFalse(encoded.exists());
    }

    @Test public void knownMissingNativeQueueEvidenceDeletesTheEncodedFileImmediately() throws Exception {
        DirectMessageSpeechHook.Pending pending = pending();
        File encoded = temporary.newFile("encoded.m4a");
        DirectMessageSpeechHook.AudioFile audio = new DirectMessageSpeechHook.AudioFile(encoded, 1000);
        pending.adoptEncoded(audio);
        assertTrue(pending.beginNative(audio, new Handler(Looper.getMainLooper())));
        pending.rejectNative();
        pending.cleanup();
        assertFalse(encoded.exists());
    }

    @Test public void enteredNativeUploadSurvivesCancellationAndExpiresAfterTenMinutes() throws Exception {
        DirectMessageSpeechHook.Pending pending = pending();
        File source = temporary.newFile("source.wav");
        File encoded = temporary.newFile("encoded.m4a");
        DirectMessageSpeechHook.AudioFile audio = new DirectMessageSpeechHook.AudioFile(encoded, 1000);
        pending.adoptSource(source);
        pending.adoptEncoded(audio);
        assertTrue(pending.beginNative(audio, new Handler(Looper.getMainLooper())));
        pending.cancel();
        assertFalse(source.exists());
        assertTrue(encoded.exists());
        ShadowLooper.getShadowMainLooper().idleFor(DirectMessageSpeechHook.AUDIO_RETENTION_MILLIS - 1,
                TimeUnit.MILLISECONDS);
        assertTrue(encoded.exists());
        ShadowLooper.getShadowMainLooper().idleFor(1, TimeUnit.MILLISECONDS);
        assertFalse(encoded.exists());
    }

    private static DirectMessageSpeechHook.Pending pending() {
        return new DirectMessageSpeechHook.Pending(null, null, null, null, "conversation", "#draft");
    }
}

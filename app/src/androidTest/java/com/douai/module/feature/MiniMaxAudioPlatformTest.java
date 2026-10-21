package com.douai.module.feature;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.*;

/** Tests original G.711 mu-law WAV bytes on Android; no transcoding, network, or playback is performed. */
@RunWith(AndroidJUnit4.class)
public final class MiniMaxAudioPlatformTest {
    private File ownedDirectory;

    @Before public void prepare() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ownedDirectory = new File(target.getCacheDir(), "minimax-audio-platform-" + UUID.randomUUID());
        assertTrue(ownedDirectory.mkdirs());
    }

    @After public void cleanup() throws Exception {
        if (ownedDirectory != null) deleteOwned(ownedDirectory);
    }

    @Test public void originalMonoMuLawWavIsPreservedAndReadableByAndroid() throws Exception {
        assertOriginalMuLaw(1);
    }

    @Test public void originalStereoMuLawWavIsPreservedAndReadableByAndroid() throws Exception {
        assertOriginalMuLaw(2);
    }

    private void assertOriginalMuLaw(int channels) throws Exception {
        byte[] original = muLawWave(channels);
        SpeechHttpClient.Resource resource = new SpeechHttpClient.Resource(original, "audio/wav");
        byte[] before = resource.bytes.clone();
        SpeechHttpClient.Resource normalized = MiniMaxAudioFormat.normalize(
                resource, "pcmu_wav", channels, () -> { });
        assertSame("The selected provider format must retain the original resource", resource, normalized);
        assertArrayEquals("Validation must not rewrite the provider's encoded samples or WAV chunks", before, normalized.bytes);
        assertArrayEquals(original, normalized.bytes);
        File source = new File(ownedDirectory, "original-" + channels + "ch.wav");
        try (OutputStream output = new FileOutputStream(source)) { output.write(normalized.bytes); }

        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(source.getAbsolutePath());
            assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            assertNotEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            assertEquals(2_000, Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
            if (Build.VERSION.SDK_INT >= 31) {
                assertEquals("8000", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE));
            }
        } finally { metadata.release(); }

        MediaExtractor tracks = new MediaExtractor();
        try {
            tracks.setDataSource(source.getAbsolutePath());
            assertEquals(1, tracks.getTrackCount());
            MediaFormat format = tracks.getTrackFormat(0);
            assertEquals("The original fmt tag 7 must remain G.711 mu-law", MediaFormat.MIMETYPE_AUDIO_G711_MLAW,
                    format.getString(MediaFormat.KEY_MIME));
            assertEquals(8_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            if (format.containsKey(MediaFormat.KEY_DURATION)) assertEquals(2_000_000L, format.getLong(MediaFormat.KEY_DURATION));
        } finally { tracks.release(); }

        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(source.getAbsolutePath());
            player.setVolume(0f, 0f);
            player.prepare();
            assertEquals(2_000, player.getDuration());
            assertFalse("The test must never start audible playback", player.isPlaying());
        } finally { player.release(); }
        assertArrayEquals("Android inspection must not modify the original file", before,
                java.nio.file.Files.readAllBytes(source.toPath()));
    }

    /** RIFF/WAVEFORMATEX mu-law (tag 7), including the required non-PCM fact chunk. */
    private static byte[] muLawWave(int channels) throws Exception {
        int frames = 8_000 * 2;
        int dataBytes = frames * channels;
        ByteArrayOutputStream output = new ByteArrayOutputStream(58 + dataBytes);
        ascii(output, "RIFF"); le32(output, 50 + dataBytes); ascii(output, "WAVEfmt ");
        le32(output, 18); le16(output, 7); le16(output, channels); le32(output, 8_000);
        le32(output, 8_000 * channels); le16(output, channels); le16(output, 8); le16(output, 0);
        ascii(output, "fact"); le32(output, 4); le32(output, frames);
        ascii(output, "data"); le32(output, dataBytes);
        // Fixed, non-silent G.711 samples. Stereo has a distinct interleaved right channel.
        int[] period = {0xaf, 0x9f, 0xaf, 0xff, 0x2f, 0x1f, 0x2f, 0xff};
        for (int frame = 0; frame < frames; frame++) {
            output.write(period[frame % period.length]);
            if (channels == 2) output.write(period[(frame + 2) % period.length]);
        }
        return output.toByteArray();
    }

    private static void ascii(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }
    private static void le16(OutputStream output, int value) throws Exception {
        output.write(value & 255); output.write((value >>> 8) & 255);
    }
    private static void le32(OutputStream output, int value) throws Exception {
        le16(output, value & 0xffff); le16(output, (value >>> 16) & 0xffff);
    }
    private static void deleteOwned(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteOwned(child);
        if (!file.delete() && file.exists()) throw new IllegalStateException("Could not remove test artifact " + file.getName());
    }
}

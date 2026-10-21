package com.douai.module.feature;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class MiniMaxAudioFormatTest {
    private static final byte[] MU_LAW = {(byte) 0xff, 0x7f, 0x00, (byte) 0x80};

    @Test public void validatesMuLawWaveWithoutReplacingTheResourceOrChangingAnyByte() {
        byte[] source = muLawWave(1, MU_LAW), before = source.clone();
        SpeechHttpClient.Resource original = resource(source, "audio/x-wav; charset=binary");
        assertSame(original, MiniMaxAudioFormat.normalize(original, "pcmu_wav", 1, () -> { }));
        assertSame(source, original.bytes);
        assertArrayEquals(before, original.bytes);
        assertMuLawWave(original, 1, MU_LAW);
    }

    @Test public void validatesActualMonoAndStereoWaveMetadataWithoutReinterpretingTheContainer() {
        for (int channels : new int[]{1, 2}) {
            byte[] source = muLawWave(channels, MU_LAW), before = source.clone();
            SpeechHttpClient.Resource original = resource(source, "audio/wav");
            assertSame(original, MiniMaxAudioFormat.normalize(original, "pcmu_wav", 3 - channels, () -> { }));
            assertArrayEquals(before, original.bytes);
            assertMuLawWave(original, channels, MU_LAW);
        }
    }

    @Test public void preservesUnknownPaddedChunksAndDifferentFmtDataOrdering() {
        byte[] source = riff(chunk("JUNK", new byte[]{1, 2, 3}), chunk("data", MU_LAW),
                chunk("LIST", new byte[]{4}), formatChunk(7, 1, 8000, 8), chunk("fact", int32(4)));
        byte[] before = source.clone();
        SpeechHttpClient.Resource original = resource(source, "audio/wav");
        assertSame(original, MiniMaxAudioFormat.normalize(original, "pcmu_wav", 1, () -> { }));
        assertArrayEquals(before, source);
        assertMuLawWave(original, 1, MU_LAW);
    }

    @Test public void rejectsContradictoryMuLawWaveMetadata() {
        byte[] valid = muLawWave(1, MU_LAW);
        int fmt = chunkOffset(valid, "fmt ") + 8;
        Map<String, byte[]> invalid = new LinkedHashMap<>();
        invalid.put("linear PCM is not mu-law", with16(valid, fmt, 1));
        invalid.put("zero channels", with16(valid, fmt + 2, 0));
        invalid.put("unsupported channels", with16(valid, fmt + 2, 3));
        invalid.put("wrong sample rate", with32(valid, fmt + 4, 16000));
        invalid.put("wrong byte rate", with32(valid, fmt + 8, 16000));
        invalid.put("wrong block alignment", with16(valid, fmt + 12, 2));
        invalid.put("wrong source bit depth", with16(valid, fmt + 14, 16));
        invalid.put("stereo partial frame", muLawWave(2, new byte[]{1, 2, 3}));
        for (Map.Entry<String, byte[]> entry : invalid.entrySet()) assertInvalidWave(entry.getKey(), entry.getValue());
    }

    @Test public void rejectsMissingDuplicateEmptyAndTruncatedWaveChunks() {
        byte[] fmt = formatChunk(7, 1, 8000, 8), data = chunk("data", MU_LAW);
        Map<String, byte[]> invalid = new LinkedHashMap<>();
        invalid.put("missing fmt", riff(data));
        invalid.put("missing data", riff(fmt));
        invalid.put("duplicate fmt", riff(fmt, fmt, data));
        invalid.put("duplicate data", riff(fmt, data, data));
        invalid.put("empty audio", riff(fmt, chunk("data", new byte[0])));
        invalid.put("incomplete chunk header", riff(fmt, new byte[7]));
        byte[] unpadded = Arrays.copyOf(chunk("data", new byte[]{1}), 9);
        invalid.put("missing odd-size padding", riff(fmt, unpadded));
        for (Map.Entry<String, byte[]> entry : invalid.entrySet()) assertInvalidWave(entry.getKey(), entry.getValue());
    }

    @Test public void rejectsRiffAndChunkLengthsThatOverflowOrEscapeTheInput() {
        byte[] valid = muLawWave(1, MU_LAW);
        int fmt = chunkOffset(valid, "fmt "), data = chunkOffset(valid, "data");
        Map<String, byte[]> invalid = new LinkedHashMap<>();
        invalid.put("short RIFF header", Arrays.copyOf(valid, 11));
        invalid.put("RIFF size is unsigned", with32(valid, 4, -1));
        invalid.put("chunk size is unsigned", with32(valid, fmt + 4, -1));
        invalid.put("short fmt", with32(valid, fmt + 4, 15));
        invalid.put("data exceeds input", with32(valid, data + 4, MU_LAW.length + 10));
        invalid.put("truncated data", with32(Arrays.copyOf(valid, valid.length - 1), 4, valid.length - 9));
        for (Map.Entry<String, byte[]> entry : invalid.entrySet()) assertInvalidWave(entry.getKey(), entry.getValue());
    }

    @Test public void explicitJsonOrTextMimeAndNonWaveBodiesAreRejectedWithoutWrapping() {
        byte[] valid = muLawWave(1, MU_LAW);
        for (String type : new String[]{"application/json", "application/problem+json; charset=utf-8",
                "text/html", "text/plain", "application/xml"}) {
            assertThrows(type, IllegalArgumentException.class, () -> MiniMaxAudioFormat.normalize(
                    resource(valid, type), "pcmu_wav", 1, () -> { }));
        }
        for (byte[] bytes : new byte[][]{new byte[0], MU_LAW, "{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8),
                "<!DOCTYPE html><html>error</html>".getBytes(StandardCharsets.US_ASCII)}) {
            assertInvalidWave("No valid original WAV container", bytes);
        }
    }

    @Test public void aacAndOtherContainerFormatsAreReturnedAsTheIdenticalResource() {
        for (String[] example : new String[][]{{"mp3", "ID3data"}, {"opus", "OggSdata"}, {"flac", "fLaCdata"}}) {
            byte[] bytes = example[1].getBytes(StandardCharsets.US_ASCII), before = bytes.clone();
            SpeechHttpClient.Resource original = resource(bytes, "audio/unknown");
            assertSame(original, MiniMaxAudioFormat.normalize(original, example[0], 1, () -> { }));
            assertSame(bytes, original.bytes); assertArrayEquals(before, bytes);
        }
        byte[] adts = {(byte) 0xff, (byte) 0xf1, 0x50, 0x40, 0x01, 0x3f, (byte) 0xfc, 0, 0};
        SpeechHttpClient.Resource aac = resource(adts, "audio/aac");
        assertSame(aac, MiniMaxAudioFormat.normalize(aac, "aac", 1, () -> { }));
        assertSame(adts, aac.bytes); assertEquals(".aac", aac.audioExtension());
        SpeechHttpClient.Resource wav = resource(pcmWave(1, (short) 0, (short) 12), "audio/wav");
        assertSame(wav, MiniMaxAudioFormat.normalize(wav, "wav", 1, () -> { }));
    }

    @Test public void cancellationPropagatesBeforeAndDuringValidationWithoutChangingTheSource() {
        byte[] source = muLawWave(1, MU_LAW), before = source.clone();
        CancellationException cancelled = new CancellationException("cancelled");
        assertSame(cancelled, assertThrows(CancellationException.class, () -> MiniMaxAudioFormat.normalize(
                resource(source, "audio/wav"), "pcmu_wav", 1, () -> { throw cancelled; })));
        AtomicInteger checks = new AtomicInteger();
        assertSame(cancelled, assertThrows(CancellationException.class, () -> MiniMaxAudioFormat.normalize(
                resource(source, "audio/wav"), "pcmu_wav", 1, () -> {
                    if (checks.incrementAndGet() >= 3) throw cancelled;
                })));
        assertTrue(checks.get() >= 3);
        assertArrayEquals(before, source);
    }

    static byte[] muLawWave(int channels, byte[] samples) {
        return riff(formatChunk(7, channels, 8000, 8),
                chunk("fact", int32(channels == 0 ? 0 : samples.length / channels)), chunk("data", samples));
    }

    static byte[] pcmWave(int channels, short... samples) {
        ByteBuffer data = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) data.putShort(sample);
        return riff(formatChunk(1, channels, 8000, 16), chunk("data", data.array()));
    }

    static void assertMuLawWave(SpeechHttpClient.Resource resource, int channels, byte[] expected) {
        byte[] bytes = resource.bytes;
        ByteBuffer wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(bytes.length - 8, wav.getInt(4));
        assertEquals("WAVE", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
        int fmt = chunkOffset(bytes, "fmt ") + 8;
        assertEquals(7, wav.getShort(fmt)); assertEquals(channels, wav.getShort(fmt + 2));
        assertEquals(8000, wav.getInt(fmt + 4)); assertEquals(8000 * channels, wav.getInt(fmt + 8));
        assertEquals(channels, wav.getShort(fmt + 12)); assertEquals(8, wav.getShort(fmt + 14));
        int data = chunkOffset(bytes, "data");
        assertEquals(expected.length, wav.getInt(data + 4));
        assertArrayEquals(expected, Arrays.copyOfRange(bytes, data + 8, data + 8 + expected.length));
        assertTrue(resource.isAudio()); assertEquals(".wav", resource.audioExtension());
    }

    private static byte[] formatChunk(int tag, int channels, int rate, int bits) {
        int block = channels * bits / 8;
        ByteBuffer fmt = ByteBuffer.allocate(tag == 7 ? 18 : 16).order(ByteOrder.LITTLE_ENDIAN);
        fmt.putShort((short) tag).putShort((short) channels).putInt(rate).putInt(rate * block);
        fmt.putShort((short) block).putShort((short) bits);
        if (tag == 7) fmt.putShort((short) 0);
        return chunk("fmt ", fmt.array());
    }

    private static byte[] chunk(String name, byte[] data) {
        ByteBuffer bytes = ByteBuffer.allocate(8 + data.length + (data.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(name.getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data);
        return bytes.array();
    }

    private static byte[] riff(byte[]... chunks) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) content.write(chunk, 0, chunk.length);
        byte[] payload = content.toByteArray();
        return ByteBuffer.allocate(12 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(4 + payload.length)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII)).put(payload).array();
    }

    private static int chunkOffset(byte[] wav, String name) {
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 12; at < wav.length; ) {
            if (name.equals(new String(wav, at, 4, StandardCharsets.US_ASCII))) return at;
            int length = data.getInt(at + 4);
            at += 8 + length + (length & 1);
        }
        throw new AssertionError("Fixture chunk missing: " + name);
    }

    private static byte[] int32(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static byte[] with16(byte[] source, int at, int value) {
        byte[] copy = source.clone(); ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putShort(at, (short) value); return copy;
    }
    private static byte[] with32(byte[] source, int at, int value) {
        byte[] copy = source.clone(); ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value); return copy;
    }
    private static SpeechHttpClient.Resource resource(byte[] bytes, String type) { return new SpeechHttpClient.Resource(bytes, type); }
    private static void assertInvalidWave(String label, byte[] bytes) {
        assertThrows(label, IllegalArgumentException.class, () -> MiniMaxAudioFormat.normalize(
                resource(bytes, "audio/wav"), "pcmu_wav", 1, () -> { }));
    }
}

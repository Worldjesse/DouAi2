package com.douai.module.feature;

import java.util.Locale;

/** Validates the documented mu-law WAV container without converting provider audio. */
final class MiniMaxAudioFormat {
    private MiniMaxAudioFormat() { }

    static SpeechHttpClient.Resource normalize(SpeechHttpClient.Resource resource, String format,
                                               int channels, Runnable active) {
        if (!"pcmu_wav".equals(format)) return resource;
        active.run();
        String mime = resource.contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!mime.isEmpty() && !mime.startsWith("audio/") && !mime.equals("application/octet-stream")) {
            throw invalid();
        }
        byte[] source = resource.bytes;
        if (source.length < 12 || !ascii(source, 0, "RIFF") || !ascii(source, 8, "WAVE")
                || uint32(source, 4) != source.length - 8L) throw invalid();
        int dataLength = -1;
        int actualChannels = 0;
        boolean foundFormat = false;
        for (int cursor = 12; cursor < source.length; ) {
            active.run();
            if (cursor > source.length - 8) throw invalid();
            long chunkSize = uint32(source, cursor + 4);
            long next = cursor + 8L + chunkSize + (chunkSize & 1);
            if (next > source.length) throw invalid();
            if (ascii(source, cursor, "fmt ")) {
                if (foundFormat || chunkSize < 16 || uint16(source, cursor + 8) != 7
                        || uint32(source, cursor + 12) != 8000
                        || uint16(source, cursor + 22) != 8) throw invalid();
                // The received WAV header is authoritative; never reinterpret it using a raw layout.
                actualChannels = uint16(source, cursor + 10);
                if (actualChannels < 1 || actualChannels > 2
                        || uint16(source, cursor + 20) != actualChannels
                        || uint32(source, cursor + 16) != 8000L * actualChannels) throw invalid();
                foundFormat = true;
            } else if (ascii(source, cursor, "data")) {
                if (dataLength >= 0) throw invalid();
                dataLength = (int) chunkSize;
            }
            cursor = (int) next;
        }
        if (!foundFormat || dataLength <= 0 || dataLength % actualChannels != 0) throw invalid();
        active.run();
        return resource;
    }

    private static int uint16(byte[] bytes, int index) {
        return (bytes[index] & 255) | ((bytes[index + 1] & 255) << 8);
    }

    private static long uint32(byte[] bytes, int index) {
        return uint16(bytes, index) | ((long) uint16(bytes, index + 2) << 16);
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) if ((bytes[offset + i] & 255) != value.charAt(i)) return false;
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("MiniMax 语音 μ-law WAV 音频数据无效");
    }
}

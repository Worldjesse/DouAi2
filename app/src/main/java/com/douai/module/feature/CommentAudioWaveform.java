package com.douai.module.feature;

import java.util.Collections;
import java.util.List;

/** Wave metadata for generated audio whose provider does not supply measured amplitudes. */
final class CommentAudioWaveform {
    private CommentAudioWaveform() { }

    static List<Float> nativeFallback() {
        // Both supported KMP mappers omit the entire audio slot for an empty wave string.
        // One normalized sample keeps the slot present; both native renderers replace a
        // short list with their built-in default bars. This is a visual fallback, not a
        // measured waveform, and it never decodes, rewrites or converts the audio file.
        return Collections.singletonList(0f);
    }
}

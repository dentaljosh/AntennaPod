package org.shiurcleanup.denoiser;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

/**
 * Pure format-validation tests. The positive case (accepted format + actual inference) requires loading
 * the ONNX model asset and therefore lives as an instrumentation test; here we only confirm that
 * unsupported formats are rejected before the denoiser is even constructed.
 */
@UnstableApi
public class GtcrnAudioProcessorTest {

    // Any non-empty byte[] — these tests all throw before the ONNX session is constructed, so the bytes
    // never hit the runtime.
    private static final byte[] DUMMY_MODEL_BYTES = new byte[]{0x00};

    @Test
    public void rejectsWrongSampleRate() {
        GtcrnAudioProcessor processor = new GtcrnAudioProcessor(DUMMY_MODEL_BYTES, () -> true);
        AudioProcessor.AudioFormat format48k = new AudioProcessor.AudioFormat(
                48_000, 1, C.ENCODING_PCM_16BIT);
        assertThrows(AudioProcessor.UnhandledAudioFormatException.class,
                () -> processor.configure(format48k));
    }

    @Test
    public void rejectsStereo() {
        GtcrnAudioProcessor processor = new GtcrnAudioProcessor(DUMMY_MODEL_BYTES, () -> true);
        AudioProcessor.AudioFormat stereoFormat = new AudioProcessor.AudioFormat(
                16_000, 2, C.ENCODING_PCM_16BIT);
        assertThrows(AudioProcessor.UnhandledAudioFormatException.class,
                () -> processor.configure(stereoFormat));
    }

    @Test
    public void rejectsFloatEncoding() {
        GtcrnAudioProcessor processor = new GtcrnAudioProcessor(DUMMY_MODEL_BYTES, () -> true);
        AudioProcessor.AudioFormat floatFormat = new AudioProcessor.AudioFormat(
                16_000, 1, C.ENCODING_PCM_FLOAT);
        assertThrows(AudioProcessor.UnhandledAudioFormatException.class,
                () -> processor.configure(floatFormat));
    }

    @Test
    public void rejectsEmptyModelBytes() {
        try {
            new GtcrnAudioProcessor(new byte[0], () -> true);
            fail("Expected IllegalArgumentException for empty model bytes");
        } catch (IllegalArgumentException expected) {
            // pass
        }
        try {
            new GtcrnAudioProcessor(null, () -> true);
            fail("Expected IllegalArgumentException for null model bytes");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }

    @Test
    public void rejectsNullEnabledCheck() {
        try {
            new GtcrnAudioProcessor(DUMMY_MODEL_BYTES, null);
            fail("Expected IllegalArgumentException for null enabledCheck");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }
}

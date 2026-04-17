package org.shiurcleanup.denoiser;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import ai.onnxruntime.OrtException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

/**
 * Media3 {@link androidx.media3.common.audio.AudioProcessor} that runs {@link StreamingGtcrn} inline
 * on the audio pipeline. Accepts only 16 kHz mono 16-bit PCM; anything else causes
 * {@link #onConfigure} to throw, which tells Media3 to omit this processor from the chain. Upstream
 * processors (resampler, downmixer) must normalize the format first.
 *
 * <p>Threading: Media3 invokes {@link #queueInput} on the audio playback thread. GTCRN inference
 * currently runs synchronously on that thread. On mid-range devices under sustained thermal load the
 * per-frame cost can approach the 16 ms hop budget — if that becomes a problem, wrap the denoiser in a
 * worker-thread queue with a small lookahead buffer. Fine as-is on a Pixel 9 Pro.
 */
@UnstableApi
public final class GtcrnAudioProcessor extends BaseAudioProcessor {

    private final byte[] modelBytes;
    private final BooleanSupplier enabledCheck;

    private StreamingGtcrn denoiser;

    private float[] inputScratch = new float[StreamingGtcrn.HOP_SIZE * 8];
    private float[] outputFloats = new float[StreamingGtcrn.HOP_SIZE * 8];
    private int outputFloatsLen;

    private final FloatChunkSink outputSink = (samples, off, len) -> {
        int needed = outputFloatsLen + len;
        if (needed > outputFloats.length) {
            int newCap = outputFloats.length;
            while (newCap < needed) {
                newCap *= 2;
            }
            float[] next = new float[newCap];
            System.arraycopy(outputFloats, 0, next, 0, outputFloatsLen);
            outputFloats = next;
        }
        System.arraycopy(samples, off, outputFloats, outputFloatsLen, len);
        outputFloatsLen += len;
    };

    /**
     * @param modelBytes the GTCRN ONNX model bytes. Must be non-empty.
     * @param enabledCheck polled on every {@link #queueInput} call; when it returns {@code false},
     *                     this processor copies input to output unchanged (pure passthrough, ONNX
     *                     session stays warm). Cheaper than tearing the session down and recreating it
     *                     when the user toggles cleanup on/off mid-episode.
     */
    public GtcrnAudioProcessor(byte[] modelBytes, BooleanSupplier enabledCheck) {
        if (modelBytes == null || modelBytes.length == 0) {
            throw new IllegalArgumentException("modelBytes must be non-empty");
        }
        if (enabledCheck == null) {
            throw new IllegalArgumentException("enabledCheck must be non-null");
        }
        this.modelBytes = modelBytes;
        this.enabledCheck = enabledCheck;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.sampleRate != StreamingGtcrn.SAMPLE_RATE
                || inputAudioFormat.channelCount != 1
                || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        if (denoiser == null) {
            try {
                denoiser = new StreamingGtcrn(modelBytes);
            } catch (OrtException e) {
                throw new UnhandledAudioFormatException(inputAudioFormat);
            }
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int bytesRemaining = inputBuffer.remaining();
        if (bytesRemaining == 0) {
            return;
        }

        if (!enabledCheck.getAsBoolean()) {
            // Cleanup toggled off. Copy input bytes straight to output; keep the ONNX session warm so
            // re-enabling is instant. Note: passthrough doesn't reset the cache — if the user toggles
            // off during playback, runs for a while, then toggles back on, the model caches will still
            // reflect the pre-toggle audio and produce a few frames of artifact before recovering.
            // Acceptable trade-off for a global toggle; reset the pipeline on track-change via onFlush.
            int outBytes = bytesRemaining;
            ByteBuffer out = replaceOutputBuffer(outBytes).order(ByteOrder.nativeOrder());
            ByteBuffer src = inputBuffer.slice().order(ByteOrder.nativeOrder());
            out.put(src);
            out.flip();
            inputBuffer.position(inputBuffer.position() + bytesRemaining);
            return;
        }

        int sampleCount = bytesRemaining / 2;
        if (inputScratch.length < sampleCount) {
            inputScratch = new float[sampleCount];
        }

        // Read signed 16-bit PCM in native byte order (matches Media3's ENCODING_PCM_16BIT convention).
        ByteBuffer src = inputBuffer.order(ByteOrder.nativeOrder());
        for (int i = 0; i < sampleCount; i++) {
            inputScratch[i] = src.getShort() / 32768.0f;
        }

        try {
            denoiser.feed(inputScratch, 0, sampleCount, outputSink);
        } catch (IOException | OrtException e) {
            // In-flight errors can't propagate from queueInput; emit silence for the buffer we tried to
            // feed so playback doesn't stall or duplicate audio. Subsequent frames will retry with the
            // same session (ONNX session is not poisoned by one bad call).
            outputFloatsLen = 0;
            writeSilence(sampleCount);
            return;
        }
        drainOutputFloats();
    }

    @Override
    protected void onQueueEndOfStream() {
        if (denoiser == null) {
            return;
        }
        try {
            denoiser.finish(outputSink);
        } catch (IOException | OrtException e) {
            // End-of-stream failure — emit whatever we have, skip the tail.
        }
        drainOutputFloats();
    }

    @Override
    protected void onFlush() {
        if (denoiser != null) {
            denoiser.reset();
        }
        outputFloatsLen = 0;
    }

    @Override
    protected void onReset() {
        if (denoiser != null) {
            try {
                denoiser.close();
            } catch (IOException e) {
                // best-effort close
            }
            denoiser = null;
        }
        outputFloatsLen = 0;
    }

    /** Writes the accumulated float output to the Media3 output buffer as int16 PCM. */
    private void drainOutputFloats() {
        if (outputFloatsLen == 0) {
            return;
        }
        int outBytes = outputFloatsLen * 2;
        ByteBuffer out = replaceOutputBuffer(outBytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < outputFloatsLen; i++) {
            float v = outputFloats[i];
            if (v > 1f) {
                v = 1f;
            } else if (v < -1f) {
                v = -1f;
            }
            out.putShort((short) (v * 32767f));
        }
        out.flip();
        outputFloatsLen = 0;
    }

    /**
     * Emit {@code sampleCount} zero samples — used only when queueInput hits an unrecoverable error.
     */
    private void writeSilence(int sampleCount) {
        int outBytes = sampleCount * 2;
        ByteBuffer out = replaceOutputBuffer(outBytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < sampleCount; i++) {
            out.putShort((short) 0);
        }
        out.flip();
    }
}

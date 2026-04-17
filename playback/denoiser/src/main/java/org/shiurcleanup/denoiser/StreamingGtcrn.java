package org.shiurcleanup.denoiser;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;

import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sample-level streaming GTCRN. Accepts mono 16 kHz float32 PCM via {@link #feed}; emits finalized
 * enhanced samples to a {@link FloatChunkSink} as each frame's output falls off the overlap-add
 * window. Peak memory is O(FFT_SIZE) — no full-file buffers.
 *
 * <p>Produces the same output as the equivalent batch pipeline for the same input (zero-padded on
 * both sides with PAD_SIZE samples).
 */
public final class StreamingGtcrn implements Closeable {
    public static final int SAMPLE_RATE = 16_000;
    public static final int FFT_SIZE = 512;
    public static final int HOP_SIZE = 256;
    public static final int FREQ_BINS = FFT_SIZE / 2 + 1;
    public static final int PAD_SIZE = FFT_SIZE / 2;

    private static final long[] MIX_SHAPE = new long[] {1, FREQ_BINS, 1, 2};
    private static final long[] CONV_CACHE_SHAPE = new long[] {2, 1, 16, 16, 33};
    private static final long[] TRA_CACHE_SHAPE = new long[] {2, 3, 1, 1, 16};
    private static final long[] INTER_CACHE_SHAPE = new long[] {2, 1, 33, 16};

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final float[] window;

    private final float[] mix = new float[elementCount(MIX_SHAPE)];
    private final float[] convCache = new float[elementCount(CONV_CACHE_SHAPE)];
    private final float[] traCache = new float[elementCount(TRA_CACHE_SHAPE)];
    private final float[] interCache = new float[elementCount(INTER_CACHE_SHAPE)];
    private final float[] real = new float[FFT_SIZE];
    private final float[] imag = new float[FFT_SIZE];

    private final float[] inputWindow = new float[FFT_SIZE];
    private final float[] outputAccum = new float[FFT_SIZE];
    private final float[] normAccum = new float[FFT_SIZE];

    // Rolling input buffer. `pending` holds samples awaiting consumption; `pendingStart` is the absolute
    // real-input position of pending[0]. `inputSamplesSeen` is total real input fed so far.
    private float[] pending = new float[8192];
    private int pendingLen;
    private long pendingStart; // absolute position of pending[0]
    private long inputSamplesSeen;

    private int framesProcessed;
    private long samplesEmitted;

    private final float[] outBatch = new float[2048];
    private int outBatchLen;

    private boolean finished;

    long stftMs;
    long inferMs;
    long istftMs;
    int frames;

    StreamingGtcrn(byte[] modelBytes) throws OrtException {
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        this.session = environment.createSession(modelBytes, options);
        this.window = Dsp.sqrtHannWindow(FFT_SIZE);
    }

    void feed(float[] samples, int offset, int len, FloatChunkSink sink) throws IOException, OrtException {
        ensurePendingCapacity(pendingLen + len);
        System.arraycopy(samples, offset, pending, pendingLen, len);
        pendingLen += len;
        inputSamplesSeen += len;
        advanceAll(sink, inputSamplesSeen);
    }

    void finish(FloatChunkSink sink) throws IOException, OrtException {
        if (finished) {
            return;
        }
        finished = true;
        // Pad virtual input on the right with PAD_SIZE zeros so the last hop's output fully finalises.
        long virtualEnd = inputSamplesSeen + PAD_SIZE;
        advanceAll(sink, virtualEnd);
        // Flush outBatch plus any remaining output samples, truncated to inputSamplesSeen.
        flushBatch(sink);
    }

    private void advanceAll(FloatChunkSink sink, long availableEndAbs) throws IOException, OrtException {
        while (canProcessFrame(availableEndAbs)) {
            processFrame(sink);
            framesProcessed++;
        }
        trimPending();
    }

    private boolean canProcessFrame(long availableEndAbs) {
        // Frame k covers real-input positions [k*HOP - PAD, k*HOP + PAD - 1] (with PAD = PAD_SIZE).
        long frameEndPos = (long) framesProcessed * HOP_SIZE + PAD_SIZE; // exclusive upper bound
        return frameEndPos <= availableEndAbs;
    }

    private void processFrame(FloatChunkSink sink) throws IOException, OrtException {
        long frameStartPos = (long) framesProcessed * HOP_SIZE - PAD_SIZE;
        // Build inputWindow: zero-fill virtual positions <0 or >= inputSamplesSeen (right pad only on finish).
        for (int i = 0; i < FFT_SIZE; i++) {
            long pos = frameStartPos + i;
            if (pos < 0 || pos >= inputSamplesSeen) {
                inputWindow[i] = 0.0f;
            } else {
                int idx = (int) (pos - pendingStart);
                inputWindow[i] = pending[idx];
            }
        }

        final long stftStart = SystemClock.elapsedRealtime();
        for (int i = 0; i < FFT_SIZE; i++) {
            real[i] = inputWindow[i] * window[i];
            imag[i] = 0.0f;
        }
        Dsp.fft(real, imag, false);
        packSpectrum(real, imag, mix);
        stftMs += SystemClock.elapsedRealtime() - stftStart;

        final long inferStart = SystemClock.elapsedRealtime();
        try (
                OnnxTensor mixTensor = OnnxTensor.createTensor(
                        environment, FloatBuffer.wrap(mix), MIX_SHAPE);
                OnnxTensor convCacheTensor = OnnxTensor.createTensor(
                        environment, FloatBuffer.wrap(convCache), CONV_CACHE_SHAPE);
                OnnxTensor traCacheTensor = OnnxTensor.createTensor(
                        environment, FloatBuffer.wrap(traCache), TRA_CACHE_SHAPE);
                OnnxTensor interCacheTensor = OnnxTensor.createTensor(
                        environment, FloatBuffer.wrap(interCache), INTER_CACHE_SHAPE)
        ) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("mix", mixTensor);
            inputs.put("conv_cache", convCacheTensor);
            inputs.put("tra_cache", traCacheTensor);
            inputs.put("inter_cache", interCacheTensor);

            try (Result result = session.run(inputs)) {
                final float[][][][] enh = (float[][][][]) result.get(0).getValue();
                final float[][][][][] convCacheOut = (float[][][][][]) result.get(1).getValue();
                final float[][][][][] traCacheOut = (float[][][][][]) result.get(2).getValue();
                final float[][][][] interCacheOut = (float[][][][]) result.get(3).getValue();

                unpackSpectrum(enh, real, imag);
                flatten(convCacheOut, convCache);
                flatten(traCacheOut, traCache);
                flatten(interCacheOut, interCache);
            }
        }
        inferMs += SystemClock.elapsedRealtime() - inferStart;

        long istftStart = SystemClock.elapsedRealtime();
        Dsp.fft(real, imag, true);
        // Overlap-add windowed output into outputAccum/normAccum
        for (int i = 0; i < FFT_SIZE; i++) {
            float windowed = real[i] * window[i];
            outputAccum[i] += windowed;
            normAccum[i] += window[i] * window[i];
        }
        istftMs += SystemClock.elapsedRealtime() - istftStart;
        frames++;

        // The leftmost HOP_SIZE samples of outputAccum are now finalised (no future frame writes there).
        // Their real-output position starts at framesProcessed * HOP_SIZE - PAD_SIZE.
        long emitRealStart = (long) framesProcessed * HOP_SIZE - PAD_SIZE;
        for (int i = 0; i < HOP_SIZE; i++) {
            long realPos = emitRealStart + i;
            float n = normAccum[i];
            float v = n > 1.0e-8f ? outputAccum[i] / n : 0.0f;
            if (realPos >= 0 && realPos < inputSamplesSeen) {
                emit(v, sink);
                samplesEmitted++;
            }
        }

        // Shift accumulators left by HOP_SIZE
        System.arraycopy(outputAccum, HOP_SIZE, outputAccum, 0, FFT_SIZE - HOP_SIZE);
        System.arraycopy(normAccum, HOP_SIZE, normAccum, 0, FFT_SIZE - HOP_SIZE);
        for (int i = FFT_SIZE - HOP_SIZE; i < FFT_SIZE; i++) {
            outputAccum[i] = 0.0f;
            normAccum[i] = 0.0f;
        }
    }

    private void emit(float v, FloatChunkSink sink) throws IOException {
        outBatch[outBatchLen++] = v;
        if (outBatchLen == outBatch.length) {
            sink.accept(outBatch, 0, outBatchLen);
            outBatchLen = 0;
        }
    }

    private void flushBatch(FloatChunkSink sink) throws IOException {
        if (outBatchLen == 0) {
            return;
        }
        sink.accept(outBatch, 0, outBatchLen);
        outBatchLen = 0;
    }

    private void trimPending() {
        // Drop samples we'll never read again. Frame `framesProcessed` (next to run) covers real-input
        // positions starting at framesProcessed*HOP - PAD_SIZE; keep everything from there onward.
        long keepFrom = (long) framesProcessed * HOP_SIZE - PAD_SIZE;
        if (keepFrom <= pendingStart) {
            return;
        }
        int drop = (int) (keepFrom - pendingStart);
        if (drop >= pendingLen) {
            pendingLen = 0;
            pendingStart = keepFrom;
            return;
        }
        System.arraycopy(pending, drop, pending, 0, pendingLen - drop);
        pendingLen -= drop;
        pendingStart = keepFrom;
    }

    private void ensurePendingCapacity(int minCapacity) {
        if (pending.length >= minCapacity) {
            return;
        }
        int newCap = pending.length;
        while (newCap < minCapacity) {
            newCap *= 2;
        }
        float[] next = new float[newCap];
        System.arraycopy(pending, 0, next, 0, pendingLen);
        pending = next;
    }

    /**
     * Zero all per-stream state so the next {@link #feed} call starts from a fresh cache. The ONNX session
     * and sqrt-Hann window are intentionally preserved — both are input-independent and reconstructing them
     * is expensive. Call this on seek / track-change / flush in a playback integration; without it,
     * residual cache state from the pre-seek audio bleeds into post-seek output for a second or two while
     * the caches refill.
     */
    public void reset() {
        java.util.Arrays.fill(mix, 0f);
        java.util.Arrays.fill(convCache, 0f);
        java.util.Arrays.fill(traCache, 0f);
        java.util.Arrays.fill(interCache, 0f);
        java.util.Arrays.fill(real, 0f);
        java.util.Arrays.fill(imag, 0f);
        java.util.Arrays.fill(inputWindow, 0f);
        java.util.Arrays.fill(outputAccum, 0f);
        java.util.Arrays.fill(normAccum, 0f);
        pendingLen = 0;
        pendingStart = 0L;
        inputSamplesSeen = 0L;
        framesProcessed = 0;
        samplesEmitted = 0L;
        outBatchLen = 0;
        finished = false;
        stftMs = 0L;
        inferMs = 0L;
        istftMs = 0L;
        frames = 0;
    }

    @Override
    public void close() throws IOException {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IOException("Failed to close ORT session", e);
        }
    }

    private static int elementCount(long[] shape) {
        long product = 1L;
        for (long dim : shape) {
            product *= dim;
        }
        return (int) product;
    }

    private static void packSpectrum(float[] real, float[] imag, float[] mix) {
        int index = 0;
        for (int bin = 0; bin < FREQ_BINS; bin++) {
            mix[index++] = real[bin];
            mix[index++] = imag[bin];
        }
    }

    private static void unpackSpectrum(float[][][][] enh, float[] real, float[] imag) {
        for (int i = 0; i < FFT_SIZE; i++) {
            real[i] = 0.0f;
            imag[i] = 0.0f;
        }
        for (int bin = 0; bin < FREQ_BINS; bin++) {
            real[bin] = enh[0][bin][0][0];
            imag[bin] = enh[0][bin][0][1];
        }
        for (int bin = 1; bin < FREQ_BINS - 1; bin++) {
            real[FFT_SIZE - bin] = real[bin];
            imag[FFT_SIZE - bin] = -imag[bin];
        }
    }

    private static void flatten(float[][][][][] src, float[] dst) {
        int index = 0;
        for (float[][][][] a : src) {
            for (float[][][] b : a) {
                for (float[][] c : b) {
                    for (float[] d : c) {
                        for (float value : d) {
                            dst[index++] = value;
                        }
                    }
                }
            }
        }
    }

    private static void flatten(float[][][][] src, float[] dst) {
        int index = 0;
        for (float[][][] a : src) {
            for (float[][] b : a) {
                for (float[] c : b) {
                    for (float value : c) {
                        dst[index++] = value;
                    }
                }
            }
        }
    }
}

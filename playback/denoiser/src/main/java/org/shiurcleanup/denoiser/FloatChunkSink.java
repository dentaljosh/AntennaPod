package org.shiurcleanup.denoiser;

import java.io.IOException;

/** Streaming consumer of mono float32 PCM chunks. Sinks may buffer or encode synchronously. */
@FunctionalInterface
public interface FloatChunkSink {
    void accept(float[] samples, int offset, int len) throws IOException;
}

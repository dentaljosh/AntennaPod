package org.shiurcleanup.denoiser;

final class Dsp {
    private Dsp() {
    }

    static float[] sqrtHannWindow(int size) {
        float[] window = new float[size];
        for (int i = 0; i < size; i++) {
            double hann = 0.5 - 0.5 * Math.cos((2.0 * Math.PI * i) / size);
            window[i] = (float) Math.sqrt(hann);
        }
        return window;
    }

    static float[] padSignal(float[] input, int padSize) {
        float[] padded = new float[input.length + 2 * padSize];
        System.arraycopy(input, 0, padded, padSize, input.length);
        return padded;
    }

    static void applyWindow(float[] frame, float[] window, float[] real, float[] imag) {
        for (int i = 0; i < frame.length; i++) {
            real[i] = frame[i] * window[i];
            imag[i] = 0.0f;
        }
    }

    static void overlapAdd(float[] real, float[] window, float[] scratch, float[] output, float[] norm, int offset) {
        for (int i = 0; i < real.length; i++) {
            scratch[i] = real[i] * window[i];
        }
        for (int i = 0; i < scratch.length; i++) {
            output[offset + i] += scratch[i];
            norm[offset + i] += window[i] * window[i];
        }
    }

    static float[] finalizeSignal(float[] paddedOutput, float[] norm, int padSize, int outputLength) {
        float[] output = new float[outputLength];
        for (int i = 0; i < outputLength; i++) {
            float n = norm[i + padSize];
            output[i] = n > 1.0e-8f ? paddedOutput[i + padSize] / n : 0.0f;
        }
        return output;
    }

    static void fft(float[] real, float[] imag, boolean inverse) {
        int n = real.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >>> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>>= 1;
            }
            j ^= bit;
            if (i < j) {
                swap(real, i, j);
                swap(imag, i, j);
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double angle = (inverse ? 2.0 : -2.0) * Math.PI / len;
            float wlenCos = (float) Math.cos(angle);
            float wlenSin = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float wcos = 1.0f;
                float wsin = 0.0f;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k;
                    int v = u + len / 2;
                    float vr = real[v] * wcos - imag[v] * wsin;
                    float vi = real[v] * wsin + imag[v] * wcos;
                    real[v] = real[u] - vr;
                    imag[v] = imag[u] - vi;
                    real[u] += vr;
                    imag[u] += vi;

                    float nextCos = wcos * wlenCos - wsin * wlenSin;
                    float nextSin = wcos * wlenSin + wsin * wlenCos;
                    wcos = nextCos;
                    wsin = nextSin;
                }
            }
        }

        if (inverse) {
            float scale = 1.0f / n;
            for (int i = 0; i < n; i++) {
                real[i] *= scale;
                imag[i] *= scale;
            }
        }
    }

    private static void swap(float[] array, int a, int b) {
        float tmp = array[a];
        array[a] = array[b];
        array[b] = tmp;
    }
}

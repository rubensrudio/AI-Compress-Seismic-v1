package com.sdc.core;

public final class LinearQuantizer {

    private LinearQuantizer() {}

    // compat: usa profile default (16 bits)
    private static final CompressionProfile DEFAULT_PROFILE =
            CompressionProfile.defaultHighQuality();

    /** Versão antiga (compat) – assume profile default (16 bits) */
    public static short[] encode(float[] normalized) {
        return encode(normalized, DEFAULT_PROFILE);
    }

    /**
     * Quantização linear em [-1,1], respeitando o número de bits efetivos.
     * effectiveBits deve estar entre 2 e 16.
     */
    public static short[] encode(float[] normalized, CompressionProfile profile) {
        int bits = profile.effectiveBits();
        if (bits < 2 || bits > 16) {
            throw new IllegalArgumentException("effectiveBits must be between 2 and 16, got " + bits);
        }

        int maxQ = (1 << (bits - 1)) - 1; // exemplo: 16 bits -> 32767, 15 bits -> 16383
        short[] out = new short[normalized.length];

        for (int i = 0; i < normalized.length; i++) {
            float v = normalized[i];

            // clamp em [-1,1]
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;

            int q = Math.round(v * maxQ);

            if (q > maxQ) q = maxQ;
            if (q < -maxQ) q = -maxQ;

            out[i] = (short) q;
        }
        return out;
    }

    /** Versão antiga (compat) – assume 16 bits */
    public static float[] decode(short[] quantized) {
        return decode(quantized, 16);
    }

    /**
     * Dequantização genérica: short -> float em [-1,1],
     * usando o mesmo número de bits da quantização.
     */
    public static float[] decode(short[] quantized, int bits) {
        if (bits < 2 || bits > 16) {
            throw new IllegalArgumentException("bits must be between 2 and 16, got " + bits);
        }
        int maxQ = (1 << (bits - 1)) - 1;
        float inv = 1.0f / (float) maxQ;

        float[] out = new float[quantized.length];
        for (int i = 0; i < quantized.length; i++) {
            out[i] = quantized[i] * inv;
        }
        return out;
    }

    // ---- MSE / PSNR permanecem iguais ----

    public static double mse(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("arrays must have same length");
        }
        double acc = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            acc += d * d;
        }
        return acc / a.length;
    }

    public static double psnr(float[] original, float[] reconstructed) {
        double mse = mse(original, reconstructed);
        if (mse == 0.0) return Double.POSITIVE_INFINITY;
        double max = 1.0; // assume [-1,1]
        return 10.0 * Math.log10((max * max) / mse);
    }
}

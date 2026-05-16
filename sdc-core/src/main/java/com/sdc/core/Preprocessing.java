package com.sdc.core;

import java.util.Arrays;

/**
 * Rotinas básicas de pré-processamento de traços sísmicos.
 *
 * v0:
 *  - normalização para [-1, 1]
 *  - delta encoding simples (diferença entre amostras consecutivas)
 */
public final class Preprocessing {

    private Preprocessing() {}

    /** Calcula min e max de um vetor. */
    public static float[] minMax(float[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("samples must not be null/empty");
        }
        float min = samples[0];
        float max = samples[0];
        for (float v : samples) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return new float[]{min, max};
    }

    /**
     * Normaliza um traço para o intervalo [-1, 1].
     * Retorna um novo array.
     */
    public static float[] normalizeToMinusOneToOne(float[] samples) {
        float[] mm = minMax(samples);
        float min = mm[0];
        float max = mm[1];

        // caso degenerado: tudo igual
        if (max == min) {
            float[] out = new float[samples.length];
            Arrays.fill(out, 0f);
            return out;
        }

        float[] out = new float[samples.length];
        float range = max - min;

        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            float norm = ( (v - min) / range ) * 2f - 1f; // [0,1] -> [-1,1]
            out[i] = norm;
        }
        return out;
    }

    /**
     * Delta encoding simples: out[0] = samples[0], out[i] = samples[i] - samples[i-1].
     */
    public static float[] deltaEncode(float[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("samples must not be null/empty");
        }
        float[] out = new float[samples.length];
        out[0] = samples[0];
        for (int i = 1; i < samples.length; i++) {
            out[i] = samples[i] - samples[i - 1];
        }
        return out;
    }

    /**
     * Delta decoding inverso: reconstrói o sinal original.
     */
    public static float[] deltaDecode(float[] deltas) {
        if (deltas == null || deltas.length == 0) {
            throw new IllegalArgumentException("deltas must not be null/empty");
        }
        float[] out = new float[deltas.length];
        out[0] = deltas[0];
        for (int i = 1; i < deltas.length; i++) {
            out[i] = out[i - 1] + deltas[i];
        }
        return out;
    }

    /**
     * Desfaz a normalização de [-1,1] para o range [min, max].
     * Inverso aproximado do normalizeToMinusOneToOne.
     */
    public static float[] denormalizeFromMinusOneToOne(float[] normalized, float min, float max) {
        if (normalized == null || normalized.length == 0) {
            throw new IllegalArgumentException("normalized must not be null/empty");
        }
        float range = max - min;
        if (range == 0f) {
            float[] out = new float[normalized.length];
            Arrays.fill(out, min);
            return out;
        }
        float[] out = new float[normalized.length];
        for (int i = 0; i < normalized.length; i++) {
            float v = normalized[i];         // [-1,1]
            float zeroToOne = (v + 1f) / 2f; // [-1,1] -> [0,1]
            out[i] = min + zeroToOne * range;
        }
        return out;
    }

}

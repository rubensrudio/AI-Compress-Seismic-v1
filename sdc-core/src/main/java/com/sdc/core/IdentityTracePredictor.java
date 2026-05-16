package com.sdc.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Implementação de identidade de {@link TracePredictor}.
 *
 * <p>{@code encode} e {@code decode} retornam uma cópia defensiva do array
 * de entrada sem transformação. É thread-safe e sem estado — um singleton
 * é exposto via {@link #INSTANCE} e acessado publicamente por
 * {@link TracePredictor#identity()}.
 */
final class IdentityTracePredictor implements TracePredictor {

    static final IdentityTracePredictor INSTANCE = new IdentityTracePredictor();

    private IdentityTracePredictor() {}

    @Override
    public float[] encode(float[] deltas) {
        Objects.requireNonNull(deltas, "deltas must not be null");
        return Arrays.copyOf(deltas, deltas.length);
    }

    @Override
    public float[] decode(float[] residuals) {
        Objects.requireNonNull(residuals, "residuals must not be null");
        return Arrays.copyOf(residuals, residuals.length);
    }
}

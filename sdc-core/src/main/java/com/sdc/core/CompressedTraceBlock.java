package com.sdc.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Representa um traço comprimido:
 *  - traceId
 *  - min/max originais (para denormalização)
 *  - samplesPerTrace (redundante com header, mas útil para debug)
 *  - payload comprimido em bytes
 */
public final class CompressedTraceBlock {

    private final int traceId;
    private final float min;
    private final float max;
    private final int samplesPerTrace;
    private final byte[] payload;

    public CompressedTraceBlock(int traceId, float min, float max,
                                int samplesPerTrace, byte[] payload) {
        if (samplesPerTrace <= 0) throw new IllegalArgumentException("samplesPerTrace must be > 0");
        this.traceId = traceId;
        this.min = min;
        this.max = max;
        this.samplesPerTrace = samplesPerTrace;
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public int traceId()          { return traceId; }
    public float min()            { return min; }
    public float max()            { return max; }
    public int samplesPerTrace()  { return samplesPerTrace; }
    public byte[] payload()       { return payload; }

    @Override
    public String toString() {
        return "CompressedTraceBlock{" +
                "traceId=" + traceId +
                ", min=" + min +
                ", max=" + max +
                ", samplesPerTrace=" + samplesPerTrace +
                ", payloadBytes=" + payload.length +
                '}';
    }

    public CompressedTraceBlock copyWithPayload(byte[] newPayload) {
        return new CompressedTraceBlock(traceId, min, max, samplesPerTrace, newPayload);
    }

    public CompressedTraceBlock deepCopy() {
        return new CompressedTraceBlock(traceId, min, max, samplesPerTrace,
                Arrays.copyOf(payload, payload.length));
    }
}

package com.sdc.core;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Minimal container primitives for .sdc blocks (placeholder). */
public final class TraceBlock {
    private final float[] samples;
    private final int traceId;

    public TraceBlock(int traceId, float[] samples) {
        this.traceId = traceId;
        this.samples = Objects.requireNonNull(samples);
    }

    public int traceId() { return traceId; }
    public float[] samples() { return samples; }

    public ByteBuffer toByteBuffer() {
        // placeholder: naive serialization (to be replaced by container writer)
        ByteBuffer buf = ByteBuffer.allocate(4 + samples.length * 4);
        buf.putInt(traceId);
        for (float v : samples) buf.putFloat(v);
        buf.flip();
        return buf;
    }
}

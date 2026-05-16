package com.sdc.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Cabeçalho mínimo do container .sdc v0.
 */
public final class SdcHeader {

    public static final int MAGIC = 0x53444331; // 'S''D''C''1'

    private final int version;
    private final int traceCount;
    private final int samplesPerTrace;

    public SdcHeader(int version, int traceCount, int samplesPerTrace) {
        if (version <= 0) throw new IllegalArgumentException("version must be > 0");
        if (traceCount < 0) throw new IllegalArgumentException("traceCount must be >= 0");
        if (samplesPerTrace <= 0) throw new IllegalArgumentException("samplesPerTrace must be > 0");
        this.version = version;
        this.traceCount = traceCount;
        this.samplesPerTrace = samplesPerTrace;
    }

    public int version()         { return version; }
    public int traceCount()      { return traceCount; }
    public int samplesPerTrace() { return samplesPerTrace; }

    @Override
    public String toString() {
        return "SdcHeader{" +
                "version=" + version +
                ", traceCount=" + traceCount +
                ", samplesPerTrace=" + samplesPerTrace +
                '}';
    }

    // Serialização binária simples

    public void write(DataOutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        out.writeInt(MAGIC);
        out.writeInt(version);
        out.writeInt(traceCount);
        out.writeInt(samplesPerTrace);
    }

    public static SdcHeader read(DataInputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException(String.format("Invalid SDC magic: 0x%08X", magic));
        }
        int version = in.readInt();
        int traceCount = in.readInt();
        int samplesPerTrace = in.readInt();
        return new SdcHeader(version, traceCount, samplesPerTrace);
    }
}

package com.sdc.core;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Escritor simples de arquivos .sdc v0:
 * header + traços (sem compressão ainda).
 */
public final class SdcFileWriter {

    private SdcFileWriter() {
        // utilitário estático
    }

    public static void write(Path target, List<TraceBlock> traces) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(traces, "traces");

        if (traces.isEmpty()) {
            throw new IllegalArgumentException("traces must not be empty");
        }

        int traceCount = traces.size();
        int samplesPerTrace = traces.get(0).samples().length;

        // sanity: todos com o mesmo tamanho
        for (TraceBlock tb : traces) {
            if (tb.samples().length != samplesPerTrace) {
                throw new IllegalArgumentException("all traces must have same samplesPerTrace");
            }
        }

        SdcHeader header = new SdcHeader(1, traceCount, samplesPerTrace);

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target)))) {

            header.write(out);

            for (TraceBlock tb : traces) {
                out.writeInt(tb.traceId());
                for (float v : tb.samples()) {
                    out.writeFloat(v);
                }
            }

            out.flush();
        }
    }

    public static void writeCompressed(java.nio.file.Path target, java.util.List<TraceBlock> traces)
            throws java.io.IOException {
        writeCompressed(target, traces, CompressionProfile.defaultHighQuality());
    }

    /**
     * Versão v2: grava traços comprimidos usando TraceBlockCodec e um perfil de compressão.
     * Layout:
     *  [MAGIC][version=2][traceCount][samplesPerTrace]
     *  repetido para cada traço:
     *    [traceId][min][max][payloadSize][payloadBytes...]
     */
    public static void writeCompressed(java.nio.file.Path target,
                                       java.util.List<TraceBlock> traces,
                                       CompressionProfile profile) throws java.io.IOException {

        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(traces, "traces");
        java.util.Objects.requireNonNull(profile, "profile");
        if (traces.isEmpty()) throw new IllegalArgumentException("traces must not be empty");

        int traceCount = traces.size();
        int samplesPerTrace = traces.get(0).samples().length;
        for (TraceBlock tb : traces) {
            if (tb.samples().length != samplesPerTrace) {
                throw new IllegalArgumentException("all traces must have same samplesPerTrace");
            }
        }

        SdcHeader header = new SdcHeader(2, traceCount, samplesPerTrace);

        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(target)))) {

            header.write(out);

            for (TraceBlock tb : traces) {
                CompressedTraceBlock cb = TraceBlockCodec.compress(tb, profile);
                byte[] payload = cb.payload();

                out.writeInt(cb.traceId());
                out.writeFloat(cb.min());
                out.writeFloat(cb.max());
                out.writeInt(payload.length);
                out.write(payload);
            }
            out.flush();
        }
    }

}

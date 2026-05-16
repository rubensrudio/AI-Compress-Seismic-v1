package com.sdc.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Leitor simples de arquivos .sdc v0.
 */
public final class SdcFileReader {

    private SdcFileReader() {
        // utilitário estático
    }

    public static SdcHeader readHeader(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            return SdcHeader.read(in);
        }
    }

    public static List<TraceBlock> readAll(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {

            SdcHeader header = SdcHeader.read(in);
            List<TraceBlock> traces = new ArrayList<>(header.traceCount());

            for (int t = 0; t < header.traceCount(); t++) {
                int traceId = in.readInt();
                float[] samples = new float[header.samplesPerTrace()];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = in.readFloat();
                }
                traces.add(new TraceBlock(traceId, samples));
            }
            return traces;
        }
    }

    /**
     * Lê um arquivo .sdc v2 (traços comprimidos) e retorna traços descomprimidos.
     */
    public static java.util.List<TraceBlock> readAllCompressed(java.nio.file.Path path) throws java.io.IOException {
        java.util.Objects.requireNonNull(path, "path");
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(path)))) {

            SdcHeader header = SdcHeader.read(in);
            if (header.version() != 2) {
                throw new java.io.IOException("Expected SDC version 2, got " + header.version());
            }

            java.util.List<TraceBlock> traces = new java.util.ArrayList<>(header.traceCount());

            for (int t = 0; t < header.traceCount(); t++) {
                int traceId = in.readInt();
                float min = in.readFloat();
                float max = in.readFloat();
                int payloadSize = in.readInt();
                byte[] payload = in.readNBytes(payloadSize);

                CompressedTraceBlock cb = new CompressedTraceBlock(
                        traceId, min, max, header.samplesPerTrace(), payload);

                TraceBlock tb = TraceBlockCodec.decompress(cb);
                traces.add(tb);
            }
            return traces;
        }
    }
}

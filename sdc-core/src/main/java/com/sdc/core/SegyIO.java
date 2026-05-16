package com.sdc.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Leitura/escrita mínima de arquivos SEG-Y (assumindo:
 *  - 3200 bytes de header textual
 *  - 400 bytes de binary header
 *  - traços com 240 bytes de trace header + ns amostras float32 big-endian (formato 5)
 *
 * Esta implementação é intencionalmente simplificada e serve como MVP
 * para a pipeline SEG-Y -> .sdc -> SEG-Y.
 */
public final class SegyIO {

    private SegyIO() {}

    public static final class SegyDataset {
        public final byte[] textualHeader;
        public final byte[] binaryHeader;
        public final java.util.List<byte[]> traceHeaders;
        public final java.util.List<TraceBlock> traces;
        public final int samplesPerTrace;
        public final int sampleFormatCode;

        public final java.util.List<TraceMeta> traceMeta;
        public final TraceGrid traceGrid;

        public SegyDataset(byte[] textualHeader,
                        byte[] binaryHeader,
                        java.util.List<byte[]> traceHeaders,
                        java.util.List<TraceBlock> traces,
                        int samplesPerTrace,
                        int sampleFormatCode,
                        java.util.List<TraceMeta> traceMeta,
                        TraceGrid traceGrid) {
            this.textualHeader = textualHeader;
            this.binaryHeader = binaryHeader;
            this.traceHeaders = traceHeaders;
            this.traces = traces;
            this.samplesPerTrace = samplesPerTrace;
            this.sampleFormatCode = sampleFormatCode;
            this.traceMeta = traceMeta;
            this.traceGrid = traceGrid;
        }

        public int traceCount() {
            return traces.size();
        }
    }

    /**
     * Lê um SEG-Y simplificado com formato de amostra 5 (IEEE float32).
     */
    public static SegyDataset read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {

            byte[] textualHeader = in.readNBytes(3200);
            if (textualHeader.length != 3200) {
                throw new IOException("Arquivo muito curto (textual header incompleto)");
            }

            byte[] binaryHeader = in.readNBytes(400);
            if (binaryHeader.length != 400) {
                throw new IOException("Arquivo muito curto (binary header incompleto)");
            }

            // Campos da binary header:
            // número de amostras por traço: bytes 20-21
            // formato da amostra: bytes 24-25
            int samplesPerTrace = readUnsignedShortBE(binaryHeader, 20);
            int formatCode      = readUnsignedShortBE(binaryHeader, 24);

            if (samplesPerTrace <= 0) {
                throw new IOException("samplesPerTrace inválido: " + samplesPerTrace);
            }

            if (formatCode != 1 && formatCode != 5) {
                throw new IOException("Formato de amostra não suportado neste MVP. formatCode=" + formatCode +
                        " (apenas 1=IBM float32 e 5=IEEE float32 são suportados)");
            }

            java.util.List<byte[]> traceHeaders = new java.util.ArrayList<>();
            java.util.List<TraceBlock> traces   = new java.util.ArrayList<>();
            java.util.List<TraceMeta> metaList  = new java.util.ArrayList<>();

            int traceIdx = 0;
            while (true) {
                byte[] traceHeader = in.readNBytes(240);
                if (traceHeader.length == 0) {
                    break;
                }
                if (traceHeader.length < 240) {
                    throw new EOFException("Trace header incompleto no trace " + traceIdx);
                }

                float[] samples = new float[samplesPerTrace];
                try {
                    for (int i = 0; i < samplesPerTrace; i++) {
                        int bits = in.readInt();
                        if (formatCode == 5) {
                            samples[i] = Float.intBitsToFloat(bits);
                        } else if (formatCode == 1) {
                            samples[i] = ibmToFloat(bits);
                        }
                    }
                } catch (EOFException eof) {
                    throw new EOFException("Samples incompletos no trace " + traceIdx);
                }

                traceHeaders.add(traceHeader);
                traces.add(new TraceBlock(traceIdx, samples));

                // Lê inline/xline do trace header
                int inline = readIntBE(traceHeader, 188); // bytes 189-192
                int xline  = readIntBE(traceHeader, 192); // bytes 193-196

                metaList.add(new TraceMeta(traceIdx, inline, xline));

                traceIdx++;
            }

            TraceGrid grid = buildTraceGrid(metaList);

            return new SegyDataset(textualHeader, binaryHeader, traceHeaders, traces,
                                samplesPerTrace, formatCode, metaList, grid);

        }
    }

    private static TraceGrid buildTraceGrid(java.util.List<TraceMeta> metaList) {
        java.util.Set<Integer> inlines = new java.util.HashSet<>();
        java.util.Set<Integer> xlines  = new java.util.HashSet<>();
        for (TraceMeta tm : metaList) {
            inlines.add(tm.inline);
            xlines.add(tm.xline);
        }

        int[] inlineValues = inlines.stream().sorted().mapToInt(Integer::intValue).toArray();
        int[] xlineValues  = xlines.stream().sorted().mapToInt(Integer::intValue).toArray();

        java.util.Map<Integer, Integer> inlineIndex = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> xlineIndex  = new java.util.HashMap<>();
        for (int i = 0; i < inlineValues.length; i++) inlineIndex.put(inlineValues[i], i);
        for (int j = 0; j < xlineValues.length; j++) xlineIndex.put(xlineValues[j], j);

        int[][] grid = new int[inlineValues.length][xlineValues.length];
        for (int i = 0; i < inlineValues.length; i++) {
            java.util.Arrays.fill(grid[i], -1);
        }

        for (TraceMeta tm : metaList) {
            Integer ii = inlineIndex.get(tm.inline);
            Integer jj = xlineIndex.get(tm.xline);
            if (ii != null && jj != null) {
                grid[ii][jj] = tm.traceIndex;
            }
        }

        return new TraceGrid(inlineValues, xlineValues, grid);
    }

    private static int readIntBE(byte[] buf, int offset) {
        int b0 = buf[offset]   & 0xFF;
        int b1 = buf[offset+1] & 0xFF;
        int b2 = buf[offset+2] & 0xFF;
        int b3 = buf[offset+3] & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    /**
     * Escreve um SEG-Y a partir de headers e traços reconstruídos.
     *
     * Os headers (textual/binary/trace) são preservados exatamente iguais,
     * apenas os samples são escritos a partir dos TraceBlocks.
     */
    public static void write(Path path, SegyDataset template, List<TraceBlock> traces) throws IOException {
        if (template.traceHeaders.size() != traces.size()) {
            throw new IllegalArgumentException("Mismatch entre número de traceHeaders e traces");
        }
        int n = traces.size();
        int samplesPerTrace = template.samplesPerTrace;
        for (int i = 0; i < n; i++) {
            if (traces.get(i).samples().length != samplesPerTrace) {
                throw new IllegalArgumentException("Trace " + i + " tem samplesPerTrace diferente do header");
            }
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {

            out.write(template.textualHeader);
            out.write(template.binaryHeader);

            for (int t = 0; t < n; t++) {
                out.write(template.traceHeaders.get(t)); // 240 bytes
                float[] samples = traces.get(t).samples();
                for (float v : samples) {
                    int bits;
                    if (template.sampleFormatCode == 5) {
                        // IEEE float32
                        bits = Float.floatToIntBits(v);
                    } else if (template.sampleFormatCode == 1) {
                        // IBM float32
                        bits = floatToIbm(v);
                    } else {
                        throw new IOException("Formato de amostra não suportado na escrita: " + template.sampleFormatCode);
                    }
                    out.writeInt(bits); // big-endian
                }
            }
            out.flush();
        }
    }

    private static int readUnsignedShortBE(byte[] buf, int offset) {
        int hi = buf[offset]   & 0xFF;
        int lo = buf[offset+1] & 0xFF;
        return (hi << 8) | lo;
    }

    /**
     * Converte um float IBM 32-bit (formato SEG-Y 1) para float IEEE.
     */
    private static float ibmToFloat(int ibm) {
        if (ibm == 0) return 0.0f;

        int sign = (ibm >>> 31) & 0x1;
        int exponent = (ibm >>> 24) & 0x7F;
        int fraction = ibm & 0x00FFFFFF;

        if (fraction == 0) return 0.0f;

        double mant = fraction / (double) 0x01000000; // 24 bits
        double value = mant * Math.pow(16.0, exponent - 64);

        return sign == 0 ? (float) value : (float) -value;
    }

    /**
     * Converte float IEEE para IBM 32-bit (formato SEG-Y 1).
     * Aproximação suficiente para reconstrução e consumo por softwares SEG-Y.
     */
    private static int floatToIbm(float f) {
        if (f == 0.0f) return 0;

        int signBit = 0;
        double value = f;
        if (value < 0) {
            signBit = 1;
            value = -value;
        }

        int exponent = 64;

        // Normaliza para faixa [1/16, 1)
        while (value >= 1.0) {
            value /= 16.0;
            exponent++;
        }
        while (value < 1.0 / 16.0) {
            value *= 16.0;
            exponent--;
        }

        if (exponent <= 0) {
            // underflow para zero
            return 0;
        }

        int fraction = (int) Math.round(value * 0x01000000);
        if (fraction >= 0x01000000) {
            fraction = 0x00FFFFFF;
        }

        int ibm = (signBit << 31) | (exponent << 24) | (fraction & 0x00FFFFFF);
        return ibm;
    }

    public static final class TraceMeta {
        public final int traceIndex;  // índice na lista de traces
        public final int inline;      // valor de inline (do header)
        public final int xline;       // valor de crossline (do header)

        public TraceMeta(int traceIndex, int inline, int xline) {
            this.traceIndex = traceIndex;
            this.inline = inline;
            this.xline = xline;
        }

        @Override
        public String toString() {
            return "TraceMeta{" +
                    "traceIndex=" + traceIndex +
                    ", inline=" + inline +
                    ", xline=" + xline +
                    '}';
        }
    }

    public static final class TraceGrid {
        public final int[] inlineValues;  // valores únicos ordenados
        public final int[] xlineValues;   // valores únicos ordenados

        // grid[inlineIdx][xlineIdx] = traceIndex (ou -1 se não existe)
        public final int[][] grid;

        public TraceGrid(int[] inlineValues, int[] xlineValues, int[][] grid) {
            this.inlineValues = inlineValues;
            this.xlineValues = xlineValues;
            this.grid = grid;
        }

        public int inlineCount() { return inlineValues.length; }
        public int xlineCount()  { return xlineValues.length; }

        /** Retorna o índice de trace para (inlineIdx, xlineIdx) ou -1. */
        public int traceIndexAt(int inlineIdx, int xlineIdx) {
            return grid[inlineIdx][xlineIdx];
        }
    }

}

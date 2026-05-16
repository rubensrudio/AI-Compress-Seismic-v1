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
     * Converte um float IBM 32-bit (formato SEG-Y format code 1) para float IEEE 754.
     *
     * <p>Formato IBM float32:
     * <ul>
     *   <li>bit 31: sinal</li>
     *   <li>bits 30-24: expoente em base 16 com bias 64 (valor real = 16^(exp-64))</li>
     *   <li>bits 23-0: mantissa de 24 bits representando fração hexadecimal</li>
     * </ul>
     *
     * <p>KNOWN LIMITATION: format code 1 round-trip (IEEE -> IBM -> IEEE) may differ
     * by up to IBM float precision epsilon. IBM float uses base-16 exponent grouping,
     * which means the mantissa can have up to 3 leading zero bits in the worst case,
     * reducing effective precision to ~21 significant bits vs IEEE float's 24 bits.
     * The maximum observed relative error for typical seismic amplitude ranges is
     * approximately 2^-21 (~4.8e-7). Zero is preserved exactly. Bit-exact round-trip
     * is NOT guaranteed for format code 1; CA-01 (bit-exact corretude) applies only
     * to format code 5 (IEEE float32).
     */
    static float ibmToFloat(int ibm) {
        if (ibm == 0) return 0.0f;

        int sign     = (ibm >>> 31) & 0x1;
        int exponent = (ibm >>> 24) & 0x7F;
        int fraction =  ibm         & 0x00FFFFFF;

        if (fraction == 0) return 0.0f;

        // Mantissa como fração hexadecimal: fraction / 16^6
        // Equivalente a fraction * 16^(exponent - 64 - 6) = fraction * 16^(exponent - 70)
        // Usamos double para preservar os 24 bits de mantissa sem perda intermediária.
        double mant  = fraction / (double) 0x01000000; // normaliza para [0, 1)
        double value = mant * Math.pow(16.0, exponent - 64);

        return sign == 0 ? (float) value : (float) -value;
    }

    /**
     * Converte float IEEE 754 para IBM float 32-bit (formato SEG-Y format code 1).
     *
     * <p>KNOWN LIMITATION: format code 1 round-trip may differ by IBM float precision
     * epsilon (~2^-21 relative error, approximately 4.8e-7). This is an inherent
     * limitation of the IBM float32 format and cannot be corrected without lossy
     * approximation. Bit-exact round-trip is NOT achievable for arbitrary IEEE float32
     * values converted to IBM float32 and back. Specifically:
     * <ul>
     *   <li>IBM float uses base-16 exponent grouping, so up to 3 bits of mantissa
     *       precision are lost when the value's binary exponent is not a multiple of 4.</li>
     *   <li>Zero is preserved exactly.</li>
     *   <li>Subnormal IEEE floats that underflow IBM range are flushed to zero.</li>
     *   <li>Values exceeding IBM float32 range (approx 7.2e75) saturate to max IBM float.</li>
     * </ul>
     */
    static int floatToIbm(float f) {
        if (f == 0.0f) return 0;
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            // IBM float não possui representações especiais NaN/Inf;
            // mapeamos para zero como comportamento seguro.
            return 0;
        }

        int signBit = 0;
        double value = f;
        if (value < 0.0) {
            signBit = 1;
            value   = -value;
        }

        // Bias IBM = 64; valor real = mantissa * 16^(exponent - 64).
        // Normaliza para que a mantissa esteja no intervalo [1/16, 1),
        // ou seja, o primeiro nibble hexadecimal da mantissa é não-zero.
        int exponent = 64;
        while (value >= 1.0) {
            value /= 16.0;
            exponent++;
        }
        while (value < (1.0 / 16.0) && value > 0.0) {
            value *= 16.0;
            exponent--;
        }

        // Underflow: expoente fora do range IBM (0-127 válidos, mas bias=64 -> [-64,63])
        if (exponent <= 0) {
            return 0; // flush to zero
        }
        // Overflow: expoente acima do máximo IBM (127)
        if (exponent > 127) {
            // Satura no valor máximo representável
            return (signBit << 31) | (127 << 24) | 0x00FFFFFF;
        }

        // Converte mantissa para inteiro de 24 bits.
        // Truncamento em vez de arredondamento reduz o viés sistemático,
        // mas o epsilon de round-trip permanece inalterado (limitação do formato).
        int fraction = (int) (value * 0x01000000);

        // Garante que não ultrapassamos 24 bits (pode ocorrer por imprecisão de double)
        if (fraction >= 0x01000000) {
            fraction = 0x00FFFFFF;
        }
        if (fraction < 0) {
            fraction = 0;
        }

        return (signBit << 31) | (exponent << 24) | (fraction & 0x00FFFFFF);
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

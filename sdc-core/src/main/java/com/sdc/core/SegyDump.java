package com.sdc.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Utilitários para "dump" de arquivos SEG-Y em formato texto/CSV,
 * separando seções:
 *  - Textual Header
 *  - Binary Header
 *  - Trace Headers
 *  - Samples (CSV)
 *
 * Obs: o CSV pode ficar grande (um row por amostra).
 */
public final class SegyDump {

    private SegyDump() {}

    // ---------- APIs de alto nível ----------

    /** Gera TXT + CSV lendo o arquivo SEG-Y do disco. */
    public static void dumpFromFile(Path segyPath) throws IOException {
        SegyIO.SegyDataset ds = SegyIO.read(segyPath);
        Path txt = defaultTxtDumpPath(segyPath);
        Path csv = defaultCsvDumpPath(segyPath);

        writeTextDump(segyPath, ds, txt);
        writeCsvSamples(segyPath, ds, csv);
    }

    /** Gera TXT + CSV usando um dataset já lido (evita ler duas vezes). */
    public static void dumpFromDataset(Path segyPath, SegyIO.SegyDataset dataset) throws IOException {
        Path txt = defaultTxtDumpPath(segyPath);
        Path csv = defaultCsvDumpPath(segyPath);

        writeTextDump(segyPath, dataset, txt);
        writeCsvSamples(segyPath, dataset, csv);
    }

    public static Path defaultTxtDumpPath(Path segyPath) {
        String base = segyPath.getFileName().toString();
        return segyPath.resolveSibling(base + ".dump.txt");
    }

    public static Path defaultCsvDumpPath(Path segyPath) {
        String base = segyPath.getFileName().toString();
        return segyPath.resolveSibling(base + ".samples.csv");
    }

    // ---------- Implementação TXT ----------

    public static void writeTextDump(Path segyPath,
                                     SegyIO.SegyDataset ds,
                                     Path outPath) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            w.write("=== SEG-Y DUMP ===");
            w.newLine();
            w.write("File: " + segyPath.toAbsolutePath());
            w.newLine();
            w.write("Trace count      : " + ds.traceCount());
            w.newLine();
            w.write("Samples per trace: " + ds.samplesPerTrace);
            w.newLine();
            w.write("Sample format    : " + ds.sampleFormatCode + " (1=IBM, 5=IEEE float32)");
            w.newLine();
            w.newLine();

            // Textual Header
            w.write("=== TEXTUAL HEADER (40 x 80 chars) ===");
            w.newLine();
            String text = new String(ds.textualHeader, StandardCharsets.US_ASCII);
            for (int i = 0; i < 40; i++) {
                int from = i * 80;
                int to = Math.min(from + 80, text.length());
                String line = text.substring(from, to);
                w.write(String.format("C%02d %s", i + 1, line));
                w.newLine();
            }
            w.newLine();

            // Binary Header
            w.write("=== BINARY HEADER (400 bytes) ===");
            w.newLine();
            w.write("Hex dump (16 bytes por linha):");
            w.newLine();
            hexDump(w, ds.binaryHeader, 16);
            w.newLine();

            int sampleInterval = readUnsignedShortBE(ds.binaryHeader, 16);
            int samplesPerTrace = readUnsignedShortBE(ds.binaryHeader, 20);
            int formatCode      = readUnsignedShortBE(ds.binaryHeader, 24);

            w.write("Campos principais:");
            w.newLine();
            w.write("  Sample interval (us): " + sampleInterval);
            w.newLine();
            w.write("  Samples per trace   : " + samplesPerTrace);
            w.newLine();
            w.write("  Sample format code  : " + formatCode);
            w.newLine();
            w.newLine();

            // Trace headers (resumo)
            w.write("=== TRACE HEADERS (resumo) ===");
            w.newLine();
            w.write("Mostrando sequencial do trace e alguns campos padrão.");
            w.newLine();
            w.write("(Campos conforme SEG-Y rev1: seq line [0-3], seq reel [4-7], field record [8-11], trace nr field [12-15])");
            w.newLine();
            w.newLine();

            List<byte[]> thList = ds.traceHeaders;
            for (int t = 0; t < thList.size(); t++) {
                byte[] th = thList.get(t);
                int seqLine   = readIntBE(th, 0);
                int seqReel   = readIntBE(th, 4);
                int fieldRec  = readIntBE(th, 8);
                int traceInFR = readIntBE(th, 12);

                w.write("TRACE " + t + ": ");
                w.write("seqLine=" + seqLine + ", ");
                w.write("seqReel=" + seqReel + ", ");
                w.write("fieldRec=" + fieldRec + ", ");
                w.write("traceInField=" + traceInFR);
                w.newLine();
            }

            w.newLine();
            w.write("=== FIM DO DUMP ESTRUTURAL ===");
            w.newLine();
            w.write("Os valores amostrais (samples) completos foram exportados em: "
                    + defaultCsvDumpPath(segyPath).getFileName());
            w.newLine();
        }
    }

    // ---------- Implementação CSV (samples) ----------

    public static void writeCsvSamples(Path segyPath,
                                       SegyIO.SegyDataset ds,
                                       Path outPath) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            // cabeçalho CSV
            w.write("traceIndex,sampleIndex,value");
            w.newLine();

            List<TraceBlock> traces = ds.traces;
            for (int t = 0; t < traces.size(); t++) {
                float[] samples = traces.get(t).samples();
                for (int i = 0; i < samples.length; i++) {
                    w.write(Integer.toString(t));
                    w.write(',');
                    w.write(Integer.toString(i));
                    w.write(',');
                    w.write(Float.toString(samples[i]));
                    w.newLine();
                }
            }
        }
    }

    // ---------- Helpers ----------

    private static void hexDump(BufferedWriter w, byte[] data, int bytesPerLine) throws IOException {
        int len = data.length;
        for (int i = 0; i < len; i += bytesPerLine) {
            int lineEnd = Math.min(i + bytesPerLine, len);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%04X: ", i));
            for (int j = i; j < lineEnd; j++) {
                sb.append(String.format("%02X ", data[j]));
            }
            w.write(sb.toString());
            w.newLine();
        }
    }

    private static int readUnsignedShortBE(byte[] buf, int offset) {
        int hi = buf[offset]   & 0xFF;
        int lo = buf[offset+1] & 0xFF;
        return (hi << 8) | lo;
    }

    private static int readIntBE(byte[] buf, int offset) {
        int b0 = buf[offset]   & 0xFF;
        int b1 = buf[offset+1] & 0xFF;
        int b2 = buf[offset+2] & 0xFF;
        int b3 = buf[offset+3] & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }
}

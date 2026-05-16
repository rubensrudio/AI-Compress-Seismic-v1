package com.sdc.core;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Gera um arquivo .sdc v2 (comprimido) com alguns traços sintéticos
 * e imprime a razão de compressão aproximada.
 */
public final class SdcCompressedSampleGenerator {

    public static void main(String[] args) throws Exception {
        Path target = Path.of("sample-compressed.sdc");

        int traceCount = 8;
        int samplesPerTrace = 2048;
        List<TraceBlock> traces = new ArrayList<>(traceCount);

        for (int t = 0; t < traceCount; t++) {
            float[] s = new float[samplesPerTrace];
            for (int i = 0; i < samplesPerTrace; i++) {
                float x = (float) i / samplesPerTrace;
                // senoide com fase diferente por traço
                float v = (float) Math.sin(2 * Math.PI * (5 + t) * x);
                v += (float) (0.01 * Math.random());
                s[i] = v;
            }
            traces.add(new TraceBlock(t, s));
        }

        // tamanho bruto (float32 sem container)
        long rawBytes = (long) traceCount * samplesPerTrace * 4L;

        SdcFileWriter.writeCompressed(target, traces);

        long fileBytes = Files.size(target);

        double ratio = (double) fileBytes / rawBytes;

        System.out.println("Arquivo gerado: " + target.toAbsolutePath());
        System.out.println("Tamanho bruto (float32): " + rawBytes + " bytes");
        System.out.println("Tamanho .sdc v2:         " + fileBytes + " bytes");
        System.out.printf("Razão approx (.sdc / raw): %.3f%n", ratio);

        // leitura de volta e PSNR de um traço
        List<TraceBlock> rec = SdcFileReader.readAllCompressed(target);
        float[] orig = traces.get(0).samples();
        float[] dec = rec.get(0).samples();
        double psnr = LinearQuantizer.psnr(orig, dec);
        System.out.printf("PSNR do traço 0: %.2f dB%n", psnr);
    }
}

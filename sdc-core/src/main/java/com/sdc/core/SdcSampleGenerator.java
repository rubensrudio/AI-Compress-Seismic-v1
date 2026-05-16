package com.sdc.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Gera um arquivo .sdc de exemplo para teste do CLI.
 */
public final class SdcSampleGenerator {

    public static void main(String[] args) throws Exception {
        // Arquivo será criado na pasta atual de execução
        Path target = Path.of("sample.sdc");

        // Dois traços simples com 3 amostras cada
        float[] s1 = {1f, 2f, 3f};
        float[] s2 = {4f, 5f, 6f};

        TraceBlock t1 = new TraceBlock(10, s1);
        TraceBlock t2 = new TraceBlock(11, s2);

        SdcFileWriter.write(target, List.of(t1, t2));

        System.out.println("Arquivo gerado: " + target.toAbsolutePath());
    }
}

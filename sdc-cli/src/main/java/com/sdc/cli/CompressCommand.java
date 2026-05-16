package com.sdc.cli;

import com.sdc.core.CompressionProfile;
import com.sdc.core.SegyCompression;
import com.sdc.core.SegyValidationException;
import com.sdc.core.SegyValidator;
import com.sdc.core.TracePredictor;

import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
        name = "compress",
        mixinStandardHelpOptions = true,
        description = "Compress a SEG-Y Rev1 file to .sdc format.",
        usageHelpAutoWidth = true
)
public class CompressCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<input.segy>", description = "Source SEG-Y Rev1 file path.")
    private Path input;

    @Parameters(index = "1", paramLabel = "<output.sdc>", description = "Destination compressed .sdc file path.")
    private Path output;

    @Option(names = {"--profile"}, paramLabel = "PROFILE",
            description = "Compression profile: HIGH_QUALITY, BALANCED (default), HIGH_COMPRESSION.",
            defaultValue = "BALANCED")
    private String profile;

    @Override
    public Integer call() {
        if (!Files.exists(input)) {
            System.err.printf("[sdc compress] Arquivo de entrada nao encontrado: %s%n", input);
            return ExitCode.SOFTWARE;
        }
        try {
            SegyValidator.validate(input);
        } catch (SegyValidationException ex) {
            System.err.printf("[sdc compress] Arquivo SEG-Y invalido: %s%n  byte offset: %d%n",
                    ex.getMessage(), ex.getByteOffset());
            return ExitCode.SOFTWARE;
        } catch (IOException ex) {
            System.err.printf("[sdc compress] Erro ao ler arquivo de entrada: %s%n", ex.getMessage());
            return ExitCode.SOFTWARE;
        }
        CompressionProfile compressionProfile = CompressionProfile.fromProfileName(profile);
        long startNanos = System.nanoTime();
        SegyCompression.CompressionResult result;
        try {
            result = SegyCompression.compressSegyToSdc(
                    input, output, compressionProfile,
                    TracePredictor.identity(), UUID.randomUUID());
        } catch (IOException ex) {
            System.err.printf("[sdc compress] Erro durante compressao: %s%n", ex.getMessage());
            return ExitCode.SOFTWARE;
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double throughputMbps = elapsedSeconds > 0
                ? (result.segyBytes / (1024.0 * 1024.0)) / elapsedSeconds : Double.NaN;
        System.out.printf("[sdc compress] Compressao concluida%n"
                + "  Entrada   : %s (%.2f MB)%n"
                + "  Saida     : %s (%.2f MB)%n"
                + "  Perfil    : %s%n"
                + "  Traces    : %d  |  Amostras/trace: %d%n"
                + "  Ratio     : %.4f (%.1f%% menor)%n"
                + "  Throughput: %.1f MB/s%n",
                input.getFileName(), result.segyBytes / (1024.0 * 1024.0),
                output.getFileName(), result.sdcBytes / (1024.0 * 1024.0),
                profile, result.traceCount, result.samplesPerTrace,
                result.ratioFile, result.savingsPercent, throughputMbps);
        return ExitCode.OK;
    }
}

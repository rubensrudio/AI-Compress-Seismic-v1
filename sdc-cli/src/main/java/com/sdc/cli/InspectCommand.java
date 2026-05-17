package com.sdc.cli;

import com.sdc.core.SegyIO;
import com.sdc.core.SegyIO.SegyDataset;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code sdc inspect} -- displays SEG-Y Rev1 file metadata as a formatted table.
 *
 * <p>Reads the file using {@link SegyIO#read(Path)} and prints to stdout:
 * file path, trace count, samples per trace, format code with label,
 * sample interval in ms from binary header bytes 16-17 when non-zero,
 * and file size in bytes.
 *
 * <p>Exit codes: 0 on success, 1 on file-not-found, unreadable, or invalid SEG-Y.
 *
 * <p>Implemented as part of TASK-025.
 */
@Command(
        name = "inspect",
        mixinStandardHelpOptions = true,
        description = "Display SEG-Y Rev1 file metadata (headers, trace count, sample range).",
        usageHelpAutoWidth = true
)
public class InspectCommand implements Callable<Integer> {

    /**
     * SEG-Y Rev1 binary header byte offset for the sample interval field (0-indexed).
     * Two bytes, big-endian unsigned short, value in microseconds per spec.
     */
    private static final int BINARY_HEADER_SAMPLE_INTERVAL_OFFSET = 16;

    @Parameters(index = "0", paramLabel = "<file.segy>", description = "SEG-Y Rev1 file to inspect.")
    private Path file;

    @Override
    public Integer call() {
        if (!Files.exists(file)) {
            System.err.println("[sdc inspect] File not found: " + file);
            return ExitCode.SOFTWARE;
        }
        if (!Files.isReadable(file)) {
            System.err.println("[sdc inspect] File is not readable: " + file);
            return ExitCode.SOFTWARE;
        }

        final SegyDataset dataset;
        try {
            dataset = SegyIO.read(file);
        } catch (IOException e) {
            System.err.println("[sdc inspect] Failed to read SEG-Y file: " + e.getMessage());
            return ExitCode.SOFTWARE;
        }

        final long fileSizeBytes;
        try {
            fileSizeBytes = Files.size(file);
        } catch (IOException e) {
            System.err.println("[sdc inspect] Failed to read file size: " + e.getMessage());
            return ExitCode.SOFTWARE;
        }

        printTable(file, dataset, fileSizeBytes);
        return ExitCode.OK;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Prints the formatted inspection table to stdout.
     *
     * <p>Sample interval is read from binary header bytes 16-17 as a big-endian
     * unsigned short (microseconds per SEG-Y Rev1 spec) and converted to milliseconds.
     * A value of zero means the field is absent in the header and is not printed.
     *
     * <p>Omissoes intencionais de escopo: inline/xline range e data de aquisicao
     * nao sao exibidos porque a convencao de header de traco (byte offsets dos campos
     * de linha inline/crossline e timestamp de aquisicao) nao esta definida no projeto
     * para esta versao; esses campos serao adicionados em implementacao futura (v2).
     * // inline/xline range e data de aquisicao omitidos: convencao de header de traco
     * // nao definida no projeto; deixados para implementacao futura (v2).
     */
    private void printTable(Path filePath, SegyDataset dataset, long fileSizeBytes) {
        int sampleIntervalMicros = readUnsignedShortBE(
                dataset.binaryHeader, BINARY_HEADER_SAMPLE_INTERVAL_OFFSET);

        System.out.println("=== SEG-Y Rev1 Inspection ===");
        System.out.printf("File:          %s%n", filePath.toAbsolutePath());
        System.out.printf("Traces:        %d%n", dataset.traceCount());
        System.out.printf("Samples/trace: %d%n", dataset.samplesPerTrace);
        System.out.printf("Format code:   %d (%s)%n",
                dataset.sampleFormatCode, formatCodeLabel(dataset.sampleFormatCode));

        if (sampleIntervalMicros > 0) {
            double intervalMs = sampleIntervalMicros / 1000.0;
            System.out.printf("Sample intv.:  %.4f ms%n", intervalMs);
        }

        System.out.printf("File size:     %,d bytes%n", fileSizeBytes);
    }

    /**
     * Returns a human-readable label for a SEG-Y Rev1 sample format code
     * per specification Table 1.
     */
    private static String formatCodeLabel(int code) {
        return switch (code) {
            case 1 -> "IBM float32";
            case 2 -> "32-bit integer (2's complement)";
            case 3 -> "16-bit integer (2's complement)";
            case 5 -> "IEEE float32";
            case 8 -> "8-bit integer (2's complement)";
            default -> "unknown";
        };
    }

    /** Reads a big-endian unsigned short (2 bytes) from {@code buf} at {@code offset}. */
    private static int readUnsignedShortBE(byte[] buf, int offset) {
        if (offset + 1 >= buf.length) {
            return 0;
        }
        return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    }
}

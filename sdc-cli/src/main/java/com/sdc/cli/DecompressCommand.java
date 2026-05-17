package com.sdc.cli;

import com.sdc.core.SdcContainerV1;
import com.sdc.core.SegyCompression;
import com.sdc.core.TracePredictor;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code sdc decompress} - decompresses a {@code .sdc} container back to SEG-Y Rev1.
 *
 * <h2>Execution flow</h2>
 * <ol>
 *   <li>Verify that {@code <input.sdc>} exists on disk; missing file means stderr + exit 1.</li>
 *   <li>Read the first 4 bytes and check the SDC magic ({@code 0x53444301});
 *       mismatch means stderr + exit 1.</li>
 *   <li>Delegate full decompression to
 *       {@link SegyCompression#decompressSdcToSegy(Path, Path, TracePredictor)}
 *       with {@link TracePredictor#identity()} (no-AI mode for v1).</li>
 *   <li>Print the restored file size in bytes to stdout; exit 0.</li>
 * </ol>
 *
 * <p>The optional {@code --template} parameter is accepted for forward-compatibility
 * with {@code .sdc} files that may not embed SEG-Y headers (prototype format), but is
 * intentionally ignored in v1 because {@link SdcContainerV1} embeds all headers.</p>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 - decompression successful</li>
 *   <li>1 - file not found, invalid SDC magic, incompatible container, or I/O error</li>
 * </ul>
 * </p>
 */
@Command(
        name = "decompress",
        mixinStandardHelpOptions = true,
        description = "Decompress a .sdc file back to SEG-Y Rev1 format.",
        usageHelpAutoWidth = true
)
public class DecompressCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<input.sdc>", description = "Source compressed .sdc file.")
    private Path input;

    @Parameters(index = "1", paramLabel = "<output.segy>", description = "Destination SEG-Y Rev1 file.")
    private Path output;

    @Option(
            names = {"--template"},
            paramLabel = "<original.segy>",
            description = "Optional original SEG-Y for header recovery "
                    + "(accepted for compatibility; ignored in v1 because headers are embedded in the container)."
    )
    private Path template;

    /**
     * Executes the decompression.
     *
     * @return {@link ExitCode#OK} (0) on success, {@link ExitCode#SOFTWARE} (1) on any failure
     */
    @Override
    public Integer call() {
        // Step 1: verify the input file exists.
        if (!Files.exists(input)) {
            System.err.printf("[sdc decompress] Input file not found: %s%n", input.toAbsolutePath());
            return ExitCode.SOFTWARE;
        }

        // Step 2: validate the SDC magic number (first 4 bytes, big-endian int).
        if (!hasValidMagic(input)) {
            System.err.printf(
                    "[sdc decompress] Invalid SDC magic in file: %s%n"
                    + "  Expected: 0x%08X  (SDC\\x01)%n"
                    + "  File does not appear to be a valid .sdc v1 container.%n",
                    input.toAbsolutePath(),
                    SdcContainerV1.MAGIC);
            return ExitCode.SOFTWARE;
        }

        // Step 3: decompress using identity predictor (no-AI mode for v1).
        try {
            SegyCompression.decompressSdcToSegy(input, output, TracePredictor.identity());
        } catch (IllegalArgumentException e) {
            // Thrown by SdcContainerV1.read() for incompatible magic or unsupported codec version.
            System.err.printf("[sdc decompress] Invalid container: %s%n", e.getMessage());
            return ExitCode.SOFTWARE;
        } catch (IOException e) {
            System.err.printf("[sdc decompress] I/O error during decompression: %s%n", e.getMessage());
            return ExitCode.SOFTWARE;
        }

        // Step 4: report restored size and exit 0.
        long restoredBytes = restoredSize(output);
        System.out.printf("[sdc decompress] Done. Restored SEG-Y: %s (%,d bytes)%n",
                output.toAbsolutePath(), restoredBytes);

        return ExitCode.OK;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the first 4 bytes of the given file and checks whether they match
     * {@link SdcContainerV1#MAGIC} ({@code 0x53444301}).
     *
     * @param path file to inspect
     * @return {@code true} if the magic matches; {@code false} on mismatch or I/O error
     */
    private static boolean hasValidMagic(Path path) {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            int magic = dis.readInt();
            return magic == SdcContainerV1.MAGIC;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the size of the restored SEG-Y file; returns {@code -1} on error
     * (non-fatal: decompression already succeeded at this point).
     */
    private static long restoredSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }
}

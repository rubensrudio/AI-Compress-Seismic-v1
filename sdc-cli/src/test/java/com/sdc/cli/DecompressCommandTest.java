package com.sdc.cli;

import com.sdc.core.CompressionProfile;
import com.sdc.core.SegyCompression;
import com.sdc.core.TracePredictor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DecompressCommand}.
 *
 * <p>All tests generate a synthetic SEG-Y Rev1 fixture in-memory (no dependency on
 * sdc-fixtures module) and exercise the command through Picocli's {@link CommandLine}.
 *
 * <h2>Synthetic SEG-Y layout</h2>
 * <ul>
 *   <li>3200 bytes EBCDIC header filled with {@code 0xC5} (letter 'E' in EBCDIC)</li>
 *   <li>400 bytes binary header: {@code samplesPerTrace=10} at bytes 20-21 (big-endian
 *       unsigned short), {@code formatCode=5} (IEEE float32) at bytes 24-25</li>
 *   <li>1 trace: 240 bytes trace header (zeros) + 10 x 4 bytes IEEE float32 big-endian
 *       all set to {@code 1.0f} (constant value for exact round-trip)</li>
 * </ul>
 *
 * <h2>Covered criteria (TASK-023)</h2>
 * <ol>
 *   <li>Round-trip: file produced by {@link SegyCompression} is decompressed to SEG-Y
 *       byte-identical to the original (SHA-256 match) for format code 5.</li>
 *   <li>Non-existent input file exits with code 1.</li>
 *   <li>Invalid magic (corrupt SDC header) exits with code 1.</li>
 *   <li>Prototype magic (0x53444331) exits with code 1.</li>
 *   <li>Success exits with code 0 and stdout reports restored size.</li>
 * </ol>
 */
class DecompressCommandTest {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int SAMPLES_PER_TRACE = 10;
    private static final int FORMAT_CODE_IEEE  = 5;

    // -------------------------------------------------------------------------
    // Synthetic SEG-Y builder
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal, valid SEG-Y Rev1 file in memory.
     *
     * <p>Layout (matches the TASK-023 spec):
     * <ul>
     *   <li>3200 bytes EBCDIC header filled with {@code 0xC5} (letter 'E' in EBCDIC)</li>
     *   <li>400 bytes binary header: {@code samplesPerTrace=10} at bytes 20-21 (big-endian
     *       unsigned short), {@code formatCode=5} at bytes 24-25</li>
     *   <li>1 trace: 240 bytes trace header (zeros) + 10 x 4 bytes IEEE float32 big-endian</li>
     * </ul>
     *
     * <p><b>Byte-identical round-trip guarantee:</b> all 10 samples have the same constant
     * value ({@code 1.0f}). When {@code min == max} within a trace, the linear quantizer
     * in {@link com.sdc.core.TraceBlockCodec} emits all-zero deltas and restores the
     * original constant value exactly, ensuring the decoded SEG-Y is byte-for-byte
     * identical to the original for format code 5 with {@link TracePredictor#identity()}.
     * Using varying values (e.g. 1.0f, 2.0f, ...) would introduce 16-bit quantization
     * noise and break SHA-256 comparison.</p>
     */
    private static byte[] buildSyntheticSegy() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // 3200 bytes EBCDIC header filled with 0xC5 ('E' in EBCDIC)
        byte[] ebcdic = new byte[3200];
        Arrays.fill(ebcdic, (byte) 0xC5);
        dos.write(ebcdic);

        // 400 bytes binary header
        byte[] binHeader = new byte[400];
        // bytes 20-21 (0-indexed): samplesPerTrace big-endian unsigned short = 10
        binHeader[20] = 0;
        binHeader[21] = (byte) SAMPLES_PER_TRACE;
        // bytes 24-25 (0-indexed): formatCode big-endian unsigned short = 5
        binHeader[24] = 0;
        binHeader[25] = (byte) FORMAT_CODE_IEEE;
        dos.write(binHeader);

        // 1 trace: 240-byte trace header (zeros)
        dos.write(new byte[240]);

        // 10 IEEE float32 big-endian samples: all constant 1.0f
        // Constant value guarantees min == max so the quantizer preserves it exactly,
        // giving a byte-identical round-trip.
        for (int i = 0; i < SAMPLES_PER_TRACE; i++) {
            dos.writeFloat(1.0f);
        }

        dos.flush();
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Command runner helper
    // -------------------------------------------------------------------------

    /**
     * Executes {@link DecompressCommand} via Picocli and returns the exit code.
     *
     * <p>Picocli's {@link CommandLine#execute(String...)} calls {@link DecompressCommand#call()}
     * and uses the returned integer as the exit code, WITHOUT invoking {@link System#exit(int)}.
     * This makes the command testable in-process.</p>
     */
    private int runDecompress(String... args) {
        CommandLine cmd = new CommandLine(new DecompressCommand());
        return cmd.execute(args);
    }

    // -------------------------------------------------------------------------
    // Test 1: round-trip byte-identical (SHA-256)
    // -------------------------------------------------------------------------

    /**
     * Compresses a synthetic SEG-Y (using {@link SegyCompression} directly to simulate
     * what {@link CompressCommand} does) and then decompresses via {@link DecompressCommand}.
     *
     * <p>Verifies that the SHA-256 of the restored SEG-Y is identical to the original,
     * confirming byte-level fidelity for format code 5 with identity predictor.</p>
     */
    @Test
    void decompress_roundTrip_restoredSegyByteIdenticalToOriginal(@TempDir Path tmpDir)
            throws Exception {

        // Arrange: write synthetic SEG-Y and compress it to .sdc
        Path originalSegy = tmpDir.resolve("original.segy");
        Files.write(originalSegy, buildSyntheticSegy());

        Path sdcFile = tmpDir.resolve("compressed.sdc");
        SegyCompression.compressSegyToSdc(
                originalSegy,
                sdcFile,
                CompressionProfile.defaultHighQuality(),
                TracePredictor.identity(),
                UUID.randomUUID());

        assertTrue(Files.exists(sdcFile), "Compressed .sdc must exist before decompress");
        assertTrue(Files.size(sdcFile) > 0, "Compressed .sdc must not be empty");

        Path restoredSegy = tmpDir.resolve("restored.segy");

        // Suppress stdout/stderr during command execution
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));

        int exitCode;
        try {
            exitCode = runDecompress(sdcFile.toString(), restoredSegy.toString());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }

        // Assert exit code 0
        assertEquals(0, exitCode,
                "DecompressCommand must return exit code 0 for a valid .sdc file");

        // Assert restored file exists and is non-empty
        assertTrue(Files.exists(restoredSegy), "Restored SEG-Y must exist");
        assertTrue(Files.size(restoredSegy) > 0, "Restored SEG-Y must not be empty");

        // Assert SHA-256 byte-identical round-trip
        byte[] origHash = sha256(originalSegy);
        byte[] restHash = sha256(restoredSegy);
        assertArrayEquals(origHash, restHash,
                "SHA-256 of restored SEG-Y must match the original. "
                + "Original: " + toHex(origHash) + " | Restored: " + toHex(restHash));

        // Assert stdout contains the restored file size.
        // The command uses %,d which may add locale-specific thousand separators.
        // Strip all non-digit characters to normalize and then search for the size.
        String stdoutContent = capturedOut.toString();
        long restoredSegySize = Files.size(restoredSegy);
        String stdoutAllDigits = stdoutContent.replaceAll("[^0-9]", "");
        String rawSizeDigits = String.valueOf(restoredSegySize);
        assertTrue(stdoutAllDigits.contains(rawSizeDigits),
                "stdout must report the restored file size in bytes. "
                + "Expected size: " + restoredSegySize + " | stdout: " + stdoutContent);
    }

    // -------------------------------------------------------------------------
    // Test 2: success without --template (v1 containers embed headers)
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@code --template} is optional and that its absence does not
     * cause any failure, since v1 containers embed all SEG-Y headers.
     */
    @Test
    void decompress_withoutTemplateOption_exitCode0(@TempDir Path tmpDir) throws Exception {
        Path originalSegy = tmpDir.resolve("input.segy");
        Files.write(originalSegy, buildSyntheticSegy());

        Path sdcFile = tmpDir.resolve("data.sdc");
        SegyCompression.compressSegyToSdc(originalSegy, sdcFile,
                CompressionProfile.defaultHighQuality(),
                TracePredictor.identity(), UUID.randomUUID());

        Path restoredSegy = tmpDir.resolve("output.segy");

        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));

        int exitCode;
        try {
            // No --template flag
            exitCode = runDecompress(sdcFile.toString(), restoredSegy.toString());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }

        assertEquals(0, exitCode,
                "DecompressCommand must succeed without --template for v1 containers");
        assertTrue(Files.exists(restoredSegy));
    }

    // -------------------------------------------------------------------------
    // Test 3: non-existent input file exits with code 1
    // -------------------------------------------------------------------------

    /**
     * Verifies that DecompressCommand returns exit code 1 when the input
     * {@code .sdc} file does not exist.
     */
    @Test
    void decompress_nonExistentInput_exitCode1(@TempDir Path tmpDir) {
        Path nonExistent = tmpDir.resolve("does_not_exist.sdc");
        Path output      = tmpDir.resolve("output.segy");

        PrintStream origErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));

        int exitCode;
        try {
            exitCode = runDecompress(nonExistent.toString(), output.toString());
        } finally {
            System.setErr(origErr);
        }

        assertEquals(1, exitCode,
                "DecompressCommand must return exit code 1 for non-existent input");
        assertFalse(Files.exists(output),
                "Output file must not be created when input is missing");
    }

    // -------------------------------------------------------------------------
    // Test 4: invalid magic bytes exit with code 1
    // -------------------------------------------------------------------------

    /**
     * Creates a file whose first 4 bytes do NOT match {@code 0x53444301}
     * (the SDC v1 magic) and verifies that {@link DecompressCommand} returns
     * exit code 1 with an error on stderr.
     */
    @Test
    void decompress_invalidMagicBytes_exitCode1(@TempDir Path tmpDir) throws IOException {
        // Write a file with a clearly wrong magic (e.g. 0xDEADBEEF)
        Path badSdc  = tmpDir.resolve("corrupt.sdc");
        byte[] corrupt = new byte[64];
        corrupt[0] = (byte) 0xDE;
        corrupt[1] = (byte) 0xAD;
        corrupt[2] = (byte) 0xBE;
        corrupt[3] = (byte) 0xEF;
        Files.write(badSdc, corrupt);

        Path output = tmpDir.resolve("output.segy");

        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(capturedErr));

        int exitCode;
        try {
            exitCode = runDecompress(badSdc.toString(), output.toString());
        } finally {
            System.setErr(origErr);
        }

        assertEquals(1, exitCode,
                "DecompressCommand must return exit code 1 for invalid SDC magic");
        assertFalse(Files.exists(output),
                "Output SEG-Y must not be created when magic is invalid");

        // stderr must contain a diagnostic message
        String errContent = capturedErr.toString();
        assertFalse(errContent.isBlank(),
                "stderr must contain an error message describing the magic mismatch");
    }

    // -------------------------------------------------------------------------
    // Test 5: prototype magic (0x53444331) exits with code 1
    // -------------------------------------------------------------------------

    /**
     * Verifies that a file bearing the prototype magic ({@code 0x53444331}) is
     * rejected with exit code 1, since it is incompatible with SdcContainerV1.
     */
    @Test
    void decompress_prototypeMagic_exitCode1(@TempDir Path tmpDir) throws IOException {
        // Write a file with the prototype magic 0x53444331 ('S''D''C''1')
        Path prototypeSdc = tmpDir.resolve("prototype.sdc");
        byte[] data = new byte[64];
        data[0] = (byte) 0x53; // 'S'
        data[1] = (byte) 0x44; // 'D'
        data[2] = (byte) 0x43; // 'C'
        data[3] = (byte) 0x31; // '1' -- prototype format, NOT 0x01
        Files.write(prototypeSdc, data);

        Path output = tmpDir.resolve("output.segy");

        PrintStream origErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));

        int exitCode;
        try {
            exitCode = runDecompress(prototypeSdc.toString(), output.toString());
        } finally {
            System.setErr(origErr);
        }

        assertEquals(1, exitCode,
                "DecompressCommand must return exit code 1 for the prototype magic 0x53444331");
    }

    // -------------------------------------------------------------------------
    // SHA-256 and hex helpers
    // -------------------------------------------------------------------------

    private static byte[] sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(path));
        return md.digest();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

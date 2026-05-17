package com.sdc.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CompressCommandTest {

    private static final int SDC_MAGIC        = 0x53444301;
    private static final int SAMPLES_PER_TRACE = 10;
    private static final int FORMAT_CODE_IEEE  = 5;

    /**
     * Builds a minimal, valid SEG-Y Rev1 file in memory.
     *
     * <p>All 10 samples have the same constant value ({@code 1.0f}). When
     * {@code min == max} within a trace the linear quantizer in
     * {@link com.sdc.core.TraceBlockCodec} emits all-zero deltas and restores the
     * original constant exactly, guaranteeing a byte-identical round-trip for
     * format code 5. Using varying values (e.g. 1.0f, 2.0f, …) would introduce
     * 16-bit quantization noise and break SHA-256 comparison.</p>
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
        // bytes 20-21: samplesPerTrace big-endian unsigned short
        binHeader[20] = 0;
        binHeader[21] = (byte) SAMPLES_PER_TRACE;
        // bytes 24-25: formatCode big-endian unsigned short = 5 (IEEE float32)
        binHeader[24] = 0;
        binHeader[25] = (byte) FORMAT_CODE_IEEE;
        dos.write(binHeader);

        // 1 trace: 240-byte trace header (zeros)
        dos.write(new byte[240]);

        // 10 constant IEEE float32 big-endian samples (all 1.0f)
        for (int i = 0; i < SAMPLES_PER_TRACE; i++) {
            dos.writeFloat(1.0f);
        }

        dos.flush();
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static byte[] sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(path));
        return md.digest();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private int runCompress(Path in, Path out, String profile) {
        CommandLine cmd = new CommandLine(new CompressCommand());
        String[] args = profile == null
                ? new String[]{in.toString(), out.toString()}
                : new String[]{in.toString(), out.toString(), "--profile", profile};
        return cmd.execute(args);
    }

    @Test
    void compress_validSegy_exitCode0AndSdcMagicCorrect(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("i.segy"), out = tmp.resolve("o.sdc");
        Files.write(in, buildSyntheticSegy());
        PrintStream oo = System.out, oe = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        int ec; try { ec = runCompress(in, out, null); } finally { System.setOut(oo); System.setErr(oe); }
        assertEquals(0, ec);
        assertTrue(Files.exists(out) && Files.size(out) > 0);
        int magic = ByteBuffer.wrap(Files.readAllBytes(out), 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        assertEquals(SDC_MAGIC, magic, String.format("Magic: expected 0x%08X got 0x%08X", SDC_MAGIC, magic));
    }

    @Test
    void compress_withHighQualityProfile_exitCode0(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("i.segy"), out = tmp.resolve("o.sdc");
        Files.write(in, buildSyntheticSegy());
        PrintStream oo = System.out, oe = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        int ec; try { ec = runCompress(in, out, "HIGH_QUALITY"); } finally { System.setOut(oo); System.setErr(oe); }
        assertEquals(0, ec); assertTrue(Files.exists(out));
    }

    @Test
    void compress_withHighCompressionProfile_exitCode0(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("i.segy"), out = tmp.resolve("o.sdc");
        Files.write(in, buildSyntheticSegy());
        PrintStream oo = System.out, oe = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        int ec; try { ec = runCompress(in, out, "HIGH_COMPRESSION"); } finally { System.setOut(oo); System.setErr(oe); }
        assertEquals(0, ec); assertTrue(Files.exists(out));
    }

    @Test
    void compress_missingInputFile_exitCode1(@TempDir Path tmp) {
        Path in = tmp.resolve("nx.segy"), out = tmp.resolve("o.sdc");
        PrintStream oe = System.err; System.setErr(new PrintStream(new ByteArrayOutputStream()));
        int ec; try { ec = runCompress(in, out, null); } finally { System.setErr(oe); }
        assertEquals(1, ec); assertFalse(Files.exists(out));
    }

    @Test
    void compress_invalidSegy_exitCode1(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("bad.segy"), out = tmp.resolve("o.sdc");
        Files.write(in, new byte[]{0x00, 0x01, 0x02});
        PrintStream oe = System.err; System.setErr(new PrintStream(new ByteArrayOutputStream()));
        int ec; try { ec = runCompress(in, out, null); } finally { System.setErr(oe); }
        assertEquals(1, ec);
    }

    /**
     * Verifies that the .sdc file produced by {@link CompressCommand} can be
     * round-tripped back to a SEG-Y that is byte-for-byte identical to the original,
     * confirmed via SHA-256 comparison.
     *
     * <p>The synthetic SEG-Y uses constant samples ({@code 1.0f}) so that the linear
     * quantizer preserves them exactly ({@code min == max} per trace → all-zero deltas),
     * guaranteeing a lossless round-trip for format code 5 with identity predictor.</p>
     */
    @Test
    void compress_sdcCanBeDecodedByContainerV1(@TempDir Path tmp) throws Exception {
        // Arrange: write synthetic SEG-Y with constant samples for exact round-trip
        Path originalSegy = tmp.resolve("original.segy");
        Files.write(originalSegy, buildSyntheticSegy());

        // Step 1: capture SHA-256 of the original SEG-Y before compression
        byte[] originalHash = sha256(originalSegy);

        Path sdcFile = tmp.resolve("compressed.sdc");

        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));

        int exitCode;
        try {
            exitCode = runCompress(originalSegy, sdcFile, "BALANCED");
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }

        assertEquals(0, exitCode, "CompressCommand must return exit code 0 for valid SEG-Y");
        assertTrue(Files.exists(sdcFile) && Files.size(sdcFile) > 0,
                "Compressed .sdc file must exist and be non-empty");

        // Step 2: decompress the .sdc back to a temporary SEG-Y
        Path restoredSegy = tmp.resolve("restored.segy");
        com.sdc.core.SegyCompression.decompressSdcToSegy(
                sdcFile, restoredSegy, com.sdc.core.TracePredictor.identity());

        assertTrue(Files.exists(restoredSegy) && Files.size(restoredSegy) > 0,
                "Restored SEG-Y must exist and be non-empty");

        // Step 3: calculate SHA-256 of the restored SEG-Y
        byte[] restoredHash = sha256(restoredSegy);

        // Step 4: assert byte-identical round-trip
        assertArrayEquals(originalHash, restoredHash,
                "SHA-256 of restored SEG-Y must match the original. "
                + "Original: " + toHex(originalHash) + " | Restored: " + toHex(restoredHash));
    }
}

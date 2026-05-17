package com.sdc.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests for {@link InspectCommand}.
 *
 * <p>Generates a synthetic SEG-Y Rev1 fixture in-memory and verifies that
 * {@code sdc inspect} displays the expected metadata on stdout with exit code 0.
 *
 * <p>Fixture layout:
 * <ul>
 *   <li>3200 bytes EBCDIC textual header filled with 0xC5</li>
 *   <li>400 bytes binary header: bytes 20-21 = samplesPerTrace = 50 (BE unsigned short),
 *       bytes 24-25 = formatCode = 5 (BE unsigned short)</li>
 *   <li>5 traces, each: 240-byte trace header + 50 x 4-byte IEEE float32 samples</li>
 * </ul>
 */
class InspectCommandTest {

    private static final int TRACE_COUNT = 5;
    private static final int SAMPLES_PER_TRACE = 50;
    private static final int FORMAT_CODE = 5;

    @TempDir
    Path tempDir;

    private Path segyFile;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void setUp() throws Exception {
        segyFile = tempDir.resolve("fixture.segy");
        writeSyntheticSegy(segyFile, TRACE_COUNT, SAMPLES_PER_TRACE, FORMAT_CODE);

        originalOut = System.out;
        originalErr = System.err;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, /*autoFlush=*/ true, "UTF-8"));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void inspectDisplaysTraceCount() throws Exception {
        int exitCode = runInspect(segyFile);
        assertEquals(0, exitCode, "Exit code must be 0 for a valid SEG-Y file");
        String output = capturedOut.toString("UTF-8");
        assertTrue(
                output.contains(String.valueOf(TRACE_COUNT)),
                "stdout must contain trace count '" + TRACE_COUNT + "', but was:\n" + output
        );
    }

    @Test
    void inspectDisplaysSamplesPerTrace() throws Exception {
        int exitCode = runInspect(segyFile);
        assertEquals(0, exitCode);
        String output = capturedOut.toString("UTF-8");
        assertTrue(
                output.contains(String.valueOf(SAMPLES_PER_TRACE)),
                "stdout must contain samplesPerTrace '" + SAMPLES_PER_TRACE + "', but was:\n" + output
        );
    }

    @Test
    void inspectDisplaysFormatCode() throws Exception {
        int exitCode = runInspect(segyFile);
        assertEquals(0, exitCode);
        String output = capturedOut.toString("UTF-8");
        assertTrue(
                output.contains(String.valueOf(FORMAT_CODE)),
                "stdout must contain format code '" + FORMAT_CODE + "', but was:\n" + output
        );
    }

    @Test
    void inspectDisplaysFileSizeInBytes() throws Exception {
        int exitCode = runInspect(segyFile);
        assertEquals(0, exitCode);
        long expectedSize = Files.size(segyFile);
        String output = capturedOut.toString("UTF-8");
        assertTrue(
                output.contains(String.valueOf(expectedSize))
                        || output.contains(String.format("%,d", expectedSize)),
                "stdout must contain file size '" + expectedSize + "', but was:\n" + output
        );
    }

    @Test
    void inspectReturnsExitOneForMissingFile() {
        System.setErr(originalErr);
        Path nonExistent = tempDir.resolve("does_not_exist.segy");
        int exitCode = runInspect(nonExistent);
        assertEquals(1, exitCode, "Exit code must be 1 when the file does not exist");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Invokes {@code InspectCommand} via Picocli's {@link CommandLine} in the
     * same JVM and returns the exit code.
     */
    private static int runInspect(Path file) {
        CommandLine cmd = new CommandLine(new InspectCommand());
        cmd.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        return cmd.execute(file.toString());
    }

    /**
     * Writes a synthetic, structurally valid SEG-Y Rev1 file to {@code dest}.
     */
    static void writeSyntheticSegy(Path dest, int traceCount, int samplesPerTrace, int formatCode)
            throws Exception {
        byte[] textualHeader = new byte[3200];
        java.util.Arrays.fill(textualHeader, (byte) 0xC5);

        byte[] binaryHeader = new byte[400];
        binaryHeader[20] = (byte) ((samplesPerTrace >>> 8) & 0xFF);
        binaryHeader[21] = (byte) (samplesPerTrace & 0xFF);
        binaryHeader[24] = (byte) ((formatCode >>> 8) & 0xFF);
        binaryHeader[25] = (byte) (formatCode & 0xFF);

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dest)))) {
            out.write(textualHeader);
            out.write(binaryHeader);
            for (int t = 0; t < traceCount; t++) {
                out.write(new byte[240]);
                for (int s = 0; s < samplesPerTrace; s++) {
                    float sample = (float) Math.sin(2.0 * Math.PI * s / samplesPerTrace);
                    out.writeInt(Float.floatToIntBits(sample));
                }
            }
            out.flush();
        }
    }
}

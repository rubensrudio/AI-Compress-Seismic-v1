package com.sdc.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes funcionais para ValidateCommand.
 *
 * Layout do SEG-Y Rev1 minimo usado nas fixtures:
 *   [0    - 3199]  : Header EBCDIC (3200 bytes, 0xC5, byte > 0x7E)
 *   [3200 - 3599]  : Binary header (400 bytes, big-endian)
 *     [3220 - 3221]: samplesPerTrace = 10 (offset 20 no BH)
 *     [3224 - 3225]: formatCode = 5 (offset 24 no BH)
 *   [3600 - 3839]  : Trace header (240 bytes, zeros)
 *   [3840 - 3879]  : 10 amostras float32 big-endian (40 bytes)
 *   Total          : 3880 bytes
 */
class ValidateCommandTest {

    private static final int EBCDIC_SIZE       = 3200;
    private static final int BINARY_HDR_SIZE   = 400;
    private static final int TRACE_HDR_SIZE    = 240;
    private static final int BYTES_PER_SAMPLE  = 4;
    private static final int SAMPLES_PER_TRACE = 10;
    private static final int FORMAT_CODE_IEEE  = 5;

    private static byte[] buildValidSegy() {
        int traceSize = TRACE_HDR_SIZE + SAMPLES_PER_TRACE * BYTES_PER_SAMPLE;
        int total = EBCDIC_SIZE + BINARY_HDR_SIZE + traceSize;
        byte[] segy = new byte[total];

        Arrays.fill(segy, 0, EBCDIC_SIZE, (byte) 0xC5);

        int bhBase = EBCDIC_SIZE;
        writeShortBE(segy, bhBase + 20, SAMPLES_PER_TRACE);
        writeShortBE(segy, bhBase + 24, FORMAT_CODE_IEEE);

        int sampleBase = EBCDIC_SIZE + BINARY_HDR_SIZE + TRACE_HDR_SIZE;
        for (int i = 0; i < SAMPLES_PER_TRACE; i++) {
            int bits = Float.floatToIntBits((float) (i + 1));
            writeIntBE(segy, sampleBase + i * BYTES_PER_SAMPLE, bits);
        }

        return segy;
    }

    private static void writeShortBE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >> 8) & 0xFF);
        buf[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeIntBE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 8)  & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    private record ExecResult(int exitCode, String stdout, String stderr) {}

    private ExecResult runValidateCapture(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        try {
            System.setOut(new PrintStream(outBuf));
            System.setErr(new PrintStream(errBuf));
            CommandLine cmd = new CommandLine(new ValidateCommand());
            int exitCode = cmd.execute(args);
            return new ExecResult(exitCode, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    /** Fixture SEG-Y valida: exit 0 e stdout contem "OK". */
    @Test
    void validSegy_shouldReturnExitCode0AndPrintOK(@TempDir Path tmpDir) throws Exception {
        Path segyFile = tmpDir.resolve("valid.sgy");
        Files.write(segyFile, buildValidSegy());

        ExecResult result = runValidateCapture(segyFile.toString());

        assertEquals(0, result.exitCode(),
            "Arquivo SEG-Y valido deve retornar exit code 0. " +
            "stdout=[" + result.stdout() + "] stderr=[" + result.stderr() + "]");

        assertTrue(result.stdout().contains("OK"),
            "Stdout deve conter 'OK'. stdout=[" + result.stdout() + "]");
    }

    /** Arquivo corrompido com 100 bytes: exit 1 e saida menciona byte offset. */
    @Test
    void corruptedSegy_tooShort_shouldReturnExitCode1WithByteOffset(@TempDir Path tmpDir)
            throws Exception {
        Path corruptedFile = tmpDir.resolve("corrupted.sgy");
        Files.write(corruptedFile, new byte[100]);

        ExecResult result = runValidateCapture(corruptedFile.toString());

        assertEquals(1, result.exitCode(),
            "Arquivo corrompido deve retornar exit code 1. " +
            "stdout=[" + result.stdout() + "] stderr=[" + result.stderr() + "]");

        String combined = result.stdout() + result.stderr();
        assertTrue(combined.contains("offset") || combined.contains("byte") ||
                   combined.contains("curto") || combined.contains("INVALIDO"),
            "Saida deve mencionar byte offset ou problema. stderr=[" + result.stderr() + "]");
    }

    /** Arquivo inexistente: exit 1. */
    @Test
    void nonExistentFile_shouldReturnExitCode1() {
        String nonExistent = "/tmp/sdc-test-does-not-exist-99887766.sgy";

        ExecResult result = runValidateCapture(nonExistent);

        assertEquals(1, result.exitCode(),
            "Arquivo inexistente deve retornar exit code 1. " +
            "stdout=[" + result.stdout() + "] stderr=[" + result.stderr() + "]");
    }

    /** SEG-Y com samplesPerTrace=0: exit 1 e stderr contem byte offset 3220. */
    @Test
    void corruptedBinaryHeader_samplesPerTraceZero_shouldReturnExitCode1WithOffset(
            @TempDir Path tmpDir) throws Exception {
        byte[] segy = buildValidSegy();

        int samplesFieldOffset = EBCDIC_SIZE + 20;
        writeShortBE(segy, samplesFieldOffset, 0);

        Path corruptedFile = tmpDir.resolve("badheader.sgy");
        Files.write(corruptedFile, segy);

        ExecResult result = runValidateCapture(corruptedFile.toString());

        assertEquals(1, result.exitCode(),
            "SEG-Y com samplesPerTrace=0 deve retornar exit code 1. " +
            "stdout=[" + result.stdout() + "] stderr=[" + result.stderr() + "]");

        String combined = result.stdout() + result.stderr();
        assertTrue(combined.contains(String.valueOf(samplesFieldOffset)),
            "Saida deve mencionar byte offset " + samplesFieldOffset +
            ". stderr=[" + result.stderr() + "]");
    }
}

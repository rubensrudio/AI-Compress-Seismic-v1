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
import static org.junit.jupiter.api.Assertions.*;

class CompressCommandTest {

    private static final int SDC_MAGIC = 0x53444301;

    private static byte[] buildSyntheticSegy() throws IOException {
        final int SAMPLES = 10;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        byte[] ebcdic = new byte[3200];
        java.util.Arrays.fill(ebcdic, (byte) 0xC5);
        dos.write(ebcdic);
        byte[] binHeader = new byte[400];
        binHeader[20] = 0; binHeader[21] = (byte) SAMPLES;
        binHeader[24] = 0; binHeader[25] = 5;
        dos.write(binHeader);
        dos.write(new byte[240]);
        for (int i = 1; i <= SAMPLES; i++) dos.writeFloat((float) i);
        dos.flush();
        return baos.toByteArray();
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

    @Test
    void compress_sdcCanBeDecodedByContainerV1(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("i.segy"), out = tmp.resolve("o.sdc");
        Files.write(in, buildSyntheticSegy());
        PrintStream oo = System.out, oe = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        int ec; try { ec = runCompress(in, out, "BALANCED"); } finally { System.setOut(oo); System.setErr(oe); }
        assertEquals(0, ec);
        Path decoded = tmp.resolve("d.segy");
        com.sdc.core.SegyCompression.decompressSdcToSegy(out, decoded, com.sdc.core.TracePredictor.identity());
        assertTrue(Files.exists(decoded) && Files.size(decoded) > 0);
    }
}

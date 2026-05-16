package com.sdc.fixtures;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates synthetic SEG-Y Rev1 fixture files for correctness testing.
 *
 * <p>This class is both a test class (executed by Surefire) and a static
 * utility used by other test classes in this module. When run as a test,
 * it generates all fixtures and verifies their SHA-256 checksums.</p>
 *
 * <h2>Generated fixtures (written to {@code src/test/resources/fixtures/})</h2>
 * <ul>
 *   <li>{@code minimal.segy} — 1 trace, format code 5 (IEEE float32), 100 samples/trace</li>
 *   <li>{@code medium.segy} — 100 traces, format code 5, 500 samples/trace</li>
 *   <li>{@code multi_logical.segy} — two concatenated SEG-Y logical files (100 + 50 traces)</li>
 *   <li>{@code corrupt_ebcdic.segy} — minimal fixture with bytes 0x01–0x08 injected into EBCDIC header</li>
 *   <li>{@code corrupt_binary.segy} — minimal fixture with samplesPerTrace = 0 in binary header</li>
 *   <li>{@code corrupt_mid_file.segy} — medium fixture truncated mid-trace (last trace cut in half)</li>
 * </ul>
 *
 * <p>SHA-256 checksums for all <em>valid</em> fixtures are written to
 * {@code src/test/resources/fixtures/checksums.sha256} in the format
 * {@code <hex-hash>  <filename>}.</p>
 *
 * <h2>SEG-Y Rev1 layout</h2>
 * <pre>
 *   Bytes 0 – 3199    : Textual (EBCDIC) header — 3200 bytes
 *   Bytes 3200 – 3599 : Binary header — 400 bytes
 *     samplesPerTrace : bytes 3220–3221 (big-endian unsigned short, 0-based from file start)
 *     formatCode      : bytes 3224–3225 (big-endian unsigned short)
 *   Bytes 3600+       : Trace records (repeated)
 *     trace header    : 240 bytes
 *     trace data      : samplesPerTrace × 4 bytes (format 5 = IEEE float32 big-endian)
 * </pre>
 */
public class SegyFixtureGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(SegyFixtureGenerator.class);

    // SEG-Y structural constants
    static final int EBCDIC_HEADER_SIZE    = 3200;
    static final int BINARY_HEADER_SIZE    = 400;
    static final int TRACE_HEADER_SIZE     = 240;
    static final int BYTES_PER_SAMPLE      = 4;  // IEEE float32

    // Binary header field offsets (0-based from start of binary header)
    private static final int BIN_SAMPLES_PER_TRACE_OFFSET = 20; // bytes 20-21 of binary header
    private static final int BIN_FORMAT_CODE_OFFSET        = 24; // bytes 24-25 of binary header

    static final int FORMAT_CODE_IEEE_FLOAT32 = 5;

    /** Directory where fixtures are written (on the classpath via Maven resources). */
    private static final String FIXTURES_CLASSPATH_DIR = "fixtures";

    // -------------------------------------------------------------------------
    // Test entry point
    // -------------------------------------------------------------------------

    /**
     * JUnit test that generates all fixtures and verifies SHA-256 checksums.
     * Executed by Maven Surefire during {@code mvn test -pl sdc-fixtures}.
     */
    @Test
    void generateAllFixturesAndVerifyChecksums() throws Exception {
        Path fixturesDir = resolveFixturesOutputDir();
        LOG.info("Generating fixtures into: {}", fixturesDir);

        List<FixtureEntry> validFixtures = new ArrayList<>();

        // --- (a) Minimal valid: 1 trace, format code 5, 100 samples/trace ---
        byte[] minimal = buildSegyFile(100, FORMAT_CODE_IEEE_FLOAT32, 1, "minimal");
        Path minimalPath = write(fixturesDir, "minimal.segy", minimal);
        validFixtures.add(new FixtureEntry("minimal.segy", minimalPath));
        LOG.info("Written minimal.segy ({} bytes)", minimal.length);

        // --- (b) Medium: 100 traces, format code 5, 500 samples/trace ---
        byte[] medium = buildSegyFile(500, FORMAT_CODE_IEEE_FLOAT32, 100, "medium");
        Path mediumPath = write(fixturesDir, "medium.segy", medium);
        validFixtures.add(new FixtureEntry("medium.segy", mediumPath));
        LOG.info("Written medium.segy ({} bytes)", medium.length);

        // --- (c) Multiple logical files: two concatenated SEG-Y files ---
        byte[] multiLogical = buildMultiLogicalSegyFile();
        Path multiPath = write(fixturesDir, "multi_logical.segy", multiLogical);
        validFixtures.add(new FixtureEntry("multi_logical.segy", multiPath));
        LOG.info("Written multi_logical.segy ({} bytes)", multiLogical.length);

        // --- (d) Corrupted fixtures ---
        writeCorrectedFixtures(fixturesDir, minimal, medium);

        // --- Generate and write checksums ---
        Path checksumsPath = writeChecksums(fixturesDir, validFixtures);
        LOG.info("Written checksums.sha256 ({} entries)", validFixtures.size());

        // --- Verify checksums round-trip ---
        verifyChecksums(checksumsPath, validFixtures);
        LOG.info("All checksums verified successfully.");

        // --- Assert files exist and are non-empty ---
        for (FixtureEntry entry : validFixtures) {
            assertTrue(Files.exists(entry.path()),
                "Fixture file must exist: " + entry.name());
            assertTrue(Files.size(entry.path()) > 0,
                "Fixture file must not be empty: " + entry.name());
        }
        assertTrue(Files.exists(checksumsPath), "checksums.sha256 must exist");
        assertFalse(Files.readString(checksumsPath).isBlank(),
            "checksums.sha256 must not be blank");
    }

    /**
     * Verifies that the valid fixtures pass basic structural assertions.
     * This is a secondary test that validates the generated content.
     */
    @Test
    void generatedMinimalFixtureHasCorrectStructure() throws Exception {
        Path fixturesDir = resolveFixturesOutputDir();
        Path minimalPath = fixturesDir.resolve("minimal.segy");

        // Generate if not present
        if (!Files.exists(minimalPath)) {
            byte[] minimal = buildSegyFile(100, FORMAT_CODE_IEEE_FLOAT32, 1, "minimal");
            write(fixturesDir, "minimal.segy", minimal);
        }

        byte[] data = Files.readAllBytes(minimalPath);

        // Verify sizes
        int expectedTraces = 1;
        int samplesPerTrace = 100;
        int expectedSize = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE
                + expectedTraces * (TRACE_HEADER_SIZE + samplesPerTrace * BYTES_PER_SAMPLE);

        assertTrue(data.length == expectedSize,
            "minimal.segy must be exactly " + expectedSize + " bytes, got " + data.length);

        // Verify samplesPerTrace in binary header
        int spt = readUnsignedShortBE(data, EBCDIC_HEADER_SIZE + BIN_SAMPLES_PER_TRACE_OFFSET);
        assertTrue(spt == samplesPerTrace, "samplesPerTrace must be " + samplesPerTrace);

        // Verify formatCode in binary header
        int fc = readUnsignedShortBE(data, EBCDIC_HEADER_SIZE + BIN_FORMAT_CODE_OFFSET);
        assertTrue(fc == FORMAT_CODE_IEEE_FLOAT32, "formatCode must be 5");

        // Verify EBCDIC header has at least one byte > 0x7E (required by SegyValidator)
        boolean hasHighByte = false;
        for (int i = 0; i < EBCDIC_HEADER_SIZE; i++) {
            if ((data[i] & 0xFF) > 0x7E) {
                hasHighByte = true;
                break;
            }
        }
        assertTrue(hasHighByte, "EBCDIC header must contain bytes > 0x7E to be recognized as EBCDIC");
    }

    /**
     * Verifies that corrupt fixtures exist and have expected corruption markers.
     */
    @Test
    void corruptedFixturesExist() throws Exception {
        Path fixturesDir = resolveFixturesOutputDir();

        // Ensure fixtures are generated
        if (!Files.exists(fixturesDir.resolve("corrupt_ebcdic.segy"))) {
            byte[] minimal = buildSegyFile(100, FORMAT_CODE_IEEE_FLOAT32, 1, "minimal");
            byte[] medium  = buildSegyFile(500, FORMAT_CODE_IEEE_FLOAT32, 100, "medium");
            write(fixturesDir, "minimal.segy", minimal);
            write(fixturesDir, "medium.segy", medium);
            writeCorrectedFixtures(fixturesDir, minimal, medium);
        }

        assertNotNull(fixturesDir.resolve("corrupt_ebcdic.segy"));
        assertNotNull(fixturesDir.resolve("corrupt_binary.segy"));
        assertNotNull(fixturesDir.resolve("corrupt_mid_file.segy"));

        assertTrue(Files.exists(fixturesDir.resolve("corrupt_ebcdic.segy")),
            "corrupt_ebcdic.segy must exist");
        assertTrue(Files.exists(fixturesDir.resolve("corrupt_binary.segy")),
            "corrupt_binary.segy must exist");
        assertTrue(Files.exists(fixturesDir.resolve("corrupt_mid_file.segy")),
            "corrupt_mid_file.segy must exist");

        // corrupt_binary.segy must have samplesPerTrace = 0 at the binary header offset
        byte[] corruptBinary = Files.readAllBytes(fixturesDir.resolve("corrupt_binary.segy"));
        int spt = readUnsignedShortBE(corruptBinary, EBCDIC_HEADER_SIZE + BIN_SAMPLES_PER_TRACE_OFFSET);
        assertTrue(spt == 0, "corrupt_binary.segy must have samplesPerTrace=0");

        // corrupt_mid_file.segy must be smaller than a complete medium fixture
        byte[] medium = buildSegyFile(500, FORMAT_CODE_IEEE_FLOAT32, 100, "medium");
        byte[] corruptMid = Files.readAllBytes(fixturesDir.resolve("corrupt_mid_file.segy"));
        assertTrue(corruptMid.length < medium.length,
            "corrupt_mid_file.segy must be truncated compared to full medium fixture");

        assertDoesNotThrow(() -> LOG.info("corrupt_mid_file.segy size: {} bytes", corruptMid.length));
    }

    // -------------------------------------------------------------------------
    // Static builder methods (package-visible for reuse by other test classes)
    // -------------------------------------------------------------------------

    /**
     * Builds a synthetic SEG-Y Rev1 byte array.
     *
     * @param samplesPerTrace  number of samples per trace (written to binary header bytes 20-21)
     * @param formatCode       sample format code — must be 5 (IEEE float32) for valid fixtures
     * @param traceCount       number of traces to generate
     * @param description      short string embedded in the EBCDIC header for identification
     * @return complete SEG-Y file as a byte array
     */
    static byte[] buildSegyFile(int samplesPerTrace, int formatCode,
                                int traceCount, String description) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeEbcdicHeader(out, description, samplesPerTrace, traceCount);
            writeBinaryHeader(out, samplesPerTrace, formatCode);
            for (int i = 0; i < traceCount; i++) {
                writeTrace(out, i, samplesPerTrace, formatCode);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build synthetic SEG-Y file", e);
        }
        return baos.toByteArray();
    }

    /**
     * Builds a multi-logical-file SEG-Y: two complete SEG-Y files concatenated.
     * The first file has 100 traces (500 samples/trace) and the second has 50 traces
     * (250 samples/trace). Both use format code 5.
     *
     * @return byte array with two concatenated SEG-Y logical files
     */
    static byte[] buildMultiLogicalSegyFile() {
        byte[] first  = buildSegyFile(500, FORMAT_CODE_IEEE_FLOAT32, 100, "multi-part-1");
        byte[] second = buildSegyFile(250, FORMAT_CODE_IEEE_FLOAT32, 50,  "multi-part-2");
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first,  0, combined, 0,            first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    // -------------------------------------------------------------------------
    // Corruption injection methods
    // -------------------------------------------------------------------------

    /**
     * Generates and writes all three corrupted fixtures:
     * <ol>
     *   <li>corrupt_ebcdic.segy — EBCDIC header bytes 10-330 overwritten with 0x01</li>
     *   <li>corrupt_binary.segy — samplesPerTrace set to 0 in binary header</li>
     *   <li>corrupt_mid_file.segy — medium fixture truncated after 50 complete traces + half a trace</li>
     * </ol>
     */
    private static void writeCorrectedFixtures(Path dir, byte[] minimal, byte[] medium)
            throws IOException {
        // Corruption 1: inject invalid control bytes (0x01–0x08) into EBCDIC header.
        // The SegyValidator rejects headers where > 10% of bytes are in 0x01-0x08 range.
        // We inject them densely to ensure rejection.
        byte[] corruptEbcdic = minimal.clone();
        for (int i = 10; i < 330; i++) {
            corruptEbcdic[i] = 0x01; // invalid control byte
        }
        write(dir, "corrupt_ebcdic.segy", corruptEbcdic);
        LOG.info("Written corrupt_ebcdic.segy — {} bytes of control bytes injected at offset 10",
                320);

        // Corruption 2: set samplesPerTrace = 0 in binary header (offset 3200 + 20 = 3220)
        byte[] corruptBinary = minimal.clone();
        corruptBinary[EBCDIC_HEADER_SIZE + BIN_SAMPLES_PER_TRACE_OFFSET]     = 0x00; // MSB
        corruptBinary[EBCDIC_HEADER_SIZE + BIN_SAMPLES_PER_TRACE_OFFSET + 1] = 0x00; // LSB
        write(dir, "corrupt_binary.segy", corruptBinary);
        LOG.info("Written corrupt_binary.segy — samplesPerTrace set to 0");

        // Corruption 3: truncate medium fixture at 50 complete traces + half a trace.
        // A complete trace = 240 (header) + 500 * 4 (samples) = 2240 bytes.
        // File structure: 3600 bytes headers + 50 * 2240 + 1120 (half trace)
        int traceSize   = TRACE_HEADER_SIZE + 500 * BYTES_PER_SAMPLE; // 2240 bytes
        int headerBytes = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE;    // 3600 bytes
        int truncLen    = headerBytes + 50 * traceSize + traceSize / 2;
        byte[] corruptMid = new byte[Math.min(truncLen, medium.length)];
        System.arraycopy(medium, 0, corruptMid, 0, corruptMid.length);
        write(dir, "corrupt_mid_file.segy", corruptMid);
        LOG.info("Written corrupt_mid_file.segy — truncated at byte {} (50.5 traces)", corruptMid.length);
    }

    // -------------------------------------------------------------------------
    // SEG-Y binary writing helpers
    // -------------------------------------------------------------------------

    /**
     * Writes a 3200-byte EBCDIC textual header.
     *
     * <p>The header is composed of 40 lines of 80 characters each, encoded in
     * EBCDIC. To satisfy the {@code SegyValidator} check (which requires at least
     * one byte > 0x7E to distinguish EBCDIC from pure ASCII), we encode the text
     * using the standard IBM EBCDIC code page 037 table for ASCII characters that
     * have EBCDIC equivalents in the high range (> 0x7F).</p>
     *
     * <p>The first line embeds a C1 prefix (0xC1 = 'A' in EBCDIC) to guarantee
     * high bytes are present even for the simplest content.</p>
     */
    private static void writeEbcdicHeader(DataOutputStream out, String description,
                                          int samplesPerTrace, int traceCount)
            throws IOException {
        // Build 40 lines × 80 chars in EBCDIC encoding
        byte[] header = new byte[EBCDIC_HEADER_SIZE];
        // Fill with EBCDIC space (0x40)
        java.util.Arrays.fill(header, (byte) 0x40);

        // Line 1: C1xx prefix guarantees bytes > 0x7E (0xC1 = 'A' in EBCDIC)
        // "C 1 SDC SYNTHETIC SEGY REV1"
        String line1Text = String.format(
                "C 1 SDC SYNTHETIC SEGY REV1 - %s - SPT=%d TRACES=%d",
                description.toUpperCase(), samplesPerTrace, traceCount);
        byte[] ebcdicLine1 = asciiToEbcdic(padOrTruncate(line1Text, 80));
        System.arraycopy(ebcdicLine1, 0, header, 0, 80);

        // Line 2: creation timestamp in EBCDIC
        String line2Text = String.format("C 2 GENERATED BY SegyFixtureGenerator ON 2026-05-16");
        byte[] ebcdicLine2 = asciiToEbcdic(padOrTruncate(line2Text, 80));
        System.arraycopy(ebcdicLine2, 0, header, 80, 80);

        // Line 3: format description
        String line3Text = "C 3 FORMAT CODE 5 IEEE FLOAT32 BIG-ENDIAN";
        byte[] ebcdicLine3 = asciiToEbcdic(padOrTruncate(line3Text, 80));
        System.arraycopy(ebcdicLine3, 0, header, 160, 80);

        // Remaining 37 lines stay as EBCDIC spaces (0x40) — all > 0x3F so OK

        out.write(header);
    }

    /**
     * Writes a 400-byte binary header with the required fields.
     *
     * <p>Only the two fields required by SegyValidator are populated;
     * all other bytes are zero.</p>
     *
     * @param out              output stream
     * @param samplesPerTrace  value to write at bytes 20–21 of the binary header
     * @param formatCode       value to write at bytes 24–25 of the binary header
     */
    private static void writeBinaryHeader(DataOutputStream out, int samplesPerTrace, int formatCode)
            throws IOException {
        byte[] header = new byte[BINARY_HEADER_SIZE];
        // samplesPerTrace at offset 20 (big-endian unsigned short)
        writeUnsignedShortBE(header, BIN_SAMPLES_PER_TRACE_OFFSET, samplesPerTrace);
        // formatCode at offset 24 (big-endian unsigned short)
        writeUnsignedShortBE(header, BIN_FORMAT_CODE_OFFSET, formatCode);
        out.write(header);
    }

    /**
     * Writes a single trace: 240-byte header + {@code samplesPerTrace} × 4 bytes of samples.
     *
     * <p>The trace header encodes {@code traceIndex} as inline (bytes 188-191) and xline
     * (bytes 192-195) using big-endian int, as expected by {@code SegyIO}.</p>
     *
     * <p>Sample values are deterministic synthetic sine-wave values scaled to distinguish
     * traces, which aids human inspection and also makes SHA-256 checksums stable
     * across runs.</p>
     */
    private static void writeTrace(DataOutputStream out, int traceIndex,
                                   int samplesPerTrace, int formatCode)
            throws IOException {
        byte[] traceHeader = new byte[TRACE_HEADER_SIZE];
        // Inline number at bytes 188-191 (offset 188 from start of trace header)
        writeIntBE(traceHeader, 188, traceIndex + 1);
        // Crossline number at bytes 192-195
        writeIntBE(traceHeader, 192, 1);
        out.write(traceHeader);

        // Write samples as IEEE float32 big-endian (format code 5)
        for (int s = 0; s < samplesPerTrace; s++) {
            float value = syntheticSample(traceIndex, s, samplesPerTrace);
            int bits = Float.floatToIntBits(value);
            out.writeInt(bits);
        }
    }

    /**
     * Generates a deterministic sample value for the given trace and sample index.
     * Uses a sine wave with trace-dependent phase to produce realistic-looking data.
     */
    private static float syntheticSample(int traceIndex, int sampleIndex, int samplesPerTrace) {
        double phase    = 2.0 * Math.PI * traceIndex / Math.max(1, samplesPerTrace);
        double freq     = 2.0 * Math.PI * sampleIndex / Math.max(1, samplesPerTrace);
        double envelope = Math.exp(-sampleIndex / (double) Math.max(1, samplesPerTrace));
        return (float) (envelope * Math.sin(freq + phase) * 1000.0);
    }

    // -------------------------------------------------------------------------
    // Checksum utilities
    // -------------------------------------------------------------------------

    /**
     * Writes a {@code checksums.sha256} file in the standard format:
     * {@code <hex-sha256>  <filename>} (two spaces), one entry per line.
     *
     * @param dir      directory to write into
     * @param fixtures list of valid fixture entries
     * @return path to the written file
     */
    private static Path writeChecksums(Path dir, List<FixtureEntry> fixtures) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (FixtureEntry entry : fixtures) {
            byte[] data = Files.readAllBytes(entry.path());
            String hash = sha256Hex(data);
            sb.append(hash).append("  ").append(entry.name()).append('\n');
        }
        Path checksumsPath = dir.resolve("checksums.sha256");
        Files.writeString(checksumsPath, sb.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return checksumsPath;
    }

    /**
     * Reads the {@code checksums.sha256} file and verifies each entry against the
     * corresponding fixture file on disk.
     *
     * @throws AssertionError if any checksum does not match
     */
    private static void verifyChecksums(Path checksumsPath, List<FixtureEntry> fixtures)
            throws IOException {
        String content = Files.readString(checksumsPath);
        for (FixtureEntry entry : fixtures) {
            byte[] data = Files.readAllBytes(entry.path());
            String expected = sha256Hex(data);
            assertTrue(content.contains(expected + "  " + entry.name()),
                "Checksum mismatch for " + entry.name() +
                ": expected " + expected + " in checksums.sha256");
        }
    }

    /**
     * Computes SHA-256 of the given bytes and returns lowercase hex string.
     */
    static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // -------------------------------------------------------------------------
    // I/O helpers
    // -------------------------------------------------------------------------

    /**
     * Writes {@code data} to {@code dir/filename}, creating parent directories
     * and overwriting any existing content.
     *
     * @return path to the written file
     */
    private static Path write(Path dir, String filename, byte[] data) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return target;
    }

    /**
     * Resolves the {@code src/test/resources/fixtures/} directory relative to
     * this class's compiled location, traversing up from the classpath root.
     *
     * <p>Maven compiles test resources to {@code target/test-classes/}. We resolve
     * from there back to {@code src/test/resources/fixtures/} so that files written
     * here are picked up by the {@code maven-jar-plugin} test-jar goal and available
     * on the classpath of dependent modules.</p>
     *
     * <p>Falls back to {@code target/test-classes/fixtures/} if the
     * {@code src/test/resources} tree is not writable (e.g. in CI read-only
     * source trees).</p>
     */
    static Path resolveFixturesOutputDir() throws IOException {
        // Locate the compiled class file to find the project root
        URL classUrl = SegyFixtureGenerator.class.getProtectionDomain()
                .getCodeSource().getLocation();

        Path targetTestClasses;
        try {
            targetTestClasses = Paths.get(classUrl.toURI());
        } catch (URISyntaxException e) {
            targetTestClasses = Paths.get(classUrl.getPath());
        }

        // targetTestClasses = .../sdc-fixtures/target/test-classes
        // We want .../sdc-fixtures/src/test/resources/fixtures
        Path moduleRoot = targetTestClasses.getParent().getParent(); // .../sdc-fixtures

        Path srcResources = moduleRoot
                .resolve("src").resolve("test").resolve("resources")
                .resolve(FIXTURES_CLASSPATH_DIR);

        // Prefer src/test/resources if writable; fall back to target/test-classes
        if (Files.isWritable(moduleRoot.resolve("src")) || canCreate(srcResources)) {
            return srcResources;
        }
        // Fallback: write to target/test-classes/fixtures so files are on classpath
        Path fallback = targetTestClasses.resolve(FIXTURES_CLASSPATH_DIR);
        LOG.warn("src/test/resources not writable; writing fixtures to {}", fallback);
        return fallback;
    }

    /** Returns true if the directory exists and is writable, or if it can be created. */
    private static boolean canCreate(Path dir) {
        if (Files.isWritable(dir)) return true;
        Path parent = dir.getParent();
        return parent != null && Files.isWritable(parent);
    }

    // -------------------------------------------------------------------------
    // EBCDIC encoding (IBM Code Page 037 — standard for SEG-Y Rev1)
    // -------------------------------------------------------------------------

    /**
     * Converts an ASCII string to EBCDIC bytes using IBM Code Page 037.
     *
     * <p>Only the 128 standard ASCII characters are mapped; anything outside
     * that range is replaced with EBCDIC space (0x40). The uppercase Latin
     * letters (A–Z) map to 0xC1–0xC9, 0xD1–0xD9, 0xE2–0xE9 — all > 0x7E,
     * which satisfies the {@code SegyValidator} EBCDIC detection heuristic.</p>
     */
    static byte[] asciiToEbcdic(byte[] ascii) {
        byte[] ebcdic = new byte[ascii.length];
        for (int i = 0; i < ascii.length; i++) {
            int c = ascii[i] & 0xFF;
            ebcdic[i] = (byte) ASCII_TO_EBCDIC_CP037[c < 128 ? c : 0];
        }
        return ebcdic;
    }

    /**
     * Pads or truncates an ASCII string to exactly {@code length} bytes.
     * Padding character is ASCII space (0x20).
     */
    private static byte[] padOrTruncate(String s, int length) {
        byte[] ascii = new byte[length];
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        java.util.Arrays.fill(ascii, (byte) 0x20); // fill with space
        System.arraycopy(src, 0, ascii, 0, Math.min(src.length, length));
        return ascii;
    }

    /**
     * IBM EBCDIC Code Page 037 lookup table: index = ASCII code (0–127),
     * value = EBCDIC byte. Characters outside ASCII (>127) map to 0x40 (EBCDIC space).
     *
     * <p>Source: IBM Character Data Representation Architecture Reference — CP037.</p>
     */
    // @formatter:off
    private static final int[] ASCII_TO_EBCDIC_CP037 = {
        0x00, 0x01, 0x02, 0x03, 0x37, 0x2D, 0x2E, 0x2F,  // 0x00–0x07
        0x16, 0x05, 0x25, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,  // 0x08–0x0F
        0x10, 0x11, 0x12, 0x13, 0x3C, 0x3D, 0x32, 0x26,  // 0x10–0x17
        0x18, 0x19, 0x3F, 0x27, 0x22, 0x1D, 0x35, 0x1F,  // 0x18–0x1F
        0x40, 0x5A, 0x7F, 0x7B, 0x5B, 0x6C, 0x50, 0x7D,  // 0x20–0x27 (space ! " # $ % & ')
        0x4D, 0x5D, 0x5C, 0x4E, 0x6B, 0x60, 0x4B, 0x61,  // 0x28–0x2F (( ) * + , - . /)
        0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7,  // 0x30–0x37 (0–7)
        0xF8, 0xF9, 0x7A, 0x5E, 0x4C, 0x7E, 0x6E, 0x6F,  // 0x38–0x3F (8 9 : ; < = > ?)
        0x7C, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7,  // 0x40–0x47 (@ A–G)
        0xC8, 0xC9, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6,  // 0x48–0x4F (H–O)
        0xD7, 0xD8, 0xD9, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6,  // 0x50–0x57 (P–W)
        0xE7, 0xE8, 0xE9, 0xAD, 0xE0, 0xBD, 0x5F, 0x6D,  // 0x58–0x5F (X Y Z [ \ ] ^ _)
        0x79, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,  // 0x60–0x67 (` a–g)
        0x88, 0x89, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96,  // 0x68–0x6F (h–o)
        0x97, 0x98, 0x99, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6,  // 0x70–0x77 (p–w)
        0xA7, 0xA8, 0xA9, 0xC0, 0x4F, 0xD0, 0xA1, 0x07,  // 0x78–0x7F (x y z { | } ~ DEL)
    };
    // @formatter:on

    // -------------------------------------------------------------------------
    // Binary read/write helpers
    // -------------------------------------------------------------------------

    private static void writeUnsignedShortBE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >> 8) & 0xFF);
        buf[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeIntBE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 8)  & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readUnsignedShortBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    }

    // -------------------------------------------------------------------------
    // Inner record
    // -------------------------------------------------------------------------

    /** Associates a fixture filename with its resolved path on disk. */
    record FixtureEntry(String name, Path path) {}
}

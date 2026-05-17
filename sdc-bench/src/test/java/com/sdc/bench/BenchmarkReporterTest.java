package com.sdc.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BenchmarkReporter}.
 *
 * <p>Tests use either the bundled fixture at
 * {@code src/test/resources/fixtures/bench/sample-latest.json} or inline JSON
 * strings written to a temporary file, so no JMH execution is required.
 */
class BenchmarkReporterTest {

    // -----------------------------------------------------------------------
    // Fixture constants (must mirror the values in sample-latest.json)
    // -----------------------------------------------------------------------

    /**
     * encode_score_ops_s in the fixture: 1600.0 ops/s.
     * Expected throughput: 1600.0 × FIXTURE_MB_PER_OP.
     */
    private static final double FIXTURE_ENCODE_OPS_S = 1600.0;

    /** decode_score_ops_s in the fixture: 1000.0 ops/s. */
    private static final double FIXTURE_DECODE_OPS_S = 1000.0;

    private static final double EXPECTED_ENCODE_MB_S =
            FIXTURE_ENCODE_OPS_S * BenchmarkReporter.FIXTURE_MB_PER_OP;

    private static final double EXPECTED_DECODE_MB_S =
            FIXTURE_DECODE_OPS_S * BenchmarkReporter.FIXTURE_MB_PER_OP;

    private static final double EXPECTED_SPEEDUP =
            EXPECTED_ENCODE_MB_S / BenchmarkReporter.PRIOR_JAVA_BASELINE_MB_S;

    private static final double DELTA = 1e-3;

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Resolves the bundled test fixture from the classpath resources.
     * Fails fast if the file is not present (setup issue, not a test failure).
     *
     * <p>Uses {@link java.net.URL#toURI()} to correctly handle Windows paths
     * (avoids the leading slash in {@code /D:/...} that {@code url.getPath()}
     * produces on Windows, which is illegal in {@link Path#of(String, String...)}).
     */
    private Path fixtureFromClasspath() throws IOException {
        var url = getClass().getClassLoader()
                .getResource("fixtures/bench/sample-latest.json");
        assertNotNull(url, "sample-latest.json must be on the test classpath");
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Cannot resolve classpath fixture path: " + url, e);
        }
    }

    // -----------------------------------------------------------------------
    // Tests: construction and metric extraction
    // -----------------------------------------------------------------------

    @Test
    void constructor_readsFixtureWithoutException() throws IOException {
        Path fixture = fixtureFromClasspath();
        // Must not throw
        BenchmarkReporter reporter = new BenchmarkReporter(fixture);
        assertNotNull(reporter);
    }

    @Test
    void getEncodeThroughputMbS_returnsExpectedValue() throws IOException {
        BenchmarkReporter reporter = new BenchmarkReporter(fixtureFromClasspath());
        assertEquals(EXPECTED_ENCODE_MB_S, reporter.getEncodeThroughputMbS(), DELTA);
    }

    @Test
    void getDecodeThroughputMbS_returnsExpectedValue() throws IOException {
        BenchmarkReporter reporter = new BenchmarkReporter(fixtureFromClasspath());
        assertEquals(EXPECTED_DECODE_MB_S, reporter.getDecodeThroughputMbS(), DELTA);
    }

    @Test
    void getSpeedupVsPriorJavaBaseline_returnsExpectedValue() throws IOException {
        BenchmarkReporter reporter = new BenchmarkReporter(fixtureFromClasspath());
        assertEquals(EXPECTED_SPEEDUP, reporter.getSpeedupVsPriorJavaBaseline(), DELTA);
    }

    // -----------------------------------------------------------------------
    // Tests: text report
    // -----------------------------------------------------------------------

    @Test
    void textReport_containsRequiredSections() throws IOException {
        String report = new BenchmarkReporter(fixtureFromClasspath()).textReport();

        assertTrue(report.contains("AI-Compress-Seismic-v1"), "Must contain project name");
        assertTrue(report.contains("Encode pipeline"), "Must contain encode section");
        assertTrue(report.contains("Decode pipeline"), "Must contain decode section");
        assertTrue(report.contains("Speedup vs. prior Java baseline"), "Must contain speedup section");
        assertTrue(report.contains("Hardware (reference)"), "Must contain hardware section");
        assertTrue(report.contains("MB/s"), "Must contain throughput unit");
    }

    @Test
    void textReport_showsPassWhenEncodeMeetsTarget() throws IOException {
        // FIXTURE_ENCODE_OPS_S = 1600 ops/s → ~76.29 MB/s — just below 76.6 MB/s target
        // Let us construct a JSON where encode is well above target to confirm [PASS] path.
        // At 1700 ops/s: 1700 × (50000 / 1048576) ≈ 81.06 MB/s >= 76.6 MB/s → PASS
        String highThroughputJson = buildMinimalJson(1700.0, 1000.0);
        Path tmp = Files.createTempFile("reporter-test-", ".json");
        Files.writeString(tmp, highThroughputJson);

        String report = new BenchmarkReporter(tmp).textReport();
        assertTrue(report.contains("[PASS]"), "Expected [PASS] when throughput >= target");
        Files.deleteIfExists(tmp);
    }

    @Test
    void textReport_showsBelowTargetWhenEncodeIsSlow() throws IOException {
        // At 100 ops/s → ~4.77 MB/s < 76.6 MB/s
        String lowJson = buildMinimalJson(100.0, 100.0);
        Path tmp = Files.createTempFile("reporter-low-", ".json");
        Files.writeString(tmp, lowJson);

        String report = new BenchmarkReporter(tmp).textReport();
        assertTrue(report.contains("[BELOW TARGET]"),
                "Expected [BELOW TARGET] when throughput < target");
        Files.deleteIfExists(tmp);
    }

    // -----------------------------------------------------------------------
    // Tests: JSON report
    // -----------------------------------------------------------------------

    @Test
    void jsonReport_containsRequiredTopLevelFields() throws IOException {
        String json = new BenchmarkReporter(fixtureFromClasspath()).jsonReport();

        assertTrue(json.contains("\"throughput_mb_s\""), "Must contain throughput_mb_s");
        assertTrue(json.contains("\"dataset_size_gb\""), "Must contain dataset_size_gb");
        assertTrue(json.contains("\"compression_ratio\""), "Must contain compression_ratio");
        assertTrue(json.contains("\"speedup_vs_prior_java_baseline\""),
                "Must contain speedup_vs_prior_java_baseline");
        assertTrue(json.contains("\"timestamp\""), "Must contain timestamp");
        assertTrue(json.contains("\"version\""), "Must contain version");
        assertTrue(json.contains("\"reference_hardware\""), "Must contain reference_hardware");
    }

    @Test
    void jsonReport_compressionRatioIsNull() throws IOException {
        String json = new BenchmarkReporter(fixtureFromClasspath()).jsonReport();
        // Jackson serialises null as the literal "null" value (not a quoted string)
        assertTrue(json.contains("\"compression_ratio\" : null"),
                "compression_ratio must be null until benchmarked on reference hardware");
    }

    @Test
    void jsonReport_isValidJsonArray_wrappedInObject() throws IOException {
        String json = new BenchmarkReporter(fixtureFromClasspath()).jsonReport();
        // Must start with '{' (object) not '[' (array)
        String trimmed = json.trim();
        assertTrue(trimmed.startsWith("{"), "JSON report must be an object");
        assertTrue(trimmed.endsWith("}"), "JSON report must end with }");
    }

    // -----------------------------------------------------------------------
    // Tests: error handling
    // -----------------------------------------------------------------------

    @Test
    void constructor_throwsIllegalStateException_whenFileIsNotArray(@TempDir Path tmpDir)
            throws IOException {
        Path badJson = tmpDir.resolve("bad.json");
        Files.writeString(badJson, "{ \"not\": \"an array\" }");

        assertThrows(IllegalStateException.class,
                () -> new BenchmarkReporter(badJson),
                "Must throw when root is not a JSON array");
    }

    @Test
    void constructor_throwsIllegalStateException_whenEncodeBenchmarkMissing(
            @TempDir Path tmpDir) throws IOException {
        // Only the decode entry, no encode
        String json = "[{\"benchmark\": \"com.sdc.bench.SdcDecodeBenchmark.decodeFullPipeline\","
                + "\"primaryMetric\":{\"score\":1000.0,\"scoreUnit\":\"ops/s\"}}]";
        Path file = tmpDir.resolve("missing-encode.json");
        Files.writeString(file, json);

        assertThrows(IllegalStateException.class,
                () -> new BenchmarkReporter(file),
                "Must throw when encodeFullPipeline entry is missing");
    }

    @Test
    void constructor_throwsIllegalStateException_whenDecodeBenchmarkMissing(
            @TempDir Path tmpDir) throws IOException {
        // Only the encode entry, no decode
        String json = "[{\"benchmark\": \"com.sdc.bench.SdcEncodeBenchmark.encodeFullPipeline\","
                + "\"primaryMetric\":{\"score\":1600.0,\"scoreUnit\":\"ops/s\"}}]";
        Path file = tmpDir.resolve("missing-decode.json");
        Files.writeString(file, json);

        assertThrows(IllegalStateException.class,
                () -> new BenchmarkReporter(file),
                "Must throw when decodeFullPipeline entry is missing");
    }

    @Test
    void constructor_throwsIOException_whenFileDoesNotExist(@TempDir Path tmpDir)
            throws IOException {
        Path missing = tmpDir.resolve("does-not-exist.json");
        assertFalse(Files.exists(missing));

        assertThrows(IOException.class,
                () -> new BenchmarkReporter(missing),
                "Must throw IOException for a missing file");
    }

    // -----------------------------------------------------------------------
    // Constant contract tests
    // -----------------------------------------------------------------------

    @Test
    void fixtureMbPerOp_matchesBenchmarkResultStoreContract() {
        // Sanity-check: 100 traces × 125 samples × 4 bytes = 50,000 B
        double expected = 50_000.0 / (1024.0 * 1024.0);
        assertEquals(expected, BenchmarkReporter.FIXTURE_MB_PER_OP, 1e-10,
                "FIXTURE_MB_PER_OP must match the value in BenchmarkResultStore");
    }

    // -----------------------------------------------------------------------
    // Inline JSON builder
    // -----------------------------------------------------------------------

    private static String buildMinimalJson(double encodeOpsS, double decodeOpsS) {
        return String.format(
                "[{\"benchmark\":\"com.sdc.bench.SdcDecodeBenchmark.decodeFullPipeline\","
                + "\"primaryMetric\":{\"score\":%f,\"scoreUnit\":\"ops/s\"}},"
                + "{\"benchmark\":\"com.sdc.bench.SdcEncodeBenchmark.encodeFullPipeline\","
                + "\"primaryMetric\":{\"score\":%f,\"scoreUnit\":\"ops/s\"}}]",
                decodeOpsS, encodeOpsS);
    }
}

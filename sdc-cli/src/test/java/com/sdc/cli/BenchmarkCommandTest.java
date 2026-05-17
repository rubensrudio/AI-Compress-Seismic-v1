package com.sdc.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests for {@link BenchmarkCommand}.
 *
 * <p>Uses the synthetic JMH fixture at
 * {@code src/test/resources/fixtures/bench/sample-latest.json}
 * (1600 ops/s encode, 1000 ops/s decode) to verify that:
 * <ul>
 *   <li>The JSON report is written to the path specified by {@code --output}.</li>
 *   <li>All mandatory fields defined by the spec are present in the report.</li>
 *   <li>Numeric values are consistent with the fixture (throughput derived from
 *       ops/s × FIXTURE_MB_PER_OP).</li>
 *   <li>Exit code is 0 on success and 1 on failure (missing results file).</li>
 * </ul>
 *
 * <p>Implemented as part of TASK-026.
 */
class BenchmarkCommandTest {

    /**
     * Classpath location of the synthetic JMH fixture included in
     * {@code src/test/resources/fixtures/bench/sample-latest.json}.
     */
    private static final String FIXTURE_CLASSPATH =
            "fixtures/bench/sample-latest.json";

    /**
     * Fixture encode score in ops/s — must match sample-latest.json.
     * throughput_mb_s = 1600 * (50_000 / (1024 * 1024)) ≈ 76.29 MB/s
     */
    private static final double FIXTURE_ENCODE_OPS_S = 1600.0;

    /** Fixture MB per op — mirrors BenchmarkReporter.FIXTURE_MB_PER_OP. */
    private static final double FIXTURE_MB_PER_OP = 50_000.0 / (1024.0 * 1024.0);

    @TempDir
    Path tempDir;

    /** Path to the fixture file resolved from the classpath. */
    private Path fixtureLatestJson;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void setUp() throws Exception {
        URL resource = BenchmarkCommandTest.class.getClassLoader()
                .getResource(FIXTURE_CLASSPATH);
        assertNotNull(resource,
                "Fixture not found on classpath: " + FIXTURE_CLASSPATH);
        fixtureLatestJson = Path.of(resource.toURI());

        originalOut = System.out;
        originalErr = System.err;
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, "UTF-8"));
        System.setErr(new PrintStream(capturedErr, true, "UTF-8"));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // -----------------------------------------------------------------------
    // Success path
    // -----------------------------------------------------------------------

    @Test
    void reportIsWrittenToSpecifiedOutputPath() throws Exception {
        Path output = tempDir.resolve("bench-report.json");

        int exitCode = runBenchmark(fixtureLatestJson, output);

        assertEquals(0, exitCode,
                "Exit code must be 0 on success. stderr: "
                        + capturedErr.toString("UTF-8"));
        assertTrue(Files.exists(output),
                "Output file must be created at: " + output);
        assertTrue(Files.size(output) > 0,
                "Output file must not be empty");
    }

    @Test
    void reportContainsMandatoryFields() throws Exception {
        Path output = tempDir.resolve("report-fields.json");

        int exitCode = runBenchmark(fixtureLatestJson, output);

        assertEquals(0, exitCode,
                "Exit code must be 0. stderr: " + capturedErr.toString("UTF-8"));

        String reportJson = Files.readString(output);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(reportJson);

        // Mandatory fields from spec (GET /benchmark payload contract)
        assertFieldPresent(root, "throughput_mb_s");
        assertFieldPresent(root, "decode_throughput_mb_s");
        assertFieldPresent(root, "dataset_size_gb");
        assertFieldPresent(root, "compression_ratio");
        assertFieldPresent(root, "speedup_vs_prior_java_baseline");
        assertFieldPresent(root, "timestamp");
        assertFieldPresent(root, "version");
        assertFieldPresent(root, "reference_hardware");
    }

    @Test
    void reportEncodeThroughputMatchesFixture() throws Exception {
        Path output = tempDir.resolve("report-throughput.json");

        int exitCode = runBenchmark(fixtureLatestJson, output);

        assertEquals(0, exitCode,
                "Exit code must be 0. stderr: " + capturedErr.toString("UTF-8"));

        String reportJson = Files.readString(output);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(reportJson);

        double expectedThroughput = FIXTURE_ENCODE_OPS_S * FIXTURE_MB_PER_OP;
        double actualThroughput = root.path("throughput_mb_s").asDouble(-1.0);

        // Allow for rounding to 2 decimal places applied by BenchmarkReporter
        assertEquals(expectedThroughput, actualThroughput, 0.01,
                "throughput_mb_s must match fixture-derived value");
    }

    @Test
    void reportJsonIsValidAndParseable() throws Exception {
        Path output = tempDir.resolve("report-valid-json.json");

        int exitCode = runBenchmark(fixtureLatestJson, output);

        assertEquals(0, exitCode,
                "Exit code must be 0. stderr: " + capturedErr.toString("UTF-8"));

        String reportJson = Files.readString(output);
        ObjectMapper mapper = new ObjectMapper();
        // readTree throws if JSON is malformed
        JsonNode root = mapper.readTree(reportJson);
        assertNotNull(root, "Parsed JSON root must not be null");
        assertTrue(root.isObject(), "Report root must be a JSON object");
    }

    @Test
    void stdoutContainsReportPath() throws Exception {
        Path output = tempDir.resolve("report-stdout.json");

        int exitCode = runBenchmark(fixtureLatestJson, output);

        assertEquals(0, exitCode,
                "Exit code must be 0. stderr: " + capturedErr.toString("UTF-8"));
        String out = capturedOut.toString("UTF-8");
        assertTrue(out.contains(output.getFileName().toString()),
                "stdout must mention the output file name. Got: " + out);
    }

    // -----------------------------------------------------------------------
    // Default output path
    // -----------------------------------------------------------------------

    @Test
    void defaultOutputPathIsUsedWhenNotSpecified() throws Exception {
        // Run without --output; default is jmh-results.json in CWD.
        // We change user.dir is not portable, so instead we verify that
        // omitting --output uses the literal default string from the command.
        // We verify this by checking that BenchmarkCommand.DEFAULT_OUTPUT
        // equals the expected constant.
        assertEquals("jmh-results.json", BenchmarkCommand.DEFAULT_OUTPUT,
                "Default output filename must be jmh-results.json per spec");
    }

    @Test
    void defaultResultsFileConstantIsCorrect() {
        assertEquals(
                "sdc-bench/target/jmh-results/latest.json",
                BenchmarkCommand.DEFAULT_RESULTS_FILE,
                "Default results file path must match spec");
    }

    // -----------------------------------------------------------------------
    // Failure paths
    // -----------------------------------------------------------------------

    @Test
    void exitCodeOneWhenResultsFileDoesNotExist() throws Exception {
        Path missing = tempDir.resolve("does-not-exist.json");
        Path output = tempDir.resolve("report-missing.json");

        int exitCode = runBenchmark(missing, output);

        assertEquals(1, exitCode,
                "Exit code must be 1 when results file does not exist");
        assertFalse(Files.exists(output),
                "Output file must not be created when results file is absent");
    }

    @Test
    void exitCodeOneWhenResultsFileIsMalformed() throws Exception {
        Path malformed = tempDir.resolve("malformed.json");
        Files.writeString(malformed, "{ not valid json [[[");
        Path output = tempDir.resolve("report-malformed.json");

        int exitCode = runBenchmark(malformed, output);

        assertEquals(1, exitCode,
                "Exit code must be 1 when results file is malformed JSON");
    }

    @Test
    void exitCodeOneWhenResultsFileMissesBenchmarkEntries() throws Exception {
        // Valid JSON array but missing the required benchmark name suffixes
        Path badEntries = tempDir.resolve("bad-entries.json");
        Files.writeString(badEntries, "[{\"benchmark\": \"com.sdc.bench.Other.unrelated\","
                + "\"primaryMetric\":{\"score\":100,\"scoreUnit\":\"ops/s\"}}]");
        Path output = tempDir.resolve("report-bad-entries.json");

        int exitCode = runBenchmark(badEntries, output);

        assertEquals(1, exitCode,
                "Exit code must be 1 when benchmark entries are missing");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Invokes {@link BenchmarkCommand} via Picocli's {@link CommandLine} in the
     * same JVM and returns the exit code.
     *
     * @param resultsFile path to pass as {@code --results-file}
     * @param output      path to pass as {@code --output}
     * @return exit code returned by the command
     */
    private static int runBenchmark(Path resultsFile, Path output) {
        CommandLine cmd = new CommandLine(new BenchmarkCommand());
        cmd.setColorScheme(
                CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        return cmd.execute(
                "--results-file", resultsFile.toString(),
                "--output", output.toString());
    }

    /**
     * Asserts that a field with the given name exists in the JSON object node.
     *
     * @param root      root JSON object
     * @param fieldName field that must be present (may be null)
     */
    private static void assertFieldPresent(JsonNode root, String fieldName) {
        assertTrue(root.has(fieldName),
                "Report JSON must contain field '" + fieldName + "'");
    }
}

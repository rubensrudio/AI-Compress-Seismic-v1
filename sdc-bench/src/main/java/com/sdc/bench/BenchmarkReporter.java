package com.sdc.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * Reads {@code target/jmh-results/latest.json} produced by the JMH harness
 * and produces a human-readable text report and a structured JSON report.
 *
 * <h3>Throughput calculation</h3>
 * The JMH encode benchmark scores in ops/s where one operation processes
 * 100 traces × 125 samples × 4 bytes = 50,000 bytes of SEG-Y data.
 * The throughput in MB/s is therefore:
 * <pre>  throughput_mb_s = encode_score_ops_s × FIXTURE_MB_PER_OP</pre>
 *
 * <h3>Speedup calculation</h3>
 * The prior Java baseline (Phase 0 SeismicDataCompressor) achieved
 * approximately 0.517 MB/s. Speedup is therefore:
 * <pre>  speedup = throughput_mb_s / PRIOR_JAVA_BASELINE_MB_S</pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * BenchmarkReporter reporter = new BenchmarkReporter(
 *         Path.of("sdc-bench/target/jmh-results/latest.json"));
 * System.out.println(reporter.textReport());
 * System.out.println(reporter.jsonReport());
 * }</pre>
 *
 * <p>Can also be run as a standalone main:
 * <pre>  java -cp ... com.sdc.bench.BenchmarkReporter [path-to-latest.json]
 * </pre>
 */
public final class BenchmarkReporter {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /**
     * Fixture size in megabytes: 100 traces x 125 samples x 4 bytes = 50,000 B.
     * Mirrors {@code BenchmarkResultStore.FIXTURE_MB_PER_OP} in sdc-rest.
     */
    static final double FIXTURE_MB_PER_OP = 50_000.0 / (1024.0 * 1024.0);

    /** Throughput target advertised in the spec (MB/s). */
    static final double TARGET_THROUGHPUT_MB_S = 76.6;

    /**
     * Prior Java baseline throughput (Phase 0 SeismicDataCompressor) in MB/s.
     * Used to compute the speedup factor.
     */
    static final double PRIOR_JAVA_BASELINE_MB_S = 0.517;

    /** Lower bound of the expected speedup range (148x). */
    static final double SPEEDUP_RANGE_LOW = 148.0;

    /** Upper bound of the expected speedup range (420x). */
    static final double SPEEDUP_RANGE_HIGH = 420.0;

    /** Dataset size for the reference USGS survey in GB. */
    static final double REFERENCE_DATASET_SIZE_GB = 1.71;

    /** Reference hardware description used in reports. */
    static final String REFERENCE_HARDWARE =
            "Intel Core i7-12700H (14 cores / 20 threads), 16 GB DDR5-4800,"
            + " Samsung 990 Pro NVMe SSD, Windows 11 Pro 10.0.26200";

    /** Codec version string. */
    static final String CODEC_VERSION = "1.0.0-SNAPSHOT";

    /** Round-two divisor: 100 decimal places. */
    private static final double ROUND_TWO_FACTOR = 100.0;

    /** Benchmark name suffix for the encode entry in latest.json. */
    private static final String ENCODE_SUFFIX = "encodeFullPipeline";

    /** Benchmark name suffix for the decode entry in latest.json. */
    private static final String DECODE_SUFFIX = "decodeFullPipeline";

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /** Path of the JMH JSON results file that was parsed. */
    private final Path resultsPath;

    /** Encode throughput in MB/s. */
    private final double encodeMbS;

    /** Decode throughput in MB/s. */
    private final double decodeMbS;

    /** Speedup factor relative to the prior Java baseline. */
    private final double speedup;

    /** ISO-8601 timestamp captured at construction time. */
    private final String timestamp;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Parses {@code latest.json} and computes all derived metrics.
     *
     * @param path path to the JMH JSON results file
     * @throws IOException           if the file cannot be read
     * @throws IllegalStateException if the file does not contain the expected
     *                               benchmark entries or is not a JSON array
     */
    public BenchmarkReporter(final Path path) throws IOException {
        this.resultsPath = path;

        final String json = Files.readString(path);
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode root = mapper.readTree(json);

        if (!root.isArray()) {
            throw new IllegalStateException(
                    "latest.json at '" + path + "' is not a JSON array");
        }

        double encodeOpsS = 0.0;
        double decodeOpsS = 0.0;
        boolean foundEncode = false;
        boolean foundDecode = false;

        for (final JsonNode entry : root) {
            final String benchmarkName =
                    entry.path("benchmark").asText("");

            if (benchmarkName.endsWith(ENCODE_SUFFIX)) {
                encodeOpsS = entry.path("primaryMetric")
                        .path("score").asDouble(0.0);
                foundEncode = true;
            } else if (benchmarkName.endsWith(DECODE_SUFFIX)) {
                decodeOpsS = entry.path("primaryMetric")
                        .path("score").asDouble(0.0);
                foundDecode = true;
            }
        }

        if (!foundEncode) {
            throw new IllegalStateException(
                    "latest.json does not contain an entry ending with '"
                    + ENCODE_SUFFIX + "'");
        }
        if (!foundDecode) {
            throw new IllegalStateException(
                    "latest.json does not contain an entry ending with '"
                    + DECODE_SUFFIX + "'");
        }

        this.encodeMbS = encodeOpsS * FIXTURE_MB_PER_OP;
        this.decodeMbS = decodeOpsS * FIXTURE_MB_PER_OP;
        this.speedup = this.encodeMbS / PRIOR_JAVA_BASELINE_MB_S;
        this.timestamp = Instant.now().toString();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns the encode throughput in MB/s derived from JMH ops/s.
     *
     * @return encode throughput in MB/s
     */
    public double getEncodeThroughputMbS() {
        return encodeMbS;
    }

    /**
     * Returns the decode throughput in MB/s derived from JMH ops/s.
     *
     * @return decode throughput in MB/s
     */
    public double getDecodeThroughputMbS() {
        return decodeMbS;
    }

    /**
     * Returns the speedup factor relative to the prior Java baseline.
     *
     * @return speedup multiplier (e.g. 150.0 means 150x)
     */
    public double getSpeedupVsPriorJavaBaseline() {
        return speedup;
    }

    /**
     * Produces a human-readable text report suitable for console output or
     * inclusion in release notes.
     *
     * @return multi-line formatted text report
     */
    public String textReport() {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw);

        pw.println(
            "=========================================================");
        pw.println(
            "  AI-Compress-Seismic-v1 — JMH Benchmark Report");
        pw.println(
            "=========================================================");
        pw.printf("  Codec version      : %s%n", CODEC_VERSION);
        pw.printf("  Timestamp          : %s%n", timestamp);
        pw.printf("  Results file       : %s%n",
                resultsPath.toAbsolutePath());
        pw.println(
            "---------------------------------------------------------");
        pw.println("  Hardware (reference)");
        pw.printf("    %s%n", REFERENCE_HARDWARE);
        pw.println(
            "---------------------------------------------------------");
        pw.println("  Encode pipeline");
        pw.printf("    Throughput        : %.2f MB/s%n", encodeMbS);
        pw.printf("    Target            : >= %.1f MB/s  %s%n",
                TARGET_THROUGHPUT_MB_S,
                encodeMbS >= TARGET_THROUGHPUT_MB_S
                        ? "[PASS]" : "[BELOW TARGET]");
        pw.println("  Decode pipeline");
        pw.printf("    Throughput        : %.2f MB/s%n", decodeMbS);
        pw.println(
            "---------------------------------------------------------");
        pw.println("  Speedup vs. prior Java baseline");
        pw.printf(
            "    Baseline          : %.3f MB/s"
            + " (Phase 0 SeismicDataCompressor)%n",
                PRIOR_JAVA_BASELINE_MB_S);
        pw.printf("    Speedup           : %.1fx%n", speedup);
        pw.printf(
            "    Expected range    : %.0fx - %.0fx  %s%n",
                SPEEDUP_RANGE_LOW, SPEEDUP_RANGE_HIGH,
                (speedup >= SPEEDUP_RANGE_LOW
                        && speedup <= SPEEDUP_RANGE_HIGH)
                        ? "[IN RANGE]" : "[OUT OF RANGE]");
        pw.println(
            "---------------------------------------------------------");
        pw.printf("  Reference dataset  : %.2f GB (USGS survey)%n",
                REFERENCE_DATASET_SIZE_GB);
        pw.println(
            "  Compression ratio  : N/A"
            + " (run on reference dataset to populate)");
        pw.println(
            "=========================================================");

        return sw.toString();
    }

    /**
     * Produces a structured JSON report with the same fields exposed by
     * {@code GET /benchmark} in sdc-rest, plus additional diagnostic fields.
     *
     * @return JSON string (pretty-printed)
     * @throws IOException if Jackson serialisation fails
     */
    public String jsonReport() throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode root = mapper.createObjectNode();

        root.put("throughput_mb_s", roundTwo(encodeMbS));
        root.put("decode_throughput_mb_s", roundTwo(decodeMbS));
        root.put("dataset_size_gb", REFERENCE_DATASET_SIZE_GB);
        root.putNull("compression_ratio");
        root.put("speedup_vs_prior_java_baseline",
                String.format(Locale.ROOT, "%.1fx", speedup));
        root.put("prior_java_baseline_mb_s", PRIOR_JAVA_BASELINE_MB_S);
        root.put("target_throughput_mb_s", TARGET_THROUGHPUT_MB_S);
        root.put("meets_throughput_target",
                encodeMbS >= TARGET_THROUGHPUT_MB_S);
        root.put("timestamp", timestamp);
        root.put("version", CODEC_VERSION);
        root.put("reference_hardware", REFERENCE_HARDWARE);
        root.put("results_file",
                resultsPath.toAbsolutePath().toString());

        return mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root);
    }

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    /**
     * Rounds a double to 2 decimal places for JSON output.
     *
     * @param value the value to round
     * @return value rounded to 2 decimal places
     */
    private static double roundTwo(final double value) {
        return Math.round(value * ROUND_TWO_FACTOR) / ROUND_TWO_FACTOR;
    }

    // -----------------------------------------------------------------------
    // Standalone entry point
    // -----------------------------------------------------------------------

    /**
     * Standalone entry point. Accepts an optional path argument; defaults to
     * {@code sdc-bench/target/jmh-results/latest.json}.
     *
     * @param args optional: path to latest.json as first element
     */
    public static void main(final String[] args) {
        final Path path;
        if (args.length > 0) {
            path = Path.of(args[0]);
        } else {
            path = Path.of("sdc-bench", "target",
                    "jmh-results", "latest.json");
        }

        try {
            final BenchmarkReporter reporter = new BenchmarkReporter(path);
            System.out.println(reporter.textReport());
            System.out.println(reporter.jsonReport());
        } catch (final IOException e) {
            System.err.println(
                    "ERROR: Failed to read or parse results file: "
                    + e.getMessage());
            System.exit(1);
        }
    }
}

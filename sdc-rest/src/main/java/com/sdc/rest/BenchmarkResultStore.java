package com.sdc.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring bean that reads the last JMH benchmark results from
 * {@code sdc.bench.results.path} (configured in {@code application.yml}) at
 * startup and exposes a {@link BenchmarkResult} snapshot.
 *
 * <h3>File format</h3>
 * The file is a JMH JSON results array, e.g.:
 * <pre>{@code
 * [
 *   {
 *     "benchmark": "com.sdc.bench.SdcEncodeBenchmark.encodeFullPipeline",
 *     "mode": "thrpt",
 *     "primaryMetric": { "score": 464.087, "scoreUnit": "ops/s" }
 *   },
 *   {
 *     "benchmark": "com.sdc.bench.SdcDecodeBenchmark.decodeFullPipeline",
 *     "mode": "thrpt",
 *     "primaryMetric": { "score": 1005.765, "scoreUnit": "ops/s" }
 *   }
 * ]
 * }</pre>
 *
 * <h3>Throughput calculation</h3>
 * The encode benchmark score is in ops/s where one operation processes
 * the synthetic fixture (100 traces × 125 samples × 4 bytes = 50,000 bytes
 * ≈ 0.047684 MB). The {@code throughput_mb_s} field is therefore:
 * <pre>  throughput_mb_s = encode_score_ops_s × 0.047684</pre>
 *
 * <p>This choice is documented here rather than hidden behind a magic
 * constant so that reviewers can validate it against the fixture definition
 * in {@code sdc-bench/}.
 *
 * <h3>Graceful degradation</h3>
 * When the file does not exist or cannot be parsed, all numerical fields of
 * the returned {@link BenchmarkResult} are {@code null}. The application
 * starts normally in this state; the endpoint returns HTTP 200 with
 * {@code null} values.
 */
@Component
public class BenchmarkResultStore {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkResultStore.class);

    /**
     * Fixture size in megabytes: 100 traces × 125 samples × 4 bytes = 50,000 B.
     * Used to convert JMH ops/s into MB/s for the encode benchmark.
     */
    static final double FIXTURE_MB_PER_OP = 50_000.0 / (1024.0 * 1024.0);

    /** Benchmark name suffix identifying the encode benchmark entry in latest.json. */
    static final String ENCODE_BENCHMARK_SUFFIX = "encodeFullPipeline";

    private final BenchmarkResult result;

    /**
     * Constructor invoked by Spring at startup.
     *
     * @param resultsPath the path to {@code latest.json}, injected from
     *                    {@code sdc.bench.results.path} in {@code application.yml}.
     *                    May be relative (resolved against the JVM working
     *                    directory, which is the monorepo root when launched via
     *                    {@code mvn spring-boot:run -pl sdc-rest}).
     */
    public BenchmarkResultStore(
            @Value("${sdc.bench.results.path}") String resultsPath) {
        this.result = loadResult(Paths.get(resultsPath));
    }

    /**
     * Returns the benchmark result snapshot loaded at startup.
     *
     * <p>All fields are non-null only when a valid {@code latest.json} was
     * present. Otherwise, the numerical fields are {@code null} and the
     * string fields are {@code null} (graceful degradation).
     *
     * @return a non-null {@link BenchmarkResult}; individual fields may be null
     */
    public BenchmarkResult getResult() {
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static BenchmarkResult loadResult(Path path) {
        if (!Files.exists(path)) {
            log.warn("Benchmark results file not found at '{}' — returning null values for GET /benchmark", path);
            return BenchmarkResult.empty();
        }

        try {
            String json = Files.readString(path);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            if (!root.isArray()) {
                log.warn("Benchmark results file '{}' is not a JSON array — returning null values", path);
                return BenchmarkResult.empty();
            }

            Double encodeScoreOpsPerSec = null;

            for (JsonNode entry : root) {
                String benchmark = entry.path("benchmark").asText("");
                if (benchmark.endsWith(ENCODE_BENCHMARK_SUFFIX)) {
                    JsonNode primaryMetric = entry.path("primaryMetric");
                    if (!primaryMetric.isMissingNode() && primaryMetric.has("score")) {
                        encodeScoreOpsPerSec = primaryMetric.get("score").asDouble();
                    }
                }
            }

            Double throughputMbS = encodeScoreOpsPerSec != null
                    ? encodeScoreOpsPerSec * FIXTURE_MB_PER_OP
                    : null;

            log.info("Benchmark results loaded from '{}' — encode ops/s={}, throughput_mb_s={}",
                    path, encodeScoreOpsPerSec, throughputMbS);

            return new BenchmarkResult(
                    throughputMbS,
                    null,   // compression_ratio — populated after first benchmark on reference hardware
                    null,   // dataset_size_gb — not present in latest.json
                    null,   // speedup_vs_prior_java_baseline — not present in latest.json
                    null,   // timestamp — not present in latest.json
                    null,   // version — not present in latest.json
                    null    // reference_hardware — not present in latest.json
            );

        } catch (IOException e) {
            log.error("Failed to read benchmark results from '{}' — returning null values", path, e);
            return BenchmarkResult.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Nested result record
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of benchmark results served by {@code GET /benchmark}.
     *
     * <p>All fields are nullable. When a field is {@code null}, it means the
     * value is not yet available (either the benchmark has not been run on the
     * reference hardware or the field is not present in the JMH output).
     *
     * <p>Field naming uses snake_case to match the JSON contract defined in
     * {@code plan.md §4.3} without requiring Jackson naming-strategy
     * configuration.
     */
    public static final class BenchmarkResult {

        /** Encode throughput in MB/s, derived from JMH ops/s × fixture size. */
        private final Double throughputMbS;

        /**
         * Compression ratio (original_size / compressed_size).
         * {@code null} until first benchmark on reference hardware.
         */
        private final Double compressionRatio;

        /**
         * Size of the reference dataset in GB. Not present in latest.json;
         * must be configured externally or set after benchmark execution.
         */
        private final Double datasetSizeGb;

        /**
         * Speedup factor vs. prior Java baseline.
         * Not present in latest.json; populated from a separate config or report.
         */
        private final String speedupVsPriorJavaBaseline;

        /** ISO-8601 timestamp of the benchmark run. Not present in latest.json. */
        private final String timestamp;

        /** Codec version string at the time of the benchmark run. */
        private final String version;

        /** Free-form string describing the reference hardware (CPU, RAM, storage). */
        private final String referenceHardware;

        /**
         * Canonical constructor.
         *
         * @param throughputMbS              encode throughput in MB/s, or {@code null}
         * @param compressionRatio           size ratio, or {@code null}
         * @param datasetSizeGb              dataset size in GB, or {@code null}
         * @param speedupVsPriorJavaBaseline speedup string, or {@code null}
         * @param timestamp                  ISO-8601 timestamp, or {@code null}
         * @param version                    codec version, or {@code null}
         * @param referenceHardware          hardware description, or {@code null}
         */
        public BenchmarkResult(
                Double throughputMbS,
                Double compressionRatio,
                Double datasetSizeGb,
                String speedupVsPriorJavaBaseline,
                String timestamp,
                String version,
                String referenceHardware) {
            this.throughputMbS               = throughputMbS;
            this.compressionRatio            = compressionRatio;
            this.datasetSizeGb               = datasetSizeGb;
            this.speedupVsPriorJavaBaseline  = speedupVsPriorJavaBaseline;
            this.timestamp                   = timestamp;
            this.version                     = version;
            this.referenceHardware           = referenceHardware;
        }

        /**
         * Returns an empty result with all fields set to {@code null}.
         *
         * @return a fully-null {@link BenchmarkResult}
         */
        static BenchmarkResult empty() {
            return new BenchmarkResult(null, null, null, null, null, null, null);
        }

        /** @return encode throughput in MB/s, or {@code null} */
        public Double getThroughputMbS() {
            return throughputMbS;
        }

        /** @return compression ratio, or {@code null} */
        public Double getCompressionRatio() {
            return compressionRatio;
        }

        /** @return dataset size in GB, or {@code null} */
        public Double getDatasetSizeGb() {
            return datasetSizeGb;
        }

        /** @return speedup vs. prior Java baseline string, or {@code null} */
        public String getSpeedupVsPriorJavaBaseline() {
            return speedupVsPriorJavaBaseline;
        }

        /** @return ISO-8601 timestamp, or {@code null} */
        public String getTimestamp() {
            return timestamp;
        }

        /** @return codec version string, or {@code null} */
        public String getVersion() {
            return version;
        }

        /** @return reference hardware description, or {@code null} */
        public String getReferenceHardware() {
            return referenceHardware;
        }
    }
}

package com.sdc.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST endpoint exposing the last JMH benchmark results at {@code GET /benchmark}.
 *
 * <h3>Response contract</h3>
 * <pre>{@code
 * HTTP 200
 * Content-Type: application/json
 *
 * {
 *   "throughput_mb_s": 22.13,
 *   "compression_ratio": null,
 *   "dataset_size_gb": null,
 *   "speedup_vs_prior_java_baseline": null,
 *   "timestamp": null,
 *   "version": null,
 *   "reference_hardware": null
 * }
 * }</pre>
 *
 * <p>The endpoint always returns HTTP 200. When the benchmark results file
 * ({@code sdc-bench/target/jmh-results/latest.json}) has not been generated
 * yet, all numerical fields are {@code null} — this is the expected state
 * before the first JMH run (graceful degradation).
 *
 * <p>The {@code throughput_mb_s} value is derived from the encode benchmark
 * score (ops/s) multiplied by the fixture size (≈ 0.0477 MB/op). See
 * {@link BenchmarkResultStore} for the full calculation.
 *
 * <p>This endpoint is intentionally unauthenticated — it serves read-only
 * metadata and is designed for consumption by CI/CD pipelines, dashboards,
 * and the public demo at {@code halotechlabs.com}.
 */
@RestController
@RequestMapping("/benchmark")
@Tag(name = "Benchmark", description = "Returns metadata from the last JMH benchmark run — GET /benchmark")
public class BenchmarkController {

    private final BenchmarkResultStore store;

    /**
     * Creates the controller with the given {@link BenchmarkResultStore} bean.
     *
     * @param store application-scoped bean holding the benchmark result loaded
     *              at startup; injected by Spring or supplied directly in tests
     *              via {@code @WebFluxTest} mock configuration
     */
    public BenchmarkController(BenchmarkResultStore store) {
        this.store = store;
    }

    /**
     * Returns the benchmark result payload.
     *
     * <p>All fields may be {@code null} when the JMH results file is absent.
     *
     * @return HTTP 200 with a JSON object containing all benchmark fields
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Last JMH benchmark results",
        description = "Returns the benchmark metrics from the most recent JMH run. " +
                      "All numerical fields are null when latest.json has not been generated yet."
    )
    @ApiResponse(responseCode = "200", description = "Benchmark result payload (fields may be null)")
    public Mono<ResponseEntity<BenchmarkPayload>> benchmark() {
        BenchmarkResultStore.BenchmarkResult r = store.getResult();
        BenchmarkPayload payload = new BenchmarkPayload(
                r.getThroughputMbS(),
                r.getCompressionRatio(),
                r.getDatasetSizeGb(),
                r.getSpeedupVsPriorJavaBaseline(),
                r.getTimestamp(),
                r.getVersion(),
                r.getReferenceHardware()
        );
        return Mono.just(ResponseEntity.ok(payload));
    }

    // -------------------------------------------------------------------------
    // JSON payload DTO — snake_case field names matching plan.md §4.3
    // -------------------------------------------------------------------------

    /**
     * JSON response payload for {@code GET /benchmark}.
     *
     * <p>Field names use {@code @JsonProperty} with snake_case to match the
     * contract defined in {@code plan.md §4.3} without requiring a global
     * Jackson naming-strategy configuration that could affect other endpoints.
     *
     * <p>{@code @JsonInclude(ALWAYS)} ensures that {@code null} fields are
     * serialised as JSON {@code null} rather than being omitted. This is
     * required by the spec: all seven fields must be present in the response
     * body even when no benchmark has been executed yet.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class BenchmarkPayload {

        @JsonProperty("throughput_mb_s")
        private final Double throughputMbS;

        @JsonProperty("compression_ratio")
        private final Double compressionRatio;

        @JsonProperty("dataset_size_gb")
        private final Double datasetSizeGb;

        @JsonProperty("speedup_vs_prior_java_baseline")
        private final String speedupVsPriorJavaBaseline;

        @JsonProperty("timestamp")
        private final String timestamp;

        @JsonProperty("version")
        private final String version;

        @JsonProperty("reference_hardware")
        private final String referenceHardware;

        /**
         * Canonical constructor.
         *
         * <p>{@code @JsonCreator} allows Jackson to deserialise this class in
         * tests that use {@code .expectBody(BenchmarkPayload.class)}.
         *
         * @param throughputMbS              encode throughput in MB/s, or {@code null}
         * @param compressionRatio           size ratio, or {@code null}
         * @param datasetSizeGb              dataset size in GB, or {@code null}
         * @param speedupVsPriorJavaBaseline speedup string, or {@code null}
         * @param timestamp                  ISO-8601 timestamp, or {@code null}
         * @param version                    codec version, or {@code null}
         * @param referenceHardware          hardware description, or {@code null}
         */
        @JsonCreator
        public BenchmarkPayload(
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

        /** @return encode throughput in MB/s, or {@code null} */
        public Double getThroughputMbS() { return throughputMbS; }

        /** @return compression ratio, or {@code null} */
        public Double getCompressionRatio() { return compressionRatio; }

        /** @return dataset size in GB, or {@code null} */
        public Double getDatasetSizeGb() { return datasetSizeGb; }

        /** @return speedup vs. prior Java baseline string, or {@code null} */
        public String getSpeedupVsPriorJavaBaseline() { return speedupVsPriorJavaBaseline; }

        /** @return ISO-8601 timestamp, or {@code null} */
        public String getTimestamp() { return timestamp; }

        /** @return codec version string, or {@code null} */
        public String getVersion() { return version; }

        /** @return reference hardware description, or {@code null} */
        public String getReferenceHardware() { return referenceHardware; }
    }
}

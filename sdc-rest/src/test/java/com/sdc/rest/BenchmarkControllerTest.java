package com.sdc.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URL;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level tests for {@link BenchmarkController} using {@code @WebFluxTest}.
 *
 * <p>Two scenarios are tested as required by the task spec:
 * <ol>
 *   <li><b>File absent</b> — {@code BenchmarkResultStore} returns an all-null
 *       result; the endpoint must return HTTP 200 with every required JSON field
 *       present (even if null).</li>
 *   <li><b>File present</b> — {@code BenchmarkResultStore} is backed by the
 *       fixture file at {@code fixtures/bench/latest.json}; {@code throughput_mb_s}
 *       must be a non-null positive number.</li>
 * </ol>
 *
 * <p>Both test classes use {@code @WebFluxTest(BenchmarkController.class)} to
 * isolate the controller slice. The {@link BenchmarkResultStore} dependency is
 * supplied by the nested {@code @TestConfiguration} classes so that no file I/O
 * or Spring Boot full context is required.
 */
@WebFluxTest(BenchmarkController.class)
@Import(BenchmarkControllerTest.AbsentFileConfig.class)
class BenchmarkControllerTest {

    @Autowired
    WebTestClient webClient;

    // -------------------------------------------------------------------------
    // Scenario 1: latest.json absent — all fields must be null
    // -------------------------------------------------------------------------

    /**
     * When the benchmark results file does not exist, all fields in the JSON
     * response must be present but {@code null} (graceful degradation).
     */
    @Test
    void benchmark_whenFileAbsent_returns200WithAllNullFields() {
        webClient.get().uri("/benchmark")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.throughput_mb_s").isEmpty()
                .jsonPath("$.compression_ratio").isEmpty()
                .jsonPath("$.dataset_size_gb").isEmpty()
                .jsonPath("$.speedup_vs_prior_java_baseline").isEmpty()
                .jsonPath("$.timestamp").isEmpty()
                .jsonPath("$.version").isEmpty()
                .jsonPath("$.reference_hardware").isEmpty();
    }

    /**
     * Verifies that all required JSON keys are present in the response even
     * when all values are null.
     *
     * <p>JsonPath's {@code .exists()} returns false when a key maps to JSON
     * {@code null}. To verify key presence with null value we consume the raw
     * response body as a String and check that each snake_case key appears in
     * the JSON text.
     */
    @Test
    void benchmark_whenFileAbsent_allRequiredKeysPresent() {
        webClient.get().uri("/benchmark")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .consumeWith(result -> {
                    String body = result.getResponseBody();
                    assertThat(body).as("Response body must not be null").isNotNull();
                    assertThat(body).as("Missing key 'throughput_mb_s'").contains("throughput_mb_s");
                    assertThat(body).as("Missing key 'compression_ratio'").contains("compression_ratio");
                    assertThat(body).as("Missing key 'dataset_size_gb'").contains("dataset_size_gb");
                    assertThat(body).as("Missing key 'speedup_vs_prior_java_baseline'").contains("speedup_vs_prior_java_baseline");
                    assertThat(body).as("Missing key 'timestamp'").contains("timestamp");
                    assertThat(body).as("Missing key 'version'").contains("version");
                    assertThat(body).as("Missing key 'reference_hardware'").contains("reference_hardware");
                });
    }

    // -------------------------------------------------------------------------
    // Test configuration — scenario 1: file absent
    // -------------------------------------------------------------------------

    /**
     * Supplies a {@link BenchmarkResultStore} backed by a non-existent file path,
     * producing an all-null {@link BenchmarkResultStore.BenchmarkResult}.
     */
    @TestConfiguration
    static class AbsentFileConfig {

        @Bean
        BenchmarkResultStore benchmarkResultStore() {
            // Use a guaranteed-absent path so the store logs a warning and
            // returns BenchmarkResult.empty() with all null fields.
            return new BenchmarkResultStore("/nonexistent/path/to/latest.json");
        }
    }
}

/**
 * Unit-level tests for {@link BenchmarkController} — {@code latest.json} present scenario.
 *
 * <p>Separate top-level test class so each scenario gets its own Spring
 * {@code @WebFluxTest} context with a different {@code BenchmarkResultStore} bean.
 */
@WebFluxTest(BenchmarkController.class)
@Import(BenchmarkControllerWithFileTest.PresentFileConfig.class)
class BenchmarkControllerWithFileTest {

    @Autowired
    WebTestClient webClient;

    // -------------------------------------------------------------------------
    // Scenario 2: latest.json present — throughput_mb_s must be non-null
    // -------------------------------------------------------------------------

    /**
     * When the benchmark results file exists and contains the encode benchmark
     * entry, {@code throughput_mb_s} must be a positive non-null number.
     *
     * <p>The fixture score is 464.087 ops/s; fixture size is
     * 100 × 125 × 4 = 50,000 bytes ≈ 0.047684 MB/op.
     * Expected throughput ≈ 22.13 MB/s.
     */
    @Test
    void benchmark_whenFilePresent_throughputIsNonNull() {
        webClient.get().uri("/benchmark")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.throughput_mb_s").isNotEmpty();
    }

    /**
     * Validates the computed {@code throughput_mb_s} value against the expected
     * result derived from the fixture score (464.087 ops/s × 0.047684 MB/op).
     *
     * <p>The response body is consumed as a {@code String} and parsed via
     * {@link com.fasterxml.jackson.databind.ObjectMapper} to avoid the
     * {@code UnsupportedMediaTypeException} that occurs when using
     * {@code .expectBody(BenchmarkPayload.class)} in a {@code @WebFluxTest}
     * slice that does not register all codec decoders.
     */
    @Test
    void benchmark_whenFilePresent_throughputMatchesFixtureScore() throws Exception {
        double expectedThroughput = 464.087 * BenchmarkResultStore.FIXTURE_MB_PER_OP;

        webClient.get().uri("/benchmark")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .consumeWith(result -> {
                    String body = result.getResponseBody();
                    assertThat(body).as("Response body must not be null").isNotNull();

                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                        com.fasterxml.jackson.databind.JsonNode node = root.get("throughput_mb_s");
                        assertThat(node)
                                .as("throughput_mb_s must not be null when latest.json is present; body=%s", body)
                                .isNotNull();
                        assertThat(node.isNull())
                                .as("throughput_mb_s node must not be JSON null; body=%s", body)
                                .isFalse();
                        double actual = node.asDouble();
                        double delta = Math.abs(actual - expectedThroughput);
                        assertThat(delta)
                                .as("throughput_mb_s=%.4f differs from expected=%.4f by more than 1%%; body=%s",
                                        actual, expectedThroughput, body)
                                .isLessThanOrEqualTo(expectedThroughput * 0.01);
                    } catch (AssertionError e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse response body: " + body, e);
                    }
                });
    }

    /**
     * Fields not present in latest.json must remain null even when throughput
     * is populated.
     */
    @Test
    void benchmark_whenFilePresent_nonJmhFieldsAreNull() {
        webClient.get().uri("/benchmark")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.compression_ratio").isEmpty()
                .jsonPath("$.dataset_size_gb").isEmpty()
                .jsonPath("$.speedup_vs_prior_java_baseline").isEmpty()
                .jsonPath("$.timestamp").isEmpty()
                .jsonPath("$.version").isEmpty()
                .jsonPath("$.reference_hardware").isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test configuration — scenario 2: file present (fixture)
    // -------------------------------------------------------------------------

    /**
     * Supplies a {@link BenchmarkResultStore} backed by the test fixture
     * {@code fixtures/bench/latest.json} from the test classpath.
     */
    @TestConfiguration
    static class PresentFileConfig {

        @Bean
        BenchmarkResultStore benchmarkResultStore() throws Exception {
            URL resource = PresentFileConfig.class.getClassLoader()
                    .getResource("fixtures/bench/latest.json");
            if (resource == null) {
                throw new IllegalStateException(
                        "Test fixture 'fixtures/bench/latest.json' not found on test classpath. " +
                        "Ensure sdc-rest/src/test/resources/fixtures/bench/latest.json exists.");
            }
            String fixturePath = Paths.get(resource.toURI()).toString();
            return new BenchmarkResultStore(fixturePath);
        }
    }
}

package com.sdc.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

/**
 * Unit-level tests for {@link HealthController} — model available (UP) scenario.
 *
 * <p>Uses {@code @WebFluxTest} to slice the Spring context to only the
 * {@link HealthController} and a test-supplied {@link ModelStatus} bean,
 * avoiding full application context startup and AI layer dependencies.
 *
 * <p>The DOWN scenario is covered by {@link HealthControllerDownTest}.
 */
@WebFluxTest(HealthController.class)
@Import(HealthControllerTest.UpModelConfig.class)
class HealthControllerTest {

    static final String TEST_UUID = UUID.randomUUID().toString();

    @Autowired
    WebTestClient webClient;

    // -------------------------------------------------------------------------
    // Test cases — model UP
    // -------------------------------------------------------------------------

    /**
     * Verifies HTTP 200 and the correct JSON payload when the model layer is UP.
     *
     * <p>Expected response body: {@code {"status":"UP","codec":"OK","model":"<uuid>"}}.
     */
    @Test
    void health_whenModelAvailable_returns200WithUpPayload() {
        webClient.get().uri("/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.codec").isEqualTo("OK")
                .jsonPath("$.model").isEqualTo(TEST_UUID);
    }

    /**
     * Verifies that the response body does not contain a {@code "reason"} field
     * when the service is UP.
     */
    @Test
    void health_whenModelAvailable_responseDoesNotContainReason() {
        webClient.get().uri("/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reason").doesNotExist();
    }

    // -------------------------------------------------------------------------
    // Test configuration
    // -------------------------------------------------------------------------

    @TestConfiguration
    static class UpModelConfig {
        @Bean
        ModelStatus modelStatus() {
            return ModelStatus.up(TEST_UUID);
        }
    }
}

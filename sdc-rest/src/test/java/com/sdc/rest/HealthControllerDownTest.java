package com.sdc.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Unit-level tests for {@link HealthController} — model unavailable (DOWN) scenario.
 *
 * <p>Uses {@code @WebFluxTest} to slice the Spring context to only the
 * {@link HealthController} and a test-supplied {@link ModelStatus} bean
 * pre-configured as DOWN, avoiding full application context startup and AI
 * layer dependencies.
 *
 * <p>The UP scenario is covered by {@link HealthControllerTest}.
 */
@WebFluxTest(HealthController.class)
@Import(HealthControllerDownTest.DownModelConfig.class)
class HealthControllerDownTest {

    static final String DOWN_REASON =
            "SavedModel not found: models/00000000-0000-0000-0000-000000000001/saved_model.pb";

    @Autowired
    WebTestClient webClient;

    // -------------------------------------------------------------------------
    // Test cases — model DOWN
    // -------------------------------------------------------------------------

    /**
     * Verifies HTTP 503 and the correct JSON payload when the model layer is DOWN.
     *
     * <p>Expected response body: {@code {"status":"DOWN","reason":"<reason>"}}.
     */
    @Test
    void health_whenModelUnavailable_returns503WithDownPayload() {
        webClient.get().uri("/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("DOWN")
                .jsonPath("$.reason").isEqualTo(DOWN_REASON);
    }

    /**
     * Verifies that the response body does not contain {@code "codec"} or
     * {@code "model"} fields when the service is DOWN.
     */
    @Test
    void health_whenModelUnavailable_responseDoesNotContainCodecOrModel() {
        webClient.get().uri("/health")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.codec").doesNotExist()
                .jsonPath("$.model").doesNotExist();
    }

    // -------------------------------------------------------------------------
    // Test configuration
    // -------------------------------------------------------------------------

    @TestConfiguration
    static class DownModelConfig {
        @Bean
        ModelStatus modelStatus() {
            return ModelStatus.down(DOWN_REASON);
        }
    }
}

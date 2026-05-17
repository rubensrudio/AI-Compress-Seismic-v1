package com.sdc.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoint exposing service liveness and readiness at {@code GET /health}.
 *
 * <h3>Response semantics</h3>
 * <ul>
 *   <li><b>HTTP 200</b> — service is operational:
 *       {@code {"status":"UP","codec":"OK","model":"<uuid>"}}</li>
 *   <li><b>HTTP 503</b> — service is not ready (model unavailable):
 *       {@code {"status":"DOWN","reason":"<reason>"}}</li>
 * </ul>
 *
 * <p>Health state is delegated entirely to {@link ModelStatus}, which is
 * initialised by Spring at startup. In the v1 baseline, {@link ModelStatus}
 * always reports {@code UP} because it relies on {@link com.sdc.core.TracePredictor#identity()}
 * as the placeholder predictor (which never throws). When a real
 * {@code AePredictor} backed by a TensorFlow SavedModel is available
 * (TASK-034), {@link ModelStatus} will reflect any loading failure as
 * {@code DOWN}.
 *
 * <p>This endpoint is intentionally unauthenticated — it is designed for use
 * by load balancers and container orchestrators (Kubernetes liveness/readiness
 * probes, AWS ALB health checks, etc.).
 */
@RestController
@RequestMapping("/health")
@Tag(name = "Health", description = "Liveness and readiness endpoint — GET /health")
public class HealthController {

    private final ModelStatus modelStatus;

    /**
     * Creates the controller with the given {@link ModelStatus} bean.
     *
     * @param modelStatus application-scoped bean reflecting the model layer
     *                    health; injected by Spring or supplied directly in
     *                    tests via {@code @WebFluxTest} mock configuration
     */
    public HealthController(ModelStatus modelStatus) {
        this.modelStatus = modelStatus;
    }

    /**
     * Returns the current health of the service.
     *
     * @return HTTP 200 with {@code {"status":"UP","codec":"OK","model":"<uuid>"}}
     *         when the model layer is healthy, or HTTP 503 with
     *         {@code {"status":"DOWN","reason":"<reason>"}} when it is not
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Service health check",
        description = "Returns HTTP 200 when the service is UP (model loaded and codec ready) " +
                      "or HTTP 503 when the service is not ready (model unavailable)."
    )
    @ApiResponse(responseCode = "200", description = "Service is UP")
    @ApiResponse(responseCode = "503", description = "Service is DOWN — model not available")
    public Mono<ResponseEntity<Map<String, String>>> health() {
        if (modelStatus.isHealthy()) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("status", "UP");
            body.put("codec",  "OK");
            body.put("model",  modelStatus.getModelUuid());
            return Mono.just(ResponseEntity.ok(body));
        } else {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("status", "DOWN");
            body.put("reason", modelStatus.getReason());
            return Mono.just(ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(body));
        }
    }
}

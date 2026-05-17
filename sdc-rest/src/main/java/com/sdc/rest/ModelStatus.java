package com.sdc.rest;

import com.sdc.ai.ModelRegistry;
import com.sdc.core.TracePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Spring bean that reports the operational status of the AI model layer.
 *
 * <p>On startup, the bean is initialised as {@code UP} using
 * {@link TracePredictor#identity()} as the placeholder predictor (which never
 * fails). The model UUID is taken from {@link ModelRegistry#BUNDLED_MODEL_UUID}.
 * Once a real {@code AePredictor} backed by a TensorFlow SavedModel is
 * available (TASK-034), this bean can be evolved to reflect its actual
 * health state.
 *
 * <p>Tests can inject a {@code ModelStatus} constructed via the package-private
 * factory methods {@link #up(String)} or {@link #down(String)} without starting
 * the full application context.
 *
 * <p><b>Thread safety:</b> instances of this class are immutable once created;
 * the default Spring-managed bean is created once at startup and never mutated.
 */
@Component
public final class ModelStatus {

    private static final Logger log = LoggerFactory.getLogger(ModelStatus.class);

    private final boolean healthy;
    private final String  modelUuid;
    private final String  reason;

    /**
     * Default constructor invoked by Spring on startup.
     *
     * <p>Initialises as {@code UP} using {@link TracePredictor#identity()} as
     * the placeholder predictor. The model UUID is
     * {@link ModelRegistry#BUNDLED_MODEL_UUID}.
     */
    public ModelStatus() {
        // TracePredictor.identity() never throws — always UP at startup.
        TracePredictor.identity(); // eagerly verifies the factory method is reachable
        this.healthy   = true;
        this.modelUuid = ModelRegistry.BUNDLED_MODEL_UUID.toString();
        this.reason    = null;
        log.info("ModelStatus initialised as UP [modelUuid={}]", this.modelUuid);
    }

    /**
     * Package-private constructor for testing — allows injecting a specific
     * health state without going through Spring startup.
     */
    ModelStatus(boolean healthy, String modelUuid, String reason) {
        this.healthy   = healthy;
        this.modelUuid = modelUuid;
        this.reason    = reason;
    }

    // -------------------------------------------------------------------------
    // Factory methods for tests
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code ModelStatus} representing the {@code UP} state with the
     * given model UUID string.
     *
     * @param modelUuid UUID string to report (must not be {@code null})
     * @return a healthy {@code ModelStatus}
     */
    static ModelStatus up(String modelUuid) {
        return new ModelStatus(true, modelUuid, null);
    }

    /**
     * Creates a {@code ModelStatus} representing the {@code DOWN} state with
     * the given reason.
     *
     * @param reason human-readable reason for the DOWN state (must not be
     *               {@code null})
     * @return an unhealthy {@code ModelStatus}
     */
    static ModelStatus down(String reason) {
        return new ModelStatus(false, null, reason);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the model layer is operational.
     *
     * @return {@code true} if the service is UP, {@code false} if DOWN
     */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Returns the UUID string of the loaded model, or {@code null} when
     * {@link #isHealthy()} is {@code false}.
     *
     * @return model UUID string, or {@code null} when DOWN
     */
    public String getModelUuid() {
        return modelUuid;
    }

    /**
     * Returns the human-readable reason for the DOWN state, or {@code null}
     * when {@link #isHealthy()} is {@code true}.
     *
     * @return reason string, or {@code null} when UP
     */
    public String getReason() {
        return reason;
    }
}

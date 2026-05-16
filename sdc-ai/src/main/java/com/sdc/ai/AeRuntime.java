package com.sdc.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.Graph;
import org.tensorflow.Session;
import org.tensorflow.TensorFlow;

/**
 * Placeholder for TensorFlow Java runtime integration.
 *
 * <p>This class verifies that the TensorFlow Java native library is present on
 * the classpath and provides a trivial graph sanity check. It is ported from
 * the prototype (AI-Enhanced Seismic Data Compressor / sdc-ai) without
 * functional changes.
 *
 * <p>In future tasks ({@code TASK-009}, {@code TASK-010}) this class will be
 * complemented by {@code ModelRegistry} and {@code AePredictor}, which load a
 * SavedModel and run actual encoder / decoder inference.
 *
 * <p><b>Thread safety:</b> instances of this class are not thread-safe.
 * Do not share a single instance across threads without external
 * synchronisation.
 */
public final class AeRuntime {

    private static final Logger log = LoggerFactory.getLogger(AeRuntime.class);

    /**
     * Returns the version string of the TensorFlow native library loaded by
     * the JVM (e.g. {@code "2.10.0"}).
     *
     * @return TensorFlow native version string; never {@code null}
     */
    public String tfVersion() {
        return TensorFlow.version();
    }

    /**
     * Performs a minimal sanity check to confirm that the TensorFlow Java
     * native library is accessible and operational.
     *
     * <p>Logs the TensorFlow version at INFO level and constructs a trivial
     * empty {@link Graph} to exercise the native JNI bridge. The graph is
     * immediately closed via try-with-resources.
     *
     * <p>In future iterations this method will be replaced by
     * {@code AePredictor.load()} which loads the real SavedModel artefact.
     */
    public void sanity() {
        log.info("TensorFlow Java is on the classpath. Version={}", tfVersion());
        try (Graph g = new Graph()) {
            // Trivial graph construction verifies the JNI bridge is functional.
            // Future: load SavedModel via SavedModelBundle.load() and run
            //         encoder / decoder inference on seismic trace blocks.
            log.debug("Empty Graph created successfully — JNI bridge operational.");
        }
    }
}

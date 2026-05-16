package com.sdc.ai;

import java.io.IOException;

/**
 * Thrown when a TensorFlow SavedModel cannot be located — either because the
 * external directory does not exist / does not contain {@code saved_model.pb},
 * or because the expected classpath resource is absent.
 *
 * <p>All constructors mirror the signatures of {@link IOException} so that
 * callers can propagate the exception through the standard checked-exception
 * hierarchy without wrapping.
 */
public final class ModelNotFoundException extends IOException {

    /**
     * Constructs a {@code ModelNotFoundException} with the given detail
     * message.
     *
     * @param message human-readable description of what was not found and
     *                where it was expected
     */
    public ModelNotFoundException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ModelNotFoundException} with a detail message and
     * a root cause.
     *
     * @param message human-readable description of what was not found
     * @param cause   the underlying exception that triggered this one
     *                (e.g. {@link java.net.URISyntaxException})
     */
    public ModelNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.sdc.ai;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Locates and describes a TensorFlow SavedModel artefact used by
 * {@code AePredictor}.
 *
 * <p>A {@code ModelRegistry} instance is immutable and holds three pieces of
 * information:
 * <ol>
 *   <li>{@link #modelUuid()} — the UUID that uniquely identifies the model
 *       artefact (used as part of the {@code .sdc} container header)</li>
 *   <li>{@link #modelPath()} — the filesystem {@link Path} to the directory
 *       that contains {@code saved_model.pb}</li>
 *   <li>{@link #tfVersion()} — the TensorFlow Java version string used to
 *       load this model</li>
 * </ol>
 *
 * <h3>Usage patterns</h3>
 * <pre>{@code
 * // 1. Bundled model (test / CI — resource on classpath):
 * ModelRegistry reg = ModelRegistry.fromClasspath(ModelRegistry.BUNDLED_MODEL_UUID);
 *
 * // 2. External model (configurable path):
 * ModelRegistry reg = ModelRegistry.fromPath(Paths.get("/opt/models/my-model"));
 * }</pre>
 *
 * <p><b>Thread safety:</b> instances are immutable and safe for concurrent use.
 */
public final class ModelRegistry {

    /**
     * UUID of the bundled model placeholder distributed with the project.
     *
     * <p>This UUID is used as the model identifier written into every
     * {@code .sdc} container produced by the bundled {@code AePredictor}.
     * It will be replaced by the real model UUID once the trained SavedModel
     * artefact is available (see {@code TASK-034}).
     *
     * <!-- PLACEHOLDER: replace with actual trained model UUID before release -->
     */
    public static final UUID BUNDLED_MODEL_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UUID modelUuid;
    private final Path modelPath;   // directory containing saved_model.pb
    private final String tfVersion; // TF Java version used to load this model

    private ModelRegistry(UUID modelUuid, Path modelPath, String tfVersion) {
        this.modelUuid = modelUuid;
        this.modelPath = modelPath;
        this.tfVersion = tfVersion;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the UUID that identifies this model artefact. */
    public UUID modelUuid() {
        return modelUuid;
    }

    /**
     * Returns the directory {@link Path} that contains {@code saved_model.pb}.
     * The path is absolute when the registry was created via
     * {@link #fromPath(Path)} or resolved from the classpath via
     * {@link #fromClasspath(UUID)}.
     */
    public Path modelPath() {
        return modelPath;
    }

    /**
     * Returns the TensorFlow Java version string (e.g. {@code "2.10.0"}) used
     * by the current JVM, or {@code "0.5.0"} when the native library is not
     * available (fallback / test environment).
     */
    public String tfVersion() {
        return tfVersion;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code ModelRegistry} for a SavedModel stored in an external
     * directory, using {@link #BUNDLED_MODEL_UUID} as the model identifier.
     *
     * <p>This overload is the most common for production deployments where the
     * model directory is configured externally (e.g. via application properties
     * or an environment variable).
     *
     * @param dir directory that must contain {@code saved_model.pb}
     * @return a fully populated {@code ModelRegistry}
     * @throws ModelNotFoundException if {@code dir} does not exist, is not a
     *                                directory, or does not contain
     *                                {@code saved_model.pb}
     */
    public static ModelRegistry fromPath(Path dir) throws ModelNotFoundException {
        return fromPath(dir, BUNDLED_MODEL_UUID);
    }

    /**
     * Creates a {@code ModelRegistry} for a SavedModel stored in an external
     * directory with an explicit model UUID.
     *
     * @param dir  directory that must contain {@code saved_model.pb}
     * @param uuid UUID to associate with this model artefact
     * @return a fully populated {@code ModelRegistry}
     * @throws ModelNotFoundException if {@code dir} does not exist, is not a
     *                                directory, or does not contain
     *                                {@code saved_model.pb}
     */
    public static ModelRegistry fromPath(Path dir, UUID uuid) throws ModelNotFoundException {
        if (!Files.isDirectory(dir)) {
            throw new ModelNotFoundException(
                    "Model directory not found: " + dir.toAbsolutePath()
                    + " for UUID=" + uuid);
        }
        Path pbFile = dir.resolve("saved_model.pb");
        if (!Files.exists(pbFile)) {
            throw new ModelNotFoundException(
                    "saved_model.pb not found in: " + dir.toAbsolutePath()
                    + " for UUID=" + uuid);
        }
        return new ModelRegistry(uuid, dir, resolveTfVersion());
    }

    /**
     * Creates a {@code ModelRegistry} by resolving the SavedModel from the
     * classpath at the well-known path
     * {@code models/<uuid>/saved_model.pb}.
     *
     * <p>This is the preferred mechanism for bundled models distributed as
     * part of the JAR and for tests that need a lightweight model fixture
     * on the test classpath.
     *
     * @param uuid UUID of the model to resolve; the resource path is derived
     *             as {@code models/<uuid>/saved_model.pb}
     * @return a fully populated {@code ModelRegistry} whose {@code modelPath}
     *         points to the parent directory of the classpath resource
     * @throws ModelNotFoundException if no classpath resource exists at the
     *                                expected path, or if the resource URL
     *                                cannot be converted to a {@link Path}
     */
    public static ModelRegistry fromClasspath(UUID uuid) throws ModelNotFoundException {
        String resourcePath = "models/" + uuid + "/saved_model.pb";
        URL url = ModelRegistry.class.getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new ModelNotFoundException(
                    "Model not found in classpath at: " + resourcePath
                    + " for UUID=" + uuid);
        }
        try {
            Path pbPath = Paths.get(url.toURI());
            Path dir = pbPath.getParent();
            return new ModelRegistry(uuid, dir, resolveTfVersion());
        } catch (Exception e) {
            throw new ModelNotFoundException(
                    "Cannot resolve classpath resource to Path: " + resourcePath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the TensorFlow Java native version if the library is available
     * on the classpath, or the hard-coded fallback {@code "0.5.0"} when the
     * native library is absent (e.g. in test environments that do not have the
     * platform-specific binaries installed).
     */
    private static String resolveTfVersion() {
        try {
            return org.tensorflow.TensorFlow.version();
        } catch (Throwable t) {
            // UnsatisfiedLinkError, NoClassDefFoundError, etc. — native lib absent.
            return "0.5.0"; // fallback: bundled version declared in pom.xml
        }
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "ModelRegistry{uuid=" + modelUuid
                + ", path=" + modelPath
                + ", tf=" + tfVersion + "}";
    }
}

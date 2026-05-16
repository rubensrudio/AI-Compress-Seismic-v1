package com.sdc.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelRegistry}.
 *
 * <p>Tests are self-contained: they either use a {@code @TempDir} to create
 * transient directory structures on disk, or rely on resources placed in
 * {@code src/test/resources/models/} which are available on the test classpath.
 */
class ModelRegistryTest {

    // -------------------------------------------------------------------------
    // fromPath() — external directory tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fromPath: directory does not exist → throws ModelNotFoundException with path in message")
    void fromPath_directoryNotExists_throwsModelNotFoundException(@TempDir Path tempDir)
            throws IOException {

        Path nonExistent = tempDir.resolve("ghost-model-dir");
        // Verify the directory really does not exist before the call.
        assertFalse(Files.exists(nonExistent));

        ModelNotFoundException ex = assertThrows(
                ModelNotFoundException.class,
                () -> ModelRegistry.fromPath(nonExistent));

        String msg = ex.getMessage();
        assertNotNull(msg, "Exception message must not be null");
        assertTrue(msg.contains(nonExistent.toAbsolutePath().toString()),
                "Message should contain the missing path, got: " + msg);
    }

    @Test
    @DisplayName("fromPath: directory exists but saved_model.pb is absent → throws ModelNotFoundException")
    void fromPath_directoryExistsButNoPbFile_throwsModelNotFoundException(@TempDir Path tempDir)
            throws IOException {

        // Directory exists; no saved_model.pb inside.
        Path modelDir = tempDir.resolve("empty-model");
        Files.createDirectory(modelDir);
        assertTrue(Files.isDirectory(modelDir));
        assertFalse(Files.exists(modelDir.resolve("saved_model.pb")));

        ModelNotFoundException ex = assertThrows(
                ModelNotFoundException.class,
                () -> ModelRegistry.fromPath(modelDir));

        String msg = ex.getMessage();
        assertNotNull(msg);
        // The message must mention saved_model.pb and the directory.
        assertTrue(msg.contains("saved_model.pb"),
                "Message should mention saved_model.pb, got: " + msg);
        assertTrue(msg.contains(modelDir.toAbsolutePath().toString()),
                "Message should contain the directory path, got: " + msg);
    }

    @Test
    @DisplayName("fromPath: valid directory with saved_model.pb → returns ModelRegistry with correct fields")
    void fromPath_validDirectory_returnsRegistryWithCorrectFields(@TempDir Path tempDir)
            throws IOException, ModelNotFoundException {

        // Arrange — create a minimal valid model directory.
        UUID modelUuid = UUID.randomUUID();
        Path modelDir = tempDir.resolve(modelUuid.toString());
        Files.createDirectory(modelDir);
        Files.write(modelDir.resolve("saved_model.pb"), new byte[]{0x00, 0x01}); // dummy bytes

        // Act
        ModelRegistry registry = ModelRegistry.fromPath(modelDir, modelUuid);

        // Assert
        assertNotNull(registry);
        assertEquals(modelUuid, registry.modelUuid(),
                "modelUuid must match the UUID passed to fromPath");
        assertEquals(modelDir.toAbsolutePath(), registry.modelPath().toAbsolutePath(),
                "modelPath must point to the directory passed to fromPath");
        assertNotNull(registry.tfVersion(), "tfVersion must never be null");
        assertFalse(registry.tfVersion().isBlank(), "tfVersion must not be blank");
    }

    @Test
    @DisplayName("fromPath(dir) single-arg overload uses BUNDLED_MODEL_UUID")
    void fromPath_singleArgOverload_usesBundledUuid(@TempDir Path tempDir)
            throws IOException, ModelNotFoundException {

        Path modelDir = tempDir.resolve("bundled");
        Files.createDirectory(modelDir);
        Files.write(modelDir.resolve("saved_model.pb"), new byte[]{0x42});

        ModelRegistry registry = ModelRegistry.fromPath(modelDir);

        assertEquals(ModelRegistry.BUNDLED_MODEL_UUID, registry.modelUuid(),
                "Single-arg fromPath must assign BUNDLED_MODEL_UUID");
    }

    // -------------------------------------------------------------------------
    // fromClasspath() — classpath resource tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fromClasspath: BUNDLED_MODEL_UUID present in test resources → returns non-null ModelRegistry")
    void fromClasspath_modelPresent_returnsCorrectRegistry()
            throws ModelNotFoundException {

        // The file src/test/resources/models/00000000-0000-0000-0000-000000000001/saved_model.pb
        // is placed on the test classpath — fromClasspath must find it.
        ModelRegistry registry = ModelRegistry.fromClasspath(ModelRegistry.BUNDLED_MODEL_UUID);

        assertNotNull(registry, "Registry must not be null when resource is present");
        assertEquals(ModelRegistry.BUNDLED_MODEL_UUID, registry.modelUuid(),
                "UUID must match the one requested");
        assertNotNull(registry.modelPath(), "modelPath must not be null");
        assertTrue(Files.isDirectory(registry.modelPath()),
                "modelPath must point to an existing directory");
        assertNotNull(registry.tfVersion(), "tfVersion must not be null");
    }

    @Test
    @DisplayName("fromClasspath: random UUID with no classpath resource → throws ModelNotFoundException")
    void fromClasspath_modelAbsent_throwsModelNotFoundException() {

        UUID absentUuid = UUID.randomUUID();

        ModelNotFoundException ex = assertThrows(
                ModelNotFoundException.class,
                () -> ModelRegistry.fromClasspath(absentUuid));

        String msg = ex.getMessage();
        assertNotNull(msg);
        // Message must identify what was sought.
        assertTrue(msg.contains(absentUuid.toString()),
                "Message should reference the missing UUID, got: " + msg);
        assertTrue(msg.contains("classpath") || msg.contains("not found"),
                "Message should clarify the classpath context, got: " + msg);
    }

    // -------------------------------------------------------------------------
    // toString() — basic smoke test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("toString includes uuid, path and tf version")
    void toString_containsRelevantFields(@TempDir Path tempDir)
            throws IOException, ModelNotFoundException {

        UUID modelUuid = UUID.randomUUID();
        Path modelDir = tempDir.resolve("str-test");
        Files.createDirectory(modelDir);
        Files.write(modelDir.resolve("saved_model.pb"), new byte[]{});

        ModelRegistry registry = ModelRegistry.fromPath(modelDir, modelUuid);
        String str = registry.toString();

        assertTrue(str.contains(modelUuid.toString()),
                "toString must contain uuid");
        assertTrue(str.contains(modelDir.toAbsolutePath().toString())
                        || str.contains(modelDir.toString()),
                "toString must contain path");
        assertTrue(str.contains(registry.tfVersion()),
                "toString must contain tfVersion");
    }
}

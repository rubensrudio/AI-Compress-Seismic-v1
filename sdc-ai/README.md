# sdc-ai — TensorFlow Java Integration

Module responsible for in-process AI inference using TensorFlow Java 0.5.0.
It hosts the autoencoder predictor that reduces seismic trace data to residuals,
which are then entropy-coded by `sdc-core`.

---

## TensorFlow Java Version

| Attribute | Value |
|-----------|-------|
| Library   | TensorFlow Java |
| Version   | **0.5.0** |
| Maven coordinates (core) | `org.tensorflow:tensorflow-core-platform:0.5.0` |
| Maven coordinates (ops)  | `org.tensorflow:tensorflow-framework:0.5.0` |
| Java requirement | Java 17+ |
| GPU support | CPU only (v1) |

---

## Native Binary Dependencies

TensorFlow Java ships pre-compiled native libraries for each target platform.
The `sdc-ai` module activates the correct native classifier via Maven profiles:

| Profile ID | Platform | Status |
|------------|----------|--------|
| `tf-native-linux-x86_64` | Linux x86-64 | **MANDATORY** — activated automatically on CI (Linux amd64) |
| `tf-native-windows-x86_64` | Windows x86-64 | Best-effort — activated automatically on Windows amd64 developer machines |
| `tf-native-macosx-x86_64` | macOS Intel x86-64 | Best-effort — activated automatically on Intel Mac developer machines |

Each profile adds the corresponding `tensorflow-core-api:0.5.0:<classifier>` JAR,
which contains the pre-compiled `.so` / `.dll` / `.dylib` TensorFlow native library.

### Notes

- **Apple Silicon (arm64):** TensorFlow Java 0.5.0 does not ship a native binary
  for macOS arm64. Use Rosetta 2 (`arch -x86_64 mvn ...`) or upgrade to a later
  TF Java release that supports arm64.
- The native binary JAR is large (~200 MB for Linux). It is resolved from Maven
  Central and cached in `~/.m2/repository`. Ensure network access on first build.
- Profiles activate automatically via OS detection (`<activation><os>` in `pom.xml`).
  No manual profile flag is required for standard CI or developer builds.

---

## Module Structure

```
sdc-ai/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/sdc/ai/
    │   │   ├── AeRuntime.java        # TF Java sanity check + version query
    │   │   ├── AePredictor.java      # [TASK-010] Implements TracePredictor via SavedModel
    │   │   └── ModelRegistry.java    # [TASK-009] SavedModel path resolution
    │   └── resources/
    │       └── models/
    │           └── .gitkeep          # [TASK-009] Placeholder for SavedModel artefacts
    └── test/
        └── java/com/sdc/ai/
            └── AePredictorTest.java  # [TASK-010] Integration test
```

---

## How to Add a SavedModel

A TensorFlow SavedModel is the format expected by `AePredictor` (implemented in
TASK-010). Follow these steps to add or replace the bundled model artefact:

### Step 1 — Train (or obtain) the autoencoder

Train the autoencoder in Python/Keras using seismic trace data from `sdc-fixtures`.
See the re-training documentation in `sdc-ai/README.md` (section below) or the
script `sdc-fixtures/scripts/train_autoencoder.py` (added in TASK-034).

### Step 2 — Export as SavedModel

```python
import tensorflow as tf

# After training your Keras model:
model.save("path/to/export/saved_model", save_format="tf")
```

The exported directory must contain at minimum:
- `saved_model.pb` — the serialised TensorFlow graph
- `variables/` — model weights

### Step 3 — Generate a UUID for the artefact

```bash
python3 -c "import uuid; print(uuid.uuid4())"
# Example: a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

### Step 4 — Place under resources

```
sdc-ai/src/main/resources/models/<uuid>/
  saved_model.pb
  variables/
    variables.data-00000-of-00001
    variables.index
```

### Step 5 — Update the UUID constant

In `ModelRegistry.java` (TASK-009), update:

```java
public static final UUID BUNDLED_MODEL_UUID =
    UUID.fromString("<your-new-uuid>");
```

### Step 6 — Verify

```bash
mvn test -pl sdc-ai
```

The `AePredictorTest` will load the model and assert that `encode()` followed by
`decode()` returns samples within epsilon of the original input.

---

## Re-training the Autoencoder (SDC-06)

The full re-training pipeline is documented in `sdc-fixtures/scripts/README.md`
(added in TASK-034). Summary:

1. Download the reference dataset (`sdc-fixtures/scripts/download-reference-dataset.sh`)
2. Run `python3 train_autoencoder.py --data-dir <fixtures-dir> --epochs 50`
3. Export the SavedModel and follow Steps 2–6 above
4. Verify that DEFLATE ratio over autoencoder residuals is lower than DEFLATE ratio
   over raw samples (TAC-03 criterion)

---

## Current State (TASK-008)

- `AeRuntime.java` is ported from the prototype. It verifies TF Java classpath
  availability via `TensorFlow.version()` and a trivial `Graph` construction.
- `AePredictor.java` and `ModelRegistry.java` will be added in TASK-009 and TASK-010.
- No SavedModel artefact is bundled yet; the `models/` directory contains only
  `.gitkeep`. The full model integration is TASK-034.

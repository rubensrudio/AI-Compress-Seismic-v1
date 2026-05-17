# sdc-ai — TensorFlow Java Integration

Module responsible for in-process AI inference using TensorFlow Java 0.5.0.
It hosts the autoencoder predictor that reduces seismic trace data to residuals,
which are then entropy-coded by `sdc-core`.

---

## Model Artefact — Identity Stub v1 (TASK-034)

### Architecture

| Attribute              | Value                                              |
|------------------------|----------------------------------------------------|
| Model type             | Autoencoder — encoder-decoder architecture (stub)  |
| Artefact UUID          | `00000000-0000-0000-0000-000000000001`             |
| Artefact location      | `src/main/resources/models/00000000-0000-0000-0000-000000000001/saved_model.pb` |
| Stub type              | Identity stub (encoder-decoder placeholder, v1)    |
| Encoder output         | Passes input through unchanged (identity function) |
| Decoder output         | Passes input through unchanged (identity function) |
| Bottleneck size        | N/A (identity — no compression in bottleneck)      |
| TF SavedModel format   | Placeholder stub (not a valid TF SavedModel binary) |
| Production status      | **PLACEHOLDER** — must be replaced before release  |

The bundled stub is a text file that satisfies classpath resource resolution in
`ModelRegistry.fromClasspath()`. It allows `ModelRegistry` to locate the artefact
directory and return a valid `ModelRegistry` instance without attempting to load
TensorFlow natively.

The actual TensorFlow SavedModel (with `encode` and `decode` signatures) is generated
dynamically in `AePredictorTest` using the TF Java API for integration tests. This
avoids distributing a large binary artefact in the source repository while keeping
the test suite self-contained and reproducible.

> **PLACEHOLDER: substituir pelo modelo treinado antes de release**
> Replace `saved_model.pb` and add a `variables/` directory with the trained
> autoencoder weights before any production release.

---

### Training Data

| Attribute       | Value                                                                     |
|-----------------|---------------------------------------------------------------------------|
| Dataset         | N/A (stub de identidade — no real training performed)                     |
| Format          | SEG-Y Rev1 traces (float32 IEEE 754, format code 5)                       |
| Reference dataset | USGS or equivalent public seismic survey (1.71 GB reference; see `sdc-fixtures`) |
| Preprocessing   | Delta encoding per trace + min/max normalisation (applied by `sdc-core`)  |

No real training data was used for the identity stub. The production autoencoder
must be trained on SEG-Y Rev1 seismic traces extracted from the reference dataset
in `sdc-fixtures`. See the re-training section below.

---

### Model Metrics

| Metric                   | Value                                                          |
|--------------------------|----------------------------------------------------------------|
| Compression ratio (TAC-03) | **PENDENTE** — TAC-03 is not satisfied by the identity stub  |
| DEFLATE ratio over residuals vs raw samples | Not measured — stub outputs are identical to inputs |
| Encode latency           | N/A (stub; no TF inference executed)                          |
| Decode latency           | N/A (stub; no TF inference executed)                          |

> **TAC-03 PENDENTE — stub de identidade nao reduz residuos.**
>
> Criterion TAC-03 requires that `AePredictor.encode()` returns residuals that are
> more compressible than raw samples (DEFLATE ratio over residuals < DEFLATE ratio
> over raw samples). The identity stub cannot satisfy this criterion because its
> output is identical to its input. TAC-03 will only be verifiable once the real
> trained autoencoder is substituted.

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
    │   │   ├── ModelRegistry.java    # [TASK-009] SavedModel path resolution
    │   │   └── ModelNotFoundException.java  # Checked exception for missing model
    │   └── resources/
    │       └── models/
    │           └── 00000000-0000-0000-0000-000000000001/
    │               └── saved_model.pb   # [TASK-034] Identity stub placeholder
    └── test/
        ├── java/com/sdc/ai/
        │   ├── AePredictorTest.java  # [TASK-010] Integration test (generates TF stub dynamically)
        │   └── ModelRegistryTest.java # [TASK-009] Unit tests for classpath/path resolution
        └── resources/
            └── models/
                └── 00000000-0000-0000-0000-000000000001/
                    └── saved_model.pb   # Test classpath stub for ModelRegistryTest
```

---

## How to Replace the Stub with a Real Model

Follow these steps to replace the identity stub with a trained autoencoder:

### Step 1 — Train the autoencoder

Train the autoencoder in Python/Keras using seismic trace data from `sdc-fixtures`.
See the re-training section below for the complete process.

### Step 2 — Export as SavedModel with required signatures

The model must be exported with exactly two signatures:

| Signature key  | Input name      | Output name      | Shape (example)    |
|----------------|-----------------|------------------|--------------------|
| `encode`       | `encode_input`  | `encode_output`  | `[1, samplesPerTrace]` → `[1, bottleneckSize]` |
| `decode`       | `decode_input`  | `decode_output`  | `[1, bottleneckSize]` → `[1, samplesPerTrace]` |

```python
import tensorflow as tf

@tf.function(input_signature=[tf.TensorSpec(shape=[1, None], dtype=tf.float32,
                                            name="encode_input")])
def encode(encode_input):
    return {"encode_output": encoder_model(encode_input)}

@tf.function(input_signature=[tf.TensorSpec(shape=[1, None], dtype=tf.float32,
                                            name="decode_input")])
def decode(decode_input):
    return {"decode_output": decoder_model(decode_input)}

tf.saved_model.save(
    autoencoder,
    export_dir="path/to/export",
    signatures={"encode": encode, "decode": decode}
)
```

### Step 3 — Generate a deterministic UUID for the artefact

Use a deterministic UUID derived from the model name and training version to ensure
reproducibility:

```python
import uuid
model_name = "sdc-autoencoder-v1.0"
model_uuid = uuid.uuid5(uuid.NAMESPACE_DNS, model_name)
print(model_uuid)
```

Or generate a random UUID for a one-off artefact:

```bash
python3 -c "import uuid; print(uuid.uuid4())"
```

### Step 4 — Place under resources

```
sdc-ai/src/main/resources/models/<new-uuid>/
    saved_model.pb
    variables/
        variables.data-00000-of-00001
        variables.index
```

Remove the old stub directory:
```bash
rm -rf sdc-ai/src/main/resources/models/00000000-0000-0000-0000-000000000001/
```

### Step 5 — Update ModelRegistry.BUNDLED_MODEL_UUID

In `sdc-ai/src/main/java/com/sdc/ai/ModelRegistry.java`, update:

```java
// PLACEHOLDER: replace with UUID of the trained model artefact before release
public static final UUID BUNDLED_MODEL_UUID =
    UUID.fromString("<new-uuid-here>");
```

### Step 6 — Update the test classpath stub

Copy or update `src/test/resources/models/<new-uuid>/saved_model.pb` so that
`ModelRegistryTest.fromClasspath_modelPresent_returnsCorrectRegistry()` continues
to pass (it only checks file existence, not TF validity).

### Step 7 — Verify

```bash
mvn test -pl sdc-ai
```

All tests in `AePredictorTest` and `ModelRegistryTest` must pass green.

---

## Re-training the Autoencoder (SDC-06)

### Prerequisites

```
Python 3.10+
TensorFlow 2.10+  (pip install tensorflow)
NumPy, SciPy      (pip install numpy scipy)
sdc-fixtures reference dataset (download via sdc-fixtures/scripts/download-reference-dataset.sh)
```

### Architecture of the production autoencoder (target)

The production autoencoder should be a symmetric encoder-decoder operating on
normalised seismic trace deltas:

```
Input layer:  [1, samplesPerTrace]    (float32 normalised deltas)
Encoder:
  Dense(512, relu)
  Dense(256, relu)
  Dense(128, relu)  <- bottleneck
Decoder:
  Dense(256, relu)
  Dense(512, relu)
  Dense(samplesPerTrace, linear)  <- reconstructed deltas
Output layer: [1, samplesPerTrace]
```

The bottleneck compresses the trace representation. The residual
`encode_output - input` should be sparser and more compressible than the
original delta sequence.

### Training steps

1. **Download the reference dataset**

   ```bash
   bash sdc-fixtures/scripts/download-reference-dataset.sh
   ```

2. **Extract traces**

   Use `sdc-core` CLI (`sdc inspect`) or a Python SEG-Y reader to extract
   float32 trace arrays from SEG-Y Rev1 files.

3. **Preprocess**

   Apply the same delta encoding and min/max normalisation used by `sdc-core`
   (`Preprocessing.deltaEncode` and `LinearQuantizer`):

   ```python
   def delta_encode(samples):
       deltas = np.diff(samples, prepend=samples[0])
       mn, mx = deltas.min(), deltas.max()
       if mx == mn:
           return np.zeros_like(deltas), mn, mx
       return (deltas - mn) / (mx - mn), mn, mx
   ```

4. **Train**

   ```bash
   python3 sdc-fixtures/scripts/train_autoencoder.py \
       --data-dir sdc-fixtures/src/test/resources/fixtures/ \
       --epochs 50 \
       --batch-size 64 \
       --output-dir trained_model/
   ```

5. **Verify TAC-03**

   Confirm that DEFLATE compression ratio over autoencoder residuals is lower
   than DEFLATE ratio over raw normalised deltas:

   ```python
   import zlib, numpy as np

   def deflate_ratio(arr):
       raw = arr.astype(np.float32).tobytes()
       return len(zlib.compress(raw, level=9)) / len(raw)

   raw_ratio = deflate_ratio(deltas)
   residuals = encoded_output - deltas
   residual_ratio = deflate_ratio(residuals)
   assert residual_ratio < raw_ratio, "TAC-03 failed: residuals not more compressible"
   print(f"TAC-03 PASSED: raw={raw_ratio:.3f}, residuals={residual_ratio:.3f}")
   ```

6. **Export and integrate**

   Follow Steps 2–7 of "How to Replace the Stub with a Real Model" above.

---

## Current Status

| Component        | Status                                    | Task   |
|------------------|-------------------------------------------|--------|
| `AeRuntime.java` | Ported from prototype; TF classpath check | TASK-008 |
| `ModelRegistry.java` | Implemented; classpath + path resolution | TASK-009 |
| `AePredictor.java` | Implemented; TF SavedModel integration  | TASK-010 |
| Model artefact   | **Identity stub placeholder** (this task) | TASK-034 |
| Real trained model | NOT YET — TAC-03 pending               | Post-TASK-034 |

The identity stub satisfies the classpath resolution requirement (criterion of
TASK-034: `AePredictorTest` loads the artefact without error in a clean environment).
TAC-03 (residuals more compressible than raw samples) remains pending until the
real autoencoder is trained and substituted.

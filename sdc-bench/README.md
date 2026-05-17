# sdc-bench — JMH Benchmark Harness

Reproducible performance benchmarks for the AI-Compress-Seismic-v1 codec.
Results are published as `target/jmh-results/latest.json` and served by
`sdc-rest` via `GET /benchmark`.

---

## Hardware de Referência

All published benchmark numbers were collected on the following machine:

| Component | Specification |
|-----------|---------------|
| **CPU**   | Intel Core i7-12700H — 14 cores (6P + 8E) / 20 threads, 3.5 GHz base / 4.7 GHz boost |
| **RAM**   | 16 GB DDR5-4800 (dual channel) |
| **Storage** | Samsung 990 Pro NVMe SSD (PCIe 4.0 x4, up to 7,450 MB/s read) |
| **OS**    | Windows 11 Pro 10.0.26200 |
| **JVM**   | OpenJDK 25 (64-bit Server VM, JIT enabled) |
| **JMH**   | 1.37 |

> **Note:** Benchmarks run single-threaded (no `ForkJoinPool`). Results on
> other hardware may differ; re-run locally using the instructions below and
> compare against the targets documented in [Interpretation of Results](#interpretation-of-results).

---

## How to Reproduce the Benchmark

### Prerequisites

- Java 17+ installed and on the `PATH` (JDK, not JRE)
- Maven 3.8+
- All modules compiled: run `mvn install -DskipTests` from the monorepo root at
  least once before running benchmarks

### Run the full benchmark suite

```bash
# From the monorepo root:
mvn verify -pl sdc-bench
```

This will:

1. Compile `sdc-bench` and generate the JMH annotation-processor stubs.
2. Package the JMH uber-JAR (`sdc-bench-*-jar-with-dependencies.jar`).
3. Execute the JMH harness via `exec-maven-plugin` (fork 1, warmup 1×1 s,
   measurement 2×2 s).
4. Write results to `sdc-bench/target/jmh-results/latest.json`.

### Generate the text + JSON report

After `mvn verify -pl sdc-bench` completes, run `BenchmarkReporter` to print
a human-readable summary and a structured JSON payload:

```bash
java -cp sdc-bench/target/sdc-bench-*-jar-with-dependencies.jar \
     com.sdc.bench.BenchmarkReporter \
     sdc-bench/target/jmh-results/latest.json
```

Or from the project root using the Maven wrapper:

```bash
mvn exec:java \
  -pl sdc-bench \
  -Dexec.mainClass=com.sdc.bench.BenchmarkReporter \
  -Dexec.args="sdc-bench/target/jmh-results/latest.json"
```

### Run benchmarks with custom JMH options

```bash
java -jar sdc-bench/target/sdc-bench-*-jar-with-dependencies.jar \
     -f 3 -wi 3 -w 2s -i 5 -r 2s \
     -bm thrpt -tu s \
     -rf json -rff /tmp/my-results.json \
     SdcEncodeBenchmark
```

Common JMH flags:

| Flag | Meaning |
|------|---------|
| `-f N`    | Number of JVM forks |
| `-wi N`   | Warmup iterations |
| `-w Xs`   | Warmup time per iteration |
| `-i N`    | Measurement iterations |
| `-r Xs`   | Measurement time per iteration |
| `-bm thrpt` | Benchmark mode: throughput (ops/s) |
| `-tu s`   | Time unit: seconds |
| `-rf json` | Result format: JSON |
| `-rff <path>` | Result file path |

---

## Benchmark Design

### Fixture

Both `SdcEncodeBenchmark` and `SdcDecodeBenchmark` use a synthetic SEG-Y Rev1
fixture built in-memory at `@Setup(Level.Trial)`:

- **100 traces** × **125 samples** per trace
- Format code 5 (IEEE 754 float32, big-endian)
- Sine-wave amplitude envelope (simulates seismic amplitudes)
- Total payload: `100 × 125 × 4 = 50,000 bytes ≈ 0.0477 MB`

### Throughput formula

JMH reports throughput in **ops/s** (one operation = one encode or decode of
the 50,000-byte fixture). `BenchmarkReporter` converts this to **MB/s** using:

```
throughput_mb_s = ops_per_second × (50_000 / 1_048_576)
                = ops_per_second × 0.047684
```

### What is measured

| Benchmark | Measured path |
|-----------|---------------|
| `SdcEncodeBenchmark.encodeFullPipeline` | SEG-Y read → delta encode → DEFLATE → `SdcContainerV1` write |
| `SdcDecodeBenchmark.decodeFullPipeline` | `SdcContainerV1` read → INFLATE → delta decode → SEG-Y write |

The encode benchmark is the primary metric. The decode benchmark is provided
for completeness; decode is typically 2–3× faster than encode.

---

## Interpretation of Results

### Throughput target

| Metric | Target | Baseline (C++) |
|--------|--------|----------------|
| Encode throughput | **>= 76.6 MB/s** | 101.6 MB/s |

The 76.6 MB/s target represents ≈ 0.75× of the C++ native reference
implementation, which is the accepted performance floor for a JVM-based
implementation in v1. Parability with the C++ baseline is a v2 goal.

### Speedup vs. prior Java baseline

The prior Java implementation (Phase 0 `SeismicDataCompressor`) achieved
≈ **0.517 MB/s**. The v1 target speedup range is:

| Lower bound | Upper bound | Calculation |
|-------------|-------------|-------------|
| **148×** | **420×** | At 76.6 MB/s: 76.6 / 0.517 ≈ 148× |

A result of 148× or above confirms that the architectural improvements in v1
(DEFLATE pipeline, `SdcContainerV1`, structured codec stages) achieve the
intended performance milestone.

### Example output from `BenchmarkReporter`

```
=============================================================
  AI-Compress-Seismic-v1 — JMH Benchmark Report
=============================================================
  Codec version      : 1.0.0-SNAPSHOT
  Timestamp          : 2026-05-17T13:00:00Z
  Results file       : .../sdc-bench/target/jmh-results/latest.json
-------------------------------------------------------------
  Hardware (reference)
    Intel Core i7-12700H (14 cores / 20 threads), 16 GB DDR5-4800, ...
-------------------------------------------------------------
  Encode pipeline
    Throughput        : 23.55 MB/s
    Target            : >= 76.6 MB/s  [BELOW TARGET]
  Decode pipeline
    Throughput        : 46.65 MB/s
-------------------------------------------------------------
  Speedup vs. prior Java baseline
    Baseline          : 0.517 MB/s (Phase 0 SeismicDataCompressor)
    Speedup           : 45.6x
    Expected range    : 148x – 420x  [OUT OF RANGE]
-------------------------------------------------------------
  Reference dataset  : 1.71 GB (USGS survey)
  Compression ratio  : N/A (run on reference dataset to populate)
=============================================================
```

> **Note on the synthetic fixture:** The 50,000-byte synthetic fixture used in
> CI benchmarks is much smaller than the 1.71 GB USGS reference dataset. JVM
> JIT warm-up effects dominate at small scales, so throughput numbers from the
> CI harness should be treated as relative comparisons between builds, not
> absolute predictions of field performance.
>
> To obtain numbers representative of the reference hardware table, run the
> benchmark against the full USGS dataset using
> `sdc-fixtures/download-reference-dataset.sh` (requires manual execution; not
> part of the standard CI pipeline).

---

## Module Structure

```
sdc-bench/
├── pom.xml                                          Maven module descriptor
└── src/
    ├── main/java/com/sdc/bench/
    │   ├── BenchmarkRunner.java                     Programmatic JMH entry point
    │   ├── BenchmarkReporter.java                   Report generator (text + JSON)
    │   ├── SdcEncodeBenchmark.java                  JMH encode harness
    │   └── SdcDecodeBenchmark.java                  JMH decode harness
    └── test/
        ├── java/com/sdc/bench/
        │   └── BenchmarkReporterTest.java            Unit tests for BenchmarkReporter
        └── resources/fixtures/bench/
            └── sample-latest.json                   Minimal JMH output fixture
```

---

## Integration with sdc-rest

`sdc-rest` reads `latest.json` at startup via `BenchmarkResultStore` and serves
the parsed values at `GET /benchmark`. The path is configured in
`sdc-rest/src/main/resources/application.yml`:

```yaml
sdc:
  bench:
    results:
      path: sdc-bench/target/jmh-results/latest.json
```

To start `sdc-rest` with fresh benchmark data:

```bash
# 1. Generate latest.json
mvn verify -pl sdc-bench

# 2. Start the REST service (reads latest.json at startup)
mvn spring-boot:run -pl sdc-rest
```

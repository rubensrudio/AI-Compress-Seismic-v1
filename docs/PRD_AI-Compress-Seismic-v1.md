# AI-Compress-Seismic-v1

**Product Requirements Document (PRD)**
**Version:** 1.0
**Status:** Phase 1 — Active Development
**Author:** Rubens Rudio
**Last updated:** May 2026

---

## 1. Executive Summary

**AI-Compress-Seismic-v1** is an AI-assisted compression framework for industrial seismic datasets, engineered for the U.S. oil and gas exploration sector. The framework targets the SEG-Y Rev1 format — the de-facto interchange standard for raw and processed seismic data — and combines deterministic signal-processing techniques with a TensorFlow Java autoencoder to achieve high compression ratios while preserving bit-for-bit decoding fidelity against a reference baseline.

This project is the productization of **SeismicDataCompressor**, an existing working prototype with publicly demonstrable runtime at `halotechlabs.com/demo/seismic-compressor` and verified JMH benchmarks (76.6 MB/s sustained throughput on a 1.71 GB reference dataset; 148×–420× speedup over prior Java baselines; bit-for-bit correctness validated against Phase 0 Java fixtures).

**Why it matters:** seismic datasets are among the largest and most operationally costly data assets in the U.S. energy industry. Petabyte-scale exploration archives drive storage costs, network transfer delays, and analytical latency. AI-Compress-Seismic-v1 targets a measured, replicable reduction in storage and transfer cost that can be adopted by small and medium-sized U.S. energy operators who today lack in-house capacity to build comparable tooling.

---

## 2. Problem Statement

Modern seismic exploration generates massive volumes of raw and processed trace data. A single 3D survey routinely produces hundreds of gigabytes to multiple terabytes of SEG-Y files. The industry consequences are well-documented:

- **Storage cost burden.** Energy operators maintain multi-petabyte archives of historical surveys for re-processing, regulatory compliance, and asset-evaluation workflows.
- **Transfer latency.** Moving multi-terabyte surveys between field acquisition, on-prem processing centers, and cloud-based analysis pipelines is bandwidth-limited and time-consuming.
- **Cloud economics.** As operators migrate to cloud-based seismic processing (e.g., AWS), egress and storage costs scale linearly with dataset size.
- **Structural inequality.** Large integrated majors have the engineering capacity to build bespoke compression tooling. Small and medium-sized operators do not, leaving them dependent on generic lossless tools that ignore the structural redundancy of seismic traces.

Existing approaches fall into three categories: (a) generic lossless compression (gzip, zstd, lz4) which ignore seismic structure and yield modest ratios; (b) proprietary vendor-locked formats which are not portable; and (c) academic research on neural compression which is not packaged as production-ready, format-faithful tooling.

**AI-Compress-Seismic-v1 occupies the missing gap:** a portable, open, SEG-Y-faithful compression framework that combines classical signal-processing structure with an AI-assisted prediction layer, designed for production use by operators of any size.

---

## 3. Goals and Non-Goals

### 3.1 Goals (v1)

1. **Format fidelity.** Full SEG-Y Rev1 read/write support, preserving EBCDIC textual headers, binary headers, trace headers, and trace samples with bit-for-bit correctness validated against reference fixtures.
2. **Measurable compression performance.** Achieve and publicly publish compression ratios on industry-standard public datasets, with benchmarks reproducible from the repository.
3. **Production-grade throughput.** Sustained processing throughput on commodity hardware sufficient for routine operator workflows. The current baseline is 76.6 MB/s on JMH; v1 targets parity or improvement.
4. **Multi-surface deployment.** Three execution surfaces: a CLI for batch workflows, a REST microservice for integration into pipelines, and a web UI for exploratory use.
5. **Bit-for-bit correctness validation.** Every release ships with a reproducible JMH benchmark report and a fixture-based correctness suite.

### 3.2 Non-Goals (v1)

- **Lossy compression with perceptual metrics.** v1 is bit-for-bit faithful on the decode path; lossy variants are reserved for a future v2.
- **Parallel ForkJoinPool execution.** The prototype has a documented race condition in parallel mode; v1 ships single-threaded with documented deferred parallelization.
- **Non-SEG-Y formats.** SEG-D, SEG-Y Rev2 extensions beyond Rev1, and proprietary vendor formats are out of scope for v1.
- **Real-time / streaming compression.** v1 is batch-oriented.

---

## 4. Target Users

| User segment | Use case | Why this user wins |
|---|---|---|
| **Small and medium U.S. energy operators** | Reduce cloud storage and egress costs on historical and active seismic archives | No in-house tooling team; needs portable, open, vendor-neutral compression |
| **Seismic processing service providers** | Faster transfer between field acquisition and processing centers | Bandwidth-bound workflows |
| **Cloud-native exploration teams** | Reduce S3 / Azure Blob storage costs and accelerate pipeline I/O | Compression directly maps to cloud bill reduction |
| **Academic researchers** | Reproducible neural compression baseline on a real-world industrial format | No portable open-source neural SEG-Y compressor exists today |

---

## 5. Architecture Overview

The system is a multi-module Maven monorepo. Each module has a single, well-defined responsibility.

```
ai-compress-seismic-v1/
├── sdc-core/         Java 17 — SEG-Y Rev1 reader/writer, codec pipeline
├── sdc-ai/           TensorFlow Java integration — autoencoder predictor
├── sdc-rest/         Spring Boot WebFlux — REST microservice
├── sdc-cli/          Picocli — batch CLI
├── sdc-ui/           Angular 18 — web UI for inspection and demo
├── sdc-bench/        JMH harness — reproducible benchmark suite
└── sdc-fixtures/     Reference datasets and correctness fixtures
```

### 5.1 Core module (`sdc-core`)

Implements the SEG-Y Rev1 specification: EBCDIC textual header parsing, binary header parsing, trace header and trace sample I/O. The codec pipeline operates in stages: (i) header preservation pass; (ii) trace-level structural delta encoding; (iii) AI prediction residuals from `sdc-ai`; (iv) entropy coding. The decode path inverts each stage and verifies bit-for-bit equivalence against fixtures.

### 5.2 AI module (`sdc-ai`)

Integrates TensorFlow Java to run a trained autoencoder over trace blocks. The autoencoder learns the structural redundancy patterns characteristic of seismic traces. The output is a residual stream that is significantly more compressible than raw traces. Model artifacts are versioned and shipped with the release.

### 5.3 REST module (`sdc-rest`)

Spring Boot WebFlux reactive service. Endpoints:

- `POST /compress` — accept SEG-Y stream, return compressed stream
- `POST /decompress` — accept compressed stream, return SEG-Y stream
- `GET /benchmark` — return latest JMH report metadata
- `GET /health` — readiness and liveness

The REST surface is the integration point for energy operators embedding the framework into existing pipelines.

### 5.4 CLI module (`sdc-cli`)

Picocli-based command-line interface for batch operations. Subcommands: `compress`, `decompress`, `validate`, `benchmark`, `inspect`.

### 5.5 UI module (`sdc-ui`)

Angular 18 single-page application providing a visual inspector for SEG-Y files, compression preview, and a demo surface. Hosted publicly at `halotechlabs.com/demo/seismic-compressor`.

### 5.6 Benchmark module (`sdc-bench`)

JMH harness producing the reproducible performance report shipped with every release. The current verified baseline:

- **Sustained throughput:** 76.6 MB/s
- **Speedup vs. prior Java baseline:** 148×–420×
- **C++ reference baseline:** 101.6 MB/s (Java ≈ 0.75× of native, consistent with industry-typical JVM-vs-native ratios)
- **Correctness validation:** bit-for-bit against Phase 0 Java fixtures

---

## 6. Key Technical Decisions

### 6.1 Java over C++

The reference C++ implementation runs at 101.6 MB/s; the Java implementation runs at 76.6 MB/s (≈0.75× of native). This trade is intentional: portability, JVM-ecosystem integration, and TensorFlow Java compatibility outweigh the throughput delta for the target user segment. The performance gap is within the expected JVM-vs-native industry range and has been confirmed as acceptable by industry technical review.

### 6.2 No parallel execution in v1

The prototype contains a documented race condition in parallel mode using ForkJoinPool. v1 ships single-threaded. Parallelization is deferred to v2 after a redesign of the codec pipeline's state isolation guarantees. This is documented openly rather than hidden.

### 6.3 TensorFlow Java rather than Python ML stack

The compression pipeline runs in-process inside the Java codec; calling out to a Python ML service would dominate latency. TensorFlow Java keeps the entire pipeline in a single JVM process.

### 6.4 Multi-surface delivery

The same core codec is exposed as CLI, REST, and UI. This serves three distinct adoption patterns (batch workflows, microservice integration, exploratory inspection) without code duplication.

---

## 7. Success Metrics

| Metric | Target | Measurement method |
|---|---|---|
| Compression ratio on reference dataset | Publicly published and reproducible | `sdc-bench` JMH report shipped with each release |
| Sustained encode throughput | ≥ 76.6 MB/s on commodity hardware | JMH benchmark on documented reference machine |
| Decode correctness | 100% bit-for-bit vs. fixtures | `sdc-core` validation suite, run on every CI build |
| SEG-Y Rev1 conformance | Full header + trace fidelity | Round-trip test against Phase 0 reference fixtures |
| Adoption signals | Forks, issues, external contributors | GitHub metrics tracked quarterly |

---

## 8. Release Phases

### Phase 1 — Months 0–12 (current)

- Productionize SeismicDataCompressor prototype into named `AI-Compress-Seismic-v1`
- Lock SEG-Y Rev1 read/write parity
- Ship reproducible JMH benchmark
- Public demo at `halotechlabs.com/demo/seismic-compressor`
- Open documentation and contributor guide

### Phase 2 — Months 12–24

- Pilot deployment with U.S.-based operator(s)
- Performance tuning targeting parity with C++ baseline
- Resolve parallel-mode race condition; enable parallel execution
- v2 release

### Phase 3 — Months 24–36

- Extended format support (SEG-D, Rev2 extensions where feasible)
- Lossy variant with documented perceptual fidelity bounds
- Cloud-native deployment templates (AWS, Azure) for direct operator adoption

---

## 9. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Throughput gap vs. C++ baseline limits adoption in latency-critical workflows | Document the gap openly; target parity in Phase 2; position v1 for the storage-cost / portability use case where the gap is acceptable |
| Parallel-mode race condition delays v2 | Race condition is isolated and documented; v1 ships single-threaded with no false claims |
| Format spec drift (SEG-Y Rev2 adoption) | Rev1 remains dominant in installed base; Rev2 extensions roadmapped for Phase 3 |
| Autoencoder model staleness | Model artifacts are versioned and re-trainable from open fixtures; retraining pipeline documented |

---

## 10. Out-of-Scope

- Real-time streaming compression
- Lossy compression with perceptual quality metrics (deferred to v2+)
- Proprietary vendor formats (Schlumberger, Halliburton, etc. internal formats)
- GPU-accelerated inference (v1 runs CPU TensorFlow Java)
- Encryption / DRM layer (encryption is the responsibility of the storage layer)

---

## 11. References

- SEG-Y Rev1 specification (SEG Technical Standards Committee)
- TensorFlow Java documentation
- JMH (Java Microbenchmark Harness) project documentation
- Existing public demo: `halotechlabs.com/demo/seismic-compressor`
- Internal: JMH benchmark report (Phase 0 baseline, Phase 1 v1 candidate)

---

*This PRD describes Phase 1 of a multi-phase implementation. The named project AI-Compress-Seismic-v1 is currently in active development as part of the petitioner's professional plan. The underlying prototype (SeismicDataCompressor) is operational with publicly verifiable benchmarks; the v1 productization formalizes that prototype against a documented specification.*

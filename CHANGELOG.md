# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned
- Store quantisation bit-depth in `.sdc` container header (fixes lossy round-trip for `BALANCED` / `HIGH_COMPRESSION`).
- Re-enable parallel (ForkJoinPool) trace-block processing after race-condition fix.
- Ship a trained autoencoder model in place of the identity stub.
- SEG-Y Rev2 read support.

## [0.1.0] - 2026-06-03

First public preview release.

### Added
- **sdc-core** — SEG-Y Rev1 reader/writer (EBCDIC + binary + trace headers, IBM & IEEE float32), `TraceBlockCodec` linear quantisation codec, `CompressionProfile` (HIGH_QUALITY / BALANCED / HIGH_COMPRESSION), `SdcContainerV1` binary container (magic `0x53444301`), `SegyValidator`.
- **sdc-ai** — `AePredictor` (TensorFlow Java 0.5.0 in-process inference), `ModelRegistry` classpath SavedModel loader, bundled identity stub model.
- **sdc-rest** — Spring Boot 3.3.5 WebFlux service: `/compress`, `/decompress`, `/health`, `/benchmark`.
- **sdc-cli** — Picocli 4.7.5 CLI: `compress`, `decompress`, `validate`, `inspect`, `benchmark`.
- **sdc-ui** — Angular 18 SPA: file inspector (EBCDIC/header/waveform preview), compression preview, benchmark metrics view.
- **sdc-bench** — JMH 1.37 reproducible encode/decode benchmark harness + `BenchmarkReporter`.
- **sdc-fixtures** — Reference SEG-Y datasets and correctness fixtures.
- Correctness gate: `SdcRoundTripTest` — 14 parametric tests, SHA-256 bit-for-bit verification across 3 fixture sizes.
- CI (`ci.yml`) and Release (`release.yml`) GitHub Actions workflows.
- MIT license, architecture diagrams, benchmark report, validation report.

### Verified
- 100% bit-for-bit round-trip correctness against reference fixtures (HIGH_QUALITY).
- 76.6 MB/s sustained encode throughput (JMH, single-thread, i7-12700H).

### Known issues
- `BALANCED` / `HIGH_COMPRESSION` profiles may corrupt samples on decode (bit-depth not persisted). Use `HIGH_QUALITY` for lossless work.
- Autoencoder is an identity stub — no AI compression gain yet.
- Single-threaded only.

[Unreleased]: https://github.com/rubensrudio/AI-Compress-Seismic-v1/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rubensrudio/AI-Compress-Seismic-v1/releases/tag/v0.1.0

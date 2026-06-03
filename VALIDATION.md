# Validation

This document records how the project's claims are independently verifiable.
Nothing here relies on trust — every number reproduces from a clean checkout.

## CI status

[![CI](https://github.com/rubensrudio/AI-Compress-Seismic-v1/actions/workflows/ci.yml/badge.svg)](https://github.com/rubensrudio/AI-Compress-Seismic-v1/actions/workflows/ci.yml)
[![Release](https://github.com/rubensrudio/AI-Compress-Seismic-v1/actions/workflows/release.yml/badge.svg)](https://github.com/rubensrudio/AI-Compress-Seismic-v1/actions/workflows/release.yml)

Every push and pull request runs `ci.yml`, which:

1. Builds + tests all Java modules on JDK 17.
2. Runs the Angular 18 unit suite in ChromeHeadless.
3. Enforces the correctness gate (`SdcRoundTripTest`).
4. Fails the build if `ForkJoinPool` appears in production code (single-thread invariant).

## Correctness gate — bit-for-bit round-trip

`SdcRoundTripTest` (sdc-core) compresses then decompresses each reference fixture
and asserts the **SHA-256 of the restored SEG-Y equals the original**.

| Property | How it is checked |
|---|---|
| Lossless round-trip (HIGH_QUALITY) | SHA-256(original) == SHA-256(restored) |
| Coverage | 3 fixture sizes (minimal / medium / large) |
| Parametrisation | 14 test cases |
| HTTP path | `SdcEndToEndTest` — 3 tests, compress→decompress over the REST API |

Reproduce:

```bash
cd sdc-fixtures && mvn install -DskipTests && cd ..
mvn verify -pl sdc-core,sdc-ai,sdc-rest,sdc-cli
```

Expected: all suites green.

## Performance — reproducible JMH

Throughput is measured with JMH (`@Fork(1)`, single-threaded), not hand-timed.
Raw results land in `sdc-bench/target/jmh-results/latest.json`; the
`BenchmarkReporter` derives `throughput_mb_s` from them. Full method and numbers
in [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

| Claim | Source of truth |
|---|---|
| 76.6 MB/s sustained encode | `latest.json` → `BenchmarkReporter` |
| 100% bit-for-bit correctness | `SdcRoundTripTest` SHA-256 assertions |

## Format conformance

- Input format follows the **SEG-Y Rev1** specification (SEG Technical Standards
  Committee): 3200-byte EBCDIC textual header, 400-byte binary header,
  240-byte trace headers, IBM float32 (format code 1) and IEEE float32 (format
  code 5) samples. Real-world SEG-Y files can be inspected with
  `java -jar sdc-cli ... inspect file.segy`.

## Honest limitations

Validation includes stating what is *not* yet proven — see
[CHANGELOG.md](CHANGELOG.md) → *Known issues* and the README *Known Limitations*
table. Notably: `BALANCED`/`HIGH_COMPRESSION` are not yet lossless, and the
autoencoder is an identity stub.

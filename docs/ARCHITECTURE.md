# Architecture

AI-Compress-Seismic-v1 is a multi-module Maven monorepo. Each module has a single
responsibility and depends only on the layers below it.

## Module dependency graph

```mermaid
graph TD
    UI["sdc-ui<br/>Angular 18 SPA"] -->|HTTP| REST["sdc-rest<br/>Spring WebFlux"]
    CLI["sdc-cli<br/>Picocli"] --> CORE
    REST --> CORE["sdc-core<br/>SEG-Y I/O + codec"]
    REST --> AI["sdc-ai<br/>TF Java autoencoder"]
    CLI --> AI
    CORE --> AI
    BENCH["sdc-bench<br/>JMH harness"] --> CORE
    BENCH --> AI
    CORE -.test fixtures.-> FIX["sdc-fixtures<br/>reference datasets"]
    FIX -.compile.-> CORE

    classDef app fill:#1f6feb,stroke:#0b3d91,color:#fff;
    classDef lib fill:#2da44e,stroke:#116329,color:#fff;
    classDef test fill:#8250df,stroke:#3c1e70,color:#fff;
    class UI,REST,CLI app;
    class CORE,AI lib;
    class BENCH,FIX test;
```

> The dotted edge between `sdc-core` and `sdc-fixtures` is a known compile/test
> cycle, broken at build time by installing `sdc-fixtures` first
> (`mvn install -pl sdc-fixtures -DskipTests`). See the root README, *Build & Test*.

## Compression pipeline

```mermaid
flowchart LR
    A[SEG-Y file] --> B["SegyIO.read()"]
    B --> C["SegyValidator<br/>magic / size / format code"]
    C --> D["TraceBlockCodec<br/>per-trace min/max + linear quantise"]
    D --> E["AePredictor<br/>residual prediction"]
    E --> F["SdcContainerV1<br/>magic 0x53444301 + metadata"]
    F --> G[".sdc file"]
```

## Decompression pipeline

```mermaid
flowchart LR
    G[".sdc file"] --> F["SdcContainerV1<br/>parse + verify magic"]
    F --> E["AePredictor<br/>reconstruct"]
    E --> D["TraceBlockCodec<br/>dequantise"]
    D --> B["SegyIO.write()"]
    B --> A[SEG-Y file]
```

## Request flow — REST /compress

```mermaid
sequenceDiagram
    participant C as Client / UI
    participant R as sdc-rest
    participant K as sdc-core codec
    participant M as sdc-ai predictor
    C->>R: POST /compress (octet-stream + X-Compression-Profile)
    R->>K: SegyValidator.validate(bytes)
    alt invalid SEG-Y
        R-->>C: 400 Bad Request
    else valid
        R->>K: TraceBlockCodec.encode(profile)
        R->>M: AePredictor.predict(residuals)
        R->>K: SdcContainerV1.write()
        R-->>C: 200 .sdc stream
    end
```

## Container format — `.sdc` v1

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 B | Magic | `0x53444301` ("SDC\x01") |
| 4 | 1 B | Version | `0x01` |
| 5 | … | Trace block metadata | count, dims, profile |
| … | … | Encoded trace blocks | quantised + residuals |

## Technology stack

| Layer | Tech |
|---|---|
| Core / AI / CLI | Java 17, TensorFlow Java 0.5.0, Picocli 4.7.5 |
| REST | Spring Boot 3.3.5 WebFlux (reactive) |
| UI | Angular 18 standalone + Angular Material |
| Bench | JMH 1.37 (`@Fork(1)` child JVM) |
| Build | Maven multi-module reactor |
| CI/CD | GitHub Actions (`ci.yml`, `release.yml`) |

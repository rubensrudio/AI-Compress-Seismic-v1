# Plano Técnico — AI-Compress-Seismic-v1: Sistema de Compressão Inteligente de Dados Sísmicos

## 1. Resumo Executivo

Este plano cobre a productização completa do protótipo `AI-Enhanced Seismic Data Compressor` (localizado em `D:\Sistemas\AI-Enhanced Seismic Data Compressor\sdc-parent`) no produto nomeado `AI-Compress-Seismic-v1`. O trabalho consiste em reorganizar o monorepo Maven multi-módulo existente, completar módulos ausentes (`sdc-rest` com endpoints de streaming, `sdc-bench`, `sdc-fixtures`, `sdc-ui` Angular 18), e elevar o módulo `sdc-ai` de placeholder para integração TensorFlow Java funcional com autoencoder treinado.

O protótipo já possui código funcional para leitura/escrita SEG-Y Rev1 (formatos 1 e 5), pipeline de codec encode/decode com delta encoding + quantização linear + DEFLATE, integração parcial com TensorFlow Java (sanidade de classpath), e um microserviço Spring Boot WebFlux (`sdc-svc`, a ser renomeado para `sdc-rest`). O delta entre protótipo e v1 é: (a) ativação real do autoencoder no pipeline de resíduos AI; (b) redesign do container `.sdc` para incluir magic number SDC, versão do codec e UUID do modelo; (c) adição dos módulos `sdc-bench` (JMH), `sdc-fixtures`, e `sdc-ui`; (d) novos endpoints REST de streaming (`POST /compress`, `POST /decompress` com `application/octet-stream`); (e) subcomandos CLI completos (`compress`, `decompress`, `validate`, `benchmark`, `inspect`).

O impacto técnico é alto: o produto entregará a operadores de energia uma ferramenta portátil, reproduzível e fiel ao formato industrial SEG-Y Rev1, com throughput verificado de ≥ 76,6 MB/s, validação automática de corretude bit-a-bit em CI e demo público em `halotechlabs.com/demo/seismic-compressor`.

Todo o trabalho acontece sobre a stack já estabelecida no protótipo: Java 17, Maven 3.x, Spring Boot 3.3.5 WebFlux, Picocli 4.7.5, TensorFlow Java 0.5.0, Apache Commons Compress, JMH 1.x, e Angular 18 para a UI. Nenhuma tecnologia nova será introduzida.

---

## 2. Premissas e Lacunas do Spec

### Lacunas explícitas (marcadas com `[LACUNA:]` no spec)

| ID | Lacuna | Impacto | Como será tratada |
|----|--------|---------|-------------------|
| L-01 | Ratio médio de compressão não especificado no PRD | O campo `compression_ratio` do payload `GET /benchmark` não tem valor de referência | Implementar o campo como `string` calculado dinamicamente a partir do último relatório JMH armazenado; publicar o valor apenas após o primeiro benchmark executado em hardware de referência. Deixar `null` até então |
| L-02 | Versão específica do TensorFlow Java não especificada no PRD | Dependência já resolvida no protótipo: `tensorflow-core-platform:0.5.0` | Adotar `0.5.0` conforme o protótipo; documentar no `sdc-ai/README` |
| L-03 | Hardware de referência do benchmark não especificado no PRD | Benchmarks não são comparáveis entre ambientes | Definir e documentar a máquina de referência (CPU modelo, RAM, tipo de storage) no README do `sdc-bench` antes da primeira release; bloquear publicação do relatório até documentação estar presente |

### Premissas adotadas

| ID | Premissa |
|----|----------|
| P-01 | O protótipo em `D:\Sistemas\AI-Enhanced Seismic Data Compressor\sdc-parent` é a base de código a ser portada; o diretório `D:\Sistemas\AI-Compress-Seismic-v1` é o destino da v1 e está atualmente vazio (apenas docs) |
| P-02 | O módulo `sdc-svc` do protótipo é renomeado e evoluído para `sdc-rest` na v1, sem quebra de API dos endpoints existentes (`/api/segy/compress`, `/api/segy/decompress`) |
| P-03 | O formato de container `.sdc` atual (magic `0x53444331`, 3 campos de 4 bytes cada) é estendido para incluir UUID do modelo AI e versão do codec; arquivos `.sdc` do protótipo não são retrocompatíveis com v1 |
| P-04 | A corretude exigida pelo spec (round-trip byte-a-byte) é para formato 5 (IEEE float32). Para formato 1 (IBM float32), a conversão dupla IEEE↔IBM introduz ruído de arredondamento documentado no código — isso é uma limitação conhecida do protótipo e precisa ser investigada antes de afirmar corretude bit-a-bit para format code 1 |
| P-05 | O autoencoder a ser integrado no `sdc-ai` é um modelo TensorFlow SavedModel já treinado externamente (Python/Keras). O spec define o pipeline de re-treinamento como `MEDIUM`; para v1, o artefato de modelo pré-treinado é distribuído junto com a release |
| P-06 | A atual API REST do protótipo aceita JSON com `sgyFile` (basename) e resolve arquivos em `sdc.data.root` configurado. O spec v1 exige streaming via `application/octet-stream`. Os dois modos coexistirão na v1: endpoints existentes mantidos para compatibilidade, novos endpoints de streaming adicionados |
| P-07 | O módulo `sdc-ui` Angular 18 é criado do zero; não existe UI no protótipo (apenas o backend) |
| P-08 | A suite de fixtures (`sdc-fixtures`) incluirá arquivos SEG-Y sintéticos gerados programaticamente e — se disponíveis publicamente — o dataset de referência de 1,71 GB do USGS ou similar; o CI executará apenas as fixtures sintéticas (< 10 min); o dataset completo é opcional e documentado |

---

## 3. Arquitetura Proposta

### 3.1 Visão de Componentes

```
ai-compress-seismic-v1/                   (Maven Parent POM — groupId: com.sdc, version: 1.0.0)
│
├── sdc-core/        [EXISTENTE — EVOLUIR]
│   Responsabilidade: Parser/writer SEG-Y Rev1, pipeline de codec, estruturas de domínio
│   Pacote raiz: com.sdc.core
│   Classes novas: SegyValidator, SdcContainerV1 (header expandido)
│   Classes alteradas: SdcHeader, SegyIO, TraceBlockCodec (integração com AePredictor)
│
├── sdc-ai/          [EXISTENTE — COMPLETAR]
│   Responsabilidade: Inferência TensorFlow Java in-process (autoencoder)
│   Pacote raiz: com.sdc.ai
│   Classes novas: AePredictor (substitui AeRuntime placeholder), ModelRegistry
│   Recurso: src/main/resources/models/<uuid>/saved_model.pb
│
├── sdc-rest/        [NOVO — baseado em sdc-svc]
│   Responsabilidade: Microserviço Spring Boot WebFlux
│   Pacote raiz: com.sdc.rest
│   Controllers novos: CompressStreamController, HealthController, BenchmarkController
│   Controllers herdados: SegyController (JSON API), SdcViewerController
│
├── sdc-cli/         [EXISTENTE — COMPLETAR]
│   Responsabilidade: CLI batch com Picocli
│   Pacote raiz: com.sdc.cli
│   Classes novas: CompressCommand, DecompressCommand, ValidateCommand,
│                  BenchmarkCommand, InspectCommand
│   Classe alterada: Main (adicionar subcomandos)
│
├── sdc-ui/          [NOVO — do zero]
│   Responsabilidade: Angular 18 SPA para inspeção e demo público
│   Stack: Angular 18, Angular Material (já usada em outros projetos do monorepo)
│   Hospedagem: halotechlabs.com/demo/seismic-compressor
│
├── sdc-bench/       [NOVO]
│   Responsabilidade: Harness JMH reproduzível
│   Pacote raiz: com.sdc.bench
│   Classes: SdcEncodeBenchmark, SdcDecodeBenchmark, BenchmarkReporter
│   Ferramenta: JMH 1.37
│
└── sdc-fixtures/    [NOVO]
    Responsabilidade: Fixtures de corretude e datasets de referência
    Conteúdo: arquivos SEG-Y sintéticos gerados por SegyFixtureGenerator,
              checksums SHA-256 para validação bit-a-bit,
              script de download opcional do dataset 1,71 GB
```

**Dependências entre módulos:**
- `sdc-core` — sem dependências internas
- `sdc-ai` — sem dependências internas (depende apenas de TF Java)
- `sdc-rest` — depende de `sdc-core` e `sdc-ai`
- `sdc-cli` — depende de `sdc-core` e `sdc-ai`
- `sdc-bench` — depende de `sdc-core` e `sdc-ai`
- `sdc-fixtures` — sem dependências internas (recursos estáticos + gerador)
- `sdc-ui` — consome a API REST do `sdc-rest` via HTTP

### 3.2 Fluxo Principal

**Encode (CLI / REST streaming):**
```
Arquivo SEG-Y Rev1
     |
     v
SegyIO.read()                        [sdc-core]
  -> SegyDataset {textualHeader, binaryHeader, traceHeaders, traces}
     |
     v
Header Preservation Pass             [sdc-core — SdcContainerV1]
  -> copia EBCDIC (3200 B) + binary header (400 B) + trace headers (240 B × N)
     |
     v
Para cada bloco de K traços:
  TraceBlockCodec.encodeStage1()     [sdc-core]
    -> normalização min/max
    -> delta encoding (Preprocessing.deltaEncode)
         |
         v
  AePredictor.encode(block)          [sdc-ai]
    -> autoencoder.forward(block) => residuals
         |
         v
  TraceBlockCodec.encodeStage2()     [sdc-core]
    -> quantização linear dos resíduos
    -> DEFLATE (java.util.zip.Deflater) sobre os resíduos quantizados
         |
         v
SdcContainerV1.write()               [sdc-core]
  -> magic(4B) + codec_version(2B) + model_uuid(16B)
  + headers_preservados + blocos_comprimidos
     |
     v
Arquivo .sdc
```

**Decode (inversão completa):**
```
Arquivo .sdc
     |
     v
SdcContainerV1.read()                [sdc-core]
  -> valida magic, versão, UUID do modelo
     |
     v
Para cada bloco comprimido:
  inflate() -> dequantiza() -> AePredictor.decode() -> delta_decode() -> denormaliza()
     |
     v
SegyIO.write()                       [sdc-core]
  -> headers preservados + samples reconstruídos
     |
     v
Arquivo SEG-Y Rev1 (byte-a-byte idêntico ao original para format code 5)
```

**Fluxo REST streaming (POST /compress):**
```
HTTP POST (application/octet-stream) -> DataBuffer Flux [Reactor]
  -> DataBufferUtils.join() -> byte[] completo
  -> SegyIO.read(tmpFile) -> pipeline encode -> SdcContainerV1
  -> return Flux<DataBuffer> (streaming response)
```

### 3.3 Decisões Arquiteturais

| # | Decisão | Alternativa rejeitada | Justificativa |
|---|---------|----------------------|---------------|
| DA-01 | Estender `SdcHeader` para `SdcContainerV1` com 3 campos novos (codec_version 2B, model_uuid 16B) em vez de criar formato novo | Criar `SdcHeaderV2` separado | O magic `SDC\x01` já existe no protótipo; incrementar a versão interna do container é a extensão natural. Arquivos `.sdc` do protótipo (version=1, sem UUID) são detectáveis pelo campo de versão e tratados como incompatíveis explicitamente |
| DA-02 | Integrar o autoencoder como `AePredictor` chamado a partir de `TraceBlockCodec` via interface `TracePredictor` | Chamar AeRuntime diretamente | Desacoplamento para permitir mock em testes unitários de `sdc-core` sem depender de TF Java; segue o padrão de injeção de dependência já usado no protótipo com `CompressionProfile` |
| DA-03 | REST streaming: receber body inteiro em memória (`DataBufferUtils.join`) antes de processar | Stream trace-a-trace via Flux | v1 é orientado a batch; o tamanho máximo de arquivo é configurável via `spring.codec.max-in-memory-size`; streaming verdadeiro introduz complexidade não justificada em v1 (fora do escopo de "real-time streaming") |
| DA-04 | `sdc-bench` como módulo Maven separado com JMH | JMH inline em testes de `sdc-core` | Relatórios JMH separados evitam contaminação com overhead do Maven Surefire; módulo standalone permite execução isolada no CI de release |
| DA-05 | `sdc-fixtures` como módulo Maven sem classes de produção (apenas recursos e um `SegyFixtureGenerator` de teste) | Fixtures embutidas nos testes de `sdc-core` | Fixtures reutilizáveis por `sdc-bench`, `sdc-core` e `sdc-rest` sem duplicação; possibilidade futura de adicionar datasets reais sem alterar o módulo de testes |
| DA-06 | Manter endpoints JSON existentes (`/api/segy/compress`, `/api/segy/decompress`) no `sdc-rest` | Substituir pelos endpoints de streaming | Compatibilidade retroativa com o demo público existente; os novos endpoints de streaming (`POST /compress`, `POST /decompress`) são adicionais |

**Trade-offs críticos:**
- DA-03 implica limite de memória: arquivo de 1,71 GB requer JVM com heap ≥ 3 GB para o endpoint REST. Documentar no `application.yml` e no README do `sdc-rest`.
- DA-02 implica que `sdc-core` deve ter dependência opcional (provided/test) em `sdc-ai` para testes; a dependência de produção fica no `sdc-rest` e `sdc-cli`.

---

## 4. Modelos de Dados

### 4.1 Estruturas de domínio existentes (sdc-core — manter)

| Classe | Responsabilidade |
|--------|-----------------|
| `SegyIO.SegyDataset` | Agregado imutável: headers (EBCDIC, binário, traços) + lista de `TraceBlock` + `TraceGrid` |
| `TraceBlock` | record: `traceId` (int), `samples` (float[]) |
| `CompressedTraceBlock` | record: `traceId`, `min`, `max`, `samplesPerTrace` (int), `payload` (byte[]) |
| `CompressionProfile` | Value object: `effectiveBits`, `deflaterLevel`, `fidelityPercentRequested` |
| `SegyIO.TraceMeta` | `traceIndex`, `inline`, `xline` |
| `SegyIO.TraceGrid` | Grid inline × crossline → traceIndex |

### 4.2 Estruturas novas (sdc-core)

**`SdcContainerV1`** — substitui/estende `SdcHeader`

| Campo | Tipo | Bytes | Descrição |
|-------|------|-------|-----------|
| magic | int | 4 | `0x53444301` — `SDC\x01` (alinhado com protótipo) |
| codec_version | short | 2 | Versão do pipeline (v1 = 1) |
| model_uuid_msb | long | 8 | UUID do artefato de modelo (MSB) |
| model_uuid_lsb | long | 8 | UUID do artefato de modelo (LSB) |
| segy_textual_header | byte[3200] | 3200 | EBCDIC preservado |
| segy_binary_header | byte[400] | 400 | Header binário preservado |
| trace_count | int | 4 | Número de traços |
| samples_per_trace | int | 4 | Amostras por traço |
| sample_format_code | int | 4 | Format code SEG-Y (1=IBM, 5=IEEE) |
| trace_headers_blob | byte[240 × N] | variável | Headers de traço preservados em sequência |
| compressed_blocks | CompressedTraceBlock[] | variável | Blocos comprimidos sequenciais |

**`SegyValidator`** — novo em `sdc-core`

Campos verificados: magic EBCDIC (primeiros bytes), comprimento mínimo do arquivo, validade do `samplesPerTrace` (> 0), `formatCode` suportado (1 ou 5), integridade estrutural de todos os traços até o fim do arquivo.

**`ModelRegistry`** — novo em `sdc-ai`

| Campo | Tipo | Descrição |
|-------|------|-----------|
| modelUuid | UUID | Identificador do artefato |
| modelPath | Path | Caminho para o diretório SavedModel |
| tfVersion | String | Versão TF Java utilizada |

### 4.3 Payload REST — GET /benchmark

```json
{
  "throughput_mb_s": 76.6,
  "dataset_size_gb": 1.71,
  "compression_ratio": null,
  "speedup_vs_prior_java_baseline": "148x-420x",
  "timestamp": "2026-05-16T00:00:00Z",
  "version": "1.0.0",
  "reference_hardware": "TBD — ver sdc-bench/README"
}
```

O campo `compression_ratio` começa como `null` e é populado após o primeiro benchmark executado no hardware de referência. O campo `reference_hardware` é uma string livre documentando CPU/RAM/storage.

### 4.4 Armazenamento de relatório JMH

O `sdc-bench` grava o relatório em `sdc-bench/target/jmh-results/latest.json`. O `sdc-rest` lê esse arquivo (caminho configurável via `sdc.bench.results.path`) no startup e serve via `GET /benchmark`. Se o arquivo não existir, o endpoint retorna 200 com todos os campos numéricos como `null`.

---

## 5. Contratos de API

### 5.1 Endpoints REST (sdc-rest)

**Novos endpoints (streaming)**

`POST /compress`
- Request: `Content-Type: application/octet-stream` — corpo do arquivo SEG-Y Rev1
- Response 200: `Content-Type: application/octet-stream` — arquivo `.sdc` comprimido
- Response 400: `application/json` `{"error": "Invalid SEG-Y Rev1 payload", "detail": "<motivo>"}`
- Response 500: `application/json` `{"error": "Codec failure", "detail": "<mensagem>"}`
- Header opcional de request: `X-Compression-Profile: HIGH_QUALITY | BALANCED | HIGH_COMPRESSION`

`POST /decompress`
- Request: `Content-Type: application/octet-stream` — corpo do arquivo `.sdc`
- Response 200: `Content-Type: application/octet-stream` — arquivo SEG-Y Rev1 restaurado
- Response 400: `application/json` `{"error": "Invalid SDC payload", "detail": "<motivo>"}`

**Endpoints existentes (JSON — mantidos)**

`POST /api/segy/compress`
- Request: `{"sgyFile": "basename.sgy", "fidelityPercent": 100.0, "profile": "HIGH_QUALITY"}`
- Response: `CompressResponse` (mantém campos atuais + adiciona `modelUuid`)

`POST /api/segy/decompress`
- Request: `{"sdcPath": "...", "templateSegyPath": "...", "outSegyPath": "..."}`
- Response: `DecompressResponse` (sem alteração)

`POST /api/segy/compress3d` — mantido sem alteração

`POST /api/segy/decompress3d` — mantido sem alteração

**Endpoints novos (metadados)**

`GET /benchmark`
- Response 200: JSON conforme seção 4.3

`GET /health`
- Response 200: `{"status": "UP", "codec": "OK", "model": "<uuid>"}` quando operacional
- Response 503: `{"status": "DOWN", "reason": "<motivo>"}` quando não pronto

**Endpoint de visualização (herdado)**

`GET /api/files` — listagem de arquivos SEG-Y no `sdc.data.root` (mantido)

### 5.2 CLI — Contratos de subcomandos

| Subcomando | Invocação | Saída esperada | Exit code |
|------------|-----------|----------------|-----------|
| `compress` | `sdc compress <input.segy> <output.sdc> [--profile HIGH_QUALITY]` | Progresso + ratio + throughput ao final | 0 ok, 1 erro |
| `decompress` | `sdc decompress <input.sdc> <output.segy> [--template <original.segy>]` | Mensagem de sucesso + tamanho restaurado | 0 ok, 1 erro |
| `validate` | `sdc validate <file.segy>` | OK ou lista de erros com byte offset | 0 válido, 1 inválido |
| `benchmark` | `sdc benchmark <dataset_path> [--output report.json]` | Executa JMH e salva JSON | 0 ok, 1 erro |
| `inspect` | `sdc inspect <file.segy>` | Tabela: traces, samples/trace, sample_interval, format_code, inline/xline range | 0 ok, 1 erro |

Para o subcomando `decompress`, o parâmetro `--template` é necessário quando o arquivo `.sdc` não embute os headers SEG-Y completos (compatibilidade com `.sdc` do protótipo). Na v1, os headers são embutidos no container, tornando o template opcional.

---

## 6. Componentes Afetados

### 6.1 Componentes existentes a evoluir (do protótipo)

| Arquivo/Módulo | Natureza da mudança | Risco |
|----------------|--------------------|----|
| `sdc-parent/pom.xml` | Adicionar módulos `sdc-rest`, `sdc-bench`, `sdc-fixtures`; atualizar versão para `1.0.0-SNAPSHOT` | Baixo |
| `sdc-core/SdcHeader.java` | Estender para `SdcContainerV1`: adicionar campos `codec_version` (short), `model_uuid_msb/lsb` (long), `segy_binary_header`, `trace_headers_blob`. Quebra de compatibilidade com `.sdc` do protótipo (intencional) | Médio |
| `sdc-core/SegyIO.java` | Completar suporte a formato 1 (IBM float): investigar e corrigir problema de round-trip para format code 1. Adicionar leitura de múltiplos SEG-Y logical files em sequência | Alto (corretude bit-a-bit) |
| `sdc-core/TraceBlockCodec.java` | Inserir chamada à interface `TracePredictor` entre o delta encoding e a quantização (estágio de resíduos AI). Manter retrocompatibilidade via `TracePredictor.identity()` para modo sem AI | Médio |
| `sdc-core/SegyCompression.java` | Substituir uso de `SdcFileWriter`/`SdcFileReader` por `SdcContainerV1`; ajustar assinaturas para incluir `CompressionProfile` e `TracePredictor` | Médio |
| `sdc-ai/AeRuntime.java` | Substituir por `AePredictor` que implementa `TracePredictor`; carregar SavedModel via `SavedModelBundle.load()`; implementar `encode(float[])` retornando resíduos e `decode(float[])` | Alto (nova funcionalidade crítica) |
| `sdc-cli/Main.java` | Refatorar de flags flat para subcomandos Picocli (`@Command` com `subcommands`); implementar 5 subcomandos como classes separadas | Médio |
| `sdc-svc/` (renomear para `sdc-rest/`) | Adicionar controllers `CompressStreamController` e `HealthController`; adicionar `BenchmarkController`; renomear pacote de `com.sdc.svc` para `com.sdc.rest` | Médio |

### 6.2 Componentes novos a criar

| Módulo | Componentes principais |
|--------|------------------------|
| `sdc-core` | `SegyValidator`, `TracePredictor` (interface), `SdcContainerV1` |
| `sdc-ai` | `AePredictor`, `ModelRegistry`, `src/main/resources/models/<uuid>/` |
| `sdc-rest` | Módulo completo criado do zero a partir de `sdc-svc`; `CompressStreamController`, `DecompressStreamController`, `HealthController`, `BenchmarkController`, `BenchmarkResultStore` |
| `sdc-cli` | `CompressCommand`, `DecompressCommand`, `ValidateCommand`, `BenchmarkCommand`, `InspectCommand` |
| `sdc-bench` | `SdcEncodeBenchmark`, `SdcDecodeBenchmark`, `BenchmarkReporter`, `pom.xml` com JMH 1.37 |
| `sdc-fixtures` | `SegyFixtureGenerator`, arquivos SEG-Y sintéticos em `src/test/resources/fixtures/`, checksums SHA-256 |
| `sdc-ui` | Projeto Angular 18 completo: `AppComponent`, `FileInspectorComponent`, `CompressionPreviewComponent`, `WaveformViewerComponent`, serviço `SdcApiService` |

---

## 7. Dependências Externas

| Dependência | Versão | Módulo(s) | Status |
|-------------|--------|-----------|--------|
| Java | 17 | Todos | Presente — nenhuma ação |
| Maven | 3.x | Build | Presente — nenhuma ação |
| TensorFlow Java (`tensorflow-core-platform`) | 0.5.0 | sdc-ai | Presente no protótipo — manter |
| TensorFlow Java (`tensorflow-framework`) | 0.5.0 | sdc-ai | Presente no protótipo — manter |
| Spring Boot WebFlux | 3.3.5 | sdc-rest | Presente no protótipo — manter |
| Spring Boot Actuator | 3.3.5 | sdc-rest | Presente no protótipo — manter |
| Micrometer Prometheus | managed | sdc-rest | Presente no protótipo — manter |
| SpringDoc OpenAPI WebFlux | 2.3.0 | sdc-rest | Presente no protótipo — manter |
| Picocli | 4.7.5 | sdc-cli | Presente no protótipo — manter |
| Apache Commons Compress | 1.26.1 | sdc-core | Presente no protótipo — manter |
| Reactor Core | 3.6.5 | sdc-core, sdc-rest | Presente no protótipo — manter |
| JMH (`jmh-core`, `jmh-generator-annprocess`) | 1.37 | sdc-bench | **Novo** — adicionar no pom.xml do sdc-bench |
| JUnit Jupiter | 5.10.2 | sdc-core (test) | Presente no protótipo — manter |
| Log4j2 SLF4J | 2.23.1 | sdc-core | Presente no protótipo — manter |
| SLF4J API | 2.0.13 | sdc-ai | Presente no protótipo — manter |
| Angular | 18 | sdc-ui | **Novo** — criar projeto via `ng new` |
| Angular Material | compatível com ng18 | sdc-ui | **Novo** — consistente com `halotechlabs`, `musicianjob-frontend` do monorepo |
| Node.js | 18+ | sdc-ui (build) | Presente no monorepo — nenhuma ação |
| Modelo autoencoder treinado (SavedModel) | — | sdc-ai | **Novo artefato** — requer treinamento externo em Python/Keras antes de integrar; é a dependência de caminho crítico para SDC-05 |

**Credenciais e infra:**
- Deploy do `sdc-ui` em `halotechlabs.com/demo/seismic-compressor` requer acesso ao servidor/pipeline de deploy existente para o domínio `halotechlabs.com` (já utilizado pelo monorepo)
- Nenhuma credencial nova de serviços terceiros é necessária para o pipeline de compressão

---

## 8. Áreas Sensíveis

- **Autenticação/autorização/sessão**: NAO. Os endpoints REST não exigem autenticação na v1. O demo público é aberto por design.

- **Pagamento/faturamento/cálculo financeiro real**: NAO.

- **Dados pessoais/sensíveis (PII, saúde, financeiro)**: NAO. O sistema processa dados sísmicos industriais (dados geofísicos brutos), sem PII.

- **Migration de dados em tabela com produção**: NAO. Sistema sem banco de dados relacional; o "migration" relevante é a quebra de formato binário `.sdc` entre protótipo e v1, que é intencional e documentada na seção 4.2.

- **Lógica regulatória/fiscal/compliance**: NAO.

- **Endpoint público sem autenticação prévia**: SIM.
  - `POST /compress` e `POST /decompress` são endpoints que processam uploads potencialmente grandes (até múltiplos GB) sem autenticação.
  - `GET /benchmark` e `GET /health` são endpoints de leitura abertos.
  - Componentes envolvidos: `sdc-rest/CompressStreamController`, `sdc-rest/DecompressStreamController`, `sdc-rest/HealthController`, `sdc-rest/BenchmarkController`.
  - Mitigação em v1: configurar `spring.codec.max-in-memory-size` para limite razoável (ex: 2 GB); documentar que proteção de rede (firewall, rate limiting) é responsabilidade da camada de infra. Autenticação adiada para v2.

- **Criptografia/manuseio de chaves**: NAO. O spec explicitamente coloca criptografia fora do escopo de v1.

- **Integração externa nova com terceiro**: SIM (parcial).
  - TensorFlow Java 0.5.0 já está no protótipo mas o autoencoder real (SavedModel pré-treinado) é um artefato externo novo que precisa ser produzido, versionado e distribuído.
  - Componentes envolvidos: `sdc-ai/AePredictor`, `sdc-ai/ModelRegistry`, `sdc-ai/src/main/resources/models/`.
  - Risco: o modelo é a dependência de caminho crítico; qualquer atraso no treinamento bloqueia SDC-03, SDC-04, SDC-05.

---

## 9. Riscos e Mitigações

| # | Risco | Probabilidade | Impacto | Mitigação |
|---|-------|---------------|---------|-----------|
| R-01 | Autoencoder não treinado ou com performance insuficiente de resíduos bloqueia SDC-03, SDC-04 e SDC-05 | Alta (artefato ainda não existe) | Crítico | Implementar `TracePredictor.identity()` como fallback no pipeline; o pipeline funciona sem AI (apenas delta + DEFLATE). Liberar milestones intermediárias com fallback. Definir sprint separado para treinamento do modelo |
| R-02 | Corretude bit-a-bit para format code 1 (IBM float): a conversão dupla IEEE↔IBM no protótipo introduz ruído de arredondamento, impedindo round-trip exato | Média | Alto | Investigar e corrigir antes de afirmar CA-01. Se a correção exata for inviável, documentar a limitação explicitamente no spec e nos testes, e excluir format code 1 do critério de corretude bit-a-bit em v1 |
| R-03 | Throughput de encode abaixo de 76,6 MB/s após adição da inferência AI no pipeline | Média | Alto | Executar benchmark antes e depois de integrar AI; perfilar com JMH; ajustar tamanho de bloco do autoencoder para minimizar overhead de chamada TF Java |
| R-04 | Limite de memória no endpoint REST streaming para arquivos > 1 GB | Alta (design atual carrega body inteiro) | Médio | Configurar `spring.codec.max-in-memory-size=2147483648` (2 GB); documentar limite; arquivos maiores requerem uso da CLI. Streaming verdadeiro é roadmap v2 |
| R-05 | Incompatibilidade de ABI entre TF Java 0.5.0 e JVM 17 em diferentes SOs | Baixa | Alto | TF Java 0.5.0 distribui binários nativos por plataforma via classificadores Maven; testar em Linux (CI) e documentar Windows/macOS como best-effort |
| R-06 | Deploy do sdc-ui em halotechlabs.com com dependência de infra existente não documentada | Média | Médio | Mapear processo de deploy existente (utilizado por projetos Angular do monorepo) antes de começar o sdc-ui; documentar no README do módulo |
| R-07 | CI com fixtures de 1,71 GB ultrapassa limite de 10 minutos | Alta se incluir dataset completo | Baixo | Usar apenas fixtures sintéticas no CI regular; dataset completo é opcional e documentado como execução local |
| R-08 | Race condition documentada no modo paralelo | N/A para v1 | — | Single-thread confirmado em v1; ForkJoinPool não utilizado; documentar na classe `TraceBlockCodec` com `@NotThreadSafe` |

---

## 10. Critérios de Aceite Técnicos

| ID | Critério | Como verificar |
|----|----------|---------------|
| TAC-01 | Round-trip SEG-Y Rev1 (format code 5) produz arquivo byte-a-byte idêntico ao original | Suite de fixtures em `sdc-fixtures`; `SdcRoundTripTest` generalizado para todas as fixtures sintéticas; SHA-256 comparado no CI |
| TAC-02 | `SegyValidator` rejeita arquivos corrompidos com mensagem de erro incluindo byte offset do primeiro problema | Testes unitários em `sdc-core` com fixtures corrompidas geradas pelo `SegyFixtureGenerator` |
| TAC-03 | `AePredictor.encode()` retorna resíduos mais compressíveis que as amostras brutas (ratio de DEFLATE sobre resíduos < ratio de DEFLATE sobre amostras brutas) | Teste de integração `sdc-ai` com fixture sintética de 1000 traços |
| TAC-04 | Throughput de encode ≥ 76,6 MB/s no dataset de referência medido por JMH | Relatório `sdc-bench/target/jmh-results/latest.json`; gate no CI de release |
| TAC-05 | `POST /compress` aceita SEG-Y binário e retorna `.sdc` decodificável para arquivo idêntico ao original | Teste de integração `sdc-rest` com arquivo de referência sintético |
| TAC-06 | `POST /decompress` retorna SEG-Y restaurado byte-a-byte idêntico ao original (para format code 5) | Idem |
| TAC-07 | `GET /health` retorna HTTP 200 `{"status":"UP"}` em ambiente com modelo carregado | Health check automático no CI após `docker compose up` ou `mvn spring-boot:run` |
| TAC-08 | `GET /benchmark` retorna JSON válido com todos os campos obrigatórios presentes (mesmo que `null` na primeira execução) | Teste de integração `sdc-rest` |
| TAC-09 | CLI: todos os 5 subcomandos executam contra arquivo de referência sintético sem erro e com exit code 0 | Teste funcional no CI via `java -jar sdc-cli.jar <subcomando> ...` |
| TAC-10 | Modelo AI carrega sem erro em JVM limpa sem TensorFlow externo instalado (`AePredictor.load()` sem exceção) | Teste de integração `sdc-ai` em ambiente de CI sem TF instalado no SO |
| TAC-11 | `sdc-ui` exibe headers EBCDIC e binário de arquivo SEG-Y de referência carregado via upload | Teste manual + smoke test automatizado (Cypress ou Playwright) no CI de deploy |
| TAC-12 | Relatório JMH é gerado e publicado como artefato no pipeline de CI/CD de release | Verificação no pipeline; arquivo `latest.json` commitado ou publicado como release asset |
| TAC-13 | Suite de corretude completa executa em < 10 minutos no CI | Medição de tempo no CI com apenas fixtures sintéticas |
| TAC-14 | Nenhum `ForkJoinPool` ou thread pool é instanciado no caminho de encode/decode | Grep no código + revisão de código obrigatória antes de merge |

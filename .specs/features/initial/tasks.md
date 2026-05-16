# Tarefas — AI-Compress-Seismic-v1: Sistema de Compressão Inteligente de Dados Sísmicos

## Resumo

- Total de tarefas: 34
- Tarefas paralelizáveis: 20
- Caminho crítico estimado: TASK-001 → TASK-002 → TASK-003 → TASK-007 → TASK-008 → TASK-009 → TASK-010 → TASK-018 → TASK-019 → TASK-020 → TASK-033

### Distribuição por Risco
- Crítico: 0
- Alto: 18
- Médio: 12
- Baixo: 4

### Distribuição por QA
- full: 18
- wave: 12
- smoke: 3
- auto: 1

### Distribuição por Perfil
- frontend: 6
- backend: 25
- infra: 3
- misto: 0

## Legenda

- `[P]` = Paralelizável com outras `[P]` que não compartilham arquivos
- Esforço: S / M / L
- Tipo: lógica-negócio | crud-padrão | ui-puro | integração-externa | migration | config | refactor | infra | teste
- Risco: crítico | alto | médio | baixo
- QA: full | wave | smoke | auto
- Perfil: frontend | backend | infra | misto

---

## Tarefas

### TASK-001 — Estrutura Maven Parent POM e módulos na v1

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: —
- **Tipo**: config
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-parent/pom.xml`
- **Descrição**: Criar o `pom.xml` raiz do monorepo `AI-Compress-Seismic-v1` (groupId `com.sdc`, version `1.0.0-SNAPSHOT`) declarando os módulos `sdc-core`, `sdc-ai`, `sdc-rest`, `sdc-cli`, `sdc-bench`, `sdc-fixtures` e `sdc-ui` (este último como módulo de build opcional). Definir `dependencyManagement` com as versões canônicas de todas as dependências listadas no plano (TF Java 0.5.0, Spring Boot 3.3.5, Picocli 4.7.5, JMH 1.37, etc.). Portar do protótipo `D:\Sistemas\AI-Enhanced Seismic Data Compressor\sdc-parent\pom.xml`.
- **Critério de verificação**: `mvn validate` executa sem erros na raiz do projeto; todos os módulos são reconhecidos pelo reactor Maven.

---

### TASK-002 [P] — Portar sdc-core: classes de domínio e utilitários existentes

- **Esforço**: M
- **Paralelizável**: Sim
- **Depende de**: TASK-001
- **Tipo**: refactor
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-core/pom.xml`
  - `sdc-core/src/main/java/com/sdc/core/SegyIO.java`
- **Descrição**: Criar o módulo `sdc-core` com seu `pom.xml` e portar as classes existentes do protótipo sem alteração funcional: `SegyIO`, `TraceBlock`, `CompressedTraceBlock`, `CompressionProfile`, `Preprocessing`, `LinearQuantizer`, `SdcSampleGenerator`, `SdcCompressedSampleGenerator`, `CubeIndexEntry`, `VolumeBlock3D`, `VolumeBlock3DCodec`, `VolumeBlock3DCompressor`, `VolumeBlockCache`, `SdcFileReader`, `SdcFileWriter`, `Sdc3DFileReader`, `Sdc3DFileWriter`, `SegyCompression`, `SegyDump`. Portar também os testes existentes (`TraceBlockTest`, `SdcRoundTripTest`, `PreprocessingTest`, `LinearQuantizerTest`, `TraceBlockCodecTest`).
- **Critério de verificação**: `mvn test -pl sdc-core` passa com todos os testes do protótipo verdes; nenhuma classe de produção alterada nesta task.

---

### TASK-003 [P] — sdc-core: interface TracePredictor e implementação identity

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-002
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-core/src/main/java/com/sdc/core/TracePredictor.java`
  - `sdc-core/src/main/java/com/sdc/core/TraceBlockCodec.java`
- **Descrição**: Criar a interface `TracePredictor` com métodos `encode(float[] samples): float[]` e `decode(float[] residuals): float[]` mais factory method estático `TracePredictor.identity()` (retorna as amostras sem alteração). Modificar `TraceBlockCodec` para receber `TracePredictor` via construtor (injeção de dependência) e chamar `predictor.encode()` entre o delta encoding e a quantização no estágio de resíduos AI. Anotar `TraceBlockCodec` com `@NotThreadSafe`. Retrocompatibilidade garantida pelo uso de `identity()` como default.
- **Critério de verificação**: Testes existentes de `TraceBlockCodecTest` continuam verdes com `identity()`; novo teste unitário confirma que o predictor é chamado no slot correto do pipeline.

---

### TASK-004 [P] — sdc-core: SdcContainerV1 — novo formato de container .sdc

- **Esforço**: M
- **Paralelizável**: Sim
- **Depende de**: TASK-002
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-core/src/main/java/com/sdc/core/SdcContainerV1.java`
  - `sdc-core/src/main/java/com/sdc/core/SdcHeader.java`
- **Descrição**: Criar `SdcContainerV1` com os campos do plano: `magic` (4B = `0x53444301`), `codec_version` (2B), `model_uuid_msb/lsb` (8B cada), `segy_textual_header` (3200B), `segy_binary_header` (400B), `trace_count` (4B), `samples_per_trace` (4B), `sample_format_code` (4B), `trace_headers_blob` (240×N bytes), `compressed_blocks`. Implementar `write(OutputStream)` e `read(InputStream)` com validação de magic e versão. Arquivos `.sdc` do protótipo (sem UUID) devem ser detectados e rejeitados com exceção descritiva. `SdcHeader` existente pode ser marcado como `@Deprecated` apontando para `SdcContainerV1`.
- **Critério de verificação**: Teste unitário escreve e relê um `SdcContainerV1` sintético; magic e UUID são preservados byte-a-byte; leitura de arquivo `.sdc` do protótipo lança `IllegalArgumentException` com mensagem indicando incompatibilidade.

---

### TASK-005 [P] — sdc-core: SegyValidator

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-002
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-core/src/main/java/com/sdc/core/SegyValidator.java`
  - `sdc-core/src/test/java/com/sdc/core/SegyValidatorTest.java`
- **Descrição**: Criar `SegyValidator` que verifica: (a) comprimento mínimo do arquivo (≥ 3600 bytes para EBCDIC + header binário); (b) presença do prefixo EBCDIC esperado (primeiros 3200 bytes); (c) `samplesPerTrace > 0` no header binário; (d) `formatCode` suportado (1 ou 5); (e) integridade estrutural de todos os traços até EOF, calculando o offset esperado de cada traço. Em caso de falha, lança `SegyValidationException` com byte offset do primeiro problema detectado. Testes com arquivos sintéticos válidos e com corrupção injetada em diferentes posições.
- **Critério de verificação**: Testes unitários passam para fixtures válidas (exit 0) e para arquivos com corrupção injetada em header EBCDIC, header binário e meio de arquivo (exceção com offset correto).

---

### TASK-006 [P] — sdc-core: investigar e corrigir round-trip IBM float (format code 1)

- **Esforço**: M
- **Paralelizável**: Sim
- **Depende de**: TASK-002
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-core/src/main/java/com/sdc/core/SegyIO.java`
  - `sdc-core/src/test/java/com/sdc/core/SegyIOFormatCode1Test.java`
- **Descrição**: Investigar a conversão dupla IEEE↔IBM float32 em `SegyIO` para format code 1. Se a correção exata for viável (precisão de representação IBM float32 ≥ IEEE float32 para todos os ranges), corrigir. Se inviável, documentar explicitamente no código com um comentário `// KNOWN LIMITATION: format code 1 round-trip may differ by IEEE754 rounding epsilon` e excluir format code 1 da asserção de corretude bit-a-bit, restringindo CA-01 a format code 5. Criar teste de regressão que documenta o comportamento atual e falha se o comportamento piorar.
- **Critério de verificação**: Teste de regressão para format code 1 passa; comportamento documentado no teste; `SdcRoundTripTest` para format code 5 continua 100% verde.

---

### TASK-007 — sdc-core: integrar SdcContainerV1 em SegyCompression

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-003, TASK-004
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-core/src/main/java/com/sdc/core/SegyCompression.java`
  - `sdc-core/src/test/java/com/sdc/core/SegyCompressionV1Test.java`
- **Descrição**: Refatorar `SegyCompression` para substituir uso de `SdcFileWriter`/`SdcFileReader` por `SdcContainerV1`. Atualizar assinaturas de `compress()` e `decompress()` para aceitar `CompressionProfile` e `TracePredictor`. Garantir que o pipeline completo (encode + decode) produz arquivo byte-a-byte idêntico para format code 5 usando `TracePredictor.identity()`. Adicionar suporte à leitura de múltiplos SEG-Y logical files em sequência via `SegyIO`.
- **Critério de verificação**: Teste de integração `SegyCompressionV1Test` executa round-trip completo em fixture sintética (format code 5) e confirma identicidade byte-a-byte; `mvn test -pl sdc-core` verde.

---

### TASK-008 — sdc-ai: pom.xml e portar AeRuntime existente

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-001
- **Tipo**: config
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-ai/pom.xml`
  - `sdc-ai/src/main/java/com/sdc/ai/AeRuntime.java`
- **Descrição**: Criar o módulo `sdc-ai` com `pom.xml` declarando dependências `tensorflow-core-platform:0.5.0` e `tensorflow-framework:0.5.0`. Portar `AeRuntime.java` existente do protótipo como ponto de partida. Configurar o módulo para empacotar os binários nativos TF Java via classificadores Maven (plataforma Linux x86_64 obrigatória para CI; Windows e macOS como best-effort). Documentar versão TF Java 0.5.0 no `sdc-ai/README.md`.
- **Critério de verificação**: `mvn compile -pl sdc-ai` conclui sem erro; `AeRuntime` compila com as dependências TF Java resolvidas.

---

### TASK-009 — sdc-ai: ModelRegistry e estrutura de recursos do SavedModel

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-008
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-ai/src/main/java/com/sdc/ai/ModelRegistry.java`
  - `sdc-ai/src/main/resources/models/.gitkeep`
- **Descrição**: Criar `ModelRegistry` com campos `modelUuid` (UUID), `modelPath` (Path) e `tfVersion` (String). Implementar `ModelRegistry.fromClasspath(UUID uuid)` que resolve o caminho `models/<uuid>/saved_model.pb` no classpath. Implementar `ModelRegistry.fromPath(Path dir)` para modelos em caminho externo configurável. Criar a estrutura de diretório `src/main/resources/models/` com `.gitkeep` e documentar o processo de adição de novos artefatos de modelo. O UUID do modelo bundled será definido como constante em `ModelRegistry.BUNDLED_MODEL_UUID`.
- **Critério de verificação**: Teste unitário `ModelRegistryTest` verifica que `fromPath()` lança `ModelNotFoundException` descritiva quando o diretório não existe; `fromClasspath()` retorna o `ModelRegistry` correto quando o recurso está presente.

---

### TASK-010 — sdc-ai: AePredictor — implementação real com TensorFlow Java

- **Esforço**: L
- **Paralelizável**: Não
- **Depende de**: TASK-003, TASK-009
- **Tipo**: integração-externa
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-ai/src/main/java/com/sdc/ai/AePredictor.java`
  - `sdc-ai/src/test/java/com/sdc/ai/AePredictorTest.java`
- **Descrição**: Criar `AePredictor` implementando `TracePredictor` (de `sdc-core`). Usar `SavedModelBundle.load(modelPath, "serve")` para carregar o modelo. Implementar `encode(float[] samples): float[]` que executa o encoder do autoencoder e retorna os resíduos; implementar `decode(float[] residuals): float[]` que executa o decoder. Carregar modelo no construtor via `ModelRegistry`; lançar exceção descritiva se o SavedModel não for encontrado. Enquanto o artefato de modelo real não estiver disponível, usar um SavedModel stub de identidade gerado em Python para desbloquear o pipeline (R-01 do plano).
- **Critério de verificação**: `AePredictorTest` carrega o stub de identidade sem erro em ambiente limpo (sem TF instalado no SO); `encode()` seguido de `decode()` com stub retorna amostras dentro de epsilon da entrada; teste de integração passa com `mvn test -pl sdc-ai`.

---

### TASK-011 — sdc-fixtures: pom.xml, SegyFixtureGenerator e fixtures sintéticas

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-002
- **Tipo**: teste
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-fixtures/pom.xml`
  - `sdc-fixtures/src/test/java/com/sdc/fixtures/SegyFixtureGenerator.java`
- **Descrição**: Criar módulo `sdc-fixtures` sem classes de produção. Implementar `SegyFixtureGenerator` que gera programaticamente arquivos SEG-Y Rev1 sintéticos: (a) arquivo mínimo válido (1 traço, format code 5); (b) arquivo médio (100 traços, format code 5); (c) arquivo com múltiplos logical files; (d) arquivos corrompidos com corrupção injetada em posições específicas (header EBCDIC, header binário, meio de arquivo). Gerar checksums SHA-256 para cada fixture válida em `src/test/resources/fixtures/checksums.sha256`. Criar script `download-reference-dataset.sh` documentando como baixar o dataset de 1,71 GB do USGS (execução opcional, não no CI padrão).
- **Critério de verificação**: `mvn test -pl sdc-fixtures` gera todas as fixtures sintéticas e verifica os checksums em < 2 minutos; fixtures estão disponíveis no classpath de teste dos demais módulos via dependência Maven.

---

### TASK-012 — sdc-core: suite de corretude round-trip com fixtures de sdc-fixtures

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-007, TASK-011
- **Tipo**: teste
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-core/src/test/java/com/sdc/core/SdcRoundTripTest.java`
  - `sdc-core/pom.xml`
- **Descrição**: Estender `SdcRoundTripTest` para iterar sobre todas as fixtures sintéticas válidas geradas pelo `SegyFixtureGenerator`. Para cada fixture, executar o pipeline completo encode → decode usando `SegyCompression` com `TracePredictor.identity()` e comparar o SHA-256 do arquivo de saída com o checksum da fixture. Adicionar dependência `sdc-fixtures` como `test` scope no `pom.xml` do `sdc-core`. Garantir execução em < 5 minutos.
- **Critério de verificação**: `mvn test -pl sdc-core` com todos os testes verdes; `SdcRoundTripTest` comprova corretude bit-a-bit para format code 5 em todas as fixtures sintéticas.

---

### TASK-013 — sdc-bench: pom.xml e harness JMH (encode + decode)

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-007, TASK-011
- **Tipo**: teste
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-bench/pom.xml`
  - `sdc-bench/src/main/java/com/sdc/bench/SdcEncodeBenchmark.java`
- **Descrição**: Criar módulo `sdc-bench` com dependências `jmh-core:1.37` e `jmh-generator-annprocess:1.37`. Implementar `SdcEncodeBenchmark` com método `@Benchmark` que executa encode completo sobre o dataset de referência (usando fixture sintética média como proxy no CI). Implementar `SdcDecodeBenchmark` analogamente. Configurar `@BenchmarkMode(Mode.Throughput)` e `@OutputTimeUnit(TimeUnit.SECONDS)`. O resultado é gravado em `target/jmh-results/latest.json` via `ResultFormatType.JSON`.
- **Critério de verificação**: `mvn verify -pl sdc-bench` executa o harness JMH e gera `target/jmh-results/latest.json` com campos `primaryMetric.score` e `primaryMetric.scoreUnit` presentes.

---

### TASK-014 — sdc-bench: BenchmarkReporter e documentação de hardware de referência

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-013
- **Tipo**: lógica-negócio
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-bench/src/main/java/com/sdc/bench/BenchmarkReporter.java`
  - `sdc-bench/README.md`
- **Descrição**: Criar `BenchmarkReporter` que lê `target/jmh-results/latest.json`, extrai `throughput_mb_s`, `dataset_size_gb`, `compression_ratio` e `speedup_vs_prior_java_baseline`, e produz um relatório formatado (texto e JSON). Preencher `sdc-bench/README.md` com: especificação da máquina de referência (campo obrigatório — CPU modelo, RAM, tipo de storage, SO), instruções para reproduzir o benchmark, e interpretação dos resultados alvo (≥ 76,6 MB/s, speedup 148×–420×).
- **Critério de verificação**: `BenchmarkReporter` lê `latest.json` e produz saída sem exceção; README tem seção "Hardware de Referência" preenchida com pelo menos CPU e RAM.

---

### TASK-015 — sdc-rest: criar módulo a partir de sdc-svc, renomear pacote

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-001
- **Tipo**: refactor
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-rest/pom.xml`
  - `sdc-rest/src/main/java/com/sdc/rest/SdcRestApplication.java`
- **Descrição**: Criar o módulo `sdc-rest` portando todo o conteúdo de `sdc-svc` do protótipo. Renomear pacote base de `com.sdc.svc` para `com.sdc.rest`. Portar `SdcServiceApplication`, `SegyController`, `SdcViewerController`, `SdcViewerService`, `SegyCompressionService`, `SegyDtos`, `OpenApiConfig` e testes existentes. Atualizar `pom.xml` com dependências de `sdc-core` e `sdc-ai`. Configurar `application.yml` com `spring.codec.max-in-memory-size: 2147483648` (2 GB) e `sdc.bench.results.path` apontando para `sdc-bench/target/jmh-results/latest.json`.
- **Critério de verificação**: `mvn test -pl sdc-rest` verde com todos os testes portados do protótipo; aplicação inicia sem erro via `mvn spring-boot:run -pl sdc-rest`.

---

### TASK-016 — sdc-rest: HealthController (GET /health)

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-015
- **Tipo**: crud-padrão
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-rest/src/main/java/com/sdc/rest/HealthController.java`
  - `sdc-rest/src/test/java/com/sdc/rest/HealthControllerTest.java`
- **Descrição**: Criar `HealthController` com `@GetMapping("/health")`. Resposta 200: `{"status": "UP", "codec": "OK", "model": "<uuid>"}` quando o `AePredictor` carrega sem erro. Resposta 503: `{"status": "DOWN", "reason": "<motivo>"}` quando o modelo não está disponível (propagado via `ApplicationContext` ou bean de status). Teste de unidade com `@WebFluxTest` verifica os dois cenários.
- **Critério de verificação**: `HealthControllerTest` verifica HTTP 200 com payload correto quando modelo disponível; verifica HTTP 503 quando modelo ausente; `mvn test -pl sdc-rest` verde.

---

### TASK-017 — sdc-rest: BenchmarkController (GET /benchmark) e BenchmarkResultStore

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-015
- **Tipo**: crud-padrão
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-rest/src/main/java/com/sdc/rest/BenchmarkController.java`
  - `sdc-rest/src/main/java/com/sdc/rest/BenchmarkResultStore.java`
- **Descrição**: Criar `BenchmarkResultStore` que lê `sdc.bench.results.path` no startup. Se o arquivo não existir, todos os campos numéricos são `null`. Criar `BenchmarkController` com `@GetMapping("/benchmark")` que serve o payload JSON: `throughput_mb_s`, `dataset_size_gb`, `compression_ratio`, `speedup_vs_prior_java_baseline`, `timestamp`, `version`, `reference_hardware`. Teste de integração com arquivo `latest.json` de fixture.
- **Critério de verificação**: `GET /benchmark` retorna HTTP 200 com JSON válido contendo todos os campos obrigatórios (mesmo que `null`); quando `latest.json` existe, os valores são lidos corretamente.

---

### TASK-018 — sdc-rest: CompressStreamController (POST /compress)

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-015, TASK-010
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-rest/src/main/java/com/sdc/rest/CompressStreamController.java`
  - `sdc-rest/src/test/java/com/sdc/rest/CompressStreamControllerTest.java`
- **Descrição**: Criar `CompressStreamController` com `@PostMapping("/compress")` que aceita `Content-Type: application/octet-stream`. Usar `DataBufferUtils.join()` para agregar o Flux de DataBuffers em `byte[]`. Salvar em arquivo temporário, executar `SegyValidator` (retorna 400 em falha com JSON de erro), executar `SegyCompression.compress()` com `AePredictor`, retornar o arquivo `.sdc` como `Flux<DataBuffer>`. Suportar header opcional `X-Compression-Profile`. Retornar HTTP 500 em falha interna. Documentar o limite de 2 GB no Javadoc e no `application.yml`.
- **Critério de verificação**: Teste de integração com fixture sintética (format code 5): POST retorna HTTP 200 com body `.sdc` decodificável; POST com payload inválido retorna HTTP 400 com JSON de erro; `mvn test -pl sdc-rest` verde.

---

### TASK-019 — sdc-rest: DecompressStreamController (POST /decompress)

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-015, TASK-010
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-rest/src/main/java/com/sdc/rest/DecompressStreamController.java`
  - `sdc-rest/src/test/java/com/sdc/rest/DecompressStreamControllerTest.java`
- **Descrição**: Criar `DecompressStreamController` com `@PostMapping("/decompress")` que aceita `Content-Type: application/octet-stream` (arquivo `.sdc`). Validar magic number e versão do container (retorna 400 se inválido). Executar `SegyCompression.decompress()` com `AePredictor`. Retornar o SEG-Y restaurado como `Flux<DataBuffer>`. Teste de integração verifica que o arquivo retornado é byte-a-byte idêntico ao original (para format code 5).
- **Critério de verificação**: Teste de integração: POST com arquivo `.sdc` válido retorna HTTP 200 com SEG-Y byte-a-byte idêntico ao original; POST com payload inválido retorna HTTP 400; `mvn test -pl sdc-rest` verde.

---

### TASK-020 — sdc-rest: teste de integração end-to-end compress → decompress

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-018, TASK-019
- **Tipo**: teste
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-rest/src/test/java/com/sdc/rest/SdcEndToEndTest.java`
  - `sdc-rest/src/test/resources/fixtures/`
- **Descrição**: Criar `SdcEndToEndTest` que: (1) faz POST para `/compress` com fixture sintética (format code 5); (2) usa o corpo da resposta como entrada para POST `/decompress`; (3) compara SHA-256 do SEG-Y restaurado com o checksum da fixture original. Também valida que `GET /health` retorna UP após startup e que `GET /benchmark` retorna JSON válido. Copiar fixtures necessárias de `sdc-fixtures` no classpath de teste via dependência.
- **Critério de verificação**: `SdcEndToEndTest` passa 100% em `mvn verify -pl sdc-rest`; tempo de execução < 60 segundos com fixture sintética.

---

### TASK-021 — sdc-cli: pom.xml e portar Main existente com estrutura de subcomandos

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-001
- **Tipo**: refactor
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-cli/pom.xml`
  - `sdc-cli/src/main/java/com/sdc/cli/Main.java`
- **Descrição**: Criar módulo `sdc-cli` portando `Main.java` e `pom.xml` do protótipo. Refatorar `Main` de flags flat para estrutura Picocli com `@Command(name = "sdc", subcommands = {CompressCommand.class, DecompressCommand.class, ValidateCommand.class, BenchmarkCommand.class, InspectCommand.class})`. Configurar empacotamento como uber-JAR via `maven-shade-plugin` ou `spring-boot-maven-plugin`. Declarar dependências de `sdc-core` e `sdc-ai`.
- **Critério de verificação**: `java -jar sdc-cli.jar --help` exibe os 5 subcomandos; `mvn package -pl sdc-cli` produz o uber-JAR sem erro.

---

### TASK-022 [P] — sdc-cli: CompressCommand

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-021, TASK-007, TASK-010
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-cli/src/main/java/com/sdc/cli/CompressCommand.java`
  - `sdc-cli/src/test/java/com/sdc/cli/CompressCommandTest.java`
- **Descrição**: Criar `CompressCommand` com `@Command(name = "compress")`. Parâmetros: `<input.segy>` (obrigatório), `<output.sdc>` (obrigatório), `--profile [HIGH_QUALITY|BALANCED|HIGH_COMPRESSION]` (opcional, default BALANCED). Executar `SegyValidator` antes do encode; em falha, exibir erro e sair com código 1. Ao final, exibir progresso, ratio de compressão e throughput (MB/s). Sair com código 0 em sucesso.
- **Critério de verificação**: Teste funcional com fixture sintética: `CompressCommand` produz arquivo `.sdc` que pode ser decodificado para SEG-Y idêntico ao original; exit code 0 em sucesso, 1 em falha.

---

### TASK-023 [P] — sdc-cli: DecompressCommand

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-021, TASK-007, TASK-010
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-cli/src/main/java/com/sdc/cli/DecompressCommand.java`
  - `sdc-cli/src/test/java/com/sdc/cli/DecompressCommandTest.java`
- **Descrição**: Criar `DecompressCommand` com `@Command(name = "decompress")`. Parâmetros: `<input.sdc>` (obrigatório), `<output.segy>` (obrigatório), `--template <original.segy>` (opcional — para compatibilidade com `.sdc` sem headers embutidos). Validar magic do container antes de processar. Exibir tamanho restaurado ao final. Exit code 0 / 1.
- **Critério de verificação**: Teste funcional: arquivo produzido por `CompressCommand` é descomprimido para SEG-Y byte-a-byte idêntico ao original (format code 5); exit code correto nos dois cenários.

---

### TASK-024 [P] — sdc-cli: ValidateCommand

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-021, TASK-005
- **Tipo**: lógica-negócio
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-cli/src/main/java/com/sdc/cli/ValidateCommand.java`
  - `sdc-cli/src/test/java/com/sdc/cli/ValidateCommandTest.java`
- **Descrição**: Criar `ValidateCommand` com `@Command(name = "validate")`. Parâmetro: `<file.segy>` (obrigatório). Chamar `SegyValidator.validate()`; em sucesso, exibir "OK — arquivo SEG-Y Rev1 válido"; em falha, exibir lista de erros com byte offset. Exit code 0 se válido, 1 se inválido. Testar com fixtures válidas e com arquivos corrompidos.
- **Critério de verificação**: Fixtures sintéticas válidas: exit 0 + mensagem OK; arquivo corrompido: exit 1 + mensagem com byte offset; teste funcional passa.

---

### TASK-025 [P] — sdc-cli: InspectCommand

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-021, TASK-002
- **Tipo**: crud-padrão
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-cli/src/main/java/com/sdc/cli/InspectCommand.java`
  - `sdc-cli/src/test/java/com/sdc/cli/InspectCommandTest.java`
- **Descrição**: Criar `InspectCommand` com `@Command(name = "inspect")`. Parâmetro: `<file.segy>` (obrigatório). Usar `SegyIO.read()` para extrair metadados e exibir tabela com: número de traços, samples/trace, intervalo de amostragem (ms), format code, inline/xline range (se presente no header de traço), data de aquisição (se presente no header EBCDIC). Exit code 0 / 1.
- **Critério de verificação**: Teste funcional com fixture sintética exibe todos os campos esperados na stdout; exit code 0.

---

### TASK-026 [P] — sdc-cli: BenchmarkCommand

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-021, TASK-013
- **Tipo**: crud-padrão
- **Risco**: médio
- **QA**: wave
- **Perfil**: backend
- **Arquivos**:
  - `sdc-cli/src/main/java/com/sdc/cli/BenchmarkCommand.java`
  - `sdc-cli/src/test/java/com/sdc/cli/BenchmarkCommandTest.java`
- **Descrição**: Criar `BenchmarkCommand` com `@Command(name = "benchmark")`. Parâmetros: `<dataset_path>` (obrigatório), `--output <report.json>` (opcional, default `./jmh-results.json`). Invoca o harness JMH de `sdc-bench` via reflection ou fork de processo (`BenchmarkReporter`), grava o relatório JSON no caminho configurado. Exit code 0 / 1.
- **Critério de verificação**: Teste funcional com fixture sintética: relatório JSON é gerado em `--output` especificado; campos obrigatórios presentes; exit code 0.

---

### TASK-027 — sdc-ui: scaffold Angular 18 + Angular Material

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-001
- **Tipo**: config
- **Risco**: baixo
- **QA**: smoke
- **Perfil**: frontend
- **Arquivos**:
  - `sdc-ui/package.json`
  - `sdc-ui/angular.json`
- **Descrição**: Criar o projeto Angular 18 em `sdc-ui/` via `ng new sdc-ui --routing --style=scss --standalone`. Adicionar Angular Material (`ng add @angular/material`) com tema consistente com `halotechlabs` e `musicianjob-frontend` do monorepo. Configurar `proxy.conf.json` para redirecionar chamadas `/api` para `http://localhost:8080` em desenvolvimento. Configurar `environment.ts` e `environment.prod.ts` com a URL da API REST. Adicionar `sdc-ui` como módulo no parent POM com `frontend-maven-plugin` ou instruções de build separadas no README.
- **Critério de verificação**: `npm install && ng serve` inicia sem erro em `sdc-ui/`; página em branco carrega no browser sem erros no console.

---

### TASK-028 [P] — sdc-ui: SdcApiService

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-027
- **Tipo**: crud-padrão
- **Risco**: médio
- **QA**: wave
- **Perfil**: frontend
- **Arquivos**:
  - `sdc-ui/src/app/services/sdc-api.service.ts`
  - `sdc-ui/src/app/services/sdc-api.service.spec.ts`
- **Descrição**: Criar `SdcApiService` com métodos: `compress(file: File): Observable<Blob>` (POST `/compress` com `application/octet-stream`), `decompress(file: File): Observable<Blob>` (POST `/decompress`), `getHealth(): Observable<HealthResponse>` (GET `/health`), `getBenchmark(): Observable<BenchmarkResponse>` (GET `/benchmark`). Tipar as respostas com interfaces TypeScript. Incluir tratamento de erros HTTP (4xx / 5xx). Testar com `HttpClientTestingModule`.
- **Critério de verificação**: `ng test` com testes do `SdcApiService` verde; mock de `POST /compress` retorna Blob e mock de erro retorna Observable com erro tipado.

---

### TASK-029 [P] — sdc-ui: FileInspectorComponent

- **Esforço**: M
- **Paralelizável**: Sim
- **Depende de**: TASK-027
- **Tipo**: ui-puro
- **Risco**: baixo
- **QA**: smoke
- **Perfil**: frontend
- **Arquivos**:
  - `sdc-ui/src/app/components/file-inspector/file-inspector.component.ts`
  - `sdc-ui/src/app/components/file-inspector/file-inspector.component.html`
- **Descrição**: Criar `FileInspectorComponent` com: zona de drag-and-drop / input de arquivo para upload de SEG-Y; após upload, exibir header EBCDIC decodificado como `<pre>` com fonte monoespaçada; exibir campos do header binário em `<mat-table>` (samples/trace, sample interval, format code, trace count); exibir preview dos primeiros N traços como waveform simples (Canvas ou SVG com `@for` de pontos). Usar `Angular Material` para layout.
- **Critério de verificação**: Componente renderiza sem erro com arquivo SEG-Y de fixture sintética carregado via FileReader; header EBCDIC aparece como texto legível; tabela de header binário exibe os campos.

---

### TASK-030 [P] — sdc-ui: CompressionPreviewComponent

- **Esforço**: S
- **Paralelizável**: Sim
- **Depende de**: TASK-028, TASK-029
- **Tipo**: ui-puro
- **Risco**: baixo
- **QA**: smoke
- **Perfil**: frontend
- **Arquivos**:
  - `sdc-ui/src/app/components/compression-preview/compression-preview.component.ts`
  - `sdc-ui/src/app/components/compression-preview/compression-preview.component.html`
- **Descrição**: Criar `CompressionPreviewComponent` que, após o upload do arquivo no `FileInspectorComponent`, exibe: tamanho original do arquivo, estimativa de ratio de compressão (obtida do `GET /benchmark` como `compression_ratio`), botão "Comprimir" que aciona `SdcApiService.compress()` e faz download do arquivo `.sdc` resultante. Exibir progresso via `mat-progress-bar`. Exibir ratio real calculado após compressão (tamanho original / tamanho do `.sdc`).
- **Critério de verificação**: Componente exibe ratio estimado após upload; clique em "Comprimir" dispara POST e o browser baixa o arquivo `.sdc`; feedback visual de progresso aparece durante a requisição.

---

### TASK-031 — sdc-ui: AppComponent e roteamento

- **Esforço**: S
- **Paralelizável**: Não
- **Depende de**: TASK-029, TASK-030
- **Tipo**: ui-puro
- **Risco**: médio
- **QA**: wave
- **Perfil**: frontend
- **Arquivos**:
  - `sdc-ui/src/app/app.component.ts`
  - `sdc-ui/src/app/app.routes.ts`
- **Descrição**: Configurar `AppComponent` com `<mat-toolbar>` exibindo o título "AI-Compress Seismic Compressor" e link para `halotechlabs.com`. Configurar roteamento: `/` → `FileInspectorComponent` + `CompressionPreviewComponent` na mesma view; `/benchmark` → página simples que consome `GET /benchmark` e exibe os resultados em tabela. Garantir responsividade básica (Mobile: breakpoint 768px) com Angular Flex Layout ou CSS Grid.
- **Critério de verificação**: `ng build --configuration=production` conclui sem erro; as rotas `/` e `/benchmark` carregam os componentes corretos no browser.

---

### TASK-032 — infra: build de produção sdc-ui e configuração de deploy em halotechlabs.com

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-031
- **Tipo**: infra
- **Risco**: alto
- **QA**: full
- **Perfil**: infra
- **Arquivos**:
  - `sdc-ui/nginx.conf`
  - `sdc-ui/Dockerfile`
- **Descrição**: Criar `Dockerfile` multi-stage para `sdc-ui`: stage 1 (Node 18) executa `ng build --configuration=production --base-href=/demo/seismic-compressor/`; stage 2 (Nginx Alpine) serve os artefatos estáticos. Criar `nginx.conf` com: `root /usr/share/nginx/html`; `try_files $uri $uri/ /index.html` para suporte a roteamento Angular; proxy reverso para `/api` → `sdc-rest:8080`. Documentar no README do módulo o processo de deploy no servidor halotechlabs.com (mapeando o pipeline existente de outros projetos Angular do monorepo).
- **Critério de verificação**: `docker build -t sdc-ui sdc-ui/` conclui sem erro; `docker run -p 80:80 sdc-ui` serve a aplicação em `localhost/demo/seismic-compressor/`; rota `/benchmark` carrega sem 404.

---

### TASK-033 — infra: CI pipeline — corretude + benchmark gate + deploy

- **Esforço**: M
- **Paralelizável**: Não
- **Depende de**: TASK-012, TASK-013, TASK-020, TASK-032
- **Tipo**: infra
- **Risco**: alto
- **QA**: full
- **Perfil**: infra
- **Arquivos**:
  - `.github/workflows/ci.yml`
  - `.github/workflows/release.yml`
- **Descrição**: Criar dois workflows GitHub Actions. `ci.yml` (todo push/PR): (a) `mvn verify` nos módulos `sdc-core`, `sdc-ai`, `sdc-rest`, `sdc-cli`, `sdc-fixtures` com JDK 17; (b) `ng test --watch=false` em `sdc-ui`; (c) gate de corretude: falha se `SdcRoundTripTest` falhar; (d) verificação de ausência de `ForkJoinPool` no código de produção (`grep -r ForkJoinPool sdc-core/src/main sdc-ai/src/main` deve retornar vazio). `release.yml` (push em tag `v*`): (a) executa `ci.yml`; (b) executa `mvn verify -pl sdc-bench` para gerar `latest.json`; (c) publica `latest.json` como release asset; (d) build e push Docker de `sdc-ui` para halotechlabs.com via secret `HALOTECHLABS_DEPLOY_KEY`.
- **Critério de verificação**: Push de PR aciona `ci.yml` e bloqueia merge se qualquer teste falhar; push de tag `v1.0.0` aciona `release.yml` e publica `latest.json` como release asset.

---

### TASK-034 — sdc-ai: artefato do modelo autoencoder (stub de produção)

- **Esforço**: L
- **Paralelizável**: Não
- **Depende de**: TASK-009
- **Tipo**: integração-externa
- **Risco**: alto
- **QA**: full
- **Perfil**: backend
- **Arquivos**:
  - `sdc-ai/src/main/resources/models/<uuid>/saved_model.pb`
  - `sdc-ai/README.md`
- **Descrição**: Treinar ou obter o SavedModel do autoencoder (arquitetura encoder-decoder em Python/Keras sobre dados SEG-Y sintéticos). Exportar como TensorFlow SavedModel para o diretório `src/main/resources/models/<uuid>/`. Gerar o UUID do artefato e atualizar `ModelRegistry.BUNDLED_MODEL_UUID`. Documentar no `sdc-ai/README.md`: arquitetura do modelo (camadas, dimensões), dados de treinamento utilizados, métricas de compressibilidade dos resíduos (ratio de DEFLATE sobre resíduos < ratio sobre amostras brutas), e o processo completo de re-treinamento (SDC-06). Se o modelo real ainda não estiver disponível, usar o stub de identidade da TASK-010 como placeholder marcado com comentário `// PLACEHOLDER: substituir pelo modelo treinado antes de release`.
- **Critério de verificação**: `AePredictorTest` carrega o artefato sem erro em ambiente limpo; `TAC-03` — DEFLATE sobre resíduos produz ratio menor que DEFLATE sobre amostras brutas (para o modelo real); para o stub de identidade, documentar que o critério TAC-03 é pendente.

---

## Grupos de Paralelização Sugeridos

- **Onda 1** (pode começar imediatamente): TASK-001

- **Onda 2** (após TASK-001): TASK-002 [P], TASK-008 [P], TASK-015, TASK-021, TASK-027

- **Onda 3** (após TASK-002): TASK-003 [P], TASK-004 [P], TASK-005 [P], TASK-006 [P]
  (após TASK-008): TASK-009
  (após TASK-015): TASK-016 [P], TASK-017 [P]
  (após TASK-021): nenhuma ainda

- **Onda 4** (após TASK-002 e TASK-003 e TASK-004): TASK-007
  (após TASK-002 e TASK-011): TASK-012
  (após TASK-009): TASK-010, TASK-034
  (após TASK-027): TASK-028 [P], TASK-029 [P]

- **Onda 5** (após TASK-007 e TASK-010): TASK-018 [P], TASK-019 [P]
  (após TASK-007 e TASK-011): TASK-013
  (após TASK-021 e TASK-007 e TASK-010): TASK-022 [P], TASK-023 [P]
  (após TASK-021 e TASK-005): TASK-024 [P]
  (após TASK-021 e TASK-002): TASK-025 [P]
  (após TASK-028 e TASK-029): TASK-030 [P]

- **Onda 6** (após TASK-018 e TASK-019): TASK-020
  (após TASK-013): TASK-014, TASK-026
  (após TASK-029 e TASK-030): TASK-031

- **Onda 7** (após TASK-020, TASK-012, TASK-031): TASK-032
  (após TASK-002): TASK-011 (pode ser na Onda 3)

- **Onda 8** (após TASK-012, TASK-013, TASK-020, TASK-032): TASK-033

> Nota: TASK-011 (sdc-fixtures) depende apenas de TASK-002 e pode ser executada em paralelo na Onda 3 junto com TASK-003–TASK-006.

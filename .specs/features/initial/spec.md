# SPEC: AI-Compress-Seismic-v1 — Sistema de Compressão Inteligente de Dados Sísmicos

**Feature:** initial
**PRD:** [PRD_AI-Compress-Seismic-v1.md](../../../docs/PRD_AI-Compress-Seismic-v1.md)
**Data:** 2026-05-16
**Status:** Draft
**Escopo:** Large (multi-módulo Maven: sdc-core, sdc-ai, sdc-rest, sdc-cli, sdc-ui, sdc-bench, sdc-fixtures)

---

## Problem Statement

Operadores de exploração de petróleo e gás geram volumes massivos de dados sísmicos no formato SEG-Y Rev1 — surveys 3D produzem centenas de gigabytes a múltiplos terabytes por campanha. O custo operacional de armazenamento e transferência desses arquivos é proibitivo, especialmente para operadores de pequeno e médio porte que não possuem equipe interna de engenharia para construir ferramentas de compressão especializadas.

As soluções existentes se enquadram em três categorias, todas insatisfatórias: (a) compressão lossless genérica (gzip, zstd, lz4), que ignora a redundância estrutural de traços sísmicos e entrega ratios modestos; (b) formatos proprietários de fornecedores, não portáteis; (c) pesquisa acadêmica em compressão neural, sem empacotamento como tooling pronto para produção e fiel ao formato SEG-Y.

**AI-Compress-Seismic-v1** ocupa essa lacuna: um framework portátil, aberto e fiel ao SEG-Y Rev1 que combina processamento de sinal clássico com uma camada de predição baseada em IA (autoencoder TensorFlow Java), projetado para uso em produção por operadores de qualquer porte.

---

## Goals

- [ ] Suporte completo de leitura/escrita SEG-Y Rev1 com fidelidade bit-a-bit nos headers EBCDIC, headers binários, headers de traço e amostras de traço
- [ ] Alcançar e publicar ratios de compressão reproduzíveis em datasets públicos da indústria, com benchmarks executáveis a partir do repositório
- [ ] Throughput sustentado ≥ 76,6 MB/s em hardware commodity (paridade ou melhoria sobre o baseline JMH do protótipo)
- [ ] Três superfícies de execução: CLI para workflows em batch, microserviço REST para integração em pipelines, e UI web para uso exploratório
- [ ] Validação de corretude bit-a-bit: toda release inclui relatório JMH reproduzível e suite de corretude baseada em fixtures

---

## Out of Scope

| Feature | Razão |
|---|---|
| Compressão lossy com métricas perceptuais | Reservado para v2 |
| Execução paralela via ForkJoinPool | Race condition documentada no protótipo; v1 opera single-thread; paralelização adiada para v2 |
| Formatos não-SEG-Y (SEG-D, SEG-Y Rev2 além do Rev1, formatos proprietários de fornecedores) | Fora do escopo de v1 |
| Compressão em tempo real / streaming | v1 é orientado a batch |
| Inferência acelerada por GPU | v1 roda TensorFlow Java em CPU |
| Camada de criptografia / DRM | Responsabilidade da camada de armazenamento |

---

## Atores

| Ator | Papel |
|---|---|
| **Operador de energia (pequeno/médio porte)** | Comprime arquivos SEG-Y locais ou em cloud para reduzir custo de armazenamento e egresso |
| **Provedor de serviços de processamento sísmico** | Usa o microserviço REST para integrar compressão em pipelines existentes de transferência campo → datacenter |
| **Equipe de exploração cloud-native** | Reduz custo de S3 / Azure Blob e acelera I/O de pipeline via compressão direta |
| **Pesquisador acadêmico** | Usa o framework como baseline reproduzível de compressão neural sobre formato industrial real |
| **Sistema CI/CD** | Executa suite de corretude e relatório JMH a cada build |

---

## Arquitetura de Módulos

O sistema é um monorepo Maven multi-módulo. Cada módulo tem responsabilidade única e bem delimitada:

```
ai-compress-seismic-v1/
├── sdc-core/       Java 17 — leitor/escritor SEG-Y Rev1, pipeline de codec
├── sdc-ai/         Integração TensorFlow Java — predictor autoencoder
├── sdc-rest/       Spring Boot WebFlux — microserviço REST
├── sdc-cli/        Picocli — CLI batch
├── sdc-ui/         Angular 18 — UI web para inspeção e demo
├── sdc-bench/      JMH harness — suite de benchmark reproduzível
└── sdc-fixtures/   Datasets de referência e fixtures de corretude
```

---

## Requisitos Funcionais

### Bloco 1: sdc-core — Pipeline de Codec SEG-Y Rev1

#### SDC-01: Leitura de arquivo SEG-Y Rev1
- **Prioridade:** CRITICAL
- **O que:** Parser completo do formato SEG-Y Rev1: header textual EBCDIC (3200 bytes), header binário (400 bytes), headers de traço (240 bytes cada) e amostras de traço (float IEEE 754 ou inteiros IBM)
- **Comportamento:**
  - Lê e preserva o header EBCDIC sem alteração de bytes
  - Decodifica o header binário conforme especificação SEG-Y Rev1 (SEG Technical Standards Committee)
  - Itera sobre traços: lê header de traço + N amostras conforme `SEGY_SAMPLE_COUNT` do header binário
  - Suporta arquivos com múltiplos SEG-Y logical files em sequência
- **Done when:**
  - Round-trip (leitura + escrita) produz arquivo byte-a-byte idêntico ao original em todas as fixtures de Phase 0
  - Suite de corretude passa 100% no CI

#### SDC-02: Escrita de arquivo SEG-Y Rev1
- **Prioridade:** CRITICAL
- **O que:** Serialização fiel do formato SEG-Y Rev1 a partir das estruturas internas do codec
- **Comportamento:**
  - Escreve header EBCDIC exatamente como lido (sem transliteração)
  - Escreve header binário com todos os campos na posição e endianness corretos (big-endian conforme Rev1)
  - Escreve headers de traço e amostras bit-a-bit conforme entrada original (após decode)
- **Done when:**
  - Arquivo de saída é byte-a-byte idêntico ao arquivo de entrada nas fixtures de Phase 0
  - Validação executada automaticamente no CI a cada build

#### SDC-03: Pipeline de codec — encode
- **Prioridade:** CRITICAL
- **O que:** Pipeline de compressão em estágios operando sobre blocos de traços
- **Estágios:**
  1. **Header preservation pass:** copia headers (EBCDIC + binário + headers de traço) sem processamento
  2. **Delta encoding estrutural por traço:** calcula deltas entre amostras consecutivas de cada traço para explorar correlação temporal
  3. **Residuais AI:** encaminha blocos de traços para `sdc-ai` (autoencoder) e obtém stream de resíduos
  4. **Entropy coding:** aplica codificação entrópica (ex: DEFLATE ou similar) sobre os resíduos
- **Comportamento:**
  - Execução single-thread (sem ForkJoinPool em v1)
  - Throughput alvo ≥ 76,6 MB/s em hardware commodity
- **Done when:**
  - Pipeline processa o dataset de referência de 1,71 GB sem erro
  - Throughput medido pelo JMH ≥ 76,6 MB/s
  - Arquivo comprimido é significativamente menor que o original (ratio publicado)

#### SDC-04: Pipeline de codec — decode
- **Prioridade:** CRITICAL
- **O que:** Inversão completa do pipeline de encode, reconstituindo o arquivo SEG-Y original
- **Estágios (inversos):**
  1. Decodificação entrópica
  2. Aplicação dos resíduos AI (decode do autoencoder)
  3. Reconstituição das amostras via delta decoding
  4. Reconstituição dos headers
- **Done when:**
  - Arquivo decodificado é byte-a-byte idêntico ao original em 100% das fixtures
  - Validação de corretude executa automaticamente no CI

---

### Bloco 2: sdc-ai — Predictor Autoencoder

#### SDC-05: Integração TensorFlow Java — inferência in-process
- **Prioridade:** CRITICAL
- **O que:** Execução do autoencoder treinado dentro da JVM, sem chamada externa a serviço Python
- **Comportamento:**
  - Carrega artefatos de modelo versionados do classpath ou caminho configurável
  - Recebe blocos de amostras de traço e retorna stream de resíduos
  - Opera em CPU (sem GPU em v1)
  - Artefatos de modelo são versionados e distribuídos junto com a release
- **Done when:**
  - Pipeline de encode/decode completo funciona com o autoencoder ativo
  - Modelo carrega sem erro em JVM sem TensorFlow externo instalado
  - Resíduos produzidos são mais compressíveis que as amostras brutas (verificável pelo ratio final)

#### SDC-06: Pipeline de re-treinamento documentado
- **Prioridade:** MEDIUM
- **O que:** Documentação e scripts para re-treinamento do autoencoder a partir das fixtures públicas
- **Done when:**
  - README do módulo `sdc-ai` descreve o processo completo de re-treinamento
  - Script de treinamento executável a partir das fixtures em `sdc-fixtures`

---

### Bloco 3: sdc-rest — Microserviço REST

#### SDC-07: POST /compress
- **Prioridade:** HIGH
- **O que:** Endpoint que recebe stream SEG-Y e retorna stream comprimido
- **Comportamento:**
  - Content-Type de entrada: `application/octet-stream` (SEG-Y binário)
  - Content-Type de saída: `application/octet-stream` (formato comprimido)
  - Processamento reativo via Spring WebFlux
  - Retorna HTTP 200 com stream comprimido em caso de sucesso
  - Retorna HTTP 400 se o payload não for um SEG-Y Rev1 válido
  - Retorna HTTP 500 em caso de falha interna do codec
- **Done when:**
  - Endpoint processa arquivo de referência e retorna stream comprimido válido
  - Decode do stream retornado produz arquivo idêntico ao original

#### SDC-08: POST /decompress
- **Prioridade:** HIGH
- **O que:** Endpoint que recebe stream comprimido e retorna stream SEG-Y original
- **Comportamento:**
  - Content-Type de entrada: `application/octet-stream` (formato comprimido)
  - Content-Type de saída: `application/octet-stream` (SEG-Y binário)
  - Retorna HTTP 200 com SEG-Y restaurado
  - Retorna HTTP 400 se o payload não for um stream comprimido válido
- **Done when:**
  - Arquivo decodificado é byte-a-byte idêntico ao original em todas as fixtures

#### SDC-09: GET /benchmark
- **Prioridade:** MEDIUM
- **O que:** Endpoint que retorna metadados do último relatório JMH
- **Comportamento:**
  - Retorna JSON com: `throughput_mb_s`, `dataset_size_gb`, `compression_ratio`, `timestamp`, `version`
  - Retorna HTTP 200 com payload JSON
- **Done when:**
  - Endpoint retorna payload JSON válido com campos obrigatórios

#### SDC-10: GET /health
- **Prioridade:** HIGH
- **O que:** Endpoint de readiness e liveness para integração com load balancers e orquestradores
- **Comportamento:**
  - Retorna HTTP 200 com `{"status": "UP"}` quando o serviço está operacional
  - Retorna HTTP 503 quando o serviço não está pronto
- **Done when:**
  - Endpoint responde corretamente em ambiente de produção e homologação

---

### Bloco 4: sdc-cli — Interface de Linha de Comando

#### SDC-11: Subcomando `compress`
- **Prioridade:** HIGH
- **O que:** `sdc compress <input.segy> <output.sdc>` — comprime arquivo SEG-Y para formato comprimido
- **Comportamento:**
  - Lê arquivo de entrada, executa pipeline de encode, escreve arquivo de saída
  - Exibe progresso e throughput ao final
  - Retorna exit code 0 em sucesso, não-zero em falha
- **Done when:**
  - Arquivo de saída produzido pode ser decodificado com `sdc decompress` para arquivo idêntico ao original

#### SDC-12: Subcomando `decompress`
- **Prioridade:** HIGH
- **O que:** `sdc decompress <input.sdc> <output.segy>` — decomprime para SEG-Y original
- **Done when:**
  - Arquivo de saída é byte-a-byte idêntico ao arquivo SEG-Y original comprimido

#### SDC-13: Subcomando `validate`
- **Prioridade:** HIGH
- **O que:** `sdc validate <file.segy>` — valida conformidade SEG-Y Rev1 do arquivo
- **Comportamento:**
  - Verifica integridade do header EBCDIC, header binário e estrutura de traços
  - Reporta erros de conformidade com localização (byte offset)
  - Retorna exit code 0 se válido, não-zero se inválido
- **Done when:**
  - Fixtures de Phase 0 passam na validação; arquivos corrompidos geram erros descritivos

#### SDC-14: Subcomando `benchmark`
- **Prioridade:** MEDIUM
- **O que:** `sdc benchmark <dataset>` — executa suite JMH e gera relatório
- **Done when:**
  - Relatório gerado é equivalente ao produzido pelo módulo `sdc-bench` standalone

#### SDC-15: Subcomando `inspect`
- **Prioridade:** MEDIUM
- **O que:** `sdc inspect <file.segy>` — exibe metadados do arquivo SEG-Y (headers, contagem de traços, range de amostras)
- **Done when:**
  - Saída exibe: número de traços, número de amostras por traço, intervalo de amostragem, data de aquisição (se presente no header)

---

### Bloco 5: sdc-ui — Interface Web

#### SDC-16: Inspetor visual de arquivo SEG-Y
- **Prioridade:** MEDIUM
- **O que:** Upload de arquivo SEG-Y pelo browser com visualização dos headers e preview de traços
- **Comportamento:**
  - Exibe header EBCDIC decodificado como texto
  - Exibe campos do header binário em tabela legível
  - Exibe preview dos primeiros N traços como waveform
- **Done when:**
  - Arquivo de referência é carregado e os headers são exibidos corretamente

#### SDC-17: Preview de compressão
- **Prioridade:** MEDIUM
- **O que:** Exibe estimativa de ratio de compressão antes de iniciar o processo
- **Done when:**
  - Usuário vê ratio estimado após upload do arquivo, antes de confirmar compressão

#### SDC-18: Demo público em halotechlabs.com/demo/seismic-compressor
- **Prioridade:** HIGH
- **O que:** Deploy da sdc-ui acessível publicamente como superfície de demonstração
- **Done when:**
  - URL pública responde com a aplicação Angular 18 funcional
  - Demo processa um arquivo de exemplo sem erro

---

### Bloco 6: sdc-bench — Benchmark JMH

#### SDC-19: Relatório JMH reproduzível por release
- **Prioridade:** CRITICAL
- **O que:** Harness JMH que produz relatório de performance reproduzível em hardware documentado
- **Métricas obrigatórias no relatório:**
  - Throughput sustentado (MB/s) — alvo ≥ 76,6 MB/s
  - Speedup vs. baseline Java anterior (alvo: 148×–420×)
  - Comparativo com baseline C++ (101,6 MB/s como referência; Java ≈ 0,75× do nativo é aceitável)
  - Corretude bit-a-bit confirmada contra fixtures de Phase 0
- **Done when:**
  - Relatório gerado automaticamente no CI a cada release
  - Relatório é publicado junto com os artefatos da release no repositório

---

### Bloco 7: sdc-fixtures — Fixtures de Corretude

#### SDC-20: Fixtures de referência Phase 0
- **Prioridade:** CRITICAL
- **O que:** Dataset de referência e fixtures de corretude herdadas do protótipo SeismicDataCompressor
- **Comportamento:**
  - Fixtures cobrem: header EBCDIC, header binário, traços individuais, arquivo completo de 1,71 GB
  - Suite de corretude executa round-trip e compara byte-a-byte
- **Done when:**
  - `sdc validate` e decode passam 100% contra todas as fixtures
  - Suite executa no CI em < 10 minutos

---

## Modelos de Dados / Estruturas Principais

### SEG-Y Rev1 — Estrutura de arquivo

| Componente | Tamanho | Descrição |
|---|---|---|
| Header textual EBCDIC | 3.200 bytes | 40 linhas × 80 chars; metadados da aquisição |
| Header binário | 400 bytes | Parâmetros de amostragem, formato de dado, número de traços |
| Traço (repetido N vezes) | 240 + (N_samples × sample_size) bytes | Header de traço (240 bytes) + amostras |

### Formato comprimido interno (`.sdc`)

| Campo | Tipo | Descrição |
|---|---|---|
| Magic number | 4 bytes | Identificador do formato `SDC\x01` |
| Versão do codec | 2 bytes | Versão do pipeline (para compatibilidade futura) |
| Versão do modelo AI | 16 bytes (UUID) | Referência ao artefato de modelo usado |
| Headers SEG-Y preservados | variável | EBCDIC + binário copiados sem processamento |
| Blocos de traços comprimidos | variável | Delta-encoded + resíduos AI + entropy-coded |

### Payload de resposta GET /benchmark

```json
{
  "throughput_mb_s": 76.6,
  "dataset_size_gb": 1.71,
  "compression_ratio": "[LACUNA: ratio médio não especificado no PRD — definir após primeiros benchmarks]",
  "speedup_vs_prior_java_baseline": "148x-420x",
  "timestamp": "2026-05-16T00:00:00Z",
  "version": "1.0.0"
}
```

---

## Interfaces Externas

| Interface | Protocolo | Descrição |
|---|---|---|
| `POST /compress` | HTTP/REST (WebFlux) | Recebe SEG-Y, retorna stream comprimido |
| `POST /decompress` | HTTP/REST (WebFlux) | Recebe stream comprimido, retorna SEG-Y |
| `GET /benchmark` | HTTP/REST (WebFlux) | Retorna metadados do último relatório JMH |
| `GET /health` | HTTP/REST (WebFlux) | Liveness e readiness |
| CLI `sdc` | Picocli (JVM) | Interface batch: compress, decompress, validate, benchmark, inspect |
| UI Angular 18 | HTTPS | Demo público e inspetor visual; hospedado em halotechlabs.com |

---

## Requisitos Não Funcionais

| ID | Requisito | Critério mensurável |
|---|---|---|
| RNF-01 | Throughput de encode | ≥ 76,6 MB/s sustentado em hardware commodity (medido por JMH) |
| RNF-02 | Corretude bit-a-bit | 100% das fixtures de Phase 0 passam no round-trip |
| RNF-03 | Execução single-thread | Sem uso de ForkJoinPool em v1; paralelização adiada |
| RNF-04 | Inferência in-process | TensorFlow Java no mesmo JVM; sem chamada a serviço Python externo |
| RNF-05 | Portabilidade | Executável em qualquer JVM 17+ em Linux, macOS, Windows sem dependências nativas além do TF Java |
| RNF-06 | Reprodutibilidade | Relatório JMH e fixtures executáveis a partir do repositório público sem configuração adicional |
| RNF-07 | Conformidade SEG-Y Rev1 | Passa na suite de conformidade contra a especificação do SEG Technical Standards Committee |
| RNF-08 | CI obrigatório | Suite de corretude executa em todo build; falha bloqueia merge |

---

## Dependências Técnicas

| Dependência | Versão | Módulo(s) |
|---|---|---|
| Java | 17 | Todos os módulos JVM |
| Maven | 3.x | Build system |
| TensorFlow Java | [LACUNA: versão específica não especificada no PRD] | sdc-ai |
| Spring Boot WebFlux | 3.x | sdc-rest |
| Picocli | 4.x | sdc-cli |
| Angular | 18 | sdc-ui |
| JMH | 1.x | sdc-bench |

---

## Riscos e Premissas

| Risco / Premissa | Impacto | Mitigação |
|---|---|---|
| Gap de throughput vs. C++ (76,6 MB/s vs 101,6 MB/s) | Pode limitar adoção em workflows latência-críticos | Documentar abertamente; posicionar v1 para caso de uso de redução de custo de armazenamento; paridade visada em Phase 2 |
| Race condition em modo paralelo | Impede paralelização em v1 | Race condition isolada e documentada; v1 opera single-thread com afirmação explícita |
| Drift de especificação SEG-Y (adoção de Rev2) | Risco de obsolescência parcial | Rev1 permanece dominante na base instalada; extensões Rev2 no roadmap de Phase 3 |
| Staleness do modelo autoencoder | Degradação de ratio ao longo do tempo | Artefatos versionados; pipeline de re-treinamento documentado em sdc-ai |
| [LACUNA: hardware de referência do benchmark não especificado no PRD] | Benchmarks não são comparáveis entre ambientes | Definir e documentar a máquina de referência (CPU, RAM, storage) no README do sdc-bench |

---

## Critérios de Aceitação da Feature

| ID | Critério | Método de verificação |
|---|---|---|
| CA-01 | Round-trip SEG-Y Rev1 produz arquivo byte-a-byte idêntico ao original | Suite de corretude `sdc-fixtures` no CI |
| CA-02 | Throughput de encode ≥ 76,6 MB/s no dataset de 1,71 GB | Relatório JMH do `sdc-bench` |
| CA-03 | Speedup de 148×–420× vs. baseline Java anterior | Relatório JMH do `sdc-bench` |
| CA-04 | Microserviço REST responde `POST /compress` e `POST /decompress` com corretude | Teste de integração com arquivo de referência |
| CA-05 | CLI executa todos os 5 subcomandos sem erro em arquivo de referência | Teste funcional em CI |
| CA-06 | Demo público em halotechlabs.com/demo/seismic-compressor está acessível e funcional | Verificação manual + smoke test automatizado |
| CA-07 | Relatório JMH é gerado e publicado automaticamente a cada release | Verificação no pipeline de CI/CD |
| CA-08 | `GET /health` retorna HTTP 200 quando o serviço está operacional | Health check no CI após deploy |
| CA-09 | Subcomando `validate` identifica corretamente arquivos SEG-Y Rev1 válidos e inválidos | Teste com fixtures válidas e corrompidas |
| CA-10 | Artefatos de modelo AI são versionados e carregados sem dependência de TensorFlow externo | Teste de carregamento isolado em ambiente limpo |

---

## Rastreabilidade de Requisitos

| Req ID | Descrição resumida | Módulo | Prioridade | Status |
|---|---|---|---|---|
| SDC-01 | Leitura SEG-Y Rev1 completa | sdc-core | CRITICAL | Pending |
| SDC-02 | Escrita SEG-Y Rev1 completa | sdc-core | CRITICAL | Pending |
| SDC-03 | Pipeline encode (delta + AI + entropy) | sdc-core + sdc-ai | CRITICAL | Pending |
| SDC-04 | Pipeline decode (inversão completa) | sdc-core + sdc-ai | CRITICAL | Pending |
| SDC-05 | Integração TensorFlow Java in-process | sdc-ai | CRITICAL | Pending |
| SDC-06 | Pipeline de re-treinamento documentado | sdc-ai | MEDIUM | Pending |
| SDC-07 | REST POST /compress | sdc-rest | HIGH | Pending |
| SDC-08 | REST POST /decompress | sdc-rest | HIGH | Pending |
| SDC-09 | REST GET /benchmark | sdc-rest | MEDIUM | Pending |
| SDC-10 | REST GET /health | sdc-rest | HIGH | Pending |
| SDC-11 | CLI compress | sdc-cli | HIGH | Pending |
| SDC-12 | CLI decompress | sdc-cli | HIGH | Pending |
| SDC-13 | CLI validate | sdc-cli | HIGH | Pending |
| SDC-14 | CLI benchmark | sdc-cli | MEDIUM | Pending |
| SDC-15 | CLI inspect | sdc-cli | MEDIUM | Pending |
| SDC-16 | UI inspetor visual SEG-Y | sdc-ui | MEDIUM | Pending |
| SDC-17 | UI preview de compressão | sdc-ui | MEDIUM | Pending |
| SDC-18 | Demo público halotechlabs.com | sdc-ui | HIGH | Pending |
| SDC-19 | Relatório JMH reproduzível por release | sdc-bench | CRITICAL | Pending |
| SDC-20 | Fixtures de referência Phase 0 | sdc-fixtures | CRITICAL | Pending |

**Total:** 20 requisitos | 0 mapeados a tasks | 20 pendentes

---

## Success Criteria

- [ ] Suite de corretude passa 100% em todas as fixtures de Phase 0 no CI
- [ ] Throughput de encode ≥ 76,6 MB/s medido por JMH no hardware de referência documentado
- [ ] Microserviço REST responde todos os 4 endpoints com comportamento correto
- [ ] CLI executa todos os 5 subcomandos contra o dataset de referência sem erro
- [ ] Demo público acessível e funcional em halotechlabs.com/demo/seismic-compressor
- [ ] Relatório JMH publicado automaticamente a cada release
- [ ] Nenhuma chamada externa a serviço Python no pipeline de compressão/descompressão
- [ ] Execução single-thread confirmada (sem ForkJoinPool em v1)

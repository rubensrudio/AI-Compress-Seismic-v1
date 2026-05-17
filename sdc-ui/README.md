# sdc-ui — Angular 18 Frontend

UI web para inspeção de arquivos SEG-Y e demonstração do pipeline de compressão AI-Compress-Seismic-v1.

## Stack

- Angular 18.2 (standalone components)
- Angular Material 18.2 (tema: indigo-pink)
- SCSS
- TypeScript 5.5

## Requisitos

- Node.js 18+ (recomendado LTS)
- npm 9+
- Angular CLI 18: `npm install -g @angular/cli@18`

## Desenvolvimento local

```bash
cd sdc-ui
npm install
ng serve
```

A aplicacao estara disponivel em `http://localhost:4200/`.
As chamadas para `/api` sao redirecionadas automaticamente para `http://localhost:8080` via `proxy.conf.json`.

O backend `sdc-rest` deve estar rodando na porta 8080 para funcionalidade completa.

## Build de producao

```bash
ng build --configuration=production
```

Os artefatos de build serao gerados em `dist/sdc-ui/browser/`.

## Testes

```bash
ng test --watch=false --browsers=ChromeHeadless
```

## Configuracao de ambientes

| Arquivo | Uso | apiUrl |
|---|---|---|
| `src/environments/environment.ts` | Desenvolvimento (`ng serve`) | `/api` (proxy para localhost:8080) |
| `src/environments/environment.prod.ts` | Producao (`ng build`) | `http://localhost:8080` |

## Proxy de desenvolvimento

O arquivo `proxy.conf.json` redireciona todas as requisicoes para `/api/*` para `http://localhost:8080/*` durante o desenvolvimento.

Configuracao no `angular.json`:
```json
"serve": {
  "options": {
    "proxyConfig": "proxy.conf.json"
  }
}
```

## Integracao Maven

O modulo `sdc-ui` esta declarado no `pom.xml` raiz (`sdc-parent`). O build Angular e independente do ciclo Maven.

Para excluir o sdc-ui do build Maven:
```bash
mvn install -pl '!sdc-ui'
# ou
mvn install -Dskip.ui=true
```

Para incluir o build npm no ciclo Maven (requer Node/npm disponivel no ambiente CI):
O `sdc-ui/pom.xml` esta preparado para receber o `frontend-maven-plugin` em uma task futura (TASK-032).

## Deploy

O deploy para `halotechlabs.com/demo/seismic-compressor/` sera configurado em TASK-032 via Dockerfile multi-stage + Nginx.

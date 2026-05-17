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

## Deploy com Docker

### Construir a imagem

```bash
docker build -t sdc-ui sdc-ui/
```

O Dockerfile multi-stage executa dois estagios:
1. **Stage builder** (`node:18-alpine`): instala dependencias via `npm ci` e executa `ng build --configuration=production --base-href=/demo/seismic-compressor/`. Os artefatos estaticos ficam em `/app/dist/sdc-ui/browser/`.
2. **Stage serve** (`nginx:alpine`): copia os artefatos para `/usr/share/nginx/html/demo/seismic-compressor/` e aplica a configuracao de Nginx (`nginx.conf`).

### Rodar localmente

```bash
docker run -p 80:80 sdc-ui
```

Acesse em: `http://localhost/demo/seismic-compressor/`

As rotas Angular (ex: `/demo/seismic-compressor/benchmark`) carregam sem 404 porque o `nginx.conf` usa `try_files $uri $uri/ /demo/seismic-compressor/index.html`.

### Configuracao de rede e proxy

O `nginx.conf` define dois blocos de `location`:

| Location | Comportamento |
|---|---|
| `/demo/seismic-compressor/` | Serve os artefatos Angular; roteamento client-side via `try_files` |
| `/api/` | Proxy reverso para `http://sdc-rest:8080/` (container name no Docker network) |

O servico `sdc-rest` deve estar acessivel pelo hostname `sdc-rest` na mesma rede Docker. Em producao, use `docker-compose` ou um overlay network:

```yaml
# docker-compose.yml (exemplo)
services:
  sdc-rest:
    image: sdc-rest:latest
    networks:
      - sdc-net

  sdc-ui:
    image: sdc-ui:latest
    ports:
      - "80:80"
    networks:
      - sdc-net
    depends_on:
      - sdc-rest

networks:
  sdc-net:
    driver: bridge
```

## Deploy em halotechlabs.com

O padrao de deploy adotado no monorepo para projetos Angular (ex: `halotechlabs`, `musicianjob-frontend`, `apostas-esportivas`) e:

1. **Build local da imagem Docker** no ambiente de CI ou na maquina do desenvolvedor:
   ```bash
   docker build -t sdc-ui:latest sdc-ui/
   ```

2. **Tag e push para o registry** (Docker Hub ou registry privado configurado em halotechlabs.com):
   ```bash
   docker tag sdc-ui:latest <registry>/sdc-ui:latest
   docker push <registry>/sdc-ui:latest
   ```

3. **Pull e restart no servidor**:
   ```bash
   ssh user@halotechlabs.com "docker pull <registry>/sdc-ui:latest && docker compose -f /srv/sdc/docker-compose.yml up -d sdc-ui"
   ```

4. **Verificacao**: acesse `https://halotechlabs.com/demo/seismic-compressor/` e confirme que a aplicacao carrega corretamente.

### Variaveis de ambiente e configuracao

| Parametro | Valor em producao | Descricao |
|---|---|---|
| `--base-href` | `/demo/seismic-compressor/` | Prefixo de URL onde a SPA Angular e servida |
| `proxy /api/` | `http://sdc-rest:8080/` | Container name do backend no Docker network |
| Porta Nginx | `80` | Exposta via `EXPOSE 80` no Dockerfile |

O `base-href` e fixo no momento do build (`ng build --base-href=...`) e nao pode ser alterado em runtime sem rebuildar a imagem. Para alterar o endereco do backend, edite `nginx.conf` e rebuilde a imagem.

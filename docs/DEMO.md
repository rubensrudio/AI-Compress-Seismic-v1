# Demo

Three ways to see the system working: hosted, local web UI, and CLI one-liner.

## 1. Hosted live demo

> `https://halotechlabs.com/demo/seismic-compressor`

Upload a `.segy` file, inspect its headers and waveforms, pick a compression
profile, and download the `.sdc` result. Backed by the same `sdc-rest` service.

## 2. Local web UI (full stack)

```bash
# Terminal 1 — backend
cd sdc-fixtures && mvn install -DskipTests && cd ..
mvn install -pl sdc-core,sdc-ai -DskipTests
mvn spring-boot:run -pl sdc-rest          # http://localhost:8080

# Terminal 2 — frontend
cd sdc-ui
npm install
npm start                                 # http://localhost:4200
```

Open `http://localhost:4200`, drop a SEG-Y file on the inspector, then compress
and download. Screenshots of each step: [docs/screenshots/](screenshots/).

## 3. CLI one-liner demo

Round-trip a bundled fixture and confirm it is byte-identical:

```bash
cd sdc-fixtures && mvn install -DskipTests && cd ..
mvn install -pl sdc-core,sdc-ai -DskipTests
mvn package -pl sdc-cli -DskipTests

JAR=sdc-cli/target/sdc-cli-1.0.0-SNAPSHOT-jar-with-dependencies.jar
SEGY=sdc-fixtures/src/test/resources/fixtures/medium.segy   # bundled fixture

java -jar $JAR inspect   $SEGY            # show EBCDIC + binary header
java -jar $JAR compress  $SEGY            # -> medium.sdc
java -jar $JAR decompress medium.sdc      # -> restored SEG-Y

# verify lossless (PowerShell)
#   (Get-FileHash $SEGY).Hash -eq (Get-FileHash restored.segy).Hash
```

Use the `HIGH_QUALITY` profile for guaranteed bit-for-bit output.

## 4. REST quick demo (curl)

```bash
curl http://localhost:8080/health
curl -X POST http://localhost:8080/compress \
  -H "Content-Type: application/octet-stream" \
  -H "X-Compression-Profile: HIGH_QUALITY" \
  --data-binary @your.segy -o out.sdc
curl -X POST http://localhost:8080/decompress \
  -H "Content-Type: application/octet-stream" \
  --data-binary @out.sdc -o restored.segy
```

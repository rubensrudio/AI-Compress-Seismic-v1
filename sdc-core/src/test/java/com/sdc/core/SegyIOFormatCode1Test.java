package com.sdc.core;

import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de regressão para round-trip IBM float (format code 1) em SegyIO.
 *
 * <p>KNOWN LIMITATION documentada: a conversão dupla IEEE -> IBM -> IEEE introduz
 * erro de arredondamento inerente ao formato IBM float32. O IBM float usa expoente
 * em base 16, o que agrupa 4 bits de expoente binário em 1 nibble hexadecimal.
 * Isso significa que a mantissa de 24 bits pode ter até 3 bits leading zero, reduzindo
 * a precisão efetiva para ~21 bits significativos vs 24 bits do IEEE float32.
 *
 * <p>Epsilon documentado: erro relativo máximo de round-trip ~2^-21 (~4.8e-7).
 * Os testes aqui FALHAM se o comportamento PIORAR além desse delta documentado.
 * Format code 5 (IEEE float32) continua com corretude bit-a-bit exata (sem epsilon).
 *
 * <p>Rastreabilidade: TASK-006 / Risco R-02 do plan.md / Premissa P-04 do plan.md.
 */
class SegyIOFormatCode1Test {

    // -------------------------------------------------------------------------
    // Epsilon documentado para round-trip IBM float32 -> IEEE float32
    // Derivado da análise do formato: base-16 com 24 bits de mantissa implica
    // precisão efetiva de 21-24 bits significativos. O pior caso ocorre quando
    // o expoente binário não é múltiplo de 4, desperdicando até 3 bits leading.
    // Valor: 2^-21 = 4.768371582031250e-7
    // Usamos uma folga de fator 8 (2^-18 ~= 3.8e-6) para cobrir erros de
    // arredondamento na normalização sem mascarar regressões reais.
    // -------------------------------------------------------------------------
    private static final float IBM_ROUND_TRIP_ABS_EPSILON = 1e-5f;   // epsilon absoluto para valores perto de zero
    private static final float IBM_ROUND_TRIP_REL_EPSILON = 4e-6f;   // epsilon relativo para valores normalizados

    // -------------------------------------------------------------------------
    // Testes de conversão unitária (ibmToFloat / floatToIbm via acesso package)
    // -------------------------------------------------------------------------

    @Test
    void zeroPreservadoExatamente() {
        // Zero deve ser preservado exatamente em ambas as direções.
        int ibmZero = SegyIO.floatToIbm(0.0f);
        assertEquals(0, ibmZero, "floatToIbm(0) deve retornar representacao IBM zero");

        float fromIbmZero = SegyIO.ibmToFloat(0);
        assertEquals(0.0f, fromIbmZero, "ibmToFloat(0) deve retornar 0.0f exato");
    }

    @Test
    void valorPositivoSimples_roundTripDentroDoEpsilon() {
        float original = 1.0f;
        int ibm = SegyIO.floatToIbm(original);
        float recovered = SegyIO.ibmToFloat(ibm);

        float delta = Math.abs(original - recovered);
        float relDelta = (Math.abs(original) > 0) ? delta / Math.abs(original) : delta;

        assertTrue(relDelta <= IBM_ROUND_TRIP_REL_EPSILON,
                String.format("Round-trip 1.0f: relDelta=%.3e excede epsilon documentado %.3e (recovered=%.9f)",
                        relDelta, IBM_ROUND_TRIP_REL_EPSILON, recovered));
    }

    @Test
    void valorNegativoSimples_roundTripDentroDoEpsilon() {
        float original = -1.0f;
        int ibm = SegyIO.floatToIbm(original);
        float recovered = SegyIO.ibmToFloat(ibm);

        float delta = Math.abs(original - recovered);
        float relDelta = delta / Math.abs(original);

        assertTrue(relDelta <= IBM_ROUND_TRIP_REL_EPSILON,
                String.format("Round-trip -1.0f: relDelta=%.3e excede epsilon documentado %.3e (recovered=%.9f)",
                        relDelta, IBM_ROUND_TRIP_REL_EPSILON, recovered));
    }

    @Test
    void amplitudesSismicasRepresentativas_roundTripDentroDoEpsilon() {
        // Amplitudes típicas de dados sísmicos: range amplo, valores reais
        float[] amplitudesRepresentativas = {
            0.001f, 0.01f, 0.1f, 0.5f, 1.0f, 10.0f, 100.0f, 1000.0f,
            -0.001f, -0.01f, -0.1f, -0.5f, -1.0f, -10.0f, -100.0f, -1000.0f,
            3.14159f, -3.14159f,
            0.000123f, 9876.54f, -5432.1f,
            // Valores na faixa de amplitude sísmica processada normalizada
            0.123456789f, -0.987654321f,
        };

        int failures = 0;
        StringBuilder msg = new StringBuilder();

        for (float original : amplitudesRepresentativas) {
            int ibm = SegyIO.floatToIbm(original);
            float recovered = SegyIO.ibmToFloat(ibm);

            float absOriginal = Math.abs(original);
            float delta = Math.abs(original - recovered);

            boolean ok;
            float threshold;
            if (absOriginal < IBM_ROUND_TRIP_ABS_EPSILON) {
                // Para valores muito pequenos, usa epsilon absoluto
                ok = delta <= IBM_ROUND_TRIP_ABS_EPSILON;
                threshold = IBM_ROUND_TRIP_ABS_EPSILON;
            } else {
                float relDelta = delta / absOriginal;
                ok = relDelta <= IBM_ROUND_TRIP_REL_EPSILON;
                threshold = IBM_ROUND_TRIP_REL_EPSILON * absOriginal;
            }

            if (!ok) {
                failures++;
                msg.append(String.format("\n  original=%.9e recovered=%.9e delta=%.3e threshold=%.3e",
                        original, recovered, delta, threshold));
            }
        }

        assertEquals(0, failures,
                "Round-trip IBM float falhou para " + failures + " valores representativos:" + msg);
    }

    @Test
    void nanEInfinito_saoMapeadosParaZeroSeguramente() {
        // IBM float nao tem NaN/Inf; comportamento seguro: mapear para zero
        assertEquals(0, SegyIO.floatToIbm(Float.NaN),
                "floatToIbm(NaN) deve retornar 0 (sem representacao IBM)");
        assertEquals(0, SegyIO.floatToIbm(Float.POSITIVE_INFINITY),
                "floatToIbm(+Inf) deve retornar 0 (sem representacao IBM)");
        assertEquals(0, SegyIO.floatToIbm(Float.NEGATIVE_INFINITY),
                "floatToIbm(-Inf) deve retornar 0 (sem representacao IBM)");
    }

    @Test
    void roundTripNaoEhBitExato_limitacaoDocumentada() {
        // Este teste DOCUMENTA que o round-trip IBM->IEEE->IBM NAO e bit-a-bit exato.
        // Se algum dia isso passar com todos iguais, significa que a implementacao
        // foi melhorada e este comentario deve ser atualizado.
        //
        // Geramos valores que SABIDAMENTE introduzem erro de base-16 vs base-2:
        // qualquer valor cujo expoente binario nao seja multiplo de 4.
        // Ex: 0.1f em IEEE tem expoente binario -4 (nao multiplo de 4: -4 e multiplo de 4!)
        // Melhor exemplo: 0.3f tem expoente binario -2
        float[] valoresSensiveisAoFormatoBase16 = {0.3f, 0.7f, 1.5f, 2.5f, 5.0f, 0.15f};

        boolean algumDiferiu = false;
        for (float original : valoresSensiveisAoFormatoBase16) {
            int ibm = SegyIO.floatToIbm(original);
            float recovered = SegyIO.ibmToFloat(ibm);
            if (original != recovered) {
                algumDiferiu = true;
                break;
            }
        }

        // A limitacao esta documentada: esperamos que pelo menos um valor difira.
        // Se TODOS forem iguais bit-a-bit, precisamos rever a analise — pode ser
        // que a implementacao tenha melhorado ou os valores de teste mudaram.
        // NOTA: Esta assertion pode falhar se a implementacao for melhorada para
        // cobrir esses casos especificos — o que seria uma melhoria, nao regressao.
        assertTrue(algumDiferiu,
                "ATENCAO: todos os valores sensíveis passaram bit-a-bit. " +
                "Verifique se a implementacao foi melhorada e atualize este teste.");
    }

    // -------------------------------------------------------------------------
    // Teste de round-trip via arquivo SEG-Y completo (format code 1)
    // -------------------------------------------------------------------------

    @Test
    void segyRoundTripFormatCode1_deltaDentroDoEpsilonDocumentado() throws Exception {
        // Cria um arquivo SEG-Y sintetico com format code 1 (IBM float32)
        int samplesPerTrace = 64;
        int traceCount = 4;

        // Valores que cobrem o range tipico de amplitude sismica
        float[][] originalSamples = new float[traceCount][samplesPerTrace];
        for (int t = 0; t < traceCount; t++) {
            for (int s = 0; s < samplesPerTrace; s++) {
                float phase = (float) (2 * Math.PI * (t * samplesPerTrace + s)) / (traceCount * samplesPerTrace);
                originalSamples[t][s] = (float) (100.0 * Math.sin(phase) + 0.5 * Math.cos(3 * phase));
            }
        }
        // Injeta zero explicitamente para testar preservacao
        originalSamples[0][0] = 0.0f;

        Path tmpSegy = Files.createTempFile("segy_fc1_test_", ".sgy");
        try {
            // Escreve o arquivo SEG-Y com format code 1 diretamente (bypass SegyIO.write
            // para controlar o conteudo binario sem depender do round-trip em teste)
            writeSegyFormatCode1(tmpSegy, originalSamples, samplesPerTrace);

            // Le o arquivo via SegyIO (conversao IBM -> IEEE)
            SegyIO.SegyDataset dataset = SegyIO.read(tmpSegy);

            assertEquals(1, dataset.sampleFormatCode,
                    "Format code deve ser 1 (IBM float32)");
            assertEquals(traceCount, dataset.traceCount(),
                    "Numero de traces deve coincidir");
            assertEquals(samplesPerTrace, dataset.samplesPerTrace,
                    "samplesPerTrace deve coincidir");

            // Verifica que os valores lidos estao dentro do epsilon documentado
            int failures = 0;
            float maxRelError = 0f;
            StringBuilder failMsg = new StringBuilder();

            for (int t = 0; t < traceCount; t++) {
                float[] recovered = dataset.traces.get(t).samples();
                for (int s = 0; s < samplesPerTrace; s++) {
                    float orig = originalSamples[t][s];
                    float rec  = recovered[s];

                    // Zero deve ser exato
                    if (orig == 0.0f) {
                        if (rec != 0.0f) {
                            failures++;
                            failMsg.append(String.format("\n  trace=%d sample=%d: zero nao preservado (recovered=%.9e)", t, s, rec));
                        }
                        continue;
                    }

                    float absOrig = Math.abs(orig);
                    float delta = Math.abs(orig - rec);
                    float relError = delta / absOrig;
                    maxRelError = Math.max(maxRelError, relError);

                    if (relError > IBM_ROUND_TRIP_REL_EPSILON) {
                        failures++;
                        if (failures <= 5) { // limita output
                            failMsg.append(String.format(
                                    "\n  trace=%d sample=%d: orig=%.9e rec=%.9e relErr=%.3e (max=%.3e)",
                                    t, s, orig, rec, relError, IBM_ROUND_TRIP_REL_EPSILON));
                        }
                    }
                }
            }

            System.out.printf("SegyIOFormatCode1Test: maxRelError=%.3e (epsilon documentado=%.3e)%n",
                    maxRelError, IBM_ROUND_TRIP_REL_EPSILON);

            assertEquals(0, failures,
                    "Round-trip format code 1 falhou para " + failures + " amostras. " +
                    "Se este erro aumentou vs. execucoes anteriores, houve REGRESSAO." + failMsg);

        } finally {
            Files.deleteIfExists(tmpSegy);
        }
    }

    @Test
    void segyWriteReadRoundTripFormatCode1_deltas() throws Exception {
        // Testa o ciclo completo: SegyIO.write() com format code 1 -> SegyIO.read()
        // Isso valida que write() e read() sao consistentes entre si.
        int samplesPerTrace = 32;
        int traceCount = 3;

        float[] seq = new float[samplesPerTrace];
        for (int i = 0; i < samplesPerTrace; i++) {
            seq[i] = (i % 5 == 0) ? 0.0f : (float) Math.sin(i * 0.3) * 50.0f;
        }

        // Prepara SegyDataset sintetico com format code 1
        byte[] textHeader = new byte[3200];
        byte[] binHeader  = new byte[400];
        // Seta samplesPerTrace no binary header (bytes 20-21, big-endian)
        binHeader[20] = (byte) ((samplesPerTrace >> 8) & 0xFF);
        binHeader[21] = (byte) (samplesPerTrace & 0xFF);
        // Seta format code 1 (bytes 24-25)
        binHeader[24] = 0x00;
        binHeader[25] = 0x01;

        List<byte[]> traceHeaders = new ArrayList<>();
        List<TraceBlock> traces   = new ArrayList<>();
        for (int t = 0; t < traceCount; t++) {
            traceHeaders.add(new byte[240]);
            float[] s = seq.clone();
            // Varia levemente cada trace
            for (int i = 0; i < s.length; i++) s[i] += t * 0.01f;
            traces.add(new TraceBlock(t, s));
        }

        // Constroi o dataset (TraceMeta e TraceGrid podem ser null/vazios para este teste)
        SegyIO.SegyDataset originalDataset = new SegyIO.SegyDataset(
                textHeader, binHeader, traceHeaders, traces,
                samplesPerTrace, 1,
                new ArrayList<>(), null);

        Path tmpOut = Files.createTempFile("segy_write_fc1_", ".sgy");
        try {
            SegyIO.write(tmpOut, originalDataset, traces);
            SegyIO.SegyDataset readBack = SegyIO.read(tmpOut);

            assertEquals(1, readBack.sampleFormatCode);
            assertEquals(traceCount, readBack.traceCount());

            float maxAbsError = 0f;
            for (int t = 0; t < traceCount; t++) {
                float[] orig = traces.get(t).samples();
                float[] rec  = readBack.traces.get(t).samples();
                for (int i = 0; i < samplesPerTrace; i++) {
                    float err = Math.abs(orig[i] - rec[i]);
                    maxAbsError = Math.max(maxAbsError, err);
                }
            }

            System.out.printf("SegyIOFormatCode1Test.write-read: maxAbsError=%.6e%n", maxAbsError);

            // O erro absoluto maximo deve ser compativel com o epsilon IBM documentado.
            // Para valores no range [-50, 50], epsilon relativo 4e-6 implica abs ~2e-4.
            float absEpsilonForRange = IBM_ROUND_TRIP_REL_EPSILON * 50.0f * 2; // fator 2 de folga
            assertTrue(maxAbsError <= absEpsilonForRange,
                    String.format("maxAbsError=%.6e excede o limite tolerado=%.6e para range ~50",
                            maxAbsError, absEpsilonForRange));

        } finally {
            Files.deleteIfExists(tmpOut);
        }
    }

    // -------------------------------------------------------------------------
    // Teste de nao-regressao: format code 5 nao e afetado por este ticket
    // -------------------------------------------------------------------------

    @Test
    void formatCode5_naoAfetadoPorAlteracoesDesteTicket() throws Exception {
        // Garante que as alteracoes em SegyIO.java nao quebraram format code 5.
        int samplesPerTrace = 16;
        int traceCount = 2;

        byte[] textHeader = new byte[3200];
        byte[] binHeader  = new byte[400];
        binHeader[20] = (byte) ((samplesPerTrace >> 8) & 0xFF);
        binHeader[21] = (byte) (samplesPerTrace & 0xFF);
        binHeader[24] = 0x00;
        binHeader[25] = 0x05; // format code 5 = IEEE float32

        List<byte[]> traceHeaders = new ArrayList<>();
        List<TraceBlock> traces   = new ArrayList<>();
        for (int t = 0; t < traceCount; t++) {
            traceHeaders.add(new byte[240]);
            float[] s = new float[samplesPerTrace];
            for (int i = 0; i < samplesPerTrace; i++) {
                s[i] = (float) (Math.PI * (t + 1) * i);
            }
            traces.add(new TraceBlock(t, s));
        }

        SegyIO.SegyDataset dataset = new SegyIO.SegyDataset(
                textHeader, binHeader, traceHeaders, traces,
                samplesPerTrace, 5,
                new ArrayList<>(), null);

        Path tmpOut = Files.createTempFile("segy_fc5_regression_", ".sgy");
        try {
            SegyIO.write(tmpOut, dataset, traces);
            SegyIO.SegyDataset readBack = SegyIO.read(tmpOut);

            assertEquals(5, readBack.sampleFormatCode,
                    "Format code 5 deve ser preservado");
            assertEquals(traceCount, readBack.traceCount());

            // Format code 5: deve ser BIT-A-BIT EXATO (zero epsilon)
            for (int t = 0; t < traceCount; t++) {
                float[] orig = traces.get(t).samples();
                float[] rec  = readBack.traces.get(t).samples();
                for (int i = 0; i < samplesPerTrace; i++) {
                    assertEquals(orig[i], rec[i], 0.0f,
                            String.format("Format code 5 nao e bit-exato: trace=%d sample=%d orig=%f rec=%f",
                                    t, i, orig[i], rec[i]));
                }
            }
        } finally {
            Files.deleteIfExists(tmpOut);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    /**
     * Escreve um arquivo SEG-Y sintetico com format code 1 (IBM float32) diretamente,
     * codificando as amostras com SegyIO.floatToIbm() que e o metodo de producao.
     */
    private static void writeSegyFormatCode1(Path path, float[][] samples, int samplesPerTrace)
            throws Exception {
        int traceCount = samples.length;

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {

            // Textual header: 3200 bytes zerados (valido por SegyIO que nao valida conteudo EBCDIC)
            out.write(new byte[3200]);

            // Binary header: 400 bytes
            byte[] binHeader = new byte[400];
            // samplesPerTrace em bytes 20-21 (0-indexed)
            binHeader[20] = (byte) ((samplesPerTrace >> 8) & 0xFF);
            binHeader[21] = (byte) (samplesPerTrace & 0xFF);
            // format code = 1 em bytes 24-25
            binHeader[24] = 0x00;
            binHeader[25] = 0x01;
            out.write(binHeader);

            // Traces
            for (int t = 0; t < traceCount; t++) {
                // Trace header: 240 bytes
                out.write(new byte[240]);
                // Amostras em IBM float32
                for (int s = 0; s < samplesPerTrace; s++) {
                    int ibmBits = SegyIO.floatToIbm(samples[t][s]);
                    out.writeInt(ibmBits);
                }
            }
            out.flush();
        }
    }
}

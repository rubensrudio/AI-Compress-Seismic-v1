package com.sdc.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class TraceBlockCodecTest {

    // -----------------------------------------------------------------------
    // Teste original — deve continuar verde com identity()
    // -----------------------------------------------------------------------

    @Test
    void compressDecompressRoundTrip() {
        // Sinal suave + um pouco de "ruído"
        int n = 1024;
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float v = (float) Math.sin(2 * Math.PI * 5 * t); // senoide 5 ciclos
            v += (float) (0.02 * Math.random());            // ruídinho
            samples[i] = v;
        }

        TraceBlock tb = new TraceBlock(42, samples);

        CompressedTraceBlock cb = TraceBlockCodec.compress(tb);
        TraceBlock rec = TraceBlockCodec.decompress(cb);

        assertEquals(tb.traceId(), rec.traceId());
        assertEquals(samples.length, rec.samples().length);

        double psnr = LinearQuantizer.psnr(samples, rec.samples());
        System.out.println("PSNR = " + psnr + " dB, payloadBytes=" + cb.payload().length);

        // qualidade mínima
        assertTrue(psnr > 35.0, "PSNR muito baixo: " + psnr);
    }

    // -----------------------------------------------------------------------
    // Novos testes para TASK-003
    // -----------------------------------------------------------------------

    /**
     * Confirma que TracePredictor.identity() produz round-trip perfeito
     * (comportamento determinístico sem AI).
     */
    @Test
    void identityPredictorPreservesRoundTrip() {
        int n = 512;
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            samples[i] = (float) Math.cos(2 * Math.PI * 3 * i / n) * 100f;
        }

        TraceBlock tb = new TraceBlock(7, samples);
        TracePredictor identity = TracePredictor.identity();

        // via overload estático com predictor explícito
        CompressedTraceBlock cb = TraceBlockCodec.compress(tb, identity);
        TraceBlock rec = TraceBlockCodec.decompress(cb, identity);

        assertEquals(tb.traceId(), rec.traceId());
        assertEquals(n, rec.samples().length);

        double psnr = LinearQuantizer.psnr(samples, rec.samples());
        assertTrue(psnr > 35.0, "PSNR com identity abaixo do mínimo: " + psnr);
    }

    /**
     * Verifica que encode() e decode() do predictor são invocados
     * exatamente uma vez durante compress e decompress, respectivamente.
     *
     * Este é o critério de verificação central da TASK-003:
     * "confirmar que o predictor é chamado no slot correto do pipeline".
     */
    @Test
    void predictorIsInvokedInCorrectPipelineSlot() {
        AtomicInteger encodeCalls = new AtomicInteger(0);
        AtomicInteger decodeCalls = new AtomicInteger(0);

        // Spy: registra invocações e delega para identity
        TracePredictor spy = new TracePredictor() {
            @Override
            public float[] encode(float[] deltas) {
                encodeCalls.incrementAndGet();
                // Verifica que recebe os deltas (não as amostras brutas nem os residuals já quantizados)
                assertNotNull(deltas, "encode() recebeu null");
                assertTrue(deltas.length > 0, "encode() recebeu array vazio");
                return TracePredictor.identity().encode(deltas);
            }

            @Override
            public float[] decode(float[] residuals) {
                decodeCalls.incrementAndGet();
                assertNotNull(residuals, "decode() recebeu null");
                assertTrue(residuals.length > 0, "decode() recebeu array vazio");
                return TracePredictor.identity().decode(residuals);
            }
        };

        float[] samples = {1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f};
        TraceBlock tb = new TraceBlock(99, samples);

        // Encode: predictor.encode() deve ser chamado exatamente 1 vez
        CompressedTraceBlock cb = TraceBlockCodec.compress(tb, spy);
        assertEquals(1, encodeCalls.get(),
                "predictor.encode() deve ser invocado exatamente 1x durante compress");
        assertEquals(0, decodeCalls.get(),
                "predictor.decode() não deve ser invocado durante compress");

        // Decode: predictor.decode() deve ser chamado exatamente 1 vez
        TraceBlock rec = TraceBlockCodec.decompress(cb, spy);
        assertEquals(1, encodeCalls.get(),
                "predictor.encode() não deve ser invocado durante decompress");
        assertEquals(1, decodeCalls.get(),
                "predictor.decode() deve ser invocado exatamente 1x durante decompress");

        assertEquals(tb.traceId(), rec.traceId());
        assertEquals(samples.length, rec.samples().length);
    }

    /**
     * Verifica que a API de instância (construtor com TracePredictor)
     * delega corretamente para o predictor injetado.
     */
    @Test
    void instanceApiDelegatesToInjectedPredictor() {
        AtomicInteger encodeCalls = new AtomicInteger(0);
        AtomicInteger decodeCalls = new AtomicInteger(0);

        TracePredictor spy = new TracePredictor() {
            @Override
            public float[] encode(float[] deltas) {
                encodeCalls.incrementAndGet();
                return TracePredictor.identity().encode(deltas);
            }

            @Override
            public float[] decode(float[] residuals) {
                decodeCalls.incrementAndGet();
                return TracePredictor.identity().decode(residuals);
            }
        };

        TraceBlockCodec codec = new TraceBlockCodec(spy);
        float[] samples = new float[64];
        for (int i = 0; i < 64; i++) samples[i] = i * 0.5f;

        TraceBlock tb = new TraceBlock(1, samples);
        CompressedTraceBlock cb = codec.compressBlock(tb);
        assertEquals(1, encodeCalls.get(), "compressBlock() deve chamar predictor.encode() 1x");

        codec.decompressBlock(cb);
        assertEquals(1, decodeCalls.get(), "decompressBlock() deve chamar predictor.decode() 1x");
    }

    /**
     * Garante que o construtor padrão (sem TracePredictor) usa identity()
     * e produz resultado equivalente ao método estático compress/decompress.
     */
    @Test
    void defaultConstructorUsesIdentityPredictor() {
        float[] samples = new float[128];
        for (int i = 0; i < 128; i++) samples[i] = (float) Math.sin(i * 0.1);

        TraceBlock tb = new TraceBlock(5, samples);

        // Via métodos estáticos (retrocompatibilidade)
        CompressedTraceBlock cbStatic = TraceBlockCodec.compress(tb);
        TraceBlock recStatic = TraceBlockCodec.decompress(cbStatic);

        // Via construtor padrão
        TraceBlockCodec codec = new TraceBlockCodec();
        CompressedTraceBlock cbInstance = codec.compressBlock(tb);
        TraceBlock recInstance = codec.decompressBlock(cbInstance);

        // Ambos devem produzir PSNR aceitável
        double psnrStatic = LinearQuantizer.psnr(samples, recStatic.samples());
        double psnrInstance = LinearQuantizer.psnr(samples, recInstance.samples());

        assertTrue(psnrStatic > 35.0, "PSNR static abaixo do mínimo: " + psnrStatic);
        assertTrue(psnrInstance > 35.0, "PSNR instance abaixo do mínimo: " + psnrInstance);
    }

    /**
     * Garante que construtor com null lança IllegalArgumentException.
     */
    @Test
    void constructorRejectsNullPredictor() {
        assertThrows(IllegalArgumentException.class,
                () -> new TraceBlockCodec(null),
                "Construtor deve rejeitar predictor null");
    }
}

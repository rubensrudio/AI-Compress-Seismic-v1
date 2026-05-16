package com.sdc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TraceBlockCodecTest {

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
}

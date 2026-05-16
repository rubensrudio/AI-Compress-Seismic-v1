package com.sdc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LinearQuantizerTest {

    @Test
    void quantizeAndRecover() {
        float[] original = {-1f, -0.5f, 0f, 0.5f, 1f};

        short[] q = LinearQuantizer.encode(original);
        float[] rec = LinearQuantizer.decode(q);

        assertEquals(original.length, rec.length);
        double psnr = LinearQuantizer.psnr(original, rec);
        // deve ser bem alto, pois o erro é só de quantização
        assertTrue(psnr > 40.0, "PSNR muito baixo: " + psnr);
    }
}

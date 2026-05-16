package com.sdc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PreprocessingTest {

    @Test
    void deltaEncodeDecodeRoundTrip() {
        float[] s = {1f, 2f, 4f, 3f};
        float[] deltas = Preprocessing.deltaEncode(s);
        float[] rec = Preprocessing.deltaDecode(deltas);

        assertEquals(s.length, rec.length);
        for (int i = 0; i < s.length; i++) {
            assertEquals(s[i], rec[i], 1e-6);
        }
    }

    @Test
    void normalizeToMinusOneToOneRange() {
        float[] s = {10f, 20f, 30f, 40f};
        float[] n = Preprocessing.normalizeToMinusOneToOne(s);

        assertEquals(s.length, n.length);
        for (float v : n) {
            assertTrue(v >= -1.0001f && v <= 1.0001f);
        }
    }
}

package com.sdc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TraceBlockTest {
    @Test
    void roundTripBuffer() {
        float[] s = new float[]{1f,2f,3f};
        TraceBlock tb = new TraceBlock(7, s);
        assertEquals(7, tb.traceId());
        assertArrayEquals(s, tb.samples());
        assertTrue(tb.toByteBuffer().remaining() > 0);
    }
}

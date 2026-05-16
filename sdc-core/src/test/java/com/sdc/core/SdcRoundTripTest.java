package com.sdc.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SdcRoundTripTest {

    @Test
    void writeAndReadSdc() throws Exception {
        float[] s1 = {1f,2f,3f};
        float[] s2 = {4f,5f,6f};
        TraceBlock t1 = new TraceBlock(10, s1);
        TraceBlock t2 = new TraceBlock(11, s2);

        Path tmp = Files.createTempFile("test", ".sdc");
        SdcFileWriter.write(tmp, List.of(t1, t2));

        SdcHeader header = SdcFileReader.readHeader(tmp);
        assertEquals(2, header.traceCount());
        assertEquals(3, header.samplesPerTrace());

        var traces = SdcFileReader.readAll(tmp);
        assertEquals(2, traces.size());
        assertEquals(10, traces.get(0).traceId());
        assertEquals(11, traces.get(1).traceId());
    }
}

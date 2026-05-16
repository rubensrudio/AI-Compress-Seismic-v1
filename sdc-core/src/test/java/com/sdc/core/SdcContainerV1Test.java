package com.sdc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para SdcContainerV1.
 *
 * Cobre os critérios de verificação da TASK-004:
 *  - Escreve e relê um SdcContainerV1 sintético
 *  - Verifica que magic e UUID são preservados byte-a-byte
 *  - Verifica que leitura de arquivo .sdc sem UUID (protótipo) lança IllegalArgumentException
 */
class SdcContainerV1Test {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Cria um container sintético mínimo com dados preenchidos. */
    private SdcContainerV1 buildSyntheticContainer(UUID modelUuid) {
        byte[] ebcdic = new byte[3200];
        Arrays.fill(ebcdic, (byte) 0x40); // espaço EBCDIC
        ebcdic[0] = 0x63; // 'C'

        byte[] binHeader = new byte[400];
        // samplesPerTrace=100 (big-endian, bytes 114-115, offset 0-indexed)
        binHeader[114] = 0x00;
        binHeader[115] = 0x64; // 100

        int traceCount = 3;
        int samplesPerTrace = 100;
        int sampleFormatCode = 5; // IEEE float32

        // trace headers blob: 240 bytes * 3 traços
        byte[] traceHeadersBlob = new byte[240 * traceCount];
        for (int i = 0; i < traceHeadersBlob.length; i++) {
            traceHeadersBlob[i] = (byte) (i & 0xFF);
        }

        // blocos comprimidos sintéticos: 3 CompressedTraceBlock
        CompressionProfile profile = CompressionProfile.defaultHighQuality();
        TraceBlock[] traces = new TraceBlock[traceCount];
        for (int i = 0; i < traceCount; i++) {
            float[] samples = new float[samplesPerTrace];
            for (int j = 0; j < samplesPerTrace; j++) {
                samples[j] = (float) Math.sin(2 * Math.PI * j / samplesPerTrace + i);
            }
            traces[i] = new TraceBlock(i, samples);
        }
        List<CompressedTraceBlock> compressedBlocks = new java.util.ArrayList<>();
        for (TraceBlock tb : traces) {
            compressedBlocks.add(TraceBlockCodec.compress(tb, profile));
        }

        return new SdcContainerV1(
                (short) 1,
                modelUuid,
                ebcdic,
                binHeader,
                traceCount,
                samplesPerTrace,
                sampleFormatCode,
                traceHeadersBlob,
                compressedBlocks
        );
    }

    // -----------------------------------------------------------------------
    // Teste 1: round-trip write/read preserva todos os campos
    // -----------------------------------------------------------------------

    @Test
    void writeAndReadPreservesAllFields() throws Exception {
        UUID modelUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        SdcContainerV1 original = buildSyntheticContainer(modelUuid);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        original.write(baos);
        byte[] serialized = baos.toByteArray();

        assertTrue(serialized.length > 0, "Serialized output must not be empty");

        SdcContainerV1 recovered = SdcContainerV1.read(new ByteArrayInputStream(serialized));

        assertEquals(original.codecVersion(), recovered.codecVersion(),
                "codec_version must be preserved");
        assertEquals(original.modelUuid(), recovered.modelUuid(),
                "model_uuid must be preserved");
        assertEquals(original.traceCount(), recovered.traceCount(),
                "trace_count must be preserved");
        assertEquals(original.samplesPerTrace(), recovered.samplesPerTrace(),
                "samples_per_trace must be preserved");
        assertEquals(original.sampleFormatCode(), recovered.sampleFormatCode(),
                "sample_format_code must be preserved");
        assertArrayEquals(original.segyTextualHeader(), recovered.segyTextualHeader(),
                "segy_textual_header must be preserved byte-a-byte");
        assertArrayEquals(original.segyBinaryHeader(), recovered.segyBinaryHeader(),
                "segy_binary_header must be preserved byte-a-byte");
        assertArrayEquals(original.traceHeadersBlob(), recovered.traceHeadersBlob(),
                "trace_headers_blob must be preserved byte-a-byte");
        assertEquals(original.compressedBlocks().size(), recovered.compressedBlocks().size(),
                "number of compressed blocks must be preserved");
    }

    // -----------------------------------------------------------------------
    // Teste 2: magic 0x53444301 é escrito corretamente (primeiros 4 bytes)
    // -----------------------------------------------------------------------

    @Test
    void magicBytesAreWrittenCorrectly() throws Exception {
        UUID modelUuid = UUID.randomUUID();
        SdcContainerV1 container = buildSyntheticContainer(modelUuid);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        container.write(baos);
        byte[] bytes = baos.toByteArray();

        // Magic 0x53444301 = 'S'(0x53) 'D'(0x44) 'C'(0x43) 0x01
        assertEquals((byte) 0x53, bytes[0], "magic byte 0 must be 0x53 ('S')");
        assertEquals((byte) 0x44, bytes[1], "magic byte 1 must be 0x44 ('D')");
        assertEquals((byte) 0x43, bytes[2], "magic byte 2 must be 0x43 ('C')");
        assertEquals((byte) 0x01, bytes[3], "magic byte 3 must be 0x01");
    }

    // -----------------------------------------------------------------------
    // Teste 3: UUID é preservado byte-a-byte no round-trip
    // -----------------------------------------------------------------------

    @Test
    void uuidIsPreservedByteForByte() throws Exception {
        // UUID com todos os bits significativos
        UUID modelUuid = UUID.fromString("12345678-9abc-def0-1234-567890abcdef");
        SdcContainerV1 container = buildSyntheticContainer(modelUuid);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        container.write(baos);

        SdcContainerV1 recovered = SdcContainerV1.read(new ByteArrayInputStream(baos.toByteArray()));

        assertEquals(modelUuid, recovered.modelUuid(),
                "UUID must survive a full write/read cycle byte-a-byte");
        assertEquals(modelUuid.getMostSignificantBits(), recovered.modelUuid().getMostSignificantBits(),
                "UUID MSB must be preserved");
        assertEquals(modelUuid.getLeastSignificantBits(), recovered.modelUuid().getLeastSignificantBits(),
                "UUID LSB must be preserved");
    }

    // -----------------------------------------------------------------------
    // Teste 4: leitura de .sdc do protótipo (magic 0x53444331) lança
    //          IllegalArgumentException com mensagem descritiva
    // -----------------------------------------------------------------------

    @Test
    void readPrototypeSdcWithoutUuidThrowsIllegalArgumentException() {
        // Simula o header do protótipo (SdcHeader):
        // magic=0x53444331, version=2, traceCount=5, samplesPerTrace=100
        // Isto é, 4 ints (4 bytes cada) = 16 bytes
        byte[] protoHeader = new byte[16];
        // magic 0x53444331 big-endian
        protoHeader[0] = 0x53;
        protoHeader[1] = 0x44;
        protoHeader[2] = 0x43;
        protoHeader[3] = 0x31;
        // version=2
        protoHeader[4] = 0x00;
        protoHeader[5] = 0x00;
        protoHeader[6] = 0x00;
        protoHeader[7] = 0x02;
        // traceCount=5
        protoHeader[8] = 0x00;
        protoHeader[9] = 0x00;
        protoHeader[10] = 0x00;
        protoHeader[11] = 0x05;
        // samplesPerTrace=100
        protoHeader[12] = 0x00;
        protoHeader[13] = 0x00;
        protoHeader[14] = 0x00;
        protoHeader[15] = 0x64;

        InputStream is = new ByteArrayInputStream(protoHeader);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SdcContainerV1.read(is),
                "Reading a prototype .sdc (without UUID) must throw IllegalArgumentException"
        );

        String msg = ex.getMessage();
        assertNotNull(msg, "Exception message must not be null");
        assertTrue(msg.contains("0x53444331") || msg.toLowerCase().contains("incompatible")
                        || msg.toLowerCase().contains("prototype") || msg.toLowerCase().contains("magic"),
                "Exception message must describe the incompatibility. Got: " + msg);
    }

    // -----------------------------------------------------------------------
    // Teste 5: leitura de stream com magic totalmente inválido lança
    //          IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void readInvalidMagicThrowsIllegalArgumentException() {
        byte[] garbage = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        InputStream is = new ByteArrayInputStream(garbage);

        assertThrows(
                IllegalArgumentException.class,
                () -> SdcContainerV1.read(is),
                "Reading a stream with invalid magic must throw IllegalArgumentException"
        );
    }

    // -----------------------------------------------------------------------
    // Teste 6: MAGIC constante tem o valor correto
    // -----------------------------------------------------------------------

    @Test
    void magicConstantHasCorrectValue() {
        assertEquals(0x53444301, SdcContainerV1.MAGIC,
                "MAGIC constant must equal 0x53444301");
    }
}

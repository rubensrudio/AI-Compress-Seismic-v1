package com.sdc.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link SegyValidator}.
 *
 * <p>Cada teste gera uma fixture SEG-Y programaticamente (sem I/O de disco),
 * injeta corrupção quando necessário e verifica o comportamento do validador.</p>
 *
 * <p>Layout de um arquivo SEG-Y Rev1 mínimo (1 traço, format code 5):</p>
 * <pre>
 *   [0    – 3199]  : Header EBCDIC (3200 bytes, codificação EBCDIC)
 *   [3200 – 3599]  : Binary header (400 bytes, big-endian)
 *     [3220 – 3221] : samplesPerTrace (unsigned short BE, offset 20 no BH)
 *     [3224 – 3225] : formatCode     (unsigned short BE, offset 24 no BH)
 *   [3600 – 3839]  : Trace header (240 bytes)
 *   [3840 – ...]   : Amostras float32 big-endian (samplesPerTrace × 4 bytes)
 * </pre>
 */
class SegyValidatorTest {

    // -------------------------------------------------------------------------
    // Constantes de layout
    // -------------------------------------------------------------------------

    private static final int EBCDIC_SIZE       = 3200;
    private static final int BINARY_HDR_SIZE   = 400;
    private static final int TRACE_HDR_SIZE    = 240;
    private static final int BYTES_PER_SAMPLE  = 4;

    /** Amostras por traço usadas nas fixtures de teste. */
    private static final int SAMPLES_PER_TRACE = 10;

    /** Format code 5 = IEEE float32 (suportado pelo SegyValidator). */
    private static final int FORMAT_CODE_IEEE  = 5;

    // -------------------------------------------------------------------------
    // Fábrica de fixture mínima válida
    // -------------------------------------------------------------------------

    /**
     * Gera um array de bytes representando um arquivo SEG-Y Rev1 mínimo válido:
     * 1 traço, {@value #SAMPLES_PER_TRACE} amostras, format code 5 (IEEE float32).
     *
     * <p>O header EBCDIC é preenchido com 0xC1 (letra 'A' em EBCDIC), garantindo
     * que os primeiros 3200 bytes contenham bytes no range EBCDIC (> 0x7F) e
     * passem na verificação de prefixo EBCDIC do validador.</p>
     *
     * <p>O binary header tem todos os bytes em zero exceto os campos
     * samplesPerTrace (bytes 20-21) e formatCode (bytes 24-25).</p>
     *
     * <p>O trace header tem todos os bytes em zero (padding válido).</p>
     *
     * <p>As amostras são floats IEEE 754 com valores simples (1.0, 2.0, ...)
     * serializados em big-endian.</p>
     *
     * @return array de bytes de um SEG-Y Rev1 mínimo e válido
     */
    private static byte[] buildMinimalValidSegy() {
        int traceDataSize = TRACE_HDR_SIZE + SAMPLES_PER_TRACE * BYTES_PER_SAMPLE;
        int totalSize = EBCDIC_SIZE + BINARY_HDR_SIZE + traceDataSize;
        byte[] segy = new byte[totalSize];

        // --- EBCDIC header (0 – 3199) ---
        // 0xC1 = letra 'A' em EBCDIC; byte > 0x7E, passa na verificação EBCDIC
        Arrays.fill(segy, 0, EBCDIC_SIZE, (byte) 0xC1);

        // --- Binary header (3200 – 3599) ---
        // Começamos do zero; apenas os campos obrigatórios são preenchidos.
        int bhBase = EBCDIC_SIZE;

        // samplesPerTrace: bytes 20-21 do binary header (big-endian)
        writeUnsignedShortBE(segy, bhBase + 20, SAMPLES_PER_TRACE);

        // formatCode: bytes 24-25 do binary header (big-endian)
        writeUnsignedShortBE(segy, bhBase + 24, FORMAT_CODE_IEEE);

        // --- Trace header (3600 – 3839) ---
        // Todo zero — válido como padding.

        // --- Amostras float32 big-endian (3840 – 3879) ---
        int sampleBase = EBCDIC_SIZE + BINARY_HDR_SIZE + TRACE_HDR_SIZE;
        for (int i = 0; i < SAMPLES_PER_TRACE; i++) {
            int bits = Float.floatToIntBits((float) (i + 1));
            writeIntBE(segy, sampleBase + i * BYTES_PER_SAMPLE, bits);
        }

        return segy;
    }

    // -------------------------------------------------------------------------
    // Utilitários de serialização big-endian
    // -------------------------------------------------------------------------

    private static void writeUnsignedShortBE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >> 8) & 0xFF);
        buf[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeIntBE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 8)  & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    // -------------------------------------------------------------------------
    // Teste 1: arquivo mínimo válido — sem exceção
    // -------------------------------------------------------------------------

    /**
     * Arquivo SEG-Y Rev1 mínimo e válido deve passar sem lançar exceção.
     */
    @Test
    void validMinimalFile_shouldNotThrow() {
        byte[] segy = buildMinimalValidSegy();
        assertDoesNotThrow(() -> SegyValidator.validate(segy),
            "Arquivo SEG-Y mínimo válido não deve lançar SegyValidationException");
    }

    /**
     * Validação via Path deve funcionar igual a validação via array de bytes.
     */
    @Test
    void validMinimalFile_viaPath_shouldNotThrow() throws Exception {
        byte[] segy = buildMinimalValidSegy();
        Path tmp = Files.createTempFile("segy-valid-", ".sgy");
        try {
            Files.write(tmp, segy);
            assertDoesNotThrow(() -> {
                try {
                    SegyValidator.validate(tmp);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }, "Validação via Path deve passar para arquivo SEG-Y mínimo válido");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // -------------------------------------------------------------------------
    // Teste 2: arquivo abaixo do tamanho mínimo
    // -------------------------------------------------------------------------

    /**
     * Arquivo menor que 3600 bytes deve falhar com offset 0.
     */
    @Test
    void fileTooShort_shouldThrowAtOffset0() {
        // Apenas 100 bytes — longe do mínimo
        byte[] tooShort = new byte[100];

        SegyValidationException ex = assertThrows(SegyValidationException.class,
            () -> SegyValidator.validate(tooShort),
            "Arquivo abaixo do tamanho mínimo deve lançar SegyValidationException");

        assertEquals(0L, ex.getByteOffset(),
            "Arquivo muito curto deve reportar offset 0");
        assertTrue(ex.getMessage().contains("muito curto"),
            "Mensagem deve indicar arquivo muito curto");
    }

    // -------------------------------------------------------------------------
    // Teste 3: corrupção no header EBCDIC (bytes ASCII puro)
    // -------------------------------------------------------------------------

    /**
     * Header EBCDIC preenchido com bytes ASCII puro (0x20–0x7E) deve ser rejeitado.
     * O offset reportado deve ser 0 (início do header EBCDIC).
     */
    @Test
    void ebcdicHeaderCorrupted_asciiPureBytes_shouldThrowAtOffset0() {
        byte[] segy = buildMinimalValidSegy();

        // Corromper o header EBCDIC substituindo 0xC1 por 0x41 ('A' em ASCII)
        // Todos os bytes ficam no range 0x20-0x7E (ASCII imprimível puro)
        Arrays.fill(segy, 0, EBCDIC_SIZE, (byte) 0x41);

        SegyValidationException ex = assertThrows(SegyValidationException.class,
            () -> SegyValidator.validate(segy),
            "Header EBCDIC com ASCII puro deve lançar SegyValidationException");

        assertEquals(0L, ex.getByteOffset(),
            "Corrupção no header EBCDIC deve reportar offset 0");
        assertTrue(ex.getMessage().toLowerCase().contains("ebcdic"),
            "Mensagem deve mencionar EBCDIC");
    }

    // -------------------------------------------------------------------------
    // Teste 4: corrupção no binary header — samplesPerTrace = 0
    // -------------------------------------------------------------------------

    /**
     * samplesPerTrace = 0 no binary header deve ser rejeitado.
     * O offset reportado deve apontar para o início do campo samplesPerTrace
     * no binary header (byte 3220 = EBCDIC_SIZE + 20).
     */
    @Test
    void binaryHeaderCorrupted_samplesPerTraceZero_shouldThrowAtCorrectOffset() {
        byte[] segy = buildMinimalValidSegy();

        // Corromper: setar samplesPerTrace = 0
        int samplesFieldOffset = EBCDIC_SIZE + 20;
        writeUnsignedShortBE(segy, samplesFieldOffset, 0);

        SegyValidationException ex = assertThrows(SegyValidationException.class,
            () -> SegyValidator.validate(segy),
            "samplesPerTrace=0 deve lançar SegyValidationException");

        assertEquals((long) samplesFieldOffset, ex.getByteOffset(),
            "Offset deve apontar para o campo samplesPerTrace no binary header (byte " +
            samplesFieldOffset + ")");
        assertTrue(ex.getMessage().contains("samplesPerTrace"),
            "Mensagem deve mencionar samplesPerTrace");
    }

    /**
     * formatCode não suportado (ex: 2) deve ser rejeitado.
     * O offset reportado deve apontar para o início do campo formatCode
     * no binary header (byte 3224 = EBCDIC_SIZE + 24).
     */
    @Test
    void binaryHeaderCorrupted_unsupportedFormatCode_shouldThrowAtCorrectOffset() {
        byte[] segy = buildMinimalValidSegy();

        // Corromper: setar formatCode = 2 (não suportado)
        int formatCodeOffset = EBCDIC_SIZE + 24;
        writeUnsignedShortBE(segy, formatCodeOffset, 2);

        SegyValidationException ex = assertThrows(SegyValidationException.class,
            () -> SegyValidator.validate(segy),
            "formatCode=2 deve lançar SegyValidationException");

        assertEquals((long) formatCodeOffset, ex.getByteOffset(),
            "Offset deve apontar para o campo formatCode no binary header (byte " +
            formatCodeOffset + ")");
        assertTrue(ex.getMessage().contains("formatCode"),
            "Mensagem deve mencionar formatCode");
    }

    // -------------------------------------------------------------------------
    // Teste 5: traço truncado no meio do arquivo
    // -------------------------------------------------------------------------

    /**
     * Arquivo com traço truncado (dados incompletos) deve ser rejeitado.
     * O offset reportado deve apontar para o início do traço truncado.
     */
    @Test
    void traceTruncated_midFile_shouldThrowAtTraceOffset() {
        byte[] validSegy = buildMinimalValidSegy();

        // Criar versão truncada: remover os últimos 3 bytes (corta dentro das amostras)
        int truncatedSize = validSegy.length - 3;
        byte[] truncated = Arrays.copyOf(validSegy, truncatedSize);

        SegyValidationException ex = assertThrows(SegyValidationException.class,
            () -> SegyValidator.validate(truncated),
            "Arquivo com traço truncado deve lançar SegyValidationException");

        // O offset deve apontar para o início do traço corrompido.
        // Com 1 traço e a corrupção nos últimos bytes, o traço truncado começa
        // em EBCDIC_SIZE + BINARY_HDR_SIZE (= 3600).
        long expectedTraceStart = EBCDIC_SIZE + BINARY_HDR_SIZE;
        assertEquals(expectedTraceStart, ex.getByteOffset(),
            "Offset deve apontar para o início do traço truncado (byte " +
            expectedTraceStart + ")");
        assertTrue(ex.getMessage().contains("truncado") || ex.getMessage().contains("trunc"),
            "Mensagem deve indicar traço truncado. Mensagem: " + ex.getMessage());
    }

    /**
     * Arquivo com 2 traços onde o segundo está truncado deve reportar o offset
     * do início do segundo traço.
     */
    @Test
    void secondTraceTruncated_shouldThrowAtSecondTraceOffset() {
        // Construir SEG-Y com 2 traços completos e depois truncar o segundo
        byte[] valid = buildMinimalValidSegy();
        int oneTraceSize = TRACE_HDR_SIZE + SAMPLES_PER_TRACE * BYTES_PER_SAMPLE;

        // Duplicar a região de traços para ter 2 traços
        int totalWithTwoTraces = valid.length + oneTraceSize;
        byte[] twoTraces = Arrays.copyOf(valid, totalWithTwoTraces);
        // O segundo traço começa depois do primeiro, copiamos o primeiro como template
        System.arraycopy(valid, EBCDIC_SIZE + BINARY_HDR_SIZE,
                         twoTraces, EBCDIC_SIZE + BINARY_HDR_SIZE + oneTraceSize,
                         oneTraceSize);

        // Truncar o segundo traço (remover os últimos 5 bytes)
        byte[] truncated = Arrays.copyOf(twoTraces, twoTraces.length - 5);

        SegyValidationException ex = assertThrows(SegyValidationException.class,
            () -> SegyValidator.validate(truncated),
            "Segundo traço truncado deve lançar SegyValidationException");

        // O segundo traço começa em: EBCDIC + BH + 1*traceSize
        long secondTraceStart = (long) EBCDIC_SIZE + BINARY_HDR_SIZE + oneTraceSize;
        assertEquals(secondTraceStart, ex.getByteOffset(),
            "Offset deve apontar para o início do segundo traço truncado (byte " +
            secondTraceStart + ")");
    }

    // -------------------------------------------------------------------------
    // Teste adicional: formato 1 (IBM float32) também deve ser aceito
    // -------------------------------------------------------------------------

    /**
     * Format code 1 (IBM float32) também é suportado e deve passar na validação.
     */
    @Test
    void validFile_formatCode1_shouldNotThrow() {
        byte[] segy = buildMinimalValidSegy();

        // Substituir format code 5 por 1
        int formatCodeOffset = EBCDIC_SIZE + 24;
        writeUnsignedShortBE(segy, formatCodeOffset, 1);

        assertDoesNotThrow(() -> SegyValidator.validate(segy),
            "Format code 1 (IBM float32) deve ser aceito pelo validador");
    }

    // -------------------------------------------------------------------------
    // Teste adicional: arquivo apenas com headers e sem traços
    // -------------------------------------------------------------------------

    /**
     * Arquivo com apenas headers (sem traços) deve ser considerado válido,
     * pois a especificação não exige número mínimo de traços.
     */
    @Test
    void validFile_headersOnly_noTraces_shouldNotThrow() {
        // Criar um SEG-Y com apenas EBCDIC + binary header (sem traços)
        byte[] segy = new byte[EBCDIC_SIZE + BINARY_HDR_SIZE];
        Arrays.fill(segy, 0, EBCDIC_SIZE, (byte) 0xC1);
        int bhBase = EBCDIC_SIZE;
        writeUnsignedShortBE(segy, bhBase + 20, SAMPLES_PER_TRACE);
        writeUnsignedShortBE(segy, bhBase + 24, FORMAT_CODE_IEEE);

        assertDoesNotThrow(() -> SegyValidator.validate(segy),
            "Arquivo com apenas headers (sem traços) deve ser aceito");
    }
}

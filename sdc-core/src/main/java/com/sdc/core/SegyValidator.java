package com.sdc.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Valida a conformidade estrutural de um arquivo SEG-Y Rev1.
 *
 * <p>Verificações executadas em ordem, parando na primeira falha e lançando
 * {@link SegyValidationException} com o byte offset do problema:</p>
 * <ol>
 *   <li><b>Tamanho mínimo</b> — arquivo deve ter ≥ 3600 bytes
 *       (3200 bytes EBCDIC + 400 bytes binary header).</li>
 *   <li><b>Prefixo EBCDIC</b> — primeiros 3200 bytes devem conter apenas
 *       bytes válidos EBCDIC ou zeros; presença de bytes &gt; 0xFF é
 *       estruturalmente impossível (são bytes), mas bytes que não pertencem
 *       ao conjunto imprimível EBCDIC e não são zeros indicam corrupção.
 *       Esta implementação aceita como válido qualquer byte no conjunto
 *       EBCDIC estendido (0x00–0xFF), verificando especificamente que
 *       os primeiros 3200 bytes NÃO são todos ASCII puro (0x20–0x7E sem
 *       nenhum byte fora desse range), o que indicaria que o arquivo é
 *       ASCII e não EBCDIC.</li>
 *   <li><b>samplesPerTrace &gt; 0</b> — bytes 20-21 do binary header
 *       (big-endian unsigned short) devem ser positivos.</li>
 *   <li><b>formatCode suportado</b> — bytes 24-25 do binary header devem
 *       ser 1 (IBM float32) ou 5 (IEEE float32).</li>
 *   <li><b>Integridade estrutural dos traços</b> — para cada traço,
 *       o offset esperado (240 bytes de trace header + samplesPerTrace × 4)
 *       é calculado e verificado contra o tamanho real do arquivo; nenhum
 *       traço pode estender-se além do EOF.</li>
 * </ol>
 *
 * <p>Esta classe é stateless e thread-safe. Todos os métodos são estáticos.</p>
 */
public final class SegyValidator {

    /** Tamanho do header textual EBCDIC em bytes. */
    public static final int EBCDIC_HEADER_SIZE = 3200;

    /** Tamanho do binary header em bytes. */
    public static final int BINARY_HEADER_SIZE = 400;

    /** Tamanho mínimo de um arquivo SEG-Y válido (sem nenhum traço). */
    public static final int MIN_FILE_SIZE = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE;

    /** Tamanho do trace header em bytes. */
    public static final int TRACE_HEADER_SIZE = 240;

    /** Tamanho de cada amostra em bytes (float32 = 4 bytes). */
    public static final int BYTES_PER_SAMPLE = 4;

    /**
     * Offset do campo samplesPerTrace no binary header (bytes 20-21, 0-based
     * relativo ao início do binary header).
     * Conforme SEG-Y Rev1: "Number of samples per data trace" — campo 5 do binary header.
     */
    private static final int BINARY_SAMPLES_PER_TRACE_OFFSET = 20;

    /**
     * Offset do campo formatCode no binary header (bytes 24-25, 0-based
     * relativo ao início do binary header).
     * Conforme SEG-Y Rev1: "Data sample format code" — campo 7 do binary header.
     */
    private static final int BINARY_FORMAT_CODE_OFFSET = 24;

    private SegyValidator() {}

    /**
     * Valida um arquivo SEG-Y Rev1 a partir de um {@link Path}.
     *
     * <p>Lê o arquivo inteiro em memória para calcular offsets estruturais.
     * Para arquivos muito grandes, prefira {@link #validate(byte[])} com
     * uma janela de leitura adequada (pelo menos os primeiros
     * {@value #MIN_FILE_SIZE} bytes mais o tamanho de todos os traços).</p>
     *
     * @param path caminho para o arquivo SEG-Y
     * @throws SegyValidationException se qualquer verificação de conformidade falhar
     * @throws IOException             se ocorrer erro de I/O ao ler o arquivo
     */
    public static void validate(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        validate(data);
    }

    /**
     * Valida um array de bytes representando um arquivo SEG-Y Rev1.
     *
     * @param data conteúdo completo do arquivo SEG-Y
     * @throws SegyValidationException se qualquer verificação de conformidade falhar
     */
    public static void validate(byte[] data) {
        long fileSize = data.length;

        // --- Verificação 1: tamanho mínimo ---
        if (fileSize < MIN_FILE_SIZE) {
            throw new SegyValidationException(
                "Arquivo SEG-Y muito curto: tamanho=" + fileSize +
                ", mínimo esperado=" + MIN_FILE_SIZE + " bytes " +
                "(3200 EBCDIC + 400 binary header)",
                0L
            );
        }

        // --- Verificação 2: prefixo EBCDIC ---
        validateEbcdicHeader(data);

        // --- Verificações 3 e 4: binary header ---
        int samplesPerTrace = validateBinaryHeader(data);

        // --- Verificação 5: integridade estrutural dos traços ---
        validateTraceIntegrity(data, fileSize, samplesPerTrace);
    }

    // -------------------------------------------------------------------------
    // Métodos de verificação individuais
    // -------------------------------------------------------------------------

    /**
     * Verifica que os primeiros {@value #EBCDIC_HEADER_SIZE} bytes do arquivo
     * representam um header EBCDIC válido.
     *
     * <p>A verificação consiste em detectar "ASCII puro" nos primeiros 3200 bytes:
     * se TODOS os bytes caem no range imprimível ASCII (0x20–0x7E) e NENHUM byte
     * está fora desse range, o arquivo muito provavelmente NÃO é EBCDIC — os
     * textos EBCDIC usam bytes como 0x40 (espaço), 0xC1–0xC9 (A–I), etc., que
     * frequentemente coexistem com bytes &gt; 0x7F e fora do range ASCII imprimível.
     * Além disso, verificamos que não há bytes no range 0x01–0x1F que não sejam
     * caracteres de controle EBCDIC legítimos (EBCDIC usa 0x0D, 0x25 como
     * fin-de-linha; bytes como 0x01–0x08 não ocorrem em texto EBCDIC normal).</p>
     *
     * <p>Implementação: aceita qualquer byte que seja 0x00 (null EBCDIC) ou
     * esteja no conjunto típico de bytes EBCDIC. Rejeita blocos onde todos os
     * bytes estão no range ASCII imprimível (0x20–0x7E), pois isso indica
     * arquivo ASCII, não EBCDIC.</p>
     *
     * @param data bytes completos do arquivo
     * @throws SegyValidationException se o header EBCDIC for inválido
     */
    private static void validateEbcdicHeader(byte[] data) {
        // Contar bytes fora do range ASCII puro imprimível (0x20-0x7E)
        // Um header EBCDIC legítimo SEMPRE terá bytes fora desse range,
        // pois letras EBCDIC maiúsculas (A=0xC1, B=0xC2...) e outros
        // caracteres ocupam o range alto 0x80-0xFF.
        // Um header ASCII puro não terá nenhum byte > 0x7E nem < 0x20
        // (exceto possivelmente CR/LF = 0x0D/0x0A).
        int nonAsciiPrintableCount = 0;
        int invalidLowByteCount = 0;

        for (int i = 0; i < EBCDIC_HEADER_SIZE; i++) {
            int b = data[i] & 0xFF;

            if (b == 0x00) {
                // byte nulo — aceitável como padding EBCDIC
                continue;
            }

            if (b > 0x7E) {
                // byte acima do ASCII imprimível — indica EBCDIC (bom sinal)
                nonAsciiPrintableCount++;
                continue;
            }

            // Bytes no range 0x01–0x08 não ocorrem em texto EBCDIC normal
            // (são caracteres de controle não utilizados no contexto de headers SEG-Y)
            if (b >= 0x01 && b <= 0x08) {
                invalidLowByteCount++;
            }
        }

        // Se não há NENHUM byte fora do ASCII imprimível, o header parece ser ASCII,
        // não EBCDIC — isso é uma falha de conformidade.
        if (nonAsciiPrintableCount == 0 && invalidLowByteCount == 0) {
            // Verificar se o conteúdo é não-nulo (header todo nulo é aceito como
            // arquivo recém-inicializado/gerado programaticamente)
            boolean hasNonNull = false;
            for (int i = 0; i < EBCDIC_HEADER_SIZE; i++) {
                if (data[i] != 0) {
                    hasNonNull = true;
                    break;
                }
            }
            if (hasNonNull) {
                // Header não é nulo mas também não tem bytes EBCDIC — parece ASCII
                throw new SegyValidationException(
                    "Header EBCDIC inválido: os primeiros " + EBCDIC_HEADER_SIZE +
                    " bytes parecem ser ASCII puro (nenhum byte fora do range 0x20-0x7E). " +
                    "SEG-Y Rev1 exige header textual em codificação EBCDIC.",
                    0L
                );
            }
        }

        // Bytes de controle inválidos no início do header indicam corrupção
        if (invalidLowByteCount > EBCDIC_HEADER_SIZE / 10) {
            // mais de 10% de bytes de controle suspeitos — corrupção provável
            throw new SegyValidationException(
                "Header EBCDIC inválido: " + invalidLowByteCount +
                " bytes de controle suspeitos (0x01-0x08) detectados nos primeiros " +
                EBCDIC_HEADER_SIZE + " bytes.",
                0L
            );
        }
    }

    /**
     * Valida os campos críticos do binary header e retorna samplesPerTrace.
     *
     * @param data bytes completos do arquivo
     * @return samplesPerTrace lido do binary header
     * @throws SegyValidationException se samplesPerTrace ou formatCode forem inválidos
     */
    private static int validateBinaryHeader(byte[] data) {
        // O binary header começa no offset EBCDIC_HEADER_SIZE (3200)
        int binaryHeaderBase = EBCDIC_HEADER_SIZE;

        // samplesPerTrace: bytes 20-21 do binary header (big-endian unsigned short)
        long samplesOffset = binaryHeaderBase + BINARY_SAMPLES_PER_TRACE_OFFSET;
        int samplesPerTrace = readUnsignedShortBE(data, (int) samplesOffset);

        if (samplesPerTrace <= 0) {
            throw new SegyValidationException(
                "samplesPerTrace inválido no binary header: valor=" + samplesPerTrace +
                " (deve ser > 0). SEG-Y Rev1 exige número positivo de amostras por traço.",
                samplesOffset
            );
        }

        // formatCode: bytes 24-25 do binary header (big-endian unsigned short)
        long formatCodeOffset = binaryHeaderBase + BINARY_FORMAT_CODE_OFFSET;
        int formatCode = readUnsignedShortBE(data, (int) formatCodeOffset);

        if (formatCode != 1 && formatCode != 5) {
            throw new SegyValidationException(
                "formatCode não suportado no binary header: valor=" + formatCode +
                " (suportados: 1=IBM float32, 5=IEEE float32).",
                formatCodeOffset
            );
        }

        return samplesPerTrace;
    }

    /**
     * Verifica a integridade estrutural de todos os traços até o EOF.
     *
     * <p>Para cada traço, calcula o offset esperado (240 bytes de trace header
     * + samplesPerTrace × 4 bytes de amostras) e verifica que o traço
     * não ultrapassa o tamanho do arquivo. Em caso de traço truncado,
     * lança exceção com o byte offset do início do traço problemático.</p>
     *
     * @param data           bytes completos do arquivo
     * @param fileSize       tamanho total do arquivo em bytes
     * @param samplesPerTrace número de amostras por traço (do binary header)
     * @throws SegyValidationException se algum traço ultrapassar o EOF
     */
    private static void validateTraceIntegrity(byte[] data, long fileSize, int samplesPerTrace) {
        long traceSize = TRACE_HEADER_SIZE + (long) samplesPerTrace * BYTES_PER_SAMPLE;
        long dataRegionStart = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE;
        long dataRegionSize = fileSize - dataRegionStart;

        if (dataRegionSize == 0) {
            // Arquivo válido sem traços (apenas headers) — aceito
            return;
        }

        if (dataRegionSize < 0) {
            // Não deveria acontecer — já verificamos MIN_FILE_SIZE — mas defensivo
            throw new SegyValidationException(
                "Tamanho de arquivo inconsistente: dataRegionSize=" + dataRegionSize,
                dataRegionStart
            );
        }

        // Verificar que a região de dados é múltiplo exato do tamanho de um traço.
        // Resíduo positivo indica bytes extras (traço truncado no final).
        long remainder = dataRegionSize % traceSize;
        if (remainder != 0) {
            // Calcular o offset do traço que está truncado
            long traceCount = dataRegionSize / traceSize;
            long truncatedTraceOffset = dataRegionStart + traceCount * traceSize;
            throw new SegyValidationException(
                "Integridade estrutural violada: a região de dados (" + dataRegionSize +
                " bytes) não é múltiplo exato do tamanho de um traço (" + traceSize +
                " bytes). " + remainder + " byte(s) sobrando após " + traceCount +
                " traço(s) completo(s) — último traço truncado.",
                truncatedTraceOffset
            );
        }
    }

    // -------------------------------------------------------------------------
    // Utilitários de leitura de bytes
    // -------------------------------------------------------------------------

    /**
     * Lê um unsigned short big-endian de dois bytes consecutivos.
     *
     * @param buf    array de bytes
     * @param offset offset 0-based do byte mais significativo
     * @return valor unsigned short (0–65535)
     */
    private static int readUnsignedShortBE(byte[] buf, int offset) {
        int hi = buf[offset]     & 0xFF;
        int lo = buf[offset + 1] & 0xFF;
        return (hi << 8) | lo;
    }
}

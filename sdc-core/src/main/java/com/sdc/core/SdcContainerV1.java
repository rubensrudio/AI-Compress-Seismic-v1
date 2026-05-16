package com.sdc.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Container binário v1 do formato comprimido .sdc.
 *
 * <p>Layout do arquivo:</p>
 * <pre>
 *   magic             4  bytes  0x53444301 ('S''D''C'\x01)
 *   codec_version     2  bytes  short (big-endian)
 *   model_uuid_msb    8  bytes  long  (big-endian) — UUID MSB
 *   model_uuid_lsb    8  bytes  long  (big-endian) — UUID LSB
 *   segy_textual_header  3200 bytes  EBCDIC preservado
 *   segy_binary_header    400 bytes  header binário preservado
 *   trace_count       4  bytes  int   (big-endian)
 *   samples_per_trace 4  bytes  int   (big-endian)
 *   sample_format_code 4 bytes  int   (big-endian)
 *   trace_headers_blob 240×N bytes  headers de traço preservados
 *   compressed_blocks  variável:
 *     repetido trace_count vezes:
 *       traceId        4  bytes int
 *       min            4  bytes float
 *       max            4  bytes float
 *       samplesPerTrace 4 bytes int
 *       payloadSize    4  bytes int
 *       payload        payloadSize bytes
 * </pre>
 *
 * <p>Arquivos .sdc gerados pelo protótipo (magic {@code 0x53444331}) são
 * incompatíveis com este container e são rejeitados com
 * {@link IllegalArgumentException}.</p>
 *
 * @see SdcHeader
 */
public final class SdcContainerV1 {

    // ------------------------------------------------------------------
    // Constantes públicas
    // ------------------------------------------------------------------

    /** Magic number do formato v1: bytes 'S'(0x53)'D'(0x44)'C'(0x43) 0x01. */
    public static final int MAGIC = 0x53444301;

    /**
     * Magic do protótipo (SdcHeader), incompatível com este container.
     * Arquivos com este magic são detectados e rejeitados em {@link #read(InputStream)}.
     */
    static final int PROTOTYPE_MAGIC = 0x53444331;

    /** Tamanho fixo do header textual SEG-Y Rev1 (EBCDIC). */
    static final int SEGY_TEXTUAL_HEADER_BYTES = 3200;

    /** Tamanho fixo do header binário SEG-Y Rev1. */
    static final int SEGY_BINARY_HEADER_BYTES = 400;

    /** Tamanho fixo de um header de traço SEG-Y Rev1. */
    static final int TRACE_HEADER_BYTES = 240;

    // ------------------------------------------------------------------
    // Campos do container
    // ------------------------------------------------------------------

    private final short codecVersion;
    private final UUID modelUuid;
    private final byte[] segyTextualHeader;    // 3200 bytes
    private final byte[] segyBinaryHeader;     // 400 bytes
    private final int traceCount;
    private final int samplesPerTrace;
    private final int sampleFormatCode;
    private final byte[] traceHeadersBlob;     // 240 × traceCount bytes
    private final List<CompressedTraceBlock> compressedBlocks;

    // ------------------------------------------------------------------
    // Construtor
    // ------------------------------------------------------------------

    /**
     * Constrói um SdcContainerV1 com todos os campos obrigatórios.
     *
     * @param codecVersion     versão do pipeline de codec (use 1 para v1)
     * @param modelUuid        UUID do artefato de modelo AI utilizado
     * @param segyTextualHeader EBCDIC header preservado (exatamente 3200 bytes)
     * @param segyBinaryHeader  header binário SEG-Y preservado (exatamente 400 bytes)
     * @param traceCount        número de traços
     * @param samplesPerTrace   amostras por traço
     * @param sampleFormatCode  format code SEG-Y (1=IBM float32, 5=IEEE float32)
     * @param traceHeadersBlob  headers de traço preservados (240 × traceCount bytes)
     * @param compressedBlocks  lista de blocos comprimidos
     */
    public SdcContainerV1(short codecVersion,
                          UUID modelUuid,
                          byte[] segyTextualHeader,
                          byte[] segyBinaryHeader,
                          int traceCount,
                          int samplesPerTrace,
                          int sampleFormatCode,
                          byte[] traceHeadersBlob,
                          List<CompressedTraceBlock> compressedBlocks) {

        if (codecVersion <= 0) {
            throw new IllegalArgumentException("codecVersion must be > 0, got " + codecVersion);
        }
        Objects.requireNonNull(modelUuid, "modelUuid must not be null");
        Objects.requireNonNull(segyTextualHeader, "segyTextualHeader must not be null");
        Objects.requireNonNull(segyBinaryHeader, "segyBinaryHeader must not be null");
        Objects.requireNonNull(traceHeadersBlob, "traceHeadersBlob must not be null");
        Objects.requireNonNull(compressedBlocks, "compressedBlocks must not be null");

        if (segyTextualHeader.length != SEGY_TEXTUAL_HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "segyTextualHeader must be exactly " + SEGY_TEXTUAL_HEADER_BYTES +
                    " bytes, got " + segyTextualHeader.length);
        }
        if (segyBinaryHeader.length != SEGY_BINARY_HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "segyBinaryHeader must be exactly " + SEGY_BINARY_HEADER_BYTES +
                    " bytes, got " + segyBinaryHeader.length);
        }
        if (traceCount < 0) {
            throw new IllegalArgumentException("traceCount must be >= 0, got " + traceCount);
        }
        if (samplesPerTrace <= 0) {
            throw new IllegalArgumentException("samplesPerTrace must be > 0, got " + samplesPerTrace);
        }
        if (sampleFormatCode <= 0) {
            throw new IllegalArgumentException("sampleFormatCode must be > 0, got " + sampleFormatCode);
        }

        int expectedBlobSize = TRACE_HEADER_BYTES * traceCount;
        if (traceHeadersBlob.length != expectedBlobSize) {
            throw new IllegalArgumentException(
                    "traceHeadersBlob must be " + expectedBlobSize +
                    " bytes (240 × " + traceCount + "), got " + traceHeadersBlob.length);
        }

        this.codecVersion = codecVersion;
        this.modelUuid = modelUuid;
        this.segyTextualHeader = Arrays.copyOf(segyTextualHeader, segyTextualHeader.length);
        this.segyBinaryHeader = Arrays.copyOf(segyBinaryHeader, segyBinaryHeader.length);
        this.traceCount = traceCount;
        this.samplesPerTrace = samplesPerTrace;
        this.sampleFormatCode = sampleFormatCode;
        this.traceHeadersBlob = Arrays.copyOf(traceHeadersBlob, traceHeadersBlob.length);
        this.compressedBlocks = List.copyOf(compressedBlocks);
    }

    // ------------------------------------------------------------------
    // Acessores
    // ------------------------------------------------------------------

    public short codecVersion()               { return codecVersion; }
    public UUID modelUuid()                   { return modelUuid; }
    public byte[] segyTextualHeader()         { return Arrays.copyOf(segyTextualHeader, segyTextualHeader.length); }
    public byte[] segyBinaryHeader()          { return Arrays.copyOf(segyBinaryHeader, segyBinaryHeader.length); }
    public int traceCount()                   { return traceCount; }
    public int samplesPerTrace()              { return samplesPerTrace; }
    public int sampleFormatCode()             { return sampleFormatCode; }
    public byte[] traceHeadersBlob()          { return Arrays.copyOf(traceHeadersBlob, traceHeadersBlob.length); }
    public List<CompressedTraceBlock> compressedBlocks() { return compressedBlocks; }

    // ------------------------------------------------------------------
    // Serialização
    // ------------------------------------------------------------------

    /**
     * Serializa este container para o {@link OutputStream} fornecido.
     * O stream não é fechado após a escrita — responsabilidade do chamador.
     *
     * @param out stream de saída (não pode ser null)
     * @throws IOException em caso de erro de I/O
     */
    public void write(OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        DataOutputStream dos = new DataOutputStream(out);

        // magic (4 bytes)
        dos.writeInt(MAGIC);

        // codec_version (2 bytes)
        dos.writeShort(codecVersion);

        // model_uuid_msb + model_uuid_lsb (8 + 8 bytes)
        dos.writeLong(modelUuid.getMostSignificantBits());
        dos.writeLong(modelUuid.getLeastSignificantBits());

        // segy_textual_header (3200 bytes)
        dos.write(segyTextualHeader);

        // segy_binary_header (400 bytes)
        dos.write(segyBinaryHeader);

        // trace_count (4 bytes)
        dos.writeInt(traceCount);

        // samples_per_trace (4 bytes)
        dos.writeInt(samplesPerTrace);

        // sample_format_code (4 bytes)
        dos.writeInt(sampleFormatCode);

        // trace_headers_blob (240 × traceCount bytes)
        dos.write(traceHeadersBlob);

        // compressed_blocks: traceId + min + max + samplesPerTrace + payloadSize + payload
        for (CompressedTraceBlock block : compressedBlocks) {
            dos.writeInt(block.traceId());
            dos.writeFloat(block.min());
            dos.writeFloat(block.max());
            dos.writeInt(block.samplesPerTrace());
            byte[] payload = block.payload();
            dos.writeInt(payload.length);
            dos.write(payload);
        }

        dos.flush();
    }

    /**
     * Lê um {@link SdcContainerV1} a partir de um {@link InputStream}.
     *
     * <p>Valida o magic number. Se o magic corresponder ao formato do protótipo
     * ({@code 0x53444331}), lança {@link IllegalArgumentException} com mensagem
     * descritiva sobre a incompatibilidade. Qualquer outro magic inválido também
     * resulta em {@link IllegalArgumentException}.</p>
     *
     * @param in stream de entrada (não pode ser null)
     * @return o container desserializado
     * @throws IllegalArgumentException se o magic for inválido ou corresponder
     *                                  ao formato incompatível do protótipo
     * @throws IOException              em caso de erro de I/O ou dados truncados
     */
    public static SdcContainerV1 read(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        DataInputStream dis = new DataInputStream(in);

        // magic (4 bytes)
        int magic = dis.readInt();

        if (magic == PROTOTYPE_MAGIC) {
            throw new IllegalArgumentException(
                    "Incompatible .sdc file: magic 0x" + Integer.toHexString(PROTOTYPE_MAGIC).toUpperCase() +
                    " corresponds to the prototype format (SdcHeader v0/v1/v2) which does not embed" +
                    " a model UUID. This file cannot be read by SdcContainerV1. " +
                    "Expected magic: 0x" + Integer.toHexString(MAGIC).toUpperCase() + ".");
        }

        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    String.format("Invalid SDC magic: 0x%08X. Expected: 0x%08X.", magic, MAGIC));
        }

        // codec_version (2 bytes)
        short codecVersion = dis.readShort();

        // model_uuid_msb + model_uuid_lsb (8 + 8 bytes)
        long uuidMsb = dis.readLong();
        long uuidLsb = dis.readLong();
        UUID modelUuid = new UUID(uuidMsb, uuidLsb);

        // segy_textual_header (3200 bytes)
        byte[] segyTextualHeader = dis.readNBytes(SEGY_TEXTUAL_HEADER_BYTES);
        if (segyTextualHeader.length != SEGY_TEXTUAL_HEADER_BYTES) {
            throw new IOException("Truncated segy_textual_header: expected " +
                    SEGY_TEXTUAL_HEADER_BYTES + " bytes, got " + segyTextualHeader.length);
        }

        // segy_binary_header (400 bytes)
        byte[] segyBinaryHeader = dis.readNBytes(SEGY_BINARY_HEADER_BYTES);
        if (segyBinaryHeader.length != SEGY_BINARY_HEADER_BYTES) {
            throw new IOException("Truncated segy_binary_header: expected " +
                    SEGY_BINARY_HEADER_BYTES + " bytes, got " + segyBinaryHeader.length);
        }

        // trace_count (4 bytes)
        int traceCount = dis.readInt();

        // samples_per_trace (4 bytes)
        int samplesPerTrace = dis.readInt();

        // sample_format_code (4 bytes)
        int sampleFormatCode = dis.readInt();

        // trace_headers_blob (240 × traceCount bytes)
        int blobSize = TRACE_HEADER_BYTES * traceCount;
        byte[] traceHeadersBlob = dis.readNBytes(blobSize);
        if (traceHeadersBlob.length != blobSize) {
            throw new IOException("Truncated trace_headers_blob: expected " +
                    blobSize + " bytes, got " + traceHeadersBlob.length);
        }

        // compressed_blocks
        List<CompressedTraceBlock> blocks = new ArrayList<>(traceCount);
        for (int i = 0; i < traceCount; i++) {
            int traceId = dis.readInt();
            float min = dis.readFloat();
            float max = dis.readFloat();
            int blockSamplesPerTrace = dis.readInt();
            int payloadSize = dis.readInt();
            byte[] payload = dis.readNBytes(payloadSize);
            if (payload.length != payloadSize) {
                throw new IOException("Truncated payload for block " + i +
                        ": expected " + payloadSize + " bytes, got " + payload.length);
            }
            blocks.add(new CompressedTraceBlock(traceId, min, max, blockSamplesPerTrace, payload));
        }

        return new SdcContainerV1(
                codecVersion, modelUuid,
                segyTextualHeader, segyBinaryHeader,
                traceCount, samplesPerTrace, sampleFormatCode,
                traceHeadersBlob, blocks
        );
    }

    // ------------------------------------------------------------------
    // Object overrides
    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return "SdcContainerV1{" +
                "magic=0x" + Integer.toHexString(MAGIC).toUpperCase() +
                ", codecVersion=" + codecVersion +
                ", modelUuid=" + modelUuid +
                ", traceCount=" + traceCount +
                ", samplesPerTrace=" + samplesPerTrace +
                ", sampleFormatCode=" + sampleFormatCode +
                ", compressedBlocks=" + compressedBlocks.size() +
                '}';
    }
}

package com.sdc.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Codec de bloco de traços sísmicos.
 *
 * <h3>Pipeline de encode</h3>
 * <ol>
 *   <li>Calcula min/max do traço</li>
 *   <li>Normaliza para [-1, 1]</li>
 *   <li>Delta encoding (correlação temporal)</li>
 *   <li><b>TracePredictor.encode()</b> — slot de resíduos AI (identity por padrão)</li>
 *   <li>Quantização linear para {@code short[]}</li>
 *   <li>Conversão {@code short[] -> byte[]}</li>
 *   <li>DEFLATE</li>
 * </ol>
 *
 * <h3>Pipeline de decode</h3>
 * <ol>
 *   <li>Inflate</li>
 *   <li>Conversão {@code byte[] -> short[]}</li>
 *   <li>Dequantização linear</li>
 *   <li><b>TracePredictor.decode()</b> — inverso do slot AI</li>
 *   <li>Delta decode</li>
 *   <li>Denormalização</li>
 * </ol>
 *
 * <p><b>Thread-safety:</b> esta classe não é thread-safe. Cada thread deve
 * usar sua própria instância — ou usar os métodos estáticos que constroem
 * o codec interno com {@link TracePredictor#identity()}.
 *
 * @NotThreadSafe — race condition documentada (R-08 do plano técnico);
 *   paralelização adiada para v2.
 */
public final class TraceBlockCodec {

    private final TracePredictor predictor;

    /**
     * Constrói um codec com o {@link TracePredictor} fornecido.
     *
     * @param predictor implementação de predição a ser aplicada no slot de
     *                  resíduos AI do pipeline; nunca {@code null}
     */
    public TraceBlockCodec(TracePredictor predictor) {
        if (predictor == null) {
            throw new IllegalArgumentException("predictor must not be null");
        }
        this.predictor = predictor;
    }

    /**
     * Constrói um codec com {@link TracePredictor#identity()} — modo sem IA.
     * Mantém retrocompatibilidade com código existente.
     */
    public TraceBlockCodec() {
        this(TracePredictor.identity());
    }

    // -----------------------------------------------------------------------
    // Métodos de instância (portam o predictor configurado)
    // -----------------------------------------------------------------------

    /**
     * Comprime um bloco de traço usando o profile padrão de alta qualidade
     * e o predictor configurado nesta instância.
     */
    public CompressedTraceBlock compressBlock(TraceBlock tb) {
        return compressBlock(tb, CompressionProfile.defaultHighQuality());
    }

    /**
     * Comprime um bloco de traço usando o profile e o predictor configurados
     * nesta instância.
     */
    public CompressedTraceBlock compressBlock(TraceBlock tb, CompressionProfile profile) {
        return compressInternal(tb, profile, predictor);
    }

    /**
     * Descomprime um bloco usando o predictor configurado nesta instância.
     */
    public TraceBlock decompressBlock(CompressedTraceBlock cb) {
        return decompressInternal(cb, predictor);
    }

    // -----------------------------------------------------------------------
    // Métodos estáticos — retrocompatibilidade total com API original
    // -----------------------------------------------------------------------

    /**
     * Comprime usando profile padrão e {@link TracePredictor#identity()}.
     * Mantém a assinatura original sem {@code TracePredictor}.
     */
    public static CompressedTraceBlock compress(TraceBlock tb) {
        return compress(tb, CompressionProfile.defaultHighQuality());
    }

    /**
     * Comprime usando o profile fornecido e {@link TracePredictor#identity()}.
     * Mantém a assinatura original sem {@code TracePredictor}.
     */
    public static CompressedTraceBlock compress(TraceBlock tb, CompressionProfile profile) {
        return compressInternal(tb, profile, TracePredictor.identity());
    }

    /**
     * Comprime usando profile padrão e o predictor fornecido.
     * Overload que adiciona suporte ao predictor sem quebrar assinaturas originais.
     */
    public static CompressedTraceBlock compress(TraceBlock tb, TracePredictor predictor) {
        return compressInternal(tb, CompressionProfile.defaultHighQuality(), predictor);
    }

    /**
     * Comprime usando o profile e o predictor fornecidos.
     * Overload completo para controle total do pipeline.
     */
    public static CompressedTraceBlock compress(TraceBlock tb, CompressionProfile profile,
                                                TracePredictor predictor) {
        return compressInternal(tb, profile, predictor);
    }

    /**
     * Descomprime usando {@link TracePredictor#identity()}.
     * Mantém a assinatura original sem {@code TracePredictor}.
     */
    public static TraceBlock decompress(CompressedTraceBlock cb) {
        return decompressInternal(cb, TracePredictor.identity());
    }

    /**
     * Descomprime usando o predictor fornecido.
     * Overload que adiciona suporte ao predictor sem quebrar assinaturas originais.
     */
    public static TraceBlock decompress(CompressedTraceBlock cb, TracePredictor predictor) {
        return decompressInternal(cb, predictor);
    }

    // -----------------------------------------------------------------------
    // Lógica central do pipeline (private)
    // -----------------------------------------------------------------------

    private static CompressedTraceBlock compressInternal(TraceBlock tb,
                                                         CompressionProfile profile,
                                                         TracePredictor predictor) {
        float[] samples = tb.samples();
        int n = samples.length;

        // 1) min/max
        float[] mm = Preprocessing.minMax(samples);
        float min = mm[0];
        float max = mm[1];

        // 2) normalização para [-1, 1]
        float[] norm = Preprocessing.normalizeToMinusOneToOne(samples);

        // 3) delta encoding
        float[] deltas = Preprocessing.deltaEncode(norm);

        // 4) slot de resíduos AI — predictor.encode() entre delta e quantização
        float[] residuals = predictor.encode(deltas);

        // 5) quantização linear (respeitando effectiveBits do profile)
        short[] q = LinearQuantizer.encode(residuals, profile);

        // 6) short[] -> byte[]
        byte[] rawBytes = shortsToBytes(q);

        // 7) DEFLATE com nível do profile
        byte[] compressed = deflate(rawBytes, profile.deflaterLevel());

        return new CompressedTraceBlock(tb.traceId(), min, max, n, compressed);
    }

    private static TraceBlock decompressInternal(CompressedTraceBlock cb,
                                                 TracePredictor predictor) {
        // 1) inflate
        byte[] rawBytes = inflate(cb.payload());

        // 2) byte[] -> short[]
        short[] q = bytesToShorts(rawBytes, cb.samplesPerTrace());

        // 3) dequantização linear
        // TODO (TASK-XX — codec bit-mismatch bug): LinearQuantizer.decode(short[]) always assumes
        // 16 bits regardless of the effectiveBits used during encode. This causes silent data
        // corruption when any profile other than HIGH_QUALITY (16 bits) is used for compression.
        // Fix: store quantizationBits inside CompressedTraceBlock (and in the SDC container
        // serialisation) and call LinearQuantizer.decode(q, cb.quantizationBits()) here.
        // Until fixed, callers must ensure encode and decode both use 16-bit profiles (HIGH_QUALITY).
        float[] dequantized = LinearQuantizer.decode(q);

        // 4) slot de resíduos AI — predictor.decode() na posição simétrica
        float[] deltas = predictor.decode(dequantized);

        // 5) delta decode
        float[] norm = Preprocessing.deltaDecode(deltas);

        // 6) denormalização usando min/max preservados
        float[] samples = Preprocessing.denormalizeFromMinusOneToOne(norm, cb.min(), cb.max());

        return new TraceBlock(cb.traceId(), samples);
    }

    // -----------------------------------------------------------------------
    // Helpers de serialização e compressão
    // -----------------------------------------------------------------------

    static byte[] shortsToBytes(short[] data) {
        byte[] out = new byte[data.length * 2];
        int j = 0;
        for (short v : data) {
            out[j++] = (byte) (v >>> 8);
            out[j++] = (byte) (v);
        }
        return out;
    }

    static short[] bytesToShorts(byte[] data, int expectedSamples) {
        if (data.length % 2 != 0) {
            throw new IllegalArgumentException("invalid short-encoded buffer length: " + data.length);
        }
        int n = data.length / 2;
        if (expectedSamples > 0 && n != expectedSamples) {
            throw new IllegalStateException("expected " + expectedSamples + " samples but got " + n);
        }
        short[] out = new short[n];
        int j = 0;
        for (int i = 0; i < n; i++) {
            int hi = data[j++] & 0xFF;
            int lo = data[j++] & 0xFF;
            out[i] = (short) ((hi << 8) | lo);
        }
        return out;
    }

    static byte[] deflate(byte[] input) {
        return deflate(input, java.util.zip.Deflater.BEST_COMPRESSION);
    }

    static byte[] deflate(byte[] input, int level) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(level);
        deflater.setInput(input);
        deflater.finish();

        byte[] buffer = new byte[4096];
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(input.length)) {
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Unexpected IO error during deflate", e);
        } finally {
            deflater.end();
        }
    }

    static byte[] inflate(byte[] input) {
        Inflater inflater = new Inflater();
        inflater.setInput(input);

        byte[] buffer = new byte[4096];
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length * 2)) {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput()) break;
                }
                baos.write(buffer, 0, count);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error during inflate", e);
        } finally {
            inflater.end();
        }
    }
}

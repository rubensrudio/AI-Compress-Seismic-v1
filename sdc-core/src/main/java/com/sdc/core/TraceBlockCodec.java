package com.sdc.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Codec de bloco:
 *
 * Encode:
 *  1) min/max
 *  2) normaliza para [-1,1]
 *  3) delta encode
 *  4) quantiza para short
 *  5) converte short[] -> byte[]
 *  6) aplica Deflater (zlib) nos bytes
 *
 * Decode faz o inverso.
 */
public final class TraceBlockCodec {

    private TraceBlockCodec() {}

    public static CompressedTraceBlock compress(TraceBlock tb) {
        return compress(tb, CompressionProfile.defaultHighQuality());
    }

    public static CompressedTraceBlock compress(TraceBlock tb, CompressionProfile profile) {
        float[] samples = tb.samples();
        int n = samples.length;

        // 1) min/max
        float[] mm = Preprocessing.minMax(samples);
        float min = mm[0];
        float max = mm[1];

        // 2) normalização
        float[] norm = Preprocessing.normalizeToMinusOneToOne(samples);

        // 3) delta
        float[] deltas = Preprocessing.deltaEncode(norm);

        // 4) quantização, agora respeitando effectiveBits do profile
        short[] q = LinearQuantizer.encode(deltas, profile);

        // 5) short[] -> byte[]
        byte[] rawBytes = shortsToBytes(q);

        // 6) Deflater com nível vindo do profile
        byte[] compressed = deflate(rawBytes, profile.deflaterLevel());

        return new CompressedTraceBlock(tb.traceId(), min, max, n, compressed);
    }

    public static TraceBlock decompress(CompressedTraceBlock cb) {
        // 1) inflar bytes
        byte[] rawBytes = inflate(cb.payload());

        // 2) bytes -> short[]
        short[] q = bytesToShorts(rawBytes, cb.samplesPerTrace());

        // 3) dequantizar
        float[] deltasNorm = LinearQuantizer.decode(q);

        // 4) delta decode
        float[] norm = Preprocessing.deltaDecode(deltasNorm);

        // 5) denormalizar usando min/max
        float[] samples = Preprocessing.denormalizeFromMinusOneToOne(norm, cb.min(), cb.max());

        return new TraceBlock(cb.traceId(), samples);
    }

    // ---------- Helpers ----------

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
            // em produção, talvez só logar; aqui vamos ser estritos
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
                    // evita loop infinito em caso de dados corrompidos
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

package com.sdc.core;

import java.util.Arrays;

public final class VolumeBlock3DCompressor {

    private VolumeBlock3DCompressor() {}

    /**
     * Bloco comprimido:
     *  - metadata do cubo (posições e dimensões)
     *  - min/max (para normalização)
     *  - payload comprimido (shorts quantizados + Deflate)
     */
    public static final class CompressedVolumeBlock3D {
        public final VolumeBlock3D blockMeta;
        public final float min;
        public final float max;
        public final byte[] payload;

        public CompressedVolumeBlock3D(VolumeBlock3D blockMeta,
                                       float min,
                                       float max,
                                       byte[] payload) {
            this.blockMeta = blockMeta;
            this.min = min;
            this.max = max;
            this.payload = payload;
        }
    }

    /**
     * Compressão de um cubo 3D:
     *
     *  1) calcula min/max do cubo
     *  2) normaliza para [-1,1]
     *  3) aplica residual 3D
     *  4) flatten para 1D
     *  5) quantiza com LinearQuantizer (profile)
     *  6) converte short[] -> byte[]
     *  7) Deflate com nível do profile
     */
    public static CompressedVolumeBlock3D compressBlock(VolumeBlock3D block,
                                                        CompressionProfile profile) {
        float[][][] data = block.samples;

        int ni = data.length;
        int nj = data[0].length;
        int nt = data[0][0].length;

        // 1) min/max do cubo inteiro
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < ni; i++) {
            for (int j = 0; j < nj; j++) {
                for (int k = 0; k < nt; k++) {
                    float v = data[i][j][k];
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
        }

        // 2) normaliza para [-1,1]
        float range = max - min;
        float[][][] norm = new float[ni][nj][nt];

        if (range == 0f) {
            // cubo todo constante
            for (int i = 0; i < ni; i++) {
                for (int j = 0; j < nj; j++) {
                    Arrays.fill(norm[i][j], 0f);
                }
            }
        } else {
            for (int i = 0; i < ni; i++) {
                for (int j = 0; j < nj; j++) {
                    for (int k = 0; k < nt; k++) {
                        float v = data[i][j][k];
                        float zeroToOne = (v - min) / range;   // [0,1]
                        norm[i][j][k] = zeroToOne * 2f - 1f;   // [-1,1]
                    }
                }
            }
        }

        // 3) residual 3D
        float[][][] residual = VolumeBlock3DCodec.residualEncode(norm);

        // 4) flatten
        float[] flat = VolumeBlock3DCodec.flatten3D(residual);

        // 5) quantiza com profile (usa effectiveBits)
        short[] q = LinearQuantizer.encode(flat, profile);

        // 6) short[] -> bytes
        byte[] rawBytes = TraceBlockCodec.shortsToBytes(q);

        // 7) Deflate com nível do profile
        byte[] compressed = TraceBlockCodec.deflate(rawBytes, profile.deflaterLevel());

        return new CompressedVolumeBlock3D(block, min, max, compressed);
    }

    /**
     * Descompressão de um cubo 3D:
     *
     *  1) Inflate (Deflate -> bytes)
     *  2) bytes -> short[]
     *  3) dequantiza para float[]
     *  4) unflatten -> residual 3D
     *  5) residualDecode 3D -> norm [-1,1]
     *  6) denormaliza usando min/max
     */
    public static float[][][] decompressBlock(CompressedVolumeBlock3D cb,
                                              CompressionProfile profile) {
        VolumeBlock3D meta = cb.blockMeta;

        int ni = meta.inlineCount;
        int nj = meta.xlineCount;
        int nt = meta.timeCount;

        // 1) Inflate
        byte[] rawBytes = TraceBlockCodec.inflate(cb.payload);

        // 2) bytes -> short[]
        short[] q = TraceBlockCodec.bytesToShorts(rawBytes, ni * nj * nt);

        // 3) dequantiza
        float[] flat = LinearQuantizer.decode(q, profile.effectiveBits());

        // 4) unflatten -> residual 3D
        float[][][] residual = VolumeBlock3DCodec.unflatten3D(flat, ni, nj, nt);

        // 5) residualDecode -> norm [-1,1]
        float[][][] norm = VolumeBlock3DCodec.residualDecode(residual);

        // 6) denormaliza para escala original
        float[][][] data = new float[ni][nj][nt];
        float min = cb.min;
        float max = cb.max;
        float range = max - min;

        if (range == 0f) {
            // cubo constante
            for (int i = 0; i < ni; i++) {
                for (int j = 0; j < nj; j++) {
                    Arrays.fill(data[i][j], min);
                }
            }
        } else {
            for (int i = 0; i < ni; i++) {
                for (int j = 0; j < nj; j++) {
                    for (int k = 0; k < nt; k++) {
                        float v = norm[i][j][k];     // [-1,1]
                        float zeroToOne = (v + 1f) / 2f; // [0,1]
                        data[i][j][k] = min + zeroToOne * range;
                    }
                }
            }
        }

        return data;
    }
}

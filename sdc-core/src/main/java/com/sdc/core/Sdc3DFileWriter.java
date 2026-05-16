package com.sdc.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Sdc3DFileWriter {

    private Sdc3DFileWriter() {}

    /**
     * Gera um .sdc v3 em modo 3D:
     *
     * Layout simplificado:
     *  [SdcHeader version=3, traceCount, samplesPerTrace]
     *  [inlineCount][xlineCount][timeCount]
     *  [blockInline][blockXline][blockTime]
     *  [blockCount]
     *  Tabela de offsets por cubo (blockCount entradas):
     *    para cada cubo:
     *      [inlineStartIndex][xlineStartIndex][timeStartIndex]
     *      [inlineCount][xlineCount][timeCount]
     *      [payloadSize]
     *  Blocos comprimidos:
     *    para cada cubo:
     *      [payloadBytes...]
     */
    public static void write3D(Path target,
                               SegyIO.SegyDataset dataset,
                               CompressionProfile profile,
                               int blockInline,
                               int blockXline,
                               int blockTime) throws IOException {

        int inlineCount = dataset.traceGrid.inlineCount();
        int xlineCount  = dataset.traceGrid.xlineCount();
        int timeCount   = dataset.samplesPerTrace;

        if (blockInline <= 0) blockInline = 8;
        if (blockXline <= 0) blockXline = 8;
        if (blockTime <= 0 || blockTime > timeCount) blockTime = timeCount;

        List<VolumeBlock3DCompressor.CompressedVolumeBlock3D> compressedBlocks = new ArrayList<>();
        List<CubeIndexEntry> index = new ArrayList<>();

        // monta os cubos varrendo inline/xline/time (por enquanto time em blocos completos ou fatias)
        for (int ii = 0; ii < inlineCount; ii += blockInline) {
            int bi = Math.min(blockInline, inlineCount - ii);
            for (int jj = 0; jj < xlineCount; jj += blockXline) {
                int bj = Math.min(blockXline, xlineCount - jj);
                for (int tt = 0; tt < timeCount; tt += blockTime) {
                    int bt = Math.min(blockTime, timeCount - tt);

                    float[][][] cube = new float[bi][bj][bt];

                    for (int di = 0; di < bi; di++) {
                        for (int dj = 0; dj < bj; dj++) {
                            int globalInlineIdx = ii + di;
                            int globalXlineIdx  = jj + dj;
                            int traceIdx = dataset.traceGrid.traceIndexAt(globalInlineIdx, globalXlineIdx);
                            if (traceIdx < 0) {
                                // traço ausente -> preenche zeros
                                for (int k = 0; k < bt; k++) {
                                    cube[di][dj][k] = 0f;
                                }
                            } else {
                                float[] samples = dataset.traces.get(traceIdx).samples();
                                System.arraycopy(samples, tt, cube[di][dj], 0, bt);
                            }
                        }
                    }

                    VolumeBlock3D vb = new VolumeBlock3D(ii, jj, tt, cube);
                    VolumeBlock3DCompressor.CompressedVolumeBlock3D cb =
                            VolumeBlock3DCompressor.compressBlock(vb, profile);

                    compressedBlocks.add(cb);
                    index.add(new CubeIndexEntry(
                            ii, jj, tt,
                            bi, bj, bt,
                            cb.min,
                            cb.max,
                            cb.payload.length
                    ));
                }
            }
        }

        int blockCount = compressedBlocks.size();
        SdcHeader header = new SdcHeader(3, dataset.traceCount(), dataset.samplesPerTrace);

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target)))) {

            header.write(out);

            // dim globais
            out.writeInt(inlineCount);
            out.writeInt(xlineCount);
            out.writeInt(timeCount);

            // dim bloco
            out.writeInt(blockInline);
            out.writeInt(blockXline);
            out.writeInt(blockTime);

            // qtde de blocos
            out.writeInt(blockCount);

            // tabela de offsets (coordenadas 3D + min/max + tamanho do payload)
            for (CubeIndexEntry e : index) {
                out.writeInt(e.inlineStartIndex);
                out.writeInt(e.xlineStartIndex);
                out.writeInt(e.timeStartIndex);
                out.writeInt(e.inlineCount);
                out.writeInt(e.xlineCount);
                out.writeInt(e.timeCount);
                out.writeFloat(e.min);
                out.writeFloat(e.max);
                out.writeInt(e.payloadSize);
            }

            // blocos comprimidos na mesma ordem
            for (VolumeBlock3DCompressor.CompressedVolumeBlock3D cb : compressedBlocks) {
                out.write(cb.payload);
            }

            out.flush();
        }
    }
}

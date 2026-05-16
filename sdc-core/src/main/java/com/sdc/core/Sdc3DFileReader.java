package com.sdc.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Sdc3DFileReader {

    private Sdc3DFileReader() {}

    public static final class Sdc3DVolume {
        public final int inlineCount;
        public final int xlineCount;
        public final int timeCount;
        public final float[][][] data; // [inline][xline][time]

        public Sdc3DVolume(int inlineCount, int xlineCount, int timeCount, float[][][] data) {
            this.inlineCount = inlineCount;
            this.xlineCount = xlineCount;
            this.timeCount = timeCount;
            this.data = data;
        }
    }

    public static final class Sdc3DSlice {
        public final int inlineCount;
        public final int xlineCount;
        public final int timeCount;

        public final String axis;
        public final int index;

        public final int start0;
        public final int count0;
        public final int start1;
        public final int count1;

        public final float[][] data; // [count0][count1]

        public Sdc3DSlice(int inlineCount,
                          int xlineCount,
                          int timeCount,
                          String axis,
                          int index,
                          int start0,
                          int count0,
                          int start1,
                          int count1,
                          float[][] data) {
            this.inlineCount = inlineCount;
            this.xlineCount = xlineCount;
            this.timeCount = timeCount;
            this.axis = axis;
            this.index = index;
            this.start0 = start0;
            this.count0 = count0;
            this.start1 = start1;
            this.count1 = count1;
            this.data = data;
        }
    }

    public static Sdc3DVolume read3D(Path path, CompressionProfile profile) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {

            SdcHeader header = SdcHeader.read(in);
            if (header.version() != 3) {
                throw new IOException("Expected SDC version 3 for 3D volume, got " + header.version());
            }

            int inlineCount = in.readInt();
            int xlineCount  = in.readInt();
            int timeCount   = in.readInt();

            int blockInline = in.readInt();
            int blockXline  = in.readInt();
            int blockTime   = in.readInt();

            int blockCount = in.readInt();

            List<CubeIndexEntry> index = new ArrayList<>(blockCount);
            for (int b = 0; b < blockCount; b++) {
                int ii = in.readInt();
                int jj = in.readInt();
                int tt = in.readInt();
                int bi = in.readInt();
                int bj = in.readInt();
                int bt = in.readInt();
                float min = in.readFloat();
                float max = in.readFloat();
                int payloadSize = in.readInt();
                index.add(new CubeIndexEntry(ii, jj, tt, bi, bj, bt, min, max, payloadSize));
            }

            float[][][] volume = new float[inlineCount][xlineCount][timeCount];

            for (CubeIndexEntry e : index) {
                byte[] payload = in.readNBytes(e.payloadSize);

                // monta metadados do bloco
                float[][][] dummy = new float[e.inlineCount][e.xlineCount][e.timeCount];
                VolumeBlock3D meta = new VolumeBlock3D(
                        e.inlineStartIndex,
                        e.xlineStartIndex,
                        e.timeStartIndex,
                        dummy
                );

                VolumeBlock3DCompressor.CompressedVolumeBlock3D cb =
                        new VolumeBlock3DCompressor.CompressedVolumeBlock3D(meta, e.min, e.max, payload);

                float[][][] cubeData = VolumeBlock3DCompressor.decompressBlock(cb, profile);

                // copia pro volume global
                for (int di = 0; di < e.inlineCount; di++) {
                    int gi = e.inlineStartIndex + di;
                    for (int dj = 0; dj < e.xlineCount; dj++) {
                        int gj = e.xlineStartIndex + dj;
                        System.arraycopy(cubeData[di][dj], 0,
                                volume[gi][gj], e.timeStartIndex, e.timeCount);
                    }
                }
            }

            return new Sdc3DVolume(inlineCount, xlineCount, timeCount, volume);
        }
    }

    /**
     * Lê apenas uma fatia 2D do volume (.sdc v3), evitando carregar o volume inteiro.
     * axis: INLINE, XLINE, TIME.
     * start0/count0 e start1/count1 fazem paginacao dentro da fatia.
     */
    public static Sdc3DSlice readSlice(Path path,
                                       CompressionProfile profile,
                                       String axisInput,
                                       int indexInput,
                                       int start0Input,
                                       int count0Input,
                                       int start1Input,
                                       int count1Input) throws IOException {
        return readSlice(path, profile, axisInput, indexInput, start0Input, count0Input, start1Input, count1Input, null);
    }

    /**
     * Lê apenas uma fatia 2D do volume (.sdc v3) com cache LRU opcional.
     */
    public static Sdc3DSlice readSlice(Path path,
                                       CompressionProfile profile,
                                       String axisInput,
                                       int indexInput,
                                       int start0Input,
                                       int count0Input,
                                       int start1Input,
                                       int count1Input,
                                       VolumeBlockCache cache) throws IOException {
        String axis = axisInput == null ? "TIME" : axisInput.trim().toUpperCase();

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {

            SdcHeader header = SdcHeader.read(in);
            if (header.version() != 3) {
                throw new IOException("Expected SDC version 3 for 3D volume, got " + header.version());
            }

            int inlineCount = in.readInt();
            int xlineCount  = in.readInt();
            int timeCount   = in.readInt();

            int blockInline = in.readInt();
            int blockXline  = in.readInt();
            int blockTime   = in.readInt();

            int blockCount = in.readInt();

            List<CubeIndexEntry> index = new ArrayList<>(blockCount);
            for (int b = 0; b < blockCount; b++) {
                int ii = in.readInt();
                int jj = in.readInt();
                int tt = in.readInt();
                int bi = in.readInt();
                int bj = in.readInt();
                int bt = in.readInt();
                float min = in.readFloat();
                float max = in.readFloat();
                int payloadSize = in.readInt();
                index.add(new CubeIndexEntry(ii, jj, tt, bi, bj, bt, min, max, payloadSize));
            }

            int axisIndex;
            int dim0Total;
            int dim1Total;

            switch (axis) {
                case "INLINE" -> {
                    axisIndex = clamp(indexInput, 0, inlineCount - 1);
                    dim0Total = xlineCount;
                    dim1Total = timeCount;
                }
                case "XLINE" -> {
                    axisIndex = clamp(indexInput, 0, xlineCount - 1);
                    dim0Total = inlineCount;
                    dim1Total = timeCount;
                }
                case "TIME" -> {
                    axisIndex = clamp(indexInput, 0, timeCount - 1);
                    dim0Total = inlineCount;
                    dim1Total = xlineCount;
                }
                default -> throw new IllegalArgumentException("Axis invalido: " + axis);
            }

            int start0 = normalizeStart(start0Input, dim0Total);
            int start1 = normalizeStart(start1Input, dim1Total);
            int count0 = normalizeCount(count0Input, dim0Total - start0);
            int count1 = normalizeCount(count1Input, dim1Total - start1);

            float[][] slice = new float[count0][count1];

            for (CubeIndexEntry e : index) {
                if (!intersectsSlice(axis, axisIndex, start0, count0, start1, count1, e)) {
                    in.skipNBytes(e.payloadSize);
                    continue;
                }

                String cacheKey = cache != null ? VolumeBlockCache.key(path.toString(), e.inlineStartIndex, e.xlineStartIndex, e.timeStartIndex) : null;

                float[][][] cubeData = null;
                if (cache != null) {
                    cubeData = cache.get(cacheKey);
                }

                if (cubeData == null) {
                    byte[] payload = in.readNBytes(e.payloadSize);

                    float[][][] dummy = new float[e.inlineCount][e.xlineCount][e.timeCount];
                    VolumeBlock3D meta = new VolumeBlock3D(
                            e.inlineStartIndex,
                            e.xlineStartIndex,
                            e.timeStartIndex,
                            dummy
                    );

                    VolumeBlock3DCompressor.CompressedVolumeBlock3D cb =
                            new VolumeBlock3DCompressor.CompressedVolumeBlock3D(meta, e.min, e.max, payload);

                    cubeData = VolumeBlock3DCompressor.decompressBlock(cb, profile);

                    if (cache != null) {
                        cache.put(cacheKey, cubeData);
                    }
                } else {
                    in.skipNBytes(e.payloadSize);
                }

                copySlice(axis, axisIndex, start0, count0, start1, count1, e, cubeData, slice);
            }

            return new Sdc3DSlice(
                    inlineCount,
                    xlineCount,
                    timeCount,
                    axis,
                    axisIndex,
                    start0,
                    count0,
                    start1,
                    count1,
                    slice
            );
        }
    }

    private static boolean intersectsSlice(String axis,
                                           int axisIndex,
                                           int start0,
                                           int count0,
                                           int start1,
                                           int count1,
                                           CubeIndexEntry e) {
        int end0 = start0 + count0;
        int end1 = start1 + count1;

        return switch (axis) {
            case "TIME" -> {
                boolean timeHit = axisIndex >= e.timeStartIndex && axisIndex < e.timeStartIndex + e.timeCount;
                boolean inlineHit = rangesOverlap(start0, end0, e.inlineStartIndex, e.inlineStartIndex + e.inlineCount);
                boolean xlineHit = rangesOverlap(start1, end1, e.xlineStartIndex, e.xlineStartIndex + e.xlineCount);
                yield timeHit && inlineHit && xlineHit;
            }
            case "INLINE" -> {
                boolean inlineHit = axisIndex >= e.inlineStartIndex && axisIndex < e.inlineStartIndex + e.inlineCount;
                boolean xlineHit = rangesOverlap(start0, end0, e.xlineStartIndex, e.xlineStartIndex + e.xlineCount);
                boolean timeHit = rangesOverlap(start1, end1, e.timeStartIndex, e.timeStartIndex + e.timeCount);
                yield inlineHit && xlineHit && timeHit;
            }
            case "XLINE" -> {
                boolean xlineHit = axisIndex >= e.xlineStartIndex && axisIndex < e.xlineStartIndex + e.xlineCount;
                boolean inlineHit = rangesOverlap(start0, end0, e.inlineStartIndex, e.inlineStartIndex + e.inlineCount);
                boolean timeHit = rangesOverlap(start1, end1, e.timeStartIndex, e.timeStartIndex + e.timeCount);
                yield xlineHit && inlineHit && timeHit;
            }
            default -> false;
        };
    }

    private static void copySlice(String axis,
                                  int axisIndex,
                                  int start0,
                                  int count0,
                                  int start1,
                                  int count1,
                                  CubeIndexEntry e,
                                  float[][][] cube,
                                  float[][] out) {
        int end0 = start0 + count0;
        int end1 = start1 + count1;

        switch (axis) {
            case "TIME" -> {
                int tLocal = axisIndex - e.timeStartIndex;
                if (tLocal < 0 || tLocal >= e.timeCount) return;
                int iStart = Math.max(start0, e.inlineStartIndex);
                int iEnd = Math.min(end0, e.inlineStartIndex + e.inlineCount);
                int jStart = Math.max(start1, e.xlineStartIndex);
                int jEnd = Math.min(end1, e.xlineStartIndex + e.xlineCount);
                for (int i = iStart; i < iEnd; i++) {
                    int iLocal = i - e.inlineStartIndex;
                    int outI = i - start0;
                    for (int j = jStart; j < jEnd; j++) {
                        int jLocal = j - e.xlineStartIndex;
                        int outJ = j - start1;
                        out[outI][outJ] = cube[iLocal][jLocal][tLocal];
                    }
                }
            }
            case "INLINE" -> {
                int iLocal = axisIndex - e.inlineStartIndex;
                if (iLocal < 0 || iLocal >= e.inlineCount) return;
                int jStart = Math.max(start0, e.xlineStartIndex);
                int jEnd = Math.min(end0, e.xlineStartIndex + e.xlineCount);
                int tStart = Math.max(start1, e.timeStartIndex);
                int tEnd = Math.min(end1, e.timeStartIndex + e.timeCount);
                for (int j = jStart; j < jEnd; j++) {
                    int jLocal = j - e.xlineStartIndex;
                    int outJ = j - start0;
                    for (int t = tStart; t < tEnd; t++) {
                        int tLocal = t - e.timeStartIndex;
                        int outT = t - start1;
                        out[outJ][outT] = cube[iLocal][jLocal][tLocal];
                    }
                }
            }
            case "XLINE" -> {
                int jLocal = axisIndex - e.xlineStartIndex;
                if (jLocal < 0 || jLocal >= e.xlineCount) return;
                int iStart = Math.max(start0, e.inlineStartIndex);
                int iEnd = Math.min(end0, e.inlineStartIndex + e.inlineCount);
                int tStart = Math.max(start1, e.timeStartIndex);
                int tEnd = Math.min(end1, e.timeStartIndex + e.timeCount);
                for (int i = iStart; i < iEnd; i++) {
                    int iLocal = i - e.inlineStartIndex;
                    int outI = i - start0;
                    for (int t = tStart; t < tEnd; t++) {
                        int tLocal = t - e.timeStartIndex;
                        int outT = t - start1;
                        out[outI][outT] = cube[iLocal][jLocal][tLocal];
                    }
                }
            }
            default -> throw new IllegalArgumentException("Axis invalido: " + axis);
        }
    }

    private static boolean rangesOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) return min;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static int normalizeStart(int start, int max) {
        if (start < 0) return 0;
        if (start > max) return max;
        return start;
    }

    private static int normalizeCount(int count, int max) {
        if (count < 0) return max;
        if (count > max) return max;
        return count;
    }
}

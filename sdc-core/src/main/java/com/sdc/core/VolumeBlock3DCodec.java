package com.sdc.core;

public final class VolumeBlock3DCodec {

    private VolumeBlock3DCodec() {}

    // 3D residual encode: v - média dos vizinhos (i-1, j, k), (i, j-1, k), (i, j, k-1)
    public static float[][][] residualEncode(float[][][] data) {
        int ni = data.length;
        int nj = data[0].length;
        int nt = data[0][0].length;

        float[][][] res = new float[ni][nj][nt];

        for (int i = 0; i < ni; i++) {
            for (int j = 0; j < nj; j++) {
                for (int k = 0; k < nt; k++) {

                    float v = data[i][j][k];
                    int count = 0;
                    float sum = 0f;

                    if (i > 0) { sum += data[i-1][j][k]; count++; }
                    if (j > 0) { sum += data[i][j-1][k]; count++; }
                    if (k > 0) { sum += data[i][j][k-1]; count++; }

                    float pred = (count > 0) ? (sum / count) : 0f;
                    res[i][j][k] = v - pred;
                }
            }
        }
        return res;
    }

    public static float[][][] residualDecode(float[][][] residual) {
        int ni = residual.length;
        int nj = residual[0].length;
        int nt = residual[0][0].length;

        float[][][] data = new float[ni][nj][nt];

        for (int i = 0; i < ni; i++) {
            for (int j = 0; j < nj; j++) {
                for (int k = 0; k < nt; k++) {

                    int count = 0;
                    float sum = 0f;

                    if (i > 0) { sum += data[i-1][j][k]; count++; }
                    if (j > 0) { sum += data[i][j-1][k]; count++; }
                    if (k > 0) { sum += data[i][j][k-1]; count++; }

                    float pred = (count > 0) ? (sum / count) : 0f;
                    data[i][j][k] = residual[i][j][k] + pred;
                }
            }
        }
        return data;
    }

    public static float[] flatten3D(float[][][] data) {
        int ni = data.length;
        int nj = data[0].length;
        int nt = data[0][0].length;
        float[] out = new float[ni * nj * nt];
        int idx = 0;
        for (int i = 0; i < ni; i++) {
            for (int j = 0; j < nj; j++) {
                System.arraycopy(data[i][j], 0, out, idx, nt);
                idx += nt;
            }
        }
        return out;
    }

    public static float[][][] unflatten3D(float[] flat, int ni, int nj, int nt) {
        if (flat.length != ni * nj * nt) {
            throw new IllegalArgumentException("flat length mismatch");
        }
        float[][][] data = new float[ni][nj][nt];
        int idx = 0;
        for (int i = 0; i < ni; i++) {
            for (int j = 0; j < nj; j++) {
                System.arraycopy(flat, idx, data[i][j], 0, nt);
                idx += nt;
            }
        }
        return data;
    }
}

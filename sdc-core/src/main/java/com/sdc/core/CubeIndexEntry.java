package com.sdc.core;

/**
 * Entrada da tabela de offsets 3D de cubos:
 * define a posição do cubo no grid global, seu tamanho e o payload.
 */
public final class CubeIndexEntry {
    public final int inlineStartIndex;
    public final int xlineStartIndex;
    public final int timeStartIndex;
    public final int inlineCount;
    public final int xlineCount;
    public final int timeCount;
    public final float min;
    public final float max;
    public final int payloadSize; // em bytes

    public CubeIndexEntry(int inlineStartIndex,
                          int xlineStartIndex,
                          int timeStartIndex,
                          int inlineCount,
                          int xlineCount,
                          int timeCount,
                          float min,
                          float max,
                          int payloadSize) {
        this.inlineStartIndex = inlineStartIndex;
        this.xlineStartIndex = xlineStartIndex;
        this.timeStartIndex = timeStartIndex;
        this.inlineCount = inlineCount;
        this.xlineCount = xlineCount;
        this.timeCount = timeCount;
        this.min = min;
        this.max = max;
        this.payloadSize = payloadSize;
    }
}

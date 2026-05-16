package com.sdc.core;

public final class VolumeBlock3D {

    public final int inlineStartIndex;
    public final int xlineStartIndex;
    public final int timeStartIndex;

    public final int inlineCount;
    public final int xlineCount;
    public final int timeCount;

    // [inline][xline][time]
    public final float[][][] samples;

    public VolumeBlock3D(int inlineStartIndex,
                         int xlineStartIndex,
                         int timeStartIndex,
                         float[][][] samples) {
        this.inlineStartIndex = inlineStartIndex;
        this.xlineStartIndex = xlineStartIndex;
        this.timeStartIndex = timeStartIndex;
        this.samples = samples;
        this.inlineCount = samples.length;
        this.xlineCount = samples[0].length;
        this.timeCount = samples[0][0].length;
    }
}

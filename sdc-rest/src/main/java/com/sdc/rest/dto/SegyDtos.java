package com.sdc.rest.dto;

public final class SegyDtos {

    private SegyDtos() {}

    /**
     * Response for /api/viewer/files and /api/viewer/sgy-files.
     * Record chosen over public-field class: immutable, auto-generates equals/hashCode/toString,
     * and Jackson serialises it without extra annotations.
     */
    public record FilesResponse(java.util.List<String> files) {}

    public static final class CompressRequest {
        /** Basename of the source SEG-Y file (no path separators). Server resolves against dataRoot. */
        public String sgyFile;

        public String profile;
        public Double fidelityPercent;
    }


    public static final class CompressResponse {
        public String segyPath;
        public String sdcPath;

        public long segyBytes;
        public long sdcBytes;
        public long rawDataBytes;

        public int traceCount;
        public int samplesPerTrace;

        public double ratio;            // manter compatibilidade: igual a ratioFile
        public double ratioFile;        // sdcBytes / segyBytes
        public double ratioData;        // sdcBytes / rawDataBytes
        public double savingsPercent;   // (1 - ratioFile) * 100

        public double psnrFirstTrace;
        public double psnrMean;
        public double psnrMin;
        public double psnrMax;

        // NOVO: info sobre o profile usado
        public double fidelityPercentRequested;
        public int effectiveBits;
        public int deflaterLevel;
    }

    public static final class DecompressRequest {
        public String sdcPath;
        public String templateSegyPath;
        public String outSegyPath;
    }

    public static final class DecompressResponse {
        public String sdcPath;
        public String templateSegyPath;
        public String outSegyPath;
        public boolean success;
        public String message;
    }

    public static final class Compress3DRequest {
        /** Basename of the source SEG-Y file (no path separators). Server resolves against dataRoot. */
        public String sgyFile;

        public String profile;
        public Double fidelityPercent;

        public Integer blockInline;
        public Integer blockXline;
        public Integer blockTime;
    }

    public static final class Decompress3DRequest {
        public String sdcPath;
        public String templateSegyPath;
        public String outSegyPath;

        // Mesmo profile/fidelity da compressão, para manter simetria
        public String profile;
        public Double fidelityPercent;
    }

    // ---------------- Visualizador (.sdc) ----------------

    public static final class ViewerInfoRequest {
        public String sdcPath;
    }

    public static final class ViewerInfoResponse {
        public String sdcPath;
        public int version;
        public int traceCount;
        public int samplesPerTrace;
        public String sampleFormat;  // Ex: "IEEE float 32 (5)", "IBM float (1)"

        // Somente para .sdc v3 (3D)
        public Integer inlineCount;
        public Integer xlineCount;
        public Integer timeCount;
        public Integer blockInline;
        public Integer blockXline;
        public Integer blockTime;
        public Integer blockCount;
    }

    public static final class TraceSliceRequest {
        public String sdcPath;
        public Integer traceStart;
        public Integer traceCount;
        public Integer sampleStart;
        public Integer sampleCount;
    }

    public static final class TraceSliceResponse {
        public String sdcPath;
        public int version;
        public int traceStart;
        public int traceCount;
        public int sampleStart;
        public int sampleCount;
        public int[] traceIds;
        public float[][] samples;
    }

    public static final class VolumeSliceRequest {
        public String sdcPath;

        // INLINE, XLINE, TIME
        public String axis;
        public Integer index;

        // Paginacao dentro da fatia
        public Integer start0;
        public Integer count0;
        public Integer start1;
        public Integer count1;

        // Perfil de decodificacao
        public String profile;
        public Double fidelityPercent;
    }

    public static final class VolumeSliceResponse {
        public String sdcPath;
        public int version;
        public String axis;
        public int index;

        public int inlineCount;
        public int xlineCount;
        public int timeCount;

        public int dim0;
        public int dim1;
        public float[][] data;

        public int start0;
        public int count0;
        public int start1;
        public int count1;

        public float min;
        public float max;
    }

    public static final class TraceHeadersRequest {
        public String sdcPath;
    }

    public static final class TraceHeader {
        public int traceNumber;
        public int inline;
        public int crossline;

        public TraceHeader(int traceNumber, int inline, int crossline) {
            this.traceNumber = traceNumber;
            this.inline = inline;
            this.crossline = crossline;
        }
    }

    public static final class TraceHeadersResponse {
        public String sdcPath;
        public int version;
        public int traceCount;
        public TraceHeader[] headers;
    }
}

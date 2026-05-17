package com.sdc.rest;

import com.sdc.core.CompressedTraceBlock;
import com.sdc.core.CompressionProfile;
import com.sdc.core.Sdc3DFileReader;
import com.sdc.core.SdcHeader;
import com.sdc.core.TraceBlock;
import com.sdc.core.TraceBlockCodec;
import com.sdc.core.VolumeBlockCache;
import com.sdc.rest.dto.SegyDtos.TraceSliceRequest;
import com.sdc.rest.dto.SegyDtos.TraceSliceResponse;
import com.sdc.rest.dto.SegyDtos.TraceHeadersRequest;
import com.sdc.rest.dto.SegyDtos.TraceHeadersResponse;
import com.sdc.rest.dto.SegyDtos.TraceHeader;
import com.sdc.rest.dto.SegyDtos.ViewerInfoRequest;
import com.sdc.rest.dto.SegyDtos.ViewerInfoResponse;
import com.sdc.rest.dto.SegyDtos.VolumeSliceRequest;
import com.sdc.rest.dto.SegyDtos.VolumeSliceResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SdcViewerService {

    private final int cacheBlockCapacity;
    private final ConcurrentHashMap<String, VolumeBlockCache> cachesByPath;
    private final String dataRoot;

    public SdcViewerService(
            @Value("${sdc.viewer.cache-blocks:64}") int cacheBlockCapacity,
            @Value("${sdc.data.root:}") String dataRoot) {
        this.cacheBlockCapacity = Math.max(1, cacheBlockCapacity);
        this.cachesByPath = new ConcurrentHashMap<>();
        this.dataRoot = dataRoot;
    }

    /**
     * Resolves an SDC path from the client. If the value contains no directory separator
     * it is treated as a basename and resolved against dataRoot. Full paths pass through
     * unchanged so existing curl-based workflows keep working.
     */
    private Path resolveSdcPath(String sdcPath) {
        if (sdcPath == null || sdcPath.isBlank()) {
            throw new IllegalArgumentException("sdcPath must not be blank");
        }
        boolean isBasename = !sdcPath.contains("/") && !sdcPath.contains("\\");
        if (isBasename && (dataRoot == null || dataRoot.isBlank())) {
            throw new IllegalArgumentException(
                "Received basename '" + sdcPath + "' but sdc.data.root is not configured");
        }
        return isBasename ? Path.of(dataRoot).resolve(sdcPath) : Path.of(sdcPath);
    }

    /** Returns basenames of files with the given extension under dataRoot. Empty list if root is blank. */
    public List<String> listFiles(String ext) {
        if (dataRoot == null || dataRoot.isBlank()) {
            return Collections.emptyList();
        }
        Path root = Path.of(dataRoot);
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        String suffix = "." + ext.toLowerCase();
        try (var stream = Files.list(root)) {
            return stream
                    .filter(p -> !Files.isDirectory(p))
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(suffix))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public ViewerInfoResponse info(ViewerInfoRequest req) throws IOException {
        Path path = resolveSdcPath(req.sdcPath);
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {

            SdcHeader header = SdcHeader.read(in);

            ViewerInfoResponse resp = new ViewerInfoResponse();
            resp.sdcPath = req.sdcPath;
            resp.version = header.version();
            resp.traceCount = header.traceCount();
            resp.samplesPerTrace = header.samplesPerTrace();
            resp.sampleFormat = "IEEE float 32 (5)";  // Default para .sdc v2 e v3

            if (header.version() == 3) {
                resp.inlineCount = in.readInt();
                resp.xlineCount = in.readInt();
                resp.timeCount = in.readInt();

                resp.blockInline = in.readInt();
                resp.blockXline = in.readInt();
                resp.blockTime = in.readInt();

                resp.blockCount = in.readInt();
            }

            return resp;
        }
    }

    public TraceSliceResponse traceSlice(TraceSliceRequest req) throws IOException {
        Path path = resolveSdcPath(req.sdcPath);
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {

            SdcHeader header = SdcHeader.read(in);
            if (header.version() != 2) {
                throw new IOException("Trace slice requer .sdc v2 (got " + header.version() + ")");
            }

            int traceStart = req.traceStart == null ? 0 : Math.max(0, req.traceStart);
            int traceCountReq = req.traceCount == null ? 1 : req.traceCount;
            if (traceCountReq < 1) traceCountReq = 1;

            int traceEnd = Math.min(header.traceCount(), traceStart + traceCountReq);
            int traceCount = Math.max(0, traceEnd - traceStart);

            int sampleStart = req.sampleStart == null ? 0 : Math.max(0, req.sampleStart);
            int maxSampleCount = Math.max(0, header.samplesPerTrace() - sampleStart);
            int sampleCountReq = req.sampleCount == null ? maxSampleCount : req.sampleCount;
            if (sampleCountReq < 0) sampleCountReq = 0;
            int sampleCount = Math.min(sampleCountReq, maxSampleCount);

            TraceSliceResponse resp = new TraceSliceResponse();
            resp.sdcPath = req.sdcPath;
            resp.version = header.version();
            resp.traceStart = traceStart;
            resp.traceCount = traceCount;
            resp.sampleStart = sampleStart;
            resp.sampleCount = sampleCount;
            resp.traceIds = new int[traceCount];
            resp.samples = new float[traceCount][sampleCount];

            if (traceCount == 0 || sampleCount == 0) {
                return resp;
            }

            for (int t = 0; t < header.traceCount(); t++) {
                int traceId = in.readInt();
                float min = in.readFloat();
                float max = in.readFloat();
                int payloadSize = in.readInt();

                if (t < traceStart || t >= traceEnd) {
                    in.skipNBytes(payloadSize);
                    continue;
                }

                byte[] payload = in.readNBytes(payloadSize);
                CompressedTraceBlock cb = new CompressedTraceBlock(
                        traceId, min, max, header.samplesPerTrace(), payload);
                TraceBlock tb = TraceBlockCodec.decompress(cb);

                int outIdx = t - traceStart;
                resp.traceIds[outIdx] = traceId;
                System.arraycopy(tb.samples(), sampleStart, resp.samples[outIdx], 0, sampleCount);
            }

            return resp;
        }
    }

    public VolumeSliceResponse volumeSlice(VolumeSliceRequest req) throws IOException {
        CompressionProfile profile = resolveProfile(req.profile, req.fidelityPercent);

        Path resolvedPath = resolveSdcPath(req.sdcPath);
        String cacheKey = resolvedPath.toAbsolutePath().toString();
        VolumeBlockCache cache = cachesByPath.computeIfAbsent(
                cacheKey,
                k -> new VolumeBlockCache(cacheBlockCapacity)
        );

        String axis = req.axis == null ? "TIME" : req.axis.trim().toUpperCase();
        int index = req.index == null ? 0 : req.index;
        int start0 = req.start0 == null ? -1 : req.start0;
        int count0 = req.count0 == null ? -1 : req.count0;
        int start1 = req.start1 == null ? -1 : req.start1;
        int count1 = req.count1 == null ? -1 : req.count1;

        Sdc3DFileReader.Sdc3DSlice slice = Sdc3DFileReader.readSlice(
                resolvedPath,
                profile,
                axis,
                index,
                start0,
                count0,
                start1,
                count1,
                cache
        );

        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < slice.count0; i++) {
            for (int j = 0; j < slice.count1; j++) {
                float v = slice.data[i][j];
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }

        VolumeSliceResponse resp = new VolumeSliceResponse();
        resp.sdcPath = req.sdcPath;
        resp.version = 3;
        resp.axis = slice.axis;
        resp.index = slice.index;
        resp.inlineCount = slice.inlineCount;
        resp.xlineCount = slice.xlineCount;
        resp.timeCount = slice.timeCount;
        resp.dim0 = slice.count0;
        resp.dim1 = slice.count1;
        resp.data = slice.data;
        resp.start0 = slice.start0;
        resp.count0 = slice.count0;
        resp.start1 = slice.start1;
        resp.count1 = slice.count1;
        resp.min = min;
        resp.max = max;

        return resp;
    }

    private static CompressionProfile resolveProfile(String profileName, Double fidelityPercent) {
        if (fidelityPercent != null) {
            return CompressionProfile.fromFidelityPercent(fidelityPercent);
        }
        if (profileName != null) {
            return CompressionProfile.fromProfileName(profileName);
        }
        return CompressionProfile.defaultHighQuality();
    }

    /** Limpa cache para um arquivo específico (útil para liberação de memória). */
    public void clearCache(String sdcPath) {
        VolumeBlockCache cache = cachesByPath.remove(sdcPath);
        if (cache != null) {
            cache.clear();
        }
    }

    /** Retorna informação sobre cache para um arquivo específico. */
    public CacheInfo getCacheInfo(String sdcPath) {
        VolumeBlockCache cache = cachesByPath.get(sdcPath);
        if (cache == null) {
            return new CacheInfo(0, cacheBlockCapacity);
        }
        return new CacheInfo(cache.size(), cache.capacity());
    }

    public TraceHeadersResponse traceHeaders(TraceHeadersRequest req) throws IOException {
        Path path = resolveSdcPath(req.sdcPath);
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {

            SdcHeader header = SdcHeader.read(in);

            TraceHeadersResponse resp = new TraceHeadersResponse();
            resp.sdcPath = req.sdcPath;
            resp.version = header.version();
            resp.traceCount = header.traceCount();

            // Ler headers dos traces do arquivo .sdc
            TraceHeader[] headers = new TraceHeader[header.traceCount()];

            if (header.version() == 2) {
                // Para .sdc v2, ler cada trace comprimido e extrair headers
                for (int i = 0; i < header.traceCount(); i++) {
                    int traceNum = in.readInt();
                    int inlineNum = in.readInt();
                    int xlineNum = in.readInt();

                    headers[i] = new TraceHeader(traceNum, inlineNum, xlineNum);

                    // Skip trace data (CompressedTraceBlock)
                    int dataSize = in.readInt();
                    in.skipBytes(dataSize);
                }
            } else if (header.version() == 3) {
                // Para .sdc v3, ler metadata de dimensões e blocos
                int inlineCount = in.readInt();
                int xlineCount = in.readInt();
                int timeCount = in.readInt();
                int blockInline = in.readInt();
                int blockXline = in.readInt();
                int blockTime = in.readInt();
                int blockCount = in.readInt();

                // Para 3D, gerar headers baseado na grid
                int traceIdx = 0;
                for (int inline = 0; inline < inlineCount && traceIdx < header.traceCount(); inline++) {
                    for (int xline = 0; xline < xlineCount && traceIdx < header.traceCount(); xline++) {
                        headers[traceIdx] = new TraceHeader(
                            traceIdx + 1,  // traceNumber (1-indexed)
                            inline + 1,     // inline (1-indexed)
                            xline + 1       // crossline (1-indexed)
                        );
                        traceIdx++;
                    }
                }
            }

            resp.headers = headers;
            return resp;
        }
    }

    public static final class CacheInfo {
        public final int blocksInCache;
        public final int blockCapacity;

        public CacheInfo(int blocksInCache, int blockCapacity) {
            this.blocksInCache = blocksInCache;
            this.blockCapacity = blockCapacity;
        }
    }

}

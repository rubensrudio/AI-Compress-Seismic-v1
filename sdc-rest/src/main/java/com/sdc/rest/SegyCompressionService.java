package com.sdc.rest;

import com.sdc.core.CompressionProfile;
import com.sdc.core.SegyCompression;
import com.sdc.rest.dto.SegyDtos.CompressRequest;
import com.sdc.rest.dto.SegyDtos.CompressResponse;
import com.sdc.rest.dto.SegyDtos.DecompressRequest;
import com.sdc.rest.dto.SegyDtos.DecompressResponse;
import com.sdc.rest.dto.SegyDtos.Compress3DRequest;
import com.sdc.rest.dto.SegyDtos.Decompress3DRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

@Service
public class SegyCompressionService {

    private final String dataRoot;

    public SegyCompressionService(@Value("${sdc.data.root:}") String dataRoot) {
        this.dataRoot = dataRoot;
    }

    /** Validates basename (no path separators) and resolves to full path under dataRoot. */
    private Path resolveInDataRoot(String basename) {
        if (basename == null || basename.contains("/") || basename.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "basename only");
        }
        if (dataRoot == null || dataRoot.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sdc.data.root not configured");
        }
        return Path.of(dataRoot).resolve(basename);
    }

    /** Derives output .sdc path from input .sgy basename: strips .sgy, appends .sdc. */
    private Path deriveOutputPath(String sgyBasename) {
        String base = sgyBasename.toLowerCase().endsWith(".sgy")
                ? sgyBasename.substring(0, sgyBasename.length() - 4)
                : sgyBasename;
        return Path.of(dataRoot).resolve(base + ".sdc");
    }

    public CompressResponse compress(CompressRequest req) throws Exception {
        Path segy = resolveInDataRoot(req.sgyFile);
        Path sdc  = deriveOutputPath(req.sgyFile);

        // Determina o profile:
        CompressionProfile profile;
        if (req.fidelityPercent != null) {
            profile = CompressionProfile.fromFidelityPercent(req.fidelityPercent);
        } else if (req.profile != null) {
            profile = CompressionProfile.fromProfileName(req.profile);
        } else {
            profile = CompressionProfile.defaultHighQuality();
        }

        SegyCompression.CompressionResult result =
                SegyCompression.compressSegyToSdc(segy, sdc, profile);

        CompressResponse resp = new CompressResponse();
        resp.segyPath = result.segyPath.toString();
        resp.sdcPath = result.sdcPath.toString();

        resp.segyBytes = result.segyBytes;
        resp.sdcBytes = result.sdcBytes;
        resp.rawDataBytes = result.rawDataBytes;

        resp.traceCount = result.traceCount;
        resp.samplesPerTrace = result.samplesPerTrace;

        resp.ratioFile = result.ratioFile;
        resp.ratioData = result.ratioData;
        resp.savingsPercent = result.savingsPercent;
        resp.ratio = result.ratioFile; // compatibilidade

        resp.psnrFirstTrace = result.psnrFirstTrace;
        resp.psnrMean = result.psnrMean;
        resp.psnrMin = result.psnrMin;
        resp.psnrMax = result.psnrMax;

        // info do profile
        resp.fidelityPercentRequested = profile.fidelityPercentRequested();
        resp.effectiveBits = profile.effectiveBits();
        resp.deflaterLevel = profile.deflaterLevel();

        return resp;
    }

    public CompressResponse compress3D(Compress3DRequest req) throws Exception {
        Path segy = resolveInDataRoot(req.sgyFile);
        Path sdc  = deriveOutputPath(req.sgyFile);

        // Determina o profile
        CompressionProfile profile;
        if (req.fidelityPercent != null) {
            profile = CompressionProfile.fromFidelityPercent(req.fidelityPercent);
        } else if (req.profile != null) {
            profile = CompressionProfile.fromProfileName(req.profile);
        } else {
            profile = CompressionProfile.defaultHighQuality();
        }

        int blockInline = (req.blockInline != null && req.blockInline > 0) ? req.blockInline : 8;
        int blockXline  = (req.blockXline  != null && req.blockXline  > 0) ? req.blockXline  : 8;
        int blockTime   = (req.blockTime   != null && req.blockTime   > 0) ? req.blockTime   : 0; // 0 = full time

        SegyCompression.CompressionResult result =
                SegyCompression.compressSegyToSdc3D(segy, sdc, profile, blockInline, blockXline, blockTime);

        CompressResponse resp = new CompressResponse();
        resp.segyPath = result.segyPath.toString();
        resp.sdcPath = result.sdcPath.toString();

        resp.segyBytes = result.segyBytes;
        resp.sdcBytes = result.sdcBytes;
        resp.rawDataBytes = result.rawDataBytes;

        resp.traceCount = result.traceCount;
        resp.samplesPerTrace = result.samplesPerTrace;

        resp.ratioFile = result.ratioFile;
        resp.ratioData = result.ratioData;
        resp.savingsPercent = result.savingsPercent;
        resp.ratio = result.ratioFile;

        resp.psnrFirstTrace = result.psnrFirstTrace;
        resp.psnrMean = result.psnrMean;
        resp.psnrMin = result.psnrMin;
        resp.psnrMax = result.psnrMax;

        // info do profile
        resp.fidelityPercentRequested = profile.fidelityPercentRequested();
        resp.effectiveBits = profile.effectiveBits();
        resp.deflaterLevel = profile.deflaterLevel();

        return resp;
    }


    public DecompressResponse decompress(DecompressRequest req) {
        DecompressResponse resp = new DecompressResponse();
        resp.sdcPath = req.sdcPath;
        resp.templateSegyPath = req.templateSegyPath;
        resp.outSegyPath = req.outSegyPath;

        try {
            SegyCompression.decompressSdcToSegy(
                    Path.of(req.sdcPath),
                    Path.of(req.templateSegyPath),
                    Path.of(req.outSegyPath)
            );
            resp.success = true;
            resp.message = "SEG-Y reconstruído com sucesso.";
        } catch (Exception e) {
            resp.success = false;
            resp.message = "Erro na descompressão: " + e.getMessage();
        }
        return resp;
    }

    public DecompressResponse decompress3D(Decompress3DRequest req) {
        DecompressResponse resp = new DecompressResponse();
        resp.sdcPath = req.sdcPath;
        resp.templateSegyPath = req.templateSegyPath;
        resp.outSegyPath = req.outSegyPath;

        // Determina o profile (deve ser compatível com o usado na compressão)
        CompressionProfile profile;
        if (req.fidelityPercent != null) {
            profile = CompressionProfile.fromFidelityPercent(req.fidelityPercent);
        } else if (req.profile != null) {
            profile = CompressionProfile.fromProfileName(req.profile);
        } else {
            profile = CompressionProfile.defaultHighQuality();
        }

        try {
            SegyCompression.decompressSdcToSegy3D(
                    Path.of(req.sdcPath),
                    Path.of(req.templateSegyPath),
                    Path.of(req.outSegyPath),
                    profile
            );
            resp.success = true;
            resp.message = "SEG-Y 3D reconstruído com sucesso.";
        } catch (Exception e) {
            resp.success = false;
            resp.message = "Erro na descompressão 3D: " + e.getMessage();
        }
        return resp;
    }
}

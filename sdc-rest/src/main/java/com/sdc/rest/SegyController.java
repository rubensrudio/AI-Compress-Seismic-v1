package com.sdc.rest;

import com.sdc.rest.dto.SegyDtos.CompressRequest;
import com.sdc.rest.dto.SegyDtos.CompressResponse;
import com.sdc.rest.dto.SegyDtos.DecompressRequest;
import com.sdc.rest.dto.SegyDtos.DecompressResponse;
import com.sdc.rest.dto.SegyDtos.Compress3DRequest;
import com.sdc.rest.dto.SegyDtos.Decompress3DRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/segy")
@Tag(name = "SEG-Y Compression", description = "APIs for compressing and decompressing SEG-Y seismic data files")
public class SegyController {

    private final SegyCompressionService service;

    public SegyController(SegyCompressionService service) {
        this.service = service;
    }

    @PostMapping("/compress")
    @Operation(
        summary = "Compress 2D SEG-Y file",
        description = "Compress a 2D SEG-Y seismic file to SDC format using configurable quantization and preprocessing"
    )
    @ApiResponse(responseCode = "200", description = "Successfully compressed SEG-Y file")
    public CompressResponse compress(@RequestBody CompressRequest req) throws Exception {
        return service.compress(req);
    }

    @PostMapping("/decompress")
    @Operation(
        summary = "Decompress 2D SDC file",
        description = "Decompress an SDC file back to SEG-Y format"
    )
    @ApiResponse(responseCode = "200", description = "Successfully decompressed SDC file")
    public DecompressResponse decompress(@RequestBody DecompressRequest req) {
        return service.decompress(req);
    }

    @PostMapping("/compress3d")
    @Operation(
        summary = "Compress 3D SEG-Y file",
        description = "Compress a 3D SEG-Y seismic volume to SDC format with volume-based blocking for efficient random access. Supports inline/crossline/time block dimensions."
    )
    @ApiResponse(responseCode = "200", description = "Successfully compressed 3D SEG-Y file")
    public CompressResponse compress3D(@RequestBody Compress3DRequest req) throws Exception {
        return service.compress3D(req);
    }

    @PostMapping("/decompress3d")
    @Operation(
        summary = "Decompress 3D SDC file",
        description = "Decompress a 3D SDC volume back to SEG-Y format"
    )
    @ApiResponse(responseCode = "200", description = "Successfully decompressed 3D SDC file")
    public DecompressResponse decompress3D(@RequestBody Decompress3DRequest req) {
        return service.decompress3D(req);
    }
}

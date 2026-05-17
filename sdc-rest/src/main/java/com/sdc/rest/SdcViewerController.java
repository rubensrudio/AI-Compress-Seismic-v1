package com.sdc.rest;

import com.sdc.rest.dto.SegyDtos.FilesResponse;
import com.sdc.rest.dto.SegyDtos.TraceSliceRequest;
import com.sdc.rest.dto.SegyDtos.TraceSliceResponse;
import com.sdc.rest.dto.SegyDtos.TraceHeadersRequest;
import com.sdc.rest.dto.SegyDtos.TraceHeadersResponse;
import com.sdc.rest.dto.SegyDtos.ViewerInfoRequest;
import com.sdc.rest.dto.SegyDtos.ViewerInfoResponse;
import com.sdc.rest.dto.SegyDtos.VolumeSliceRequest;
import com.sdc.rest.dto.SegyDtos.VolumeSliceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/viewer")
@Tag(name = "SDC Viewer", description = "APIs for viewing and analyzing SDC compressed seismic data files")
public class SdcViewerController {

    private final SdcViewerService service;

    public SdcViewerController(SdcViewerService service) {
        this.service = service;
    }

    @GetMapping("/files")
    @Operation(summary = "List available SDC files", description = "Returns basenames of .sdc files under sdc.data.root. Empty list when root is blank.")
    public FilesResponse listSdcFiles() {
        return new FilesResponse(service.listFiles("sdc"));
    }

    @GetMapping("/sgy-files")
    @Operation(summary = "List available SEG-Y files", description = "Returns basenames of .sgy files under sdc.data.root. Empty list when root is blank.")
    public FilesResponse listSgyFiles() {
        return new FilesResponse(service.listFiles("sgy"));
    }

    @PostMapping("/info")
    @Operation(
        summary = "Get SDC file metadata",
        description = "Retrieve detailed information about an SDC file including dimensions, format, and block structure"
    )
    @ApiResponse(responseCode = "200", description = "Successfully retrieved file metadata")
    public ViewerInfoResponse info(@RequestBody ViewerInfoRequest req) throws Exception {
        return service.info(req);
    }

    @PostMapping("/trace-slice")
    @Operation(
        summary = "Extract trace data slice",
        description = "Extract a subset of traces and samples from the SDC file. Useful for 2D seismic data visualization."
    )
    @ApiResponse(responseCode = "200", description = "Successfully extracted trace slice")
    public TraceSliceResponse traceSlice(@RequestBody TraceSliceRequest req) throws Exception {
        return service.traceSlice(req);
    }

    @PostMapping("/volume-slice")
    @Operation(
        summary = "Extract 3D volume slice",
        description = "Extract a 2D slice from 3D volume data along inline, crossline, or time axis. Supports efficient random access via block caching."
    )
    @ApiResponse(responseCode = "200", description = "Successfully extracted volume slice")
    public VolumeSliceResponse volumeSlice(@RequestBody VolumeSliceRequest req) throws Exception {
        return service.volumeSlice(req);
    }

    @PostMapping("/trace-headers")
    @Operation(
        summary = "Get trace header information",
        description = "Retrieve trace header values for specified traces"
    )
    @ApiResponse(responseCode = "200", description = "Successfully retrieved trace headers")
    public TraceHeadersResponse traceHeaders(@RequestBody TraceHeadersRequest req) throws Exception {
        return service.traceHeaders(req);
    }

    @GetMapping("/cache-info")
    @Operation(
        summary = "Get cache statistics",
        description = "Retrieve current cache information including hit/miss rates and memory usage for a specific SDC file"
    )
    @ApiResponse(responseCode = "200", description = "Successfully retrieved cache information")
    public SdcViewerService.CacheInfo cacheInfo(
        @Parameter(description = "Path to the SDC file", required = true)
        @RequestParam String sdcPath
    ) {
        return service.getCacheInfo(sdcPath);
    }

    @DeleteMapping("/cache-clear")
    @Operation(
        summary = "Clear file cache",
        description = "Clear all cached blocks for a specific SDC file to free up memory"
    )
    @ApiResponse(responseCode = "200", description = "Successfully cleared cache")
    public void cacheClear(
        @Parameter(description = "Path to the SDC file", required = true)
        @RequestParam String sdcPath
    ) {
        service.clearCache(sdcPath);
    }
}

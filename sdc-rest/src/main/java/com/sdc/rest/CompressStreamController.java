package com.sdc.rest;

import com.sdc.core.CompressionProfile;
import com.sdc.core.SegyCompression;
import com.sdc.core.SegyValidationException;
import com.sdc.core.SegyValidator;
import com.sdc.core.TracePredictor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for streaming SEG-Y compression.
 *
 * <p><b>POST /compress</b> — accepts a raw SEG-Y Rev1 binary payload
 * ({@code Content-Type: application/octet-stream}) and returns the compressed
 * {@code .sdc} file as a streaming response.
 *
 * <h3>Processing steps</h3>
 * <ol>
 *   <li>Aggregate the incoming {@link Flux} of {@link DataBuffer} into a single
 *       {@code byte[]} using {@link DataBufferUtils#join(org.reactivestreams.Publisher)}.</li>
 *   <li>Persist the bytes to a temporary file (cleaned up after response).</li>
 *   <li>Validate SEG-Y Rev1 conformance via {@link SegyValidator}; returns HTTP 400
 *       with a JSON error body on any validation failure.</li>
 *   <li>Compress via {@link SegyCompression#compressSegyToSdc(Path, Path, CompressionProfile,
 *       TracePredictor, UUID)}.</li>
 *   <li>Stream the resulting {@code .sdc} bytes back as
 *       {@code Content-Type: application/octet-stream}.</li>
 * </ol>
 *
 * <h3>Header {@code X-Compression-Profile}</h3>
 * Optional request header. Accepted values (case-insensitive):
 * {@code HIGH_QUALITY}, {@code BALANCED}, {@code HIGH_COMPRESSION}.
 * Defaults to {@code BALANCED} when absent or unrecognised.
 *
 * <h3>2 GB limit</h3>
 * The entire SEG-Y payload is buffered in memory before processing (see DA-03
 * in plan.md). The limit is controlled by
 * {@code spring.codec.max-in-memory-size} (currently 2 GB in
 * {@code application.yml}). Files larger than 2 GB must be compressed via the
 * CLI ({@code sdc compress}), which streams trace-by-trace without holding the
 * full payload in RAM.
 *
 * <h3>Error responses</h3>
 * <ul>
 *   <li>HTTP 400 — SEG-Y validation failed; body:
 *       {@code {"error":"Invalid SEG-Y Rev1 payload","detail":"<reason>"}}</li>
 *   <li>HTTP 500 — codec or I/O failure; body:
 *       {@code {"error":"Codec failure","detail":"<reason>"}}</li>
 * </ul>
 */
@RestController
@RequestMapping("/compress")
@Tag(name = "Streaming Compression", description = "Binary streaming endpoint: POST /compress (SEG-Y → .sdc)")
public class CompressStreamController {

    private static final Logger log = LoggerFactory.getLogger(CompressStreamController.class);

    /**
     * Compresses a raw SEG-Y Rev1 binary payload received as
     * {@code application/octet-stream} and returns the resulting
     * {@code .sdc} file as a binary stream.
     *
     * <p><b>2 GB limit:</b> the payload is buffered entirely in memory. For files
     * larger than 2 GB use the CLI: {@code sdc compress <input.segy> <output.sdc>}.
     *
     * @param bodyFlux           reactive flux of incoming data buffers (raw SEG-Y bytes)
     * @param compressionProfile optional {@code X-Compression-Profile} header value
     * @return compressed {@code .sdc} bytes or an error JSON body
     */
    @PostMapping(
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    @Operation(
        summary = "Compress SEG-Y Rev1 to .sdc (streaming)",
        description = "Receives a raw SEG-Y Rev1 binary payload and returns the compressed .sdc file. " +
                      "Payload is limited to 2 GB (see spring.codec.max-in-memory-size in application.yml)."
    )
    @ApiResponse(responseCode = "200", description = "Compressed .sdc file")
    @ApiResponse(responseCode = "400", description = "Invalid SEG-Y Rev1 payload")
    @ApiResponse(responseCode = "500", description = "Internal codec failure")
    public Mono<ResponseEntity<Flux<DataBuffer>>> compress(
            @RequestBody Flux<DataBuffer> bodyFlux,
            @RequestHeader(value = "X-Compression-Profile", required = false)
                    String compressionProfile) {

        return DataBufferUtils.join(bodyFlux)
                .flatMap(joined -> {
                    byte[] segyBytes = new byte[joined.readableByteCount()];
                    joined.read(segyBytes);
                    DataBufferUtils.release(joined);
                    return processCompression(segyBytes, compressionProfile);
                })
                .onErrorResume(ex -> {
                    // Errors not handled inside processCompression (e.g. join overflow)
                    log.error("Unexpected error during /compress", ex);
                    Flux<DataBuffer> errBody = errorJsonFlux(
                        "{\"error\":\"Codec failure\",\"detail\":\"" + sanitise(ex.getMessage()) + "\"}");
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(errBody));
                });
    }

    // -------------------------------------------------------------------------
    // Internal processing
    // -------------------------------------------------------------------------

    private Mono<ResponseEntity<Flux<DataBuffer>>> processCompression(
            byte[] segyBytes, String profileHeader) {

        // Step 1: validate SEG-Y
        try {
            SegyValidator.validate(segyBytes);
        } catch (SegyValidationException e) {
            log.warn("SEG-Y validation failed: {}", e.getMessage());
            String detail = sanitise(e.getMessage());
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Invalid SEG-Y Rev1 payload\",\"detail\":\"" + detail + "\"}");
            return Mono.just(ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        }

        // Step 2: write to temp file
        Path tmpSegy;
        Path tmpSdc;
        try {
            tmpSegy = Files.createTempFile("sdc-compress-in-", ".sgy");
            tmpSdc  = Files.createTempFile("sdc-compress-out-", ".sdc");
            Files.write(tmpSegy, segyBytes);
        } catch (IOException e) {
            log.error("Failed to create temp files for compression", e);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Codec failure\",\"detail\":\"" + sanitise(e.getMessage()) + "\"}");
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        }

        // Step 3: resolve compression profile
        CompressionProfile profile = resolveProfile(profileHeader);

        // Step 4: compress
        try {
            SegyCompression.compressSegyToSdc(
                    tmpSegy, tmpSdc, profile, TracePredictor.identity(), UUID.randomUUID());
        } catch (Exception e) {
            log.error("Codec failure during compression", e);
            cleanupQuietly(tmpSegy, tmpSdc);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Codec failure\",\"detail\":\"" + sanitise(e.getMessage()) + "\"}");
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        } finally {
            cleanupQuietly(tmpSegy);
        }

        // Step 5: read compressed bytes and stream back
        byte[] sdcBytes;
        try {
            sdcBytes = Files.readAllBytes(tmpSdc);
        } catch (IOException e) {
            log.error("Failed to read compressed output", e);
            cleanupQuietly(tmpSdc);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Codec failure\",\"detail\":\"" + sanitise(e.getMessage()) + "\"}");
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        } finally {
            cleanupQuietly(tmpSdc);
        }

        Flux<DataBuffer> responseFlux = Flux.just(
                DefaultDataBufferFactory.sharedInstance.wrap(sdcBytes));

        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(responseFlux));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@code X-Compression-Profile} header value to a
     * {@link CompressionProfile}. Defaults to BALANCED when the header is
     * absent, blank, or unrecognised.
     */
    private static CompressionProfile resolveProfile(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return CompressionProfile.fromProfileName("BALANCED");
        }
        try {
            return CompressionProfile.fromProfileName(headerValue.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("Unrecognised X-Compression-Profile '{}', using BALANCED", headerValue);
            return CompressionProfile.fromProfileName("BALANCED");
        }
    }

    /** Builds a single-buffer Flux wrapping a JSON error string. */
    private static Flux<DataBuffer> errorJsonFlux(String json) {
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes));
    }

    /** Removes characters that could break a JSON string literal. */
    private static String sanitise(String msg) {
        if (msg == null) return "unknown error";
        return msg.replace("\"", "'").replace("\n", " ").replace("\r", "");
    }

    /** Deletes files silently, logging any error at WARN level. */
    private static void cleanupQuietly(Path... paths) {
        for (Path p : paths) {
            if (p != null) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file {}: {}", p, e.getMessage());
                }
            }
        }
    }
}

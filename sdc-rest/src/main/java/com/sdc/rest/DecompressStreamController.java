package com.sdc.rest;

import com.sdc.core.SdcContainerV1;
import com.sdc.core.SegyCompression;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * REST endpoint for streaming SEG-Y decompression.
 *
 * <p><b>POST /decompress</b> — accepts a raw {@code .sdc} binary payload
 * ({@code Content-Type: application/octet-stream}) and returns the restored
 * SEG-Y Rev1 file as a streaming response.
 *
 * <h3>Processing steps</h3>
 * <ol>
 *   <li>Aggregate the incoming {@link Flux} of {@link DataBuffer} into a single
 *       {@code byte[]} using {@link DataBufferUtils#join(org.reactivestreams.Publisher)}.</li>
 *   <li>Validate the SDC container: check magic number ({@code 0x53444301}) and
 *       minimum payload length (&ge; 8 bytes for magic + codec_version). Returns
 *       HTTP 400 with a JSON error body on any validation failure.</li>
 *   <li>Persist the bytes to a temporary file (cleaned up after response).</li>
 *   <li>Decompress via {@link SegyCompression#decompressSdcToSegy(Path, Path, TracePredictor)}
 *       using {@link TracePredictor#identity()} (AePredictor real pending TASK-034).</li>
 *   <li>Stream the resulting SEG-Y bytes back as
 *       {@code Content-Type: application/octet-stream}.</li>
 * </ol>
 *
 * <h3>2 GB limit</h3>
 * The entire {@code .sdc} payload is buffered in memory before processing (see DA-03
 * in plan.md). The limit is controlled by {@code spring.codec.max-in-memory-size}
 * (currently 2 GB in {@code application.yml}). Files larger than 2 GB must be
 * decompressed via the CLI ({@code sdc decompress}).
 *
 * <h3>Error responses</h3>
 * <ul>
 *   <li>HTTP 400 — invalid SDC payload; body:
 *       {@code {"error":"Invalid SDC payload","detail":"<reason>"}}</li>
 *   <li>HTTP 500 — codec or I/O failure; body:
 *       {@code {"error":"Codec failure","detail":"<reason>"}}</li>
 * </ul>
 */
@RestController
@RequestMapping("/decompress")
@Tag(name = "Streaming Decompression", description = "Binary streaming endpoint: POST /decompress (.sdc → SEG-Y)")
public class DecompressStreamController {

    private static final Logger log = LoggerFactory.getLogger(DecompressStreamController.class);

    /**
     * Minimum valid SDC payload size in bytes.
     *
     * <p>A valid {@link SdcContainerV1} starts with at least:
     * <ul>
     *   <li>4 bytes — magic number</li>
     *   <li>2 bytes — codec_version (short)</li>
     * </ul>
     * Any payload shorter than this cannot be a valid SDC container.
     */
    private static final int SDC_MIN_BYTES = 6;

    /**
     * Decompresses a raw {@code .sdc} binary payload received as
     * {@code application/octet-stream} and returns the restored SEG-Y Rev1
     * file as a binary stream.
     *
     * <p><b>2 GB limit:</b> the payload is buffered entirely in memory. For files
     * larger than 2 GB use the CLI: {@code sdc decompress <input.sdc> <output.segy>}.
     *
     * @param bodyFlux reactive flux of incoming data buffers (raw .sdc bytes)
     * @return decompressed SEG-Y bytes or an error JSON body
     */
    @PostMapping(
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    @Operation(
        summary = "Decompress .sdc to SEG-Y Rev1 (streaming)",
        description = "Receives a raw .sdc binary payload and returns the restored SEG-Y Rev1 file. " +
                      "Payload is limited to 2 GB (see spring.codec.max-in-memory-size in application.yml)."
    )
    @ApiResponse(responseCode = "200", description = "Restored SEG-Y Rev1 file")
    @ApiResponse(responseCode = "400", description = "Invalid SDC payload")
    @ApiResponse(responseCode = "500", description = "Internal codec failure")
    public Mono<ResponseEntity<Flux<DataBuffer>>> decompress(
            @RequestBody Flux<DataBuffer> bodyFlux) {

        return DataBufferUtils.join(bodyFlux)
                .flatMap(joined -> {
                    byte[] sdcBytes = new byte[joined.readableByteCount()];
                    joined.read(sdcBytes);
                    DataBufferUtils.release(joined);
                    return processDecompression(sdcBytes);
                })
                .onErrorResume(ex -> {
                    log.error("Unexpected error during /decompress", ex);
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

    private Mono<ResponseEntity<Flux<DataBuffer>>> processDecompression(byte[] sdcBytes) {

        // Step 1: validate magic number and minimum length
        ResponseEntity<Flux<DataBuffer>> validationError = validateSdcHeader(sdcBytes);
        if (validationError != null) {
            return Mono.just(validationError);
        }

        // Step 2: write to temp files
        Path tmpSdc;
        Path tmpSegy;
        try {
            tmpSdc  = Files.createTempFile("sdc-decompress-in-",  ".sdc");
            tmpSegy = Files.createTempFile("sdc-decompress-out-", ".sgy");
            Files.write(tmpSdc, sdcBytes);
        } catch (IOException e) {
            log.error("Failed to create temp files for decompression", e);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Codec failure\",\"detail\":\"" + sanitise(e.getMessage()) + "\"}");
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        }

        // Step 3: decompress
        // NOTE: TracePredictor.identity() is used here as a placeholder.
        // AePredictor (real autoencoder) is pending TASK-034.
        try {
            SegyCompression.decompressSdcToSegy(tmpSdc, tmpSegy, TracePredictor.identity());
        } catch (IllegalArgumentException e) {
            // SdcContainerV1.read() throws IllegalArgumentException for invalid magic/version
            log.warn("SDC validation failed during decompression: {}", e.getMessage());
            cleanupQuietly(tmpSdc, tmpSegy);
            String detail = sanitise(e.getMessage());
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Invalid SDC payload\",\"detail\":\"" + detail + "\"}");
            return Mono.just(ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        } catch (Exception e) {
            log.error("Codec failure during decompression", e);
            cleanupQuietly(tmpSdc, tmpSegy);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Codec failure\",\"detail\":\"" + sanitise(e.getMessage()) + "\"}");
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        } finally {
            cleanupQuietly(tmpSdc);
        }

        // Step 4: read decompressed bytes and stream back
        byte[] segyBytes;
        try {
            segyBytes = Files.readAllBytes(tmpSegy);
        } catch (IOException e) {
            log.error("Failed to read decompressed SEG-Y output", e);
            cleanupQuietly(tmpSegy);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Codec failure\",\"detail\":\"" + sanitise(e.getMessage()) + "\"}");
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody));
        } finally {
            cleanupQuietly(tmpSegy);
        }

        Flux<DataBuffer> responseFlux = Flux.just(
                DefaultDataBufferFactory.sharedInstance.wrap(segyBytes));

        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(responseFlux));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Validates the SDC container header: checks minimum payload size and magic number.
     *
     * @param sdcBytes raw bytes of the SDC payload
     * @return a 400 error {@link ResponseEntity} if validation fails, or {@code null}
     *         if the payload passes all pre-flight checks
     */
    private static ResponseEntity<Flux<DataBuffer>> validateSdcHeader(byte[] sdcBytes) {
        if (sdcBytes == null || sdcBytes.length < SDC_MIN_BYTES) {
            String detail = "Payload too short: " + (sdcBytes == null ? 0 : sdcBytes.length) +
                            " bytes (minimum " + SDC_MIN_BYTES + " bytes required for SDC header)";
            log.warn("SDC pre-flight validation failed: {}", detail);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Invalid SDC payload\",\"detail\":\"" + sanitise(detail) + "\"}");
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody);
        }

        int magic = ByteBuffer.wrap(sdcBytes, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (magic != SdcContainerV1.MAGIC) {
            String detail = String.format(
                "Invalid SDC magic: 0x%08X. Expected: 0x%08X.", magic, SdcContainerV1.MAGIC);
            log.warn("SDC pre-flight validation failed: {}", detail);
            Flux<DataBuffer> errBody = errorJsonFlux(
                "{\"error\":\"Invalid SDC payload\",\"detail\":\"" + sanitise(detail) + "\"}");
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errBody);
        }

        return null; // validation passed
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

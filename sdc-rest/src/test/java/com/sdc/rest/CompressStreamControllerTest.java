package com.sdc.rest;

import com.sdc.core.SdcContainerV1;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CompressStreamController} (POST /compress).
 *
 * <p>Uses a minimal synthetic SEG-Y Rev1 fixture (format code 5 / IEEE float32)
 * built in-memory to avoid classpath resource dependencies. The Spring
 * application context is started on a random port.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompressStreamControllerTest {

    // SEG-Y structural constants (mirrored from SegyFixtureGenerator for self-containment)
    private static final int EBCDIC_HEADER_SIZE = 3200;
    private static final int BINARY_HEADER_SIZE = 400;
    private static final int TRACE_HEADER_SIZE  = 240;
    private static final int FORMAT_CODE_IEEE   = 5;  // IEEE float32 big-endian

    // Fixture dimensions
    private static final int SAMPLES_PER_TRACE = 100;
    private static final int TRACE_COUNT       = 3;

    @Autowired
    WebTestClient webClient;

    // -------------------------------------------------------------------------
    // Happy-path: valid SEG-Y → HTTP 200 with decodable .sdc body
    // -------------------------------------------------------------------------

    /**
     * Verifies that a valid SEG-Y Rev1 payload (format code 5, 3 traces,
     * 100 samples/trace) is accepted, compressed, and the response body starts
     * with the SdcContainerV1 magic number {@code 0x53444301}.
     */
    @Test
    void compress_validSegyFormatCode5_returns200WithSdcBody() {
        byte[] segyPayload = buildMinimalSegyFormatCode5(SAMPLES_PER_TRACE, TRACE_COUNT);

        byte[] responseBody = webClient.post()
                .uri("/compress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(segyPayload)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(responseBody).isNotNull();
        assertThat(responseBody.length).isGreaterThan(4);

        // Verify the magic number SDC\x01 (0x53444301) at offset 0
        int magic = ByteBuffer.wrap(responseBody, 0, 4)
                              .order(ByteOrder.BIG_ENDIAN)
                              .getInt();
        assertThat(magic)
                .as("Response body must start with SdcContainerV1 magic 0x%08X", SdcContainerV1.MAGIC)
                .isEqualTo(SdcContainerV1.MAGIC);
    }

    /**
     * Verifies that the optional {@code X-Compression-Profile} header is accepted
     * and the response is still HTTP 200 with a valid .sdc body.
     */
    @Test
    void compress_withCompressionProfileHeader_returns200() {
        byte[] segyPayload = buildMinimalSegyFormatCode5(SAMPLES_PER_TRACE, TRACE_COUNT);

        webClient.post()
                .uri("/compress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Compression-Profile", "HIGH_QUALITY")
                .bodyValue(segyPayload)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM);
    }

    // -------------------------------------------------------------------------
    // Error path: invalid payload → HTTP 400 with JSON error body
    // -------------------------------------------------------------------------

    /**
     * Verifies that a random-bytes payload (not a valid SEG-Y Rev1 file)
     * is rejected with HTTP 400 and a JSON error body.
     */
    @Test
    void compress_randomBytesPayload_returns400WithJsonError() {
        byte[] invalidPayload = new byte[512];
        // Fill with non-SEG-Y bytes (all 0x42 = ASCII 'B')
        java.util.Arrays.fill(invalidPayload, (byte) 0x42);

        webClient.post()
                .uri("/compress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(invalidPayload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid SEG-Y Rev1 payload");
    }

    /**
     * Verifies that a SEG-Y with samplesPerTrace=0 in the binary header is
     * rejected with HTTP 400 and a JSON error body containing a detail field.
     */
    @Test
    void compress_segyWithZeroSamplesPerTrace_returns400WithJsonError() {
        byte[] corruptPayload = buildSegyWithZeroSamplesPerTrace();

        webClient.post()
                .uri("/compress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(corruptPayload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid SEG-Y Rev1 payload")
                .jsonPath("$.detail").isNotEmpty();
    }

    /**
     * Verifies that a payload shorter than the minimum SEG-Y size (3600 bytes)
     * is rejected with HTTP 400.
     */
    @Test
    void compress_tooShortPayload_returns400() {
        byte[] tooShort = new byte[100];

        webClient.post()
                .uri("/compress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(tooShort)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid SEG-Y Rev1 payload");
    }

    // -------------------------------------------------------------------------
    // Fixture builders
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal valid SEG-Y Rev1 byte array with format code 5
     * (IEEE float32 big-endian).
     *
     * <p>Layout:
     * <ul>
     *   <li>3200 bytes EBCDIC header (filled with EBCDIC space 0x40, first byte
     *       overwritten with 0xC3 = 'C' in EBCDIC to guarantee bytes > 0x7F)</li>
     *   <li>400 bytes binary header (samplesPerTrace at offset 20, formatCode
     *       at offset 24 — both big-endian unsigned short)</li>
     *   <li>N traces × (240 bytes trace header + samplesPerTrace × 4 bytes float32)</li>
     * </ul>
     */
    static byte[] buildMinimalSegyFormatCode5(int samplesPerTrace, int traceCount) {
        int traceDataSize = TRACE_HEADER_SIZE + samplesPerTrace * 4;
        int totalSize = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE + traceCount * traceDataSize;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
             DataOutputStream out = new DataOutputStream(baos)) {

            // --- EBCDIC header (3200 bytes) ---
            // Fill with EBCDIC space (0x40)
            byte[] ebcdic = new byte[EBCDIC_HEADER_SIZE];
            java.util.Arrays.fill(ebcdic, (byte) 0x40);
            // Inject 0xC3 (EBCDIC 'C') at offset 0 so validator detects it as EBCDIC
            ebcdic[0] = (byte) 0xC3;
            // Add a recognisable label at EBCDIC offsets for debugging
            // EBCDIC 'S'=0xE2 'E'=0xC5 'G'=0xC7 'Y'=0xE8
            ebcdic[1] = (byte) 0xE2;
            ebcdic[2] = (byte) 0xC5;
            ebcdic[3] = (byte) 0xC7;
            ebcdic[4] = (byte) 0xE8;
            out.write(ebcdic);

            // --- Binary header (400 bytes) ---
            byte[] binaryHeader = new byte[BINARY_HEADER_SIZE];
            // samplesPerTrace at bytes 20-21 (big-endian unsigned short)
            binaryHeader[20] = (byte) ((samplesPerTrace >> 8) & 0xFF);
            binaryHeader[21] = (byte) (samplesPerTrace & 0xFF);
            // formatCode at bytes 24-25 (big-endian unsigned short = 5)
            binaryHeader[24] = 0x00;
            binaryHeader[25] = (byte) FORMAT_CODE_IEEE;
            out.write(binaryHeader);

            // --- Traces ---
            for (int t = 0; t < traceCount; t++) {
                // Trace header (240 bytes) — all zeros except trace sequence number
                byte[] traceHeader = new byte[TRACE_HEADER_SIZE];
                // Trace sequence number within SEG-Y file (bytes 4-7, big-endian int)
                traceHeader[4] = (byte) ((t + 1) >> 24);
                traceHeader[5] = (byte) ((t + 1) >> 16);
                traceHeader[6] = (byte) ((t + 1) >> 8);
                traceHeader[7] = (byte) (t + 1);
                out.write(traceHeader);

                // Samples — IEEE float32 big-endian
                for (int s = 0; s < samplesPerTrace; s++) {
                    // Use a simple sine-wave pattern for realistic seismic-like data
                    float value = (float) Math.sin(2.0 * Math.PI * s / samplesPerTrace);
                    int bits = Float.floatToIntBits(value);
                    out.writeInt(bits);
                }
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build synthetic SEG-Y fixture", e);
        }
    }

    /**
     * Builds a SEG-Y byte array where the binary header has samplesPerTrace = 0,
     * which should be rejected by {@link com.sdc.core.SegyValidator}.
     */
    private static byte[] buildSegyWithZeroSamplesPerTrace() {
        // Reuse the minimal builder then overwrite samplesPerTrace to 0
        byte[] valid = buildMinimalSegyFormatCode5(SAMPLES_PER_TRACE, 1);

        // The binary header starts at EBCDIC_HEADER_SIZE (3200).
        // samplesPerTrace is at binary header offset 20, i.e. file offset 3220.
        int sptOffset = EBCDIC_HEADER_SIZE + 20;
        valid[sptOffset]     = 0x00;
        valid[sptOffset + 1] = 0x00;

        return valid;
    }
}

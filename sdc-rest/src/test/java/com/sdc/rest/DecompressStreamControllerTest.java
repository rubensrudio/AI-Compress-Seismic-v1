package com.sdc.rest;

import com.sdc.core.CompressionProfile;
import com.sdc.core.SdcContainerV1;
import com.sdc.core.SegyCompression;
import com.sdc.core.TracePredictor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DecompressStreamController} (POST /decompress).
 *
 * <p>Uses minimal synthetic SEG-Y Rev1 and SDC fixtures (format code 5 / IEEE float32)
 * built in-memory to avoid classpath resource dependencies. The Spring application
 * context is started on a random port.</p>
 *
 * <p>The happy-path test:
 * <ol>
 *   <li>Builds a synthetic SEG-Y (format code 5, 3 traces, 100 samples/trace)</li>
 *   <li>Compresses it in-memory via {@link SegyCompression#compressSegyToSdc} to get a
 *       valid .sdc payload</li>
 *   <li>POSTs the .sdc payload to POST /decompress</li>
 *   <li>Verifies the restored SEG-Y is byte-for-byte identical to the original</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecompressStreamControllerTest {

    // SEG-Y structural constants
    private static final int EBCDIC_HEADER_SIZE = 3200;
    private static final int BINARY_HEADER_SIZE = 400;
    private static final int TRACE_HEADER_SIZE  = 240;
    private static final int FORMAT_CODE_IEEE   = 5;

    // Fixture dimensions
    private static final int SAMPLES_PER_TRACE = 100;
    private static final int TRACE_COUNT       = 3;

    @Autowired
    WebTestClient webClient;

    // -------------------------------------------------------------------------
    // Happy-path: valid .sdc → HTTP 200 with SEG-Y byte-for-byte identical to original
    // -------------------------------------------------------------------------

    /**
     * Verifies the full round-trip: SEG-Y → in-memory compress → .sdc → /decompress → SEG-Y.
     *
     * <p>The .sdc is produced directly via {@link SegyCompression#compressSegyToSdc}
     * with {@link TracePredictor#identity()} (same predictor used by the controller),
     * so no dependency on the /compress endpoint is required.
     *
     * <p>The fixture uses <b>constant samples per trace</b> (min == max for each trace)
     * to ensure byte-for-byte identity after the linear quantisation round-trip —
     * see {@code SegyCompressionV1Test#generateExactRoundTripSamples} for the rationale.
     * Sine-wave fixtures would differ by quantisation rounding, so they are intentionally
     * avoided in this round-trip assertion.
     *
     * <p>The restored SEG-Y bytes must be byte-for-byte identical to the original
     * (format code 5, IEEE float32).
     */
    @Test
    void decompress_validSdcFormatCode5_returns200WithOriginalSegy() throws IOException {
        // Step 1: build synthetic SEG-Y with constant samples (survives quantisation round-trip)
        byte[] originalSegy = buildConstantSampleSegyFormatCode5(SAMPLES_PER_TRACE, TRACE_COUNT);
        Path tmpSegy = Files.createTempFile("sdc-test-in-", ".sgy");
        Path tmpSdc  = Files.createTempFile("sdc-test-out-", ".sdc");
        try {
            Files.write(tmpSegy, originalSegy);

            // Step 2: compress in-memory via SegyCompression (no /compress endpoint needed)
            SegyCompression.compressSegyToSdc(
                    tmpSegy, tmpSdc,
                    CompressionProfile.defaultHighQuality(),
                    TracePredictor.identity(),
                    UUID.randomUUID());

            byte[] sdcPayload = Files.readAllBytes(tmpSdc);
            assertThat(sdcPayload).isNotNull().hasSizeGreaterThan(4);

            // Verify the .sdc starts with the correct magic (sanity check)
            int magic = ByteBuffer.wrap(sdcPayload, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            assertThat(magic).isEqualTo(SdcContainerV1.MAGIC);

            // Step 3: decompress via POST /decompress
            byte[] restoredSegy = webClient.post()
                    .uri("/decompress")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(sdcPayload)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .expectBody(byte[].class)
                    .returnResult()
                    .getResponseBody();

            // Step 4: verify byte-for-byte identity
            assertThat(restoredSegy)
                    .as("Restored SEG-Y must be byte-for-byte identical to the original (format code 5)")
                    .isNotNull()
                    .isEqualTo(originalSegy);
        } finally {
            Files.deleteIfExists(tmpSegy);
            Files.deleteIfExists(tmpSdc);
        }
    }

    // -------------------------------------------------------------------------
    // Error path: invalid payload → HTTP 400 with JSON error body
    // -------------------------------------------------------------------------

    /**
     * Verifies that a random-bytes payload (not a valid .sdc file) is rejected
     * with HTTP 400 and a JSON error body containing the "error" field.
     */
    @Test
    void decompress_randomBytesPayload_returns400WithJsonError() {
        byte[] invalidPayload = new byte[512];
        // Fill with non-SDC bytes (all 0x42 = ASCII 'B' — not 0x53444301)
        java.util.Arrays.fill(invalidPayload, (byte) 0x42);

        webClient.post()
                .uri("/decompress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(invalidPayload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid SDC payload");
    }

    /**
     * Verifies that a payload shorter than {@code SDC_MIN_BYTES} (6 bytes) is
     * rejected with HTTP 400.
     */
    @Test
    void decompress_tooShortPayload_returns400() {
        byte[] tooShort = new byte[4]; // fewer than 6 bytes (magic + codec_version minimum)

        webClient.post()
                .uri("/decompress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(tooShort)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid SDC payload");
    }

    /**
     * Verifies that a payload with correct magic length but wrong magic value
     * is rejected with HTTP 400 and an error detail mentioning the invalid magic.
     */
    @Test
    void decompress_wrongMagicInPayload_returns400WithDetail() {
        // Craft a payload with a wrong magic (0xDEADBEEF instead of 0x53444301)
        byte[] wrongMagicPayload = new byte[64];
        ByteBuffer.wrap(wrongMagicPayload).order(ByteOrder.BIG_ENDIAN).putInt(0, 0xDEADBEEF);

        webClient.post()
                .uri("/decompress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(wrongMagicPayload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid SDC payload")
                .jsonPath("$.detail").isNotEmpty();
    }

    /**
     * Verifies that a payload with the prototype magic ({@code 0x53444331}) is
     * rejected with HTTP 400 (incompatible legacy format detection).
     */
    @Test
    void decompress_prototypeMagicPayload_returns400() {
        // Build a payload with the prototype magic 0x53444331 (different from v1 0x53444301)
        byte[] legacyPayload = new byte[64];
        // Prototype magic: 'S'=0x53, 'D'=0x44, 'C'=0x43, '1'=0x31
        ByteBuffer.wrap(legacyPayload).order(ByteOrder.BIG_ENDIAN).putInt(0, 0x53444331);

        webClient.post()
                .uri("/decompress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(legacyPayload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid SDC payload");
    }

    // -------------------------------------------------------------------------
    // Additional sanity check
    // -------------------------------------------------------------------------

    /**
     * Verifies the magic number constant used in validation matches SdcContainerV1.MAGIC.
     * This is a sanity check to ensure the controller validates against the correct constant.
     */
    @Test
    void sdcContainerV1Magic_matches_expectedConstant() {
        assertThat(SdcContainerV1.MAGIC)
                .as("SdcContainerV1.MAGIC must be 0x53444301")
                .isEqualTo(0x53444301);
    }

    // -------------------------------------------------------------------------
    // Fixture builders
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal valid SEG-Y Rev1 byte array with format code 5
     * (IEEE float32 big-endian) using <b>constant samples per trace</b>.
     *
     * <p>Each trace t uses a constant value {@code t * 0.1f}. When min == max for a
     * trace, the linear quantisation codec preserves the exact float value on
     * round-trip (no rounding noise), guaranteeing byte-for-byte identity of the
     * decompressed SEG-Y.
     *
     * <p>Layout:
     * <ul>
     *   <li>3200 bytes EBCDIC header (EBCDIC space 0x40; first byte 0xC3 = 'C')</li>
     *   <li>400 bytes binary header</li>
     *   <li>N traces × (240 bytes trace header + samplesPerTrace × 4 bytes float32)</li>
     * </ul>
     */
    static byte[] buildConstantSampleSegyFormatCode5(int samplesPerTrace, int traceCount) {
        int traceDataSize = TRACE_HEADER_SIZE + samplesPerTrace * 4;
        int totalSize = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE + traceCount * traceDataSize;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
             DataOutputStream out = new DataOutputStream(baos)) {

            // --- EBCDIC header (3200 bytes) ---
            byte[] ebcdic = new byte[EBCDIC_HEADER_SIZE];
            java.util.Arrays.fill(ebcdic, (byte) 0x40); // EBCDIC space
            ebcdic[0] = (byte) 0xC3; // EBCDIC 'C'
            ebcdic[1] = (byte) 0xE2; // EBCDIC 'S'
            ebcdic[2] = (byte) 0xC5; // EBCDIC 'E'
            ebcdic[3] = (byte) 0xC7; // EBCDIC 'G'
            ebcdic[4] = (byte) 0xE8; // EBCDIC 'Y'
            out.write(ebcdic);

            // --- Binary header (400 bytes) ---
            byte[] binaryHeader = new byte[BINARY_HEADER_SIZE];
            binaryHeader[20] = (byte) ((samplesPerTrace >> 8) & 0xFF);
            binaryHeader[21] = (byte) (samplesPerTrace & 0xFF);
            binaryHeader[24] = 0x00;
            binaryHeader[25] = (byte) FORMAT_CODE_IEEE;
            out.write(binaryHeader);

            // --- Traces with constant samples (min == max => quantisation is lossless) ---
            for (int t = 0; t < traceCount; t++) {
                byte[] traceHeader = new byte[TRACE_HEADER_SIZE];
                traceHeader[4] = (byte) ((t + 1) >> 24);
                traceHeader[5] = (byte) ((t + 1) >> 16);
                traceHeader[6] = (byte) ((t + 1) >> 8);
                traceHeader[7] = (byte) (t + 1);
                out.write(traceHeader);

                // Constant value per trace: 0.0f, 0.1f, 0.2f, ...
                float value = t * 0.1f;
                for (int s = 0; s < samplesPerTrace; s++) {
                    out.writeInt(Float.floatToIntBits(value));
                }
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build constant-sample SEG-Y fixture", e);
        }
    }

    /**
     * Builds a minimal valid SEG-Y Rev1 byte array with format code 5
     * (IEEE float32 big-endian) using sine-wave samples.
     *
     * <p>Used only for error-path tests (invalid payload scenarios) where
     * byte-for-byte identity is not required.
     */
    static byte[] buildMinimalSegyFormatCode5(int samplesPerTrace, int traceCount) {
        int traceDataSize = TRACE_HEADER_SIZE + samplesPerTrace * 4;
        int totalSize = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE + traceCount * traceDataSize;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
             DataOutputStream out = new DataOutputStream(baos)) {

            byte[] ebcdic = new byte[EBCDIC_HEADER_SIZE];
            java.util.Arrays.fill(ebcdic, (byte) 0x40);
            ebcdic[0] = (byte) 0xC3;
            out.write(ebcdic);

            byte[] binaryHeader = new byte[BINARY_HEADER_SIZE];
            binaryHeader[20] = (byte) ((samplesPerTrace >> 8) & 0xFF);
            binaryHeader[21] = (byte) (samplesPerTrace & 0xFF);
            binaryHeader[24] = 0x00;
            binaryHeader[25] = (byte) FORMAT_CODE_IEEE;
            out.write(binaryHeader);

            for (int t = 0; t < traceCount; t++) {
                byte[] traceHeader = new byte[TRACE_HEADER_SIZE];
                out.write(traceHeader);
                for (int s = 0; s < samplesPerTrace; s++) {
                    float value = (float) Math.sin(2.0 * Math.PI * s / samplesPerTrace);
                    out.writeInt(Float.floatToIntBits(value));
                }
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build synthetic SEG-Y fixture", e);
        }
    }
}

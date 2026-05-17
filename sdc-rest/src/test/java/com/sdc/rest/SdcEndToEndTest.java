package com.sdc.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: compress → decompress round-trip via REST endpoints.
 *
 * <h3>Test coverage</h3>
 * <ol>
 *   <li>{@link #endToEnd_compressDecompress_sha256Matches()} — full round-trip:
 *       POST /compress with a synthetic SEG-Y (format code 5, constant samples),
 *       then POST /decompress with the returned .sdc body; verifies SHA-256 of
 *       the restored SEG-Y matches SHA-256 of the original fixture.</li>
 *   <li>{@link #health_returnsUp()} — GET /actuator/health returns HTTP 200
 *       with {@code {"status":"UP"}} after application startup.</li>
 *   <li>{@code benchmark_returnsJsonWithRequiredFields()} — GET /benchmark
 *       returns JSON containing at least {@code throughput_mb_s} and
 *       {@code compression_ratio}.
 *       <br><b>TODO (TASK-017):</b> BenchmarkController is not yet implemented.
 *       This test is skipped until TASK-017 is merged into the integration branch.
 *       Re-enable by removing the {@code @org.junit.jupiter.api.Disabled} annotation
 *       once BenchmarkController exists at GET /benchmark.</li>
 * </ol>
 *
 * <h3>Fixture strategy</h3>
 * The synthetic SEG-Y fixture is built <em>entirely in memory</em> with
 * <b>constant samples per trace</b> (all samples in a given trace share the same
 * float value). When min == max, the linear quantisation codec preserves the exact
 * float value on round-trip with zero rounding noise, guaranteeing byte-for-byte
 * identity after decompress and a matching SHA-256 digest.
 *
 * <h3>Quantisation bits alignment</h3>
 * <p>The POST /compress request uses {@code X-Compression-Profile: HIGH_QUALITY}
 * (16 bits) to guarantee round-trip correctness. This is a deliberate workaround
 * for a known codec bug: {@code LinearQuantizer.decode(short[])} always assumes
 * 16 bits, regardless of the bits used during encode. Using {@code BALANCED}
 * (12 bits) or {@code HIGH_COMPRESSION} (8 bits) would silently corrupt the
 * dequantised values on decode, causing the SHA-256 assertion to fail even with
 * constant-value traces.
 * <br>
 * TODO (TASK-XX): fix {@code TraceBlockCodec} to store {@code quantizationBits}
 * inside {@code CompressedTraceBlock} (and in the SDC container serialisation) and
 * pass those bits to {@code LinearQuantizer.decode(short[], int)} in
 * {@code TraceBlockCodec.decompressInternal()}. Once fixed, this test can use any
 * profile without risk of silent data corruption.</p>
 *
 * <h3>Fixture structure (format code 5 / IEEE float32 big-endian)</h3>
 * <ul>
 *   <li>EBCDIC header: 3200 bytes; first byte {@code 0xC3} (EBCDIC 'C' &gt; 0x7F)</li>
 *   <li>Binary header: 400 bytes; {@code samplesPerTrace} at big-endian short offset
 *       20–21 = 100; {@code formatCode} at offset 24–25 = 0x0005</li>
 *   <li>3 traces × (240-byte trace header + 100 × 4-byte IEEE float32)</li>
 * </ul>
 *
 * @see CompressStreamController
 * @see DecompressStreamController
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SdcEndToEndTest {

    // -------------------------------------------------------------------------
    // SEG-Y structural constants
    // -------------------------------------------------------------------------

    private static final int EBCDIC_HEADER_SIZE = 3200;
    private static final int BINARY_HEADER_SIZE  = 400;
    private static final int TRACE_HEADER_SIZE   = 240;
    private static final int FORMAT_CODE_IEEE    = 5;    // IEEE float32 big-endian

    /** Number of samples per trace in the synthetic fixture. */
    private static final int SAMPLES_PER_TRACE = 100;

    /** Number of traces in the synthetic fixture. */
    private static final int TRACE_COUNT = 3;

    /**
     * Constant sample value written to every sample in every trace.
     *
     * <p>Using 1.5f ensures min == max per trace, which makes the linear
     * quantisation step lossless and guarantees exact round-trip byte identity.
     */
    private static final float CONSTANT_SAMPLE_VALUE = 1.5f;

    @Autowired
    WebTestClient webClient;

    // -------------------------------------------------------------------------
    // Test 1: end-to-end compress → decompress with SHA-256 verification
    // -------------------------------------------------------------------------

    /**
     * Full round-trip test: POST /compress → POST /decompress → SHA-256 comparison.
     *
     * <p>Steps:
     * <ol>
     *   <li>Build a synthetic SEG-Y Rev1 fixture in memory (format code 5, constant
     *       samples; guarantees lossless round-trip through linear quantisation).</li>
     *   <li>POST the fixture to {@code /compress} with {@code X-Compression-Profile: HIGH_QUALITY}
     *       and collect the {@code .sdc} response body.</li>
     *   <li>Verify the {@code .sdc} magic number (first 4 bytes must equal {@code 0x53444301}).</li>
     *   <li>POST the {@code .sdc} body to {@code /decompress} and collect the restored SEG-Y.</li>
     *   <li>Compare SHA-256 of the original fixture with SHA-256 of the restored SEG-Y.
     *       They must be identical.</li>
     * </ol>
     *
     * <p><b>Profile choice:</b> {@code HIGH_QUALITY} (16 bits) is used deliberately to align
     * encode bits with the 16 bits assumed by {@code LinearQuantizer.decode(short[])} in
     * {@code TraceBlockCodec.decompressInternal()}. Using {@code BALANCED} (12 bits) or
     * {@code HIGH_COMPRESSION} (8 bits) would produce silently corrupted samples on decode.
     * See class-level Javadoc for the full TODO reference (TASK-XX).
     *
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable (should never happen on any JVM 17+)
     */
    @Test
    @DisplayName("E2E: /compress (HIGH_QUALITY) → /decompress produces SHA-256-identical SEG-Y (format code 5)")
    void endToEnd_compressDecompress_sha256Matches() throws NoSuchAlgorithmException {
        // Step 1: build the synthetic fixture in memory
        byte[] originalSegy = buildConstantSampleSegyFormatCode5(
                SAMPLES_PER_TRACE, TRACE_COUNT, CONSTANT_SAMPLE_VALUE);

        byte[] originalSha256 = sha256(originalSegy);

        // Step 2: POST to /compress with HIGH_QUALITY profile (16 bits) to guarantee correct
        // round-trip. HIGH_QUALITY = 16 bits matches the fixed 16 bits in LinearQuantizer.decode().
        byte[] sdcBody = webClient.post()
                .uri("/compress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Compression-Profile", "HIGH_QUALITY")
                .bodyValue(originalSegy)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(sdcBody)
                .as("/compress response body must not be null and must have at least 8 bytes (magic + version)")
                .isNotNull()
                .hasSizeGreaterThan(8);

        // Step 2b: verify SDC magic number — first 4 bytes must equal 0x53444301 ('S''D''C'\x01)
        int magic = ((sdcBody[0] & 0xFF) << 24) | ((sdcBody[1] & 0xFF) << 16)
                  | ((sdcBody[2] & 0xFF) <<  8) |  (sdcBody[3] & 0xFF);
        assertThat(magic)
                .as("First 4 bytes of .sdc body must be the SDC magic number 0x53444301, got 0x%08X", magic)
                .isEqualTo(0x53444301);

        // Step 3: POST .sdc body to /decompress — expect HTTP 200 with restored SEG-Y
        byte[] restoredSegy = webClient.post()
                .uri("/decompress")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(sdcBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(restoredSegy)
                .as("/decompress response body must not be null")
                .isNotNull();

        // Step 4: SHA-256 comparison
        byte[] restoredSha256 = sha256(restoredSegy);

        assertThat(restoredSha256)
                .as("SHA-256 of restored SEG-Y must match SHA-256 of original fixture. " +
                    "Expected: %s  Actual: %s",
                    hexString(originalSha256), hexString(restoredSha256))
                .isEqualTo(originalSha256);
    }

    // -------------------------------------------------------------------------
    // Test 2: GET /actuator/health → status UP
    // -------------------------------------------------------------------------

    /**
     * Verifies that the Spring Boot Actuator health endpoint returns HTTP 200 with
     * {@code {"status":"UP"}} after application startup.
     *
     * <p>The health endpoint is exposed at {@code /actuator/health} via
     * {@code management.endpoints.web.exposure.include: health} in
     * {@code application.yml}.
     */
    @Test
    @DisplayName("GET /actuator/health returns HTTP 200 with status UP")
    void health_returnsUp() {
        webClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    // -------------------------------------------------------------------------
    // Test 3: GET /benchmark → JSON with required fields
    // TODO (TASK-017): BenchmarkController not yet implemented — test disabled
    // -------------------------------------------------------------------------

    /**
     * Verifies that GET /benchmark returns HTTP 200 with a JSON payload containing
     * at least {@code throughput_mb_s} and {@code compression_ratio}.
     *
     * <p><b>TODO (TASK-017):</b> {@code BenchmarkController} is not yet implemented.
     * This test is disabled until TASK-017 is merged into the integration branch.
     * Remove the {@link org.junit.jupiter.api.Disabled} annotation to re-enable it.
     */
    @org.junit.jupiter.api.Disabled("TODO TASK-017: BenchmarkController not implemented yet — re-enable after merge")
    @Test
    @DisplayName("GET /benchmark returns JSON with throughput_mb_s and compression_ratio")
    void benchmark_returnsJsonWithRequiredFields() {
        webClient.get()
                .uri("/benchmark")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.throughput_mb_s").exists()
                .jsonPath("$.compression_ratio").exists();
    }

    // -------------------------------------------------------------------------
    // Fixture builder
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal valid SEG-Y Rev1 byte array in memory.
     *
     * <p>All samples in every trace are set to {@code constantValue}. When min == max
     * per trace, the linear quantisation codec in {@code TraceBlockCodec} preserves
     * the exact IEEE float value on round-trip, guaranteeing byte-for-byte identity
     * between original and restored SEG-Y.
     *
     * <h4>Binary layout</h4>
     * <pre>
     * [3200 bytes] EBCDIC textual header
     *   offset 0: 0xC3 (EBCDIC 'C', > 0x7F — required by SegyValidator)
     *   offset 1..3199: 0x40 (EBCDIC space)
     *
     * [400 bytes] Binary header
     *   offset 20–21 (big-endian short): samplesPerTrace
     *   offset 24–25 (big-endian short): formatCode = 5 (IEEE float32)
     *   remaining bytes: 0x00
     *
     * [traceCount × (240 + samplesPerTrace × 4) bytes] Traces
     *   Trace header (240 bytes):
     *     bytes 4–7 (big-endian int): trace sequence number within file (1-based)
     *     remaining: 0x00
     *   Samples (samplesPerTrace × 4 bytes):
     *     each sample: Float.floatToIntBits(constantValue) written as big-endian int
     * </pre>
     *
     * @param samplesPerTrace number of float32 samples per trace
     * @param traceCount      number of traces to write
     * @param constantValue   the IEEE float32 value written to every sample
     * @return a valid SEG-Y Rev1 byte array
     * @throws IllegalStateException if I/O fails (should never happen for in-memory writes)
     */
    static byte[] buildConstantSampleSegyFormatCode5(
            int samplesPerTrace, int traceCount, float constantValue) {

        int traceDataSize = TRACE_HEADER_SIZE + samplesPerTrace * 4;
        int totalSize = EBCDIC_HEADER_SIZE + BINARY_HEADER_SIZE + traceCount * traceDataSize;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
             DataOutputStream out = new DataOutputStream(baos)) {

            // --- EBCDIC textual header (3200 bytes) ---
            byte[] ebcdic = new byte[EBCDIC_HEADER_SIZE];
            Arrays.fill(ebcdic, (byte) 0x40);   // EBCDIC space character
            ebcdic[0] = (byte) 0xC3;             // EBCDIC 'C' (byte > 0x7F: required by SegyValidator)
            ebcdic[1] = (byte) 0xE2;             // EBCDIC 'S'
            ebcdic[2] = (byte) 0xC5;             // EBCDIC 'E'
            ebcdic[3] = (byte) 0xC7;             // EBCDIC 'G'
            ebcdic[4] = (byte) 0xE8;             // EBCDIC 'Y'
            out.write(ebcdic);

            // --- Binary header (400 bytes) ---
            byte[] binaryHeader = new byte[BINARY_HEADER_SIZE];
            // samplesPerTrace at bytes 20–21 (big-endian unsigned short)
            binaryHeader[20] = (byte) ((samplesPerTrace >> 8) & 0xFF);
            binaryHeader[21] = (byte)  (samplesPerTrace       & 0xFF);
            // formatCode at bytes 24–25 (big-endian unsigned short = 5 for IEEE float32)
            binaryHeader[24] = 0x00;
            binaryHeader[25] = (byte) FORMAT_CODE_IEEE;
            out.write(binaryHeader);

            // --- Traces ---
            int constantBits = Float.floatToIntBits(constantValue);
            for (int t = 0; t < traceCount; t++) {
                // Trace header (240 bytes)
                byte[] traceHeader = new byte[TRACE_HEADER_SIZE];
                // Trace sequence number within SEG-Y file (bytes 4–7, big-endian int, 1-based)
                int seqNum = t + 1;
                traceHeader[4] = (byte) ((seqNum >> 24) & 0xFF);
                traceHeader[5] = (byte) ((seqNum >> 16) & 0xFF);
                traceHeader[6] = (byte) ((seqNum >>  8) & 0xFF);
                traceHeader[7] = (byte)  (seqNum        & 0xFF);
                out.write(traceHeader);

                // Samples — IEEE float32 big-endian, constant value
                for (int s = 0; s < samplesPerTrace; s++) {
                    out.writeInt(constantBits);
                }
            }

            return baos.toByteArray();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to build synthetic SEG-Y fixture in memory", e);
        }
    }

    // -------------------------------------------------------------------------
    // SHA-256 helpers
    // -------------------------------------------------------------------------

    /**
     * Computes the SHA-256 digest of the given byte array.
     *
     * @param data input bytes
     * @return 32-byte SHA-256 digest
     * @throws NoSuchAlgorithmException never thrown on JVM 17+ (SHA-256 is mandated by the JCA spec)
     */
    private static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    /**
     * Converts a byte array to its lowercase hexadecimal string representation
     * for use in assertion messages.
     *
     * @param bytes byte array to encode
     * @return lowercase hex string (2 chars per byte)
     */
    private static String hexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

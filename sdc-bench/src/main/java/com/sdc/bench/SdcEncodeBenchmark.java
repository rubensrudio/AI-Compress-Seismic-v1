package com.sdc.bench;

import com.sdc.core.CompressionProfile;
import com.sdc.core.SegyCompression;
import com.sdc.core.TracePredictor;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the SDC encode pipeline.
 *
 * <p>The fixture is built in-memory at setup time (100 traces × 125 samples
 * each in IEEE float32 format code 5). This avoids a circular dependency on
 * sdc-fixtures and keeps the benchmark self-contained.
 *
 * <p>Run via: {@code mvn verify -pl sdc-bench}
 * The JSON result is written to {@code target/jmh-results/latest.json}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class SdcEncodeBenchmark {

    /** Number of traces in the synthetic fixture. */
    private static final int TRACE_COUNT = 100;

    /** Number of float32 samples per trace. */
    private static final int SAMPLES_PER_TRACE = 125;

    /** SEG-Y Rev1 format code for IEEE 754 float32. */
    private static final int FORMAT_CODE_IEEE = 5;

    /** SEG-Y textual header size in bytes (EBCDIC). */
    private static final int TEXTUAL_HEADER_BYTES = 3200;

    /** SEG-Y binary header size in bytes. */
    private static final int BINARY_HEADER_BYTES = 400;

    /** SEG-Y trace header size in bytes. */
    private static final int TRACE_HEADER_BYTES = 240;

    private Path inputSegyPath;
    private Path outputSdcPath;
    private CompressionProfile profile;
    private TracePredictor predictor;

    /**
     * Creates a minimal but valid SEG-Y Rev1 fixture on disk before each trial.
     * Using {@link Level#Trial} avoids I/O overhead inside the measured method.
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        inputSegyPath = Files.createTempFile("sdc-bench-encode-in-", ".segy");
        outputSdcPath = Files.createTempFile("sdc-bench-encode-out-", ".sdc");

        writeSyntheticSegy(inputSegyPath, TRACE_COUNT, SAMPLES_PER_TRACE);

        profile   = CompressionProfile.balanced();
        predictor = TracePredictor.identity();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        Files.deleteIfExists(inputSegyPath);
        Files.deleteIfExists(outputSdcPath);
    }

    /**
     * Measures throughput of the full encode pipeline:
     * SEG-Y read → delta encode → DEFLATE → SdcContainerV1 write.
     */
    @Benchmark
    public void encodeFullPipeline() throws IOException {
        SegyCompression.compressSegyToSdc(
                inputSegyPath,
                outputSdcPath,
                profile,
                predictor,
                java.util.UUID.randomUUID());
    }

    // -----------------------------------------------------------------------
    // Fixture builder — in-memory synthetic SEG-Y Rev1 (format code 5)
    // -----------------------------------------------------------------------

    /**
     * Writes a minimal SEG-Y Rev1 file with IEEE float32 (format code 5) traces.
     *
     * <p>Layout:
     * <ul>
     *   <li>3 200 bytes — EBCDIC textual header (filled with spaces)</li>
     *   <li>400 bytes  — binary header (big-endian; critical fields set)</li>
     *   <li>For each trace: 240 bytes trace header + samplesPerTrace × 4 bytes samples</li>
     * </ul>
     */
    static void writeSyntheticSegy(Path path, int traceCount, int samplesPerTrace)
            throws IOException {

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {

            // --- Textual header (3200 bytes EBCDIC — all 0x40 = EBCDIC space) ---
            byte[] textualHeader = new byte[TEXTUAL_HEADER_BYTES];
            java.util.Arrays.fill(textualHeader, (byte) 0x40);
            out.write(textualHeader);

            // --- Binary header (400 bytes, big-endian) ---
            // Only the fields that SegyIO cares about:
            //   bytes 3217-3218 (offset 16): interval (us) = 2000
            //   bytes 3221-3222 (offset 20): samples per trace = SAMPLES_PER_TRACE
            //   bytes 3225-3226 (offset 24): data sample format code = 5 (IEEE float)
            byte[] binaryHeader = new byte[BINARY_HEADER_BYTES];
            // interval (big-endian short at byte offset 16 within binary header)
            writeShortBE(binaryHeader, 16, (short) 2000);
            // samples per trace (big-endian short at byte offset 20)
            writeShortBE(binaryHeader, 20, (short) samplesPerTrace);
            // format code (big-endian short at byte offset 24)
            writeShortBE(binaryHeader, 24, (short) FORMAT_CODE_IEEE);
            out.write(binaryHeader);

            // --- Traces ---
            float[] samples = buildSineWave(samplesPerTrace);
            for (int t = 0; t < traceCount; t++) {
                // Trace header (240 bytes) — all zeros (valid minimal header)
                out.write(new byte[TRACE_HEADER_BYTES]);

                // Samples as IEEE big-endian float32
                for (float sample : samples) {
                    int bits = Float.floatToIntBits(sample);
                    out.writeInt(bits);  // DataOutputStream.writeInt is big-endian
                }
            }
        }
    }

    /** Builds a simple sine-wave sample array to simulate realistic seismic amplitudes. */
    private static float[] buildSineWave(int length) {
        float[] s = new float[length];
        for (int i = 0; i < length; i++) {
            s[i] = (float) (1000.0 * Math.sin(2.0 * Math.PI * i / length));
        }
        return s;
    }

    /** Writes a big-endian short into a byte array at the given offset. */
    private static void writeShortBE(byte[] buf, int offset, short value) {
        buf[offset]     = (byte) ((value >> 8) & 0xFF);
        buf[offset + 1] = (byte) (value & 0xFF);
    }
}

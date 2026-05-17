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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the SDC decode pipeline.
 *
 * <p>The fixture is built in-memory at setup time (100 traces × 125 samples
 * each in IEEE float32 format code 5). The .sdc file is produced once during
 * {@link #setUp()} and reused across all measurement iterations to isolate
 * the decode path from the encode overhead.
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
public class SdcDecodeBenchmark {

    /** Number of traces in the synthetic fixture. */
    private static final int TRACE_COUNT = 100;

    /** Number of float32 samples per trace. */
    private static final int SAMPLES_PER_TRACE = 125;

    private Path inputSdcPath;
    private Path outputSegyPath;
    private TracePredictor predictor;

    /**
     * Prepares the .sdc fixture once per trial by running the full encode pipeline.
     * The decode benchmark measures only the decompress step.
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        // Create synthetic SEG-Y input
        Path tmpSegy = Files.createTempFile("sdc-bench-decode-src-", ".segy");
        SdcEncodeBenchmark.writeSyntheticSegy(tmpSegy, TRACE_COUNT, SAMPLES_PER_TRACE);

        // Produce the .sdc fixture via encode
        inputSdcPath = Files.createTempFile("sdc-bench-decode-in-", ".sdc");
        CompressionProfile profile = CompressionProfile.balanced();
        predictor = TracePredictor.identity();

        SegyCompression.compressSegyToSdc(
                tmpSegy,
                inputSdcPath,
                profile,
                predictor,
                UUID.randomUUID());

        Files.deleteIfExists(tmpSegy);

        outputSegyPath = Files.createTempFile("sdc-bench-decode-out-", ".segy");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        Files.deleteIfExists(inputSdcPath);
        Files.deleteIfExists(outputSegyPath);
    }

    /**
     * Measures throughput of the full decode pipeline:
     * SdcContainerV1 read → INFLATE → delta decode → SEG-Y write.
     */
    @Benchmark
    public void decodeFullPipeline() throws IOException {
        SegyCompression.decompressSdcToSegy(
                inputSdcPath,
                outputSegyPath,
                predictor);
    }
}

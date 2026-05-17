package com.sdc.bench;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Entry point for running the JMH benchmark suite programmatically.
 *
 * <p>Results are written to {@code target/jmh-results/latest.json} using
 * {@link ResultFormatType#JSON} so that CI pipelines can assert on
 * {@code primaryMetric.score} and {@code primaryMetric.scoreUnit}.
 *
 * <p>Invoked by {@code maven-exec-plugin} during the {@code integration-test}
 * phase (see {@code sdc-bench/pom.xml}).
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {}

    public static void main(String[] args) throws RunnerException, IOException {
        String resultsDir  = "target/jmh-results";
        String resultsFile = resultsDir + "/latest.json";

        Files.createDirectories(Paths.get(resultsDir));

        Options opts = new OptionsBuilder()
                .include(SdcEncodeBenchmark.class.getSimpleName())
                .include(SdcDecodeBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result(resultsFile)
                .shouldFailOnError(true)
                .build();

        new Runner(opts).run();
    }
}

package com.sdc.cli;

import com.sdc.bench.BenchmarkReporter;

import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

/**
 * {@code sdc benchmark} — reads the latest JMH results file and writes a
 * structured JSON performance report to the configured output path.
 *
 * <p>This command does <em>not</em> re-execute the JMH harness (which would be
 * prohibitively slow in CI). Instead it delegates to
 * {@link BenchmarkReporter#jsonReport()} to transform the existing
 * {@code latest.json} produced by the sdc-bench JMH run into the canonical
 * structured report format.
 *
 * <h3>Usage</h3>
 * <pre>
 *   sdc benchmark [--output &lt;report.json&gt;] [--results-file &lt;path&gt;]
 * </pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>0 — report written successfully</li>
 *   <li>1 — results file not found, unreadable, malformed, or I/O error on output</li>
 * </ul>
 *
 * <p>Implemented as part of TASK-026.
 */
@Command(
        name = "benchmark",
        mixinStandardHelpOptions = true,
        description = "Generate a JSON performance report from the latest JMH results.",
        usageHelpAutoWidth = true
)
public class BenchmarkCommand implements Callable<Integer> {

    /** Default path for the JMH results file produced by sdc-bench. */
    static final String DEFAULT_RESULTS_FILE =
            "sdc-bench/target/jmh-results/latest.json";

    /** Default output path for the generated JSON report. */
    static final String DEFAULT_OUTPUT = "jmh-results.json";

    @Option(
            names = {"--output"},
            paramLabel = "<report.json>",
            description = "Output path for the JSON performance report "
                    + "(default: " + DEFAULT_OUTPUT + ").",
            defaultValue = DEFAULT_OUTPUT
    )
    private Path outputReport;

    @Option(
            names = {"--results-file"},
            paramLabel = "<path>",
            description = "Path to the JMH JSON results file to read "
                    + "(default: " + DEFAULT_RESULTS_FILE + ").",
            defaultValue = DEFAULT_RESULTS_FILE
    )
    private Path resultsFile;

    @Override
    public Integer call() {
        if (!Files.exists(resultsFile)) {
            System.err.printf(
                    "[sdc benchmark] JMH results file not found: %s%n"
                    + "  Run 'mvn verify -pl sdc-bench' first to generate it.%n",
                    resultsFile.toAbsolutePath());
            return ExitCode.SOFTWARE;
        }
        if (!Files.isReadable(resultsFile)) {
            System.err.printf(
                    "[sdc benchmark] JMH results file is not readable: %s%n",
                    resultsFile.toAbsolutePath());
            return ExitCode.SOFTWARE;
        }

        final BenchmarkReporter reporter;
        try {
            reporter = new BenchmarkReporter(resultsFile);
        } catch (IllegalStateException ex) {
            System.err.printf(
                    "[sdc benchmark] Malformed JMH results file: %s%n  %s%n",
                    resultsFile.toAbsolutePath(), ex.getMessage());
            return ExitCode.SOFTWARE;
        } catch (IOException ex) {
            System.err.printf(
                    "[sdc benchmark] Failed to read JMH results file: %s%n  %s%n",
                    resultsFile.toAbsolutePath(), ex.getMessage());
            return ExitCode.SOFTWARE;
        }

        final String json;
        try {
            json = reporter.jsonReport();
        } catch (IOException ex) {
            System.err.printf(
                    "[sdc benchmark] Failed to serialise report to JSON: %s%n",
                    ex.getMessage());
            return ExitCode.SOFTWARE;
        }

        try {
            Files.writeString(outputReport, json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ex) {
            System.err.printf(
                    "[sdc benchmark] Failed to write report to '%s': %s%n",
                    outputReport.toAbsolutePath(), ex.getMessage());
            return ExitCode.SOFTWARE;
        }

        System.out.printf(
                "[sdc benchmark] Report written to: %s%n"
                + "  Encode throughput : %.2f MB/s%n"
                + "  Decode throughput : %.2f MB/s%n"
                + "  Speedup vs. prior : %.1fx%n",
                outputReport.toAbsolutePath(),
                reporter.getEncodeThroughputMbS(),
                reporter.getDecodeThroughputMbS(),
                reporter.getSpeedupVsPriorJavaBaseline());

        return ExitCode.OK;
    }
}

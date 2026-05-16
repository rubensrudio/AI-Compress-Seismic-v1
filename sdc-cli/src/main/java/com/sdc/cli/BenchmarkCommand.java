package com.sdc.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * Stub for the {@code sdc benchmark} subcommand.
 *
 * <p>Full implementation is tracked in TASK-026. This stub satisfies the
 * structural requirement that Main declares a working subcommand hierarchy
 * and that {@code --help} lists all five subcommands (TASK-021 criterion).
 *
 * <p>When invoked, exits with a not-implemented notice.
 */
@Command(
        name = "benchmark",
        mixinStandardHelpOptions = true,
        description = "Run the JMH benchmark harness and produce a performance report.",
        usageHelpAutoWidth = true
)
public class BenchmarkCommand implements Runnable {

    @Parameters(index = "0", paramLabel = "<dataset_path>", description = "Path to the SEG-Y dataset directory for benchmarking.")
    private Path dataset;

    @Option(
            names = {"--output"},
            paramLabel = "<report.json>",
            description = "Output path for the JMH results JSON (default: ./jmh-results.json).",
            defaultValue = "jmh-results.json"
    )
    private Path outputReport;

    @Override
    public void run() {
        // TODO (TASK-026): invoke sdc-bench JMH harness, write JSON report to --output.
        System.err.println("[sdc] Command not yet implemented. This feature will be available in a future release.");
        System.exit(1);
    }
}

package com.sdc.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * Stub for the {@code sdc compress} subcommand.
 *
 * <p>Full implementation is tracked in TASK-022. This stub satisfies the
 * structural requirement that Main declares a working subcommand hierarchy
 * and that {@code --help} lists all five subcommands (TASK-021 criterion).
 *
 * <p>When invoked, prints a notice that the subcommand is not yet implemented
 * and exits with code 1.
 */
@Command(
        name = "compress",
        mixinStandardHelpOptions = true,
        description = "Compress a SEG-Y Rev1 file to .sdc format.",
        usageHelpAutoWidth = true
)
public class CompressCommand implements Runnable {

    @Parameters(index = "0", paramLabel = "<input.segy>", description = "Source SEG-Y Rev1 file.")
    private Path input;

    @Parameters(index = "1", paramLabel = "<output.sdc>", description = "Destination compressed .sdc file.")
    private Path output;

    @Option(
            names = {"--profile"},
            paramLabel = "PROFILE",
            description = "Compression profile: HIGH_QUALITY, BALANCED (default), HIGH_COMPRESSION.",
            defaultValue = "BALANCED"
    )
    private String profile;

    @Override
    public void run() {
        // TODO (TASK-022): invoke SegyValidator, SegyCompression.compress(), report progress/ratio.
        System.err.println("[sdc] Command not yet implemented. This feature will be available in a future release.");
        System.exit(1);
    }
}

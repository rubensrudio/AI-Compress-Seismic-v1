package com.sdc.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

/**
 * Stub for the {@code sdc inspect} subcommand.
 *
 * <p>Full implementation is tracked in TASK-025. This stub satisfies the
 * structural requirement that Main declares a working subcommand hierarchy
 * and that {@code --help} lists all five subcommands (TASK-021 criterion).
 *
 * <p>When invoked, exits with a not-implemented notice.
 */
@Command(
        name = "inspect",
        mixinStandardHelpOptions = true,
        description = "Display SEG-Y Rev1 file metadata (headers, trace count, sample range).",
        usageHelpAutoWidth = true
)
public class InspectCommand implements Runnable {

    @Parameters(index = "0", paramLabel = "<file.segy>", description = "SEG-Y Rev1 file to inspect.")
    private Path file;

    @Override
    public void run() {
        // TODO (TASK-025): use SegyIO to extract metadata and display as a formatted table.
        System.err.println("[sdc inspect] Not yet implemented — tracked in TASK-025.");
        throw new picocli.CommandLine.ParameterException(
                new picocli.CommandLine(this),
                "inspect subcommand not yet implemented (TASK-025)"
        );
    }
}

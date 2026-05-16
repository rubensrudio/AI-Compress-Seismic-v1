package com.sdc.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

/**
 * Stub for the {@code sdc validate} subcommand.
 *
 * <p>Full implementation is tracked in TASK-024. This stub satisfies the
 * structural requirement that Main declares a working subcommand hierarchy
 * and that {@code --help} lists all five subcommands (TASK-021 criterion).
 *
 * <p>When invoked, exits with a not-implemented notice.
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validate SEG-Y Rev1 conformance of a file.",
        usageHelpAutoWidth = true
)
public class ValidateCommand implements Runnable {

    @Parameters(index = "0", paramLabel = "<file.segy>", description = "SEG-Y Rev1 file to validate.")
    private Path file;

    @Override
    public void run() {
        // TODO (TASK-024): invoke SegyValidator, report errors with byte offset, set exit code.
        System.err.println("[sdc] Command not yet implemented. This feature will be available in a future release.");
        System.exit(1);
    }
}

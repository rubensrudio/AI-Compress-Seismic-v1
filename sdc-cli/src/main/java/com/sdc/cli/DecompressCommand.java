package com.sdc.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * Stub for the {@code sdc decompress} subcommand.
 *
 * <p>Full implementation is tracked in TASK-023. This stub satisfies the
 * structural requirement that Main declares a working subcommand hierarchy
 * and that {@code --help} lists all five subcommands (TASK-021 criterion).
 *
 * <p>When invoked, exits with a not-implemented notice.
 */
@Command(
        name = "decompress",
        mixinStandardHelpOptions = true,
        description = "Decompress a .sdc file back to SEG-Y Rev1 format.",
        usageHelpAutoWidth = true
)
public class DecompressCommand implements Runnable {

    @Parameters(index = "0", paramLabel = "<input.sdc>", description = "Source compressed .sdc file.")
    private Path input;

    @Parameters(index = "1", paramLabel = "<output.segy>", description = "Destination SEG-Y Rev1 file.")
    private Path output;

    @Option(
            names = {"--template"},
            paramLabel = "<original.segy>",
            description = "Optional original SEG-Y for header recovery (compatibility with headerless .sdc files)."
    )
    private Path template;

    @Override
    public void run() {
        // TODO (TASK-023): validate .sdc magic, invoke SegyCompression.decompress(), report restored size.
        System.err.println("[sdc decompress] Not yet implemented — tracked in TASK-023.");
        throw new picocli.CommandLine.ParameterException(
                new picocli.CommandLine(this),
                "decompress subcommand not yet implemented (TASK-023)"
        );
    }
}

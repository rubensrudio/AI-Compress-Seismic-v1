package com.sdc.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point for the SDC CLI tool.
 *
 * <p>Invocation examples:
 * <pre>
 *   sdc --help
 *   sdc compress input.segy output.sdc
 *   sdc decompress input.sdc output.segy
 *   sdc validate file.segy
 *   sdc benchmark /path/to/dataset
 *   sdc inspect file.segy
 * </pre>
 *
 * <p>This class is intentionally thin: it declares the top-level {@code @Command}
 * with all five subcommands and delegates execution to them. Business logic lives
 * exclusively in the respective {@code *Command} classes.
 *
 * <p>sdc-ai is NOT wired as a runtime dependency here. The CLI operates with
 * {@link com.sdc.core.IdentityTracePredictor} until a lightweight AI shim that
 * avoids pulling in TensorFlow Java native binaries is available.
 */
@Command(
        name = "sdc",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = {
            "AI-Compress Seismic v1 — Seismic Data Compression CLI",
            "",
            "Compresses and decompresses SEG-Y Rev1 files using a",
            "delta-encoding + entropy-coding pipeline."
        },
        subcommands = {
            CompressCommand.class,
            DecompressCommand.class,
            ValidateCommand.class,
            BenchmarkCommand.class,
            InspectCommand.class,
            CommandLine.HelpCommand.class
        }
)
public class Main {

    /**
     * Application entry point.
     *
     * <p>Returns a Picocli exit code and propagates it to the OS via
     * {@link System#exit(int)} so that the CLI is usable in shell scripts.
     *
     * @param args command-line arguments forwarded by the OS
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }
}

package com.sdc.cli;

import com.sdc.core.SegyValidationException;
import com.sdc.core.SegyValidator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Subcomando {@code sdc validate} - valida a conformidade SEG-Y Rev1 de um arquivo.
 *
 * Fluxo:
 *   1. Verifica existencia do arquivo; falha com exit 1 se nao existir.
 *   2. Chama SegyValidator.validate(path).
 *   3. Em sucesso: stdout "OK - arquivo SEG-Y Rev1 valido"; exit 0.
 *   4. Em falha (SegyValidationException): stderr com mensagem + byte offset; exit 1.
 *   5. Em erro I/O: stderr com causa; exit 1.
 *
 * Implementa Callable<Integer> para comunicar exit code sem System.exit(),
 * permitindo uso seguro em testes unitarios.
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Validate SEG-Y Rev1 structural conformance of a file.",
        usageHelpAutoWidth = true
)
public class ValidateCommand implements Callable<Integer> {

    static final int EXIT_VALID   = 0;
    static final int EXIT_INVALID = 1;

    @Parameters(index = "0", paramLabel = "<file.segy>", description = "SEG-Y Rev1 file to validate.")
    private Path file;

    @Override
    public Integer call() {
        if (!Files.exists(file)) {
            System.err.println("[sdc validate] Erro: arquivo nao encontrado: " + file);
            return EXIT_INVALID;
        }

        if (!Files.isReadable(file)) {
            System.err.println("[sdc validate] Erro: sem permissao de leitura: " + file);
            return EXIT_INVALID;
        }

        try {
            SegyValidator.validate(file);

        } catch (SegyValidationException e) {
            System.err.println("[sdc validate] INVALIDO: " + e.getMessage());
            return EXIT_INVALID;

        } catch (IOException e) {
            System.err.println("[sdc validate] Erro de I/O ao ler '" + file + "': " + e.getMessage());
            return EXIT_INVALID;
        }

        System.out.println("OK - arquivo SEG-Y Rev1 valido: " + file);
        return EXIT_VALID;
    }
}

package com.sdc.core;

/**
 * Exceção lançada quando um arquivo SEG-Y Rev1 falha na validação de conformidade.
 *
 * <p>Sempre carrega o {@code byteOffset} do primeiro byte problemático detectado,
 * permitindo que ferramentas de diagnóstico (CLI, REST) reportem a localização
 * exata da corrupção sem re-leitura do arquivo.</p>
 *
 * <p>Classe unchecked para simplificar uso em lambdas e pipelines reativos.
 * Callers que precisam tratar falhas de validação devem capturar
 * {@code SegyValidationException} explicitamente.</p>
 */
public final class SegyValidationException extends RuntimeException {

    /** Offset do primeiro byte problemático (0-based), ou -1 se não aplicável. */
    private final long byteOffset;

    /**
     * Cria uma exceção com mensagem e offset.
     *
     * @param message   descrição do problema de conformidade
     * @param byteOffset offset 0-based do primeiro byte problemático
     */
    public SegyValidationException(String message, long byteOffset) {
        super(message + " [byte offset: " + byteOffset + "]");
        this.byteOffset = byteOffset;
    }

    /**
     * Cria uma exceção com mensagem, offset e causa raiz.
     *
     * @param message    descrição do problema de conformidade
     * @param byteOffset offset 0-based do primeiro byte problemático
     * @param cause      causa raiz
     */
    public SegyValidationException(String message, long byteOffset, Throwable cause) {
        super(message + " [byte offset: " + byteOffset + "]", cause);
        this.byteOffset = byteOffset;
    }

    /**
     * Retorna o offset (0-based) do primeiro byte problemático detectado.
     * Retorna {@code -1} quando o offset não é aplicável (ex: arquivo vazio).
     *
     * @return byte offset do problema
     */
    public long getByteOffset() {
        return byteOffset;
    }
}

package com.sdc.core;

/**
 * Estratégia de predição de resíduos aplicada no pipeline de codec entre
 * o delta encoding e a quantização linear.
 *
 * <p>No encode: {@link #encode(float[])} recebe os deltas normalizados e
 * retorna os resíduos que serão quantizados e comprimidos com DEFLATE.
 * <p>No decode: {@link #decode(float[])} recebe os resíduos dequantizados
 * e reconstrói os deltas normalizados originais.
 *
 * <p>A implementação canônica de produção é {@code AePredictor} (módulo
 * {@code sdc-ai}) que usa um autoencoder TensorFlow Java. A implementação
 * neutra {@link #identity()} é usada em modo sem IA e em testes unitários
 * do módulo {@code sdc-core} para preservar a retrocompatibilidade com
 * todas as assinaturas existentes.
 *
 * <p><b>Contrato de round-trip:</b> para qualquer implementação correta,
 * {@code decode(encode(x))} deve retornar um array igual a {@code x} dentro
 * da tolerância numérica definida pela implementação (identidade exata para
 * {@link #identity()}).
 */
public interface TracePredictor {

    /**
     * Transforma os deltas normalizados em resíduos para compressão.
     *
     * @param deltas array de deltas normalizados; não deve ser nulo nem vazio
     * @return array de resíduos com mesmo comprimento que {@code deltas}
     */
    float[] encode(float[] deltas);

    /**
     * Reconstrói os deltas normalizados a partir dos resíduos dequantizados.
     *
     * @param residuals array de resíduos dequantizados; não deve ser nulo nem vazio
     * @return array de deltas reconstruídos com mesmo comprimento que {@code residuals}
     */
    float[] decode(float[] residuals);

    /**
     * Retorna uma implementação de identidade: {@code encode} e {@code decode}
     * devolvem uma cópia defensiva do array de entrada sem qualquer transformação.
     *
     * <p>Uso: modo sem IA, testes unitários de {@code sdc-core} e fallback quando
     * o artefato de modelo TensorFlow não está disponível.
     *
     * @return predictor de identidade, thread-safe e sem estado
     */
    static TracePredictor identity() {
        return IdentityTracePredictor.INSTANCE;
    }
}

package com.sdc.ai;

import com.sdc.core.TracePredictor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.Result;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.SessionFunction;
import org.tensorflow.Tensor;
import org.tensorflow.ndarray.Shape;
import org.tensorflow.types.TFloat32;

import java.util.Map;
import java.util.Objects;

/**
 * Implementação de {@link TracePredictor} baseada em autoencoder TensorFlow Java.
 *
 * <p>Carrega um SavedModel do diretório fornecido pelo {@link ModelRegistry} via
 * {@link SavedModelBundle#load(String, String...)} e executa inferência in-process
 * na JVM, sem chamada a serviço Python externo.
 *
 * <h3>Convenção de signatures do modelo de produção</h3>
 * <ul>
 *   <li><b>Encode signature key:</b> {@value #DEFAULT_ENCODE_SIGNATURE} com
 *       input lógico {@code "encode_input"} (shape {@code [1, samplesPerTrace]})
 *       e output {@code "encode_output"} (shape {@code [1, bottleneckSize]})</li>
 *   <li><b>Decode signature key:</b> {@value #DEFAULT_DECODE_SIGNATURE} com
 *       input lógico {@code "decode_input"} (shape {@code [1, bottleneckSize]})
 *       e output {@code "decode_output"} (shape {@code [1, samplesPerTrace]})</li>
 * </ul>
 *
 * <p>Para o stub de identidade (TASK-010), use o construtor configurável
 * {@link #AePredictor(ModelRegistry, String, String, String, String, String, String)}
 * especificando os nomes de signature e de tensor usados pelo stub.
 *
 * <p><b>Thread safety:</b> instâncias desta classe <em>não</em> são thread-safe.
 * Não compartilhe uma única instância entre threads sem sincronização externa.
 *
 * @see ModelRegistry
 * @see TracePredictor
 */
public final class AePredictor implements TracePredictor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AePredictor.class);

    /**
     * Chave de signature padrão do encoder no modelo de produção.
     * Deve corresponder à chave exportada pelo script de treinamento Python/Keras.
     */
    public static final String DEFAULT_ENCODE_SIGNATURE  = "encode";
    /**
     * Nome lógico do tensor de entrada do encoder (conforme a signature exportada).
     */
    public static final String DEFAULT_ENCODE_INPUT_NAME = "encode_input";
    /**
     * Nome lógico do tensor de saída do encoder.
     */
    public static final String DEFAULT_ENCODE_OUTPUT_NAME = "encode_output";

    /**
     * Chave de signature padrão do decoder no modelo de produção.
     */
    public static final String DEFAULT_DECODE_SIGNATURE  = "decode";
    /**
     * Nome lógico do tensor de entrada do decoder.
     */
    public static final String DEFAULT_DECODE_INPUT_NAME = "decode_input";
    /**
     * Nome lógico do tensor de saída do decoder.
     */
    public static final String DEFAULT_DECODE_OUTPUT_NAME = "decode_output";

    private final SavedModelBundle bundle;
    private final SessionFunction encodeFunction;
    private final SessionFunction decodeFunction;
    private final String encodeInputName;
    private final String encodeOutputName;
    private final String decodeInputName;
    private final String decodeOutputName;

    // -------------------------------------------------------------------------
    // Construtores
    // -------------------------------------------------------------------------

    /**
     * Construtor padrão — usa as signatures e nomes de tensor canônicos do modelo
     * de produção ({@value #DEFAULT_ENCODE_SIGNATURE} / {@value #DEFAULT_DECODE_SIGNATURE}).
     *
     * @param registry referência ao artefato de modelo a carregar
     * @throws ModelNotFoundException se o SavedModel não puder ser carregado ou
     *                                se as signatures padrão não estiverem presentes
     */
    public AePredictor(ModelRegistry registry) throws ModelNotFoundException {
        this(registry,
             DEFAULT_ENCODE_SIGNATURE,  DEFAULT_ENCODE_INPUT_NAME,  DEFAULT_ENCODE_OUTPUT_NAME,
             DEFAULT_DECODE_SIGNATURE,  DEFAULT_DECODE_INPUT_NAME,  DEFAULT_DECODE_OUTPUT_NAME);
    }

    /**
     * Construtor configurável — permite especificar as chaves de signature e os
     * nomes de tensor lógicos para encode e decode.
     *
     * <p>Use este construtor quando o SavedModel exportado usar chaves de signature
     * diferentes das padrões (ex: stub de identidade gerado para testes, ou modelo
     * de produção com esquema de nomes diferente).
     *
     * @param registry           referência ao artefato de modelo a carregar
     * @param encodeSignatureKey chave da signature do encoder (ex: {@code "encode"})
     * @param encodeInputName    nome do tensor de entrada do encoder na signature
     * @param encodeOutputName   nome do tensor de saída do encoder na signature
     * @param decodeSignatureKey chave da signature do decoder (ex: {@code "decode"})
     * @param decodeInputName    nome do tensor de entrada do decoder na signature
     * @param decodeOutputName   nome do tensor de saída do decoder na signature
     * @throws ModelNotFoundException se o SavedModel não puder ser carregado ou
     *                                se alguma das signatures especificadas não existir
     */
    public AePredictor(ModelRegistry registry,
                       String encodeSignatureKey,
                       String encodeInputName,
                       String encodeOutputName,
                       String decodeSignatureKey,
                       String decodeInputName,
                       String decodeOutputName) throws ModelNotFoundException {
        Objects.requireNonNull(registry,           "registry must not be null");
        Objects.requireNonNull(encodeSignatureKey, "encodeSignatureKey must not be null");
        Objects.requireNonNull(encodeInputName,    "encodeInputName must not be null");
        Objects.requireNonNull(encodeOutputName,   "encodeOutputName must not be null");
        Objects.requireNonNull(decodeSignatureKey, "decodeSignatureKey must not be null");
        Objects.requireNonNull(decodeInputName,    "decodeInputName must not be null");
        Objects.requireNonNull(decodeOutputName,   "decodeOutputName must not be null");

        try {
            log.info("Loading SavedModel from: {} [uuid={}]",
                     registry.modelPath(), registry.modelUuid());
            this.bundle = SavedModelBundle.load(
                    registry.modelPath().toAbsolutePath().toString(), "serve");
            log.info("SavedModel loaded successfully [uuid={}]", registry.modelUuid());
        } catch (Exception e) {
            throw new ModelNotFoundException(
                    "Failed to load SavedModel from: " + registry.modelPath()
                    + " (uuid=" + registry.modelUuid() + "): " + e.getMessage(), e);
        }

        try {
            this.encodeFunction = bundle.function(encodeSignatureKey);
        } catch (Exception e) {
            bundle.close();
            throw new ModelNotFoundException(
                    "Encode signature '" + encodeSignatureKey + "' not found in SavedModel at: "
                    + registry.modelPath(), e);
        }

        try {
            this.decodeFunction = bundle.function(decodeSignatureKey);
        } catch (Exception e) {
            bundle.close();
            throw new ModelNotFoundException(
                    "Decode signature '" + decodeSignatureKey + "' not found in SavedModel at: "
                    + registry.modelPath(), e);
        }

        this.encodeInputName  = encodeInputName;
        this.encodeOutputName = encodeOutputName;
        this.decodeInputName  = decodeInputName;
        this.decodeOutputName = decodeOutputName;
    }

    // -------------------------------------------------------------------------
    // TracePredictor
    // -------------------------------------------------------------------------

    /**
     * Executa o encoder do autoencoder sobre os deltas fornecidos.
     *
     * <p>O tensor de entrada tem shape {@code [1, deltas.length]}; o tensor de
     * saída tem shape {@code [1, bottleneckSize]} (igual a {@code deltas.length}
     * para o stub de identidade).
     *
     * @param deltas array de deltas normalizados; não deve ser nulo
     * @return array de resíduos produzidos pelo encoder
     */
    @Override
    public float[] encode(float[] deltas) {
        Objects.requireNonNull(deltas, "deltas must not be null");
        return callFunction(encodeFunction, deltas, encodeInputName, encodeOutputName);
    }

    /**
     * Executa o decoder do autoencoder sobre os resíduos fornecidos.
     *
     * <p>O tensor de entrada tem shape {@code [1, residuals.length]}; o tensor de
     * saída tem shape {@code [1, samplesPerTrace]}.
     *
     * @param residuals array de resíduos dequantizados; não deve ser nulo
     * @return array de deltas reconstruídos pelo decoder
     */
    @Override
    public float[] decode(float[] residuals) {
        Objects.requireNonNull(residuals, "residuals must not be null");
        return callFunction(decodeFunction, residuals, decodeInputName, decodeOutputName);
    }

    /**
     * Fecha o {@link SavedModelBundle} e libera os recursos nativos associados.
     *
     * <p>Após chamar {@code close()}, qualquer invocação de {@link #encode} ou
     * {@link #decode} produzirá comportamento indefinido.
     */
    @Override
    public void close() {
        log.debug("Closing AePredictor and releasing SavedModelBundle resources.");
        bundle.close();
    }

    // -------------------------------------------------------------------------
    // Inferência interna
    // -------------------------------------------------------------------------

    /**
     * Executa uma passagem de inferência (encode ou decode) usando a
     * {@link SessionFunction} da signature especificada.
     *
     * <p>Constrói um tensor de entrada com shape {@code [1, input.length]},
     * invoca a função com o nome lógico de entrada e extrai o array de saída
     * pelo nome lógico de saída.
     *
     * @param function      função da signature a invocar
     * @param input         dados de entrada (1-D)
     * @param inputName     nome lógico do tensor de entrada na signature
     * @param outputName    nome lógico do tensor de saída na signature
     * @return resultado da inferência como array de float
     */
    private static float[] callFunction(SessionFunction function,
                                        float[] input,
                                        String inputName,
                                        String outputName) {
        int n = input.length;

        // Constrói tensor [1, n] a partir do array de entrada.
        try (TFloat32 inputTensor = TFloat32.tensorOf(Shape.of(1, n), t -> {
            for (int i = 0; i < n; i++) {
                t.setFloat(input[i], 0, i);
            }
        })) {
            try (Result result = function.call(Map.of(inputName, inputTensor))) {
                // Extrai tensor de saída pelo nome lógico definido na signature.
                Tensor outputTensor = result.get(outputName)
                        .orElseThrow(() -> new IllegalStateException(
                                "Output tensor '" + outputName + "' not found in inference result"));

                try (outputTensor) {
                    TFloat32 out = (TFloat32) outputTensor;
                    // O tamanho de saída pode diferir do input (bottleneck do autoencoder).
                    // shape().get(1) é o equivalente não-deprecated de shape().size(1).
                    int outSize = (int) out.shape().get(1);
                    float[] resultArray = new float[outSize];
                    for (int i = 0; i < outSize; i++) {
                        resultArray[i] = out.getFloat(0, i);
                    }
                    return resultArray;
                }
            }
        }
    }
}

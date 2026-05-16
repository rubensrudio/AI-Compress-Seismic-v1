package com.sdc.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.tensorflow.Graph;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Session;
import org.tensorflow.Signature;
import org.tensorflow.TensorFlow;
import org.tensorflow.ndarray.Shape;
import org.tensorflow.op.Ops;
import org.tensorflow.op.core.Placeholder;
import org.tensorflow.types.TFloat32;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração para {@link AePredictor}.
 *
 * <h3>Estratégia de stub (Abordagem A — geração dinâmica via TF Java)</h3>
 * <p>No {@code @BeforeAll}, usamos a API do TF Java 0.5.0 para construir um
 * SavedModel real de identidade com <b>duas signatures</b>:
 * <ul>
 *   <li>{@value #ENCODE_SIGNATURE} — input {@value #ENCODE_INPUT_NAME},
 *       output {@value #ENCODE_OUTPUT_NAME}</li>
 *   <li>{@value #DECODE_SIGNATURE} — input {@value #DECODE_INPUT_NAME},
 *       output {@value #DECODE_OUTPUT_NAME}</li>
 * </ul>
 * Ambas as signatures executam a operação {@code Identity} (saída = entrada).
 *
 * <p>O stub é gerado em um {@link TempDir} estático e carregado pelo
 * {@link AePredictor} via {@link ModelRegistry}.
 *
 * <h3>Fallback gracioso</h3>
 * <p>Se o TF nativo não estiver disponível (UnsatisfiedLinkError), todos os
 * testes desta classe são pulados via {@link Assumptions#assumeTrue} — sem
 * falha de build, conforme especificado na TASK-010.
 */
@DisplayName("AePredictor — integração com SavedModel stub de identidade")
class AePredictorTest {

    /**
     * Diretório temporário estático compartilhado por toda a classe de teste.
     * O stub SavedModel é gerado aqui no {@code @BeforeAll}.
     */
    @TempDir
    static Path tempStubDir;

    /** Path do diretório do SavedModel stub. Preenchido pelo {@code @BeforeAll}. */
    private static Path stubModelDir;

    // -------------------------------------------------------------------------
    // Constantes de signature do stub — espelham os defaults de AePredictor
    // -------------------------------------------------------------------------

    /** Chave da signature de encode no stub. */
    static final String ENCODE_SIGNATURE   = AePredictor.DEFAULT_ENCODE_SIGNATURE;
    /** Nome lógico do tensor de entrada do encode. */
    static final String ENCODE_INPUT_NAME  = AePredictor.DEFAULT_ENCODE_INPUT_NAME;
    /** Nome lógico do tensor de saída do encode. */
    static final String ENCODE_OUTPUT_NAME = AePredictor.DEFAULT_ENCODE_OUTPUT_NAME;

    /** Chave da signature de decode no stub. */
    static final String DECODE_SIGNATURE   = AePredictor.DEFAULT_DECODE_SIGNATURE;
    /** Nome lógico do tensor de entrada do decode. */
    static final String DECODE_INPUT_NAME  = AePredictor.DEFAULT_DECODE_INPUT_NAME;
    /** Nome lógico do tensor de saída do decode. */
    static final String DECODE_OUTPUT_NAME = AePredictor.DEFAULT_DECODE_OUTPUT_NAME;

    // -------------------------------------------------------------------------
    // Setup / Teardown
    // -------------------------------------------------------------------------

    /**
     * Verifica disponibilidade do TF nativo e gera o SavedModel stub de identidade
     * com duas signatures (encode e decode).
     *
     * <p>Se o TF nativo não estiver acessível, todos os testes desta classe
     * são pulados via {@link Assumptions#assumeTrue}.
     */
    @BeforeAll
    static void generateIdentityStub() throws IOException {
        // Verificar se o TF nativo está disponível antes de qualquer operação
        try {
            String version = TensorFlow.version();
            System.out.println("[AePredictorTest] TF Java native version: " + version);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            Assumptions.assumeTrue(false,
                "TF native library not available — skipping AePredictor tests. "
                + "Cause: " + e.getMessage());
            return;
        }

        // Gerar SavedModel stub de identidade em tempStubDir/identity-stub/
        stubModelDir = tempStubDir.resolve("identity-stub");

        // Construir grafo compartilhado com placeholder + identity.
        // O grafo tem um único placeholder com shape [-1] (vetor dinâmico).
        // Cada signature expõe o mesmo grafo com nomes lógicos diferentes.
        try (Graph graph = new Graph();
             Session session = new Session(graph)) {

            Ops tf = Ops.create(graph);

            // Placeholder float32 de shape dinâmico — aceita vetores de qualquer tamanho.
            // O shape [-1] é equivalente a "qualquer comprimento" no TF Java 0.5.0.
            var encodeIn  = tf.withName(ENCODE_INPUT_NAME)
                              .placeholder(TFloat32.class,
                                           Placeholder.shape(Shape.of(-1)));
            var encodeOut = tf.withName(ENCODE_OUTPUT_NAME)
                              .identity(encodeIn);

            var decodeIn  = tf.withName(DECODE_INPUT_NAME)
                              .placeholder(TFloat32.class,
                                           Placeholder.shape(Shape.of(-1)));
            var decodeOut = tf.withName(DECODE_OUTPUT_NAME)
                              .identity(decodeIn);

            // Signature "encode": encode_input → encode_output
            Signature encodeSignature = Signature.builder()
                    .key(ENCODE_SIGNATURE)
                    .input(ENCODE_INPUT_NAME,   encodeIn)
                    .output(ENCODE_OUTPUT_NAME, encodeOut)
                    .build();

            // Signature "decode": decode_input → decode_output
            Signature decodeSignature = Signature.builder()
                    .key(DECODE_SIGNATURE)
                    .input(DECODE_INPUT_NAME,   decodeIn)
                    .output(DECODE_OUTPUT_NAME, decodeOut)
                    .build();

            // Exportar SavedModel com ambas as signatures na tag "serve"
            SavedModelBundle.exporter(stubModelDir.toAbsolutePath().toString())
                    .withTags("serve")
                    .withSession(session)
                    .withSignatures(encodeSignature, decodeSignature)
                    .export();

            System.out.println("[AePredictorTest] Identity stub exported to: " + stubModelDir);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Cria um {@link AePredictor} configurado com o stub de identidade.
     *
     * <p>O stub usa as chaves de signature e nomes de tensor idênticos aos
     * defaults de {@link AePredictor}, portanto este método pode usar o
     * construtor padrão de 1 argumento.
     */
    private AePredictor createStubPredictor() throws Exception {
        ModelRegistry registry = ModelRegistry.fromPath(stubModelDir);
        // O stub usa os mesmos nomes de signature dos defaults de AePredictor.
        return new AePredictor(registry);
    }

    // -------------------------------------------------------------------------
    // Testes de carregamento
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("construtor: carrega stub de identidade sem lançar exceção")
    void constructor_loadsIdentityStub_withoutException() throws Exception {
        try (AePredictor predictor = createStubPredictor()) {
            assertNotNull(predictor, "AePredictor deve ser construído com sucesso");
        }
    }

    @Test
    @DisplayName("construtor: lança ModelNotFoundException para saved_model.pb malformado")
    void constructor_throwsModelNotFoundException_forMalformedModel(@TempDir Path tempDir)
            throws Exception {
        // Diretório com saved_model.pb inválido (bytes aleatórios)
        Path badDir = tempDir.resolve("bad-model");
        Files.createDirectory(badDir);
        Files.write(badDir.resolve("saved_model.pb"), new byte[]{0x00, 0x01, 0x02});

        ModelRegistry registry = ModelRegistry.fromPath(badDir);

        assertThrows(ModelNotFoundException.class,
                () -> new AePredictor(registry),
                "Deve lançar ModelNotFoundException para SavedModel inválido/malformado");
    }

    // -------------------------------------------------------------------------
    // Testes de inferência — encode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("encode() com stub de identidade retorna array com mesmos valores da entrada")
    void encode_withIdentityStub_returnsInputUnchanged() throws Exception {
        float[] input = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

        try (AePredictor predictor = createStubPredictor()) {
            float[] encoded = predictor.encode(input);

            assertNotNull(encoded, "encode() não deve retornar null");
            assertEquals(input.length, encoded.length,
                    "encode() com stub de identidade deve preservar o comprimento");
            for (int i = 0; i < input.length; i++) {
                assertEquals(input[i], encoded[i], 1e-5f,
                        "encode()[" + i + "]: esperado " + input[i] + ", obtido " + encoded[i]);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Testes de inferência — decode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("decode() com stub de identidade retorna array com mesmos valores da entrada")
    void decode_withIdentityStub_returnsInputUnchanged() throws Exception {
        float[] residuals = {-0.5f, 0.0f, 0.5f, 1.0f, -1.0f};

        try (AePredictor predictor = createStubPredictor()) {
            float[] decoded = predictor.decode(residuals);

            assertNotNull(decoded, "decode() não deve retornar null");
            assertEquals(residuals.length, decoded.length,
                    "decode() com stub de identidade deve preservar o comprimento");
            for (int i = 0; i < residuals.length; i++) {
                assertEquals(residuals[i], decoded[i], 1e-5f,
                        "decode()[" + i + "]: esperado " + residuals[i] + ", obtido " + decoded[i]);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Teste de round-trip — critério central da TASK-010
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("encode() seguido de decode() com stub retorna amostras dentro de epsilon da entrada")
    void encodeDecodePipeline_withIdentityStub_returnsInputWithinEpsilon() throws Exception {
        float[] samples = {0.1f, 0.2f, 0.3f, -0.1f, -0.2f, 0.5f, 1.0f, -1.0f};
        float epsilon = 1e-5f;

        try (AePredictor predictor = createStubPredictor()) {
            float[] encoded = predictor.encode(samples);
            float[] decoded = predictor.decode(encoded);

            assertNotNull(decoded, "Pipeline encode→decode não deve retornar null");
            assertEquals(samples.length, decoded.length,
                    "Comprimento após encode→decode deve ser idêntico ao original");

            for (int i = 0; i < samples.length; i++) {
                assertEquals(samples[i], decoded[i], epsilon,
                        String.format(
                            "samples[%d]: esperado %.6f, obtido %.6f (epsilon=%.1e)",
                            i, samples[i], decoded[i], epsilon));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Testes de validação de entrada
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("encode() lança NullPointerException para entrada nula")
    void encode_nullInput_throwsNullPointerException() throws Exception {
        try (AePredictor predictor = createStubPredictor()) {
            assertThrows(NullPointerException.class,
                    () -> predictor.encode(null),
                    "encode(null) deve lançar NullPointerException");
        }
    }

    @Test
    @DisplayName("decode() lança NullPointerException para entrada nula")
    void decode_nullInput_throwsNullPointerException() throws Exception {
        try (AePredictor predictor = createStubPredictor()) {
            assertThrows(NullPointerException.class,
                    () -> predictor.decode(null),
                    "decode(null) deve lançar NullPointerException");
        }
    }
}

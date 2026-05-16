package com.sdc.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Helpers de alto nível para:
 *  - SEG-Y -> .sdc (compressão via SdcContainerV1)
 *  - .sdc -> SEG-Y (descompressão via SdcContainerV1)
 *
 * <p>Os novos métodos aceitam {@link CompressionProfile} e {@link TracePredictor}
 * como parâmetros de injeção de dependência. Os overloads de retrocompatibilidade
 * delegam para os métodos canônicos com valores default:
 * {@link TracePredictor#identity()} e {@link UUID#randomUUID()}.
 *
 * <p><b>Side-effects removidos (DT-1):</b> as chamadas para
 * {@code SegyDump.dumpFromDataset()} e {@code SegyDump.dumpFromFile()} foram
 * removidas. Eram side-effects não autorizados que geravam arquivos TXT/CSV
 * em disco durante compressão/descompressão — comportamento inadequado para
 * uma biblioteca.
 *
 * <p><b>Thread-safety:</b> os métodos estáticos são seguros para chamada
 * concorrente desde que cada chamada use um {@link TracePredictor} próprio
 * e não compartilhado; a classe em si não possui estado mutável.
 */
public final class SegyCompression {

    private SegyCompression() {}

    // -----------------------------------------------------------------------
    // Inner class CompressionResult
    // -----------------------------------------------------------------------

    public static final class CompressionResult {
        public final Path segyPath;
        public final Path sdcPath;

        public final long segyBytes;      // tamanho total do arquivo SEG-Y
        public final long sdcBytes;       // tamanho total do .sdc
        public final long rawDataBytes;   // apenas dados dos traços (ns * nTraces * 4)

        public final int traceCount;
        public final int samplesPerTrace;

        public final double ratioFile;      // sdcBytes / segyBytes
        public final double ratioData;      // sdcBytes / rawDataBytes
        public final double savingsPercent; // (1 - ratioFile) * 100

        public final double psnrFirstTrace;
        public final double psnrMean;
        public final double psnrMin;
        public final double psnrMax;

        public CompressionResult(Path segyPath,
                                Path sdcPath,
                                long segyBytes,
                                long sdcBytes,
                                long rawDataBytes,
                                int traceCount,
                                int samplesPerTrace,
                                double ratioFile,
                                double ratioData,
                                double savingsPercent,
                                double psnrFirstTrace,
                                double psnrMean,
                                double psnrMin,
                                double psnrMax) {
            this.segyPath = segyPath;
            this.sdcPath = sdcPath;
            this.segyBytes = segyBytes;
            this.sdcBytes = sdcBytes;
            this.rawDataBytes = rawDataBytes;
            this.traceCount = traceCount;
            this.samplesPerTrace = samplesPerTrace;
            this.ratioFile = ratioFile;
            this.ratioData = ratioData;
            this.savingsPercent = savingsPercent;
            this.psnrFirstTrace = psnrFirstTrace;
            this.psnrMean = psnrMean;
            this.psnrMin = psnrMin;
            this.psnrMax = psnrMax;
        }
    }

    // -----------------------------------------------------------------------
    // Métodos canônicos (novos) — aceitam TracePredictor e UUID
    // -----------------------------------------------------------------------

    /**
     * Comprime um arquivo SEG-Y para o formato .sdc v1 usando o predictor e o
     * modelo UUID fornecidos.
     *
     * <p>Internamente:
     * <ol>
     *   <li>Lê o SEG-Y via {@link SegyIO#read(Path)}</li>
     *   <li>Comprime cada traço via {@link TraceBlockCodec} com o {@code predictor}</li>
     *   <li>Constrói {@link SdcContainerV1} com todos os campos preservados</li>
     *   <li>Serializa via {@link SdcContainerV1#write(OutputStream)} para {@code sdcPath}</li>
     * </ol>
     *
     * <p>Nenhum arquivo auxiliar (TXT/CSV) é gerado como side-effect (DT-1 corrigido).
     *
     * @param segyPath  caminho do arquivo SEG-Y de entrada
     * @param sdcPath   caminho do arquivo .sdc de saída
     * @param profile   perfil de compressão (bits efetivos, nível DEFLATE)
     * @param predictor predictor de resíduos AI a usar; use {@link TracePredictor#identity()}
     *                  para modo sem IA
     * @param modelUuid UUID do artefato de modelo AI gravado no container
     * @return métricas de compressão
     * @throws IOException em caso de erro de I/O
     */
    public static CompressionResult compressSegyToSdc(
            Path segyPath,
            Path sdcPath,
            CompressionProfile profile,
            TracePredictor predictor,
            UUID modelUuid) throws IOException {

        SegyIO.SegyDataset dataset = SegyIO.read(segyPath);
        List<TraceBlock> traceBlocks = dataset.traces;

        int traceCount = dataset.traceCount();
        int samplesPerTrace = dataset.samplesPerTrace;
        int sampleFormatCode = dataset.sampleFormatCode;

        long segyBytes = Files.size(segyPath);
        long rawDataBytes = (long) traceCount * samplesPerTrace * 4L;

        // --- Comprimir cada traço com o predictor injetado ---
        List<CompressedTraceBlock> compressedBlocks = new ArrayList<>(traceCount);
        for (TraceBlock tb : traceBlocks) {
            compressedBlocks.add(TraceBlockCodec.compress(tb, profile, predictor));
        }

        // --- Construir trace headers blob (240 bytes por traço em sequência) ---
        byte[] traceHeadersBlob = buildTraceHeadersBlob(dataset.traceHeaders);

        // --- Construir SdcContainerV1 ---
        SdcContainerV1 container = new SdcContainerV1(
                (short) 1,
                modelUuid,
                dataset.textualHeader,
                dataset.binaryHeader,
                traceCount,
                samplesPerTrace,
                sampleFormatCode,
                traceHeadersBlob,
                compressedBlocks
        );

        // --- Serializar para sdcPath ---
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(sdcPath))) {
            container.write(out);
        }

        long sdcBytes = Files.size(sdcPath);
        double ratioFile = (double) sdcBytes / (double) segyBytes;
        double ratioData = (double) sdcBytes / (double) rawDataBytes;
        double savingsPercent = (1.0 - ratioFile) * 100.0;

        // --- Calcular PSNR via round-trip interno (sem gravar em disco) ---
        double psnrFirst = Double.NaN;
        double psnrMean  = Double.NaN;
        double psnrMin   = Double.NaN;
        double psnrMax   = Double.NaN;

        if (!compressedBlocks.isEmpty()) {
            double sum = 0.0;
            int n = compressedBlocks.size();
            for (int i = 0; i < n; i++) {
                float[] orig = traceBlocks.get(i).samples();
                float[] dec  = TraceBlockCodec.decompress(compressedBlocks.get(i), predictor).samples();
                double psnr  = LinearQuantizer.psnr(orig, dec);
                if (i == 0) {
                    psnrFirst = psnr;
                    psnrMin   = psnr;
                    psnrMax   = psnr;
                } else {
                    if (psnr < psnrMin) psnrMin = psnr;
                    if (psnr > psnrMax) psnrMax = psnr;
                }
                sum += psnr;
            }
            psnrMean = sum / n;
        }

        return new CompressionResult(
                segyPath,
                sdcPath,
                segyBytes,
                sdcBytes,
                rawDataBytes,
                traceCount,
                samplesPerTrace,
                ratioFile,
                ratioData,
                savingsPercent,
                psnrFirst,
                psnrMean,
                psnrMin,
                psnrMax
        );
    }

    /**
     * Descomprime um arquivo .sdc v1 para SEG-Y usando o predictor fornecido.
     *
     * <p>Internamente:
     * <ol>
     *   <li>Lê via {@link SdcContainerV1#read(java.io.InputStream)}</li>
     *   <li>Descomprime cada bloco via {@link TraceBlockCodec#decompress} com o {@code predictor}</li>
     *   <li>Reconstrói o SEG-Y a partir dos campos do container (headers preservados + samples)</li>
     * </ol>
     *
     * <p>Nenhum arquivo auxiliar (TXT/CSV) é gerado como side-effect (DT-1 corrigido).
     *
     * @param sdcPath     caminho do arquivo .sdc de entrada
     * @param outSegyPath caminho do SEG-Y de saída
     * @param predictor   predictor de resíduos AI; deve ser o mesmo usado na compressão
     * @throws IOException em caso de erro de I/O ou formato inválido
     */
    public static void decompressSdcToSegy(
            Path sdcPath,
            Path outSegyPath,
            TracePredictor predictor) throws IOException {

        SdcContainerV1 container;
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(sdcPath))) {
            container = SdcContainerV1.read(in);
        }

        int traceCount      = container.traceCount();
        int samplesPerTrace = container.samplesPerTrace();
        int formatCode      = container.sampleFormatCode();

        // --- Descomprimir cada bloco ---
        List<CompressedTraceBlock> compressedBlocks = container.compressedBlocks();
        List<TraceBlock> traces = new ArrayList<>(traceCount);
        for (CompressedTraceBlock cb : compressedBlocks) {
            traces.add(TraceBlockCodec.decompress(cb, predictor));
        }

        // --- Reconstruir trace headers a partir do blob ---
        byte[] blob = container.traceHeadersBlob();
        List<byte[]> traceHeaders = splitTraceHeadersBlob(blob, traceCount);

        // --- Montar SegyDataset sintético para reutilizar SegyIO.write() ---
        SegyIO.SegyDataset reconstructed = new SegyIO.SegyDataset(
                container.segyTextualHeader(),
                container.segyBinaryHeader(),
                traceHeaders,
                traces,
                samplesPerTrace,
                formatCode,
                buildDummyTraceMeta(traceCount),
                buildDummyTraceGrid()
        );

        SegyIO.write(outSegyPath, reconstructed, traces);
    }

    // -----------------------------------------------------------------------
    // Overloads de retrocompatibilidade
    // -----------------------------------------------------------------------

    /**
     * Overload de retrocompatibilidade: usa {@link TracePredictor#identity()} e
     * um {@link UUID#randomUUID()}.
     */
    public static CompressionResult compressSegyToSdc(
            Path segyPath,
            Path sdcPath,
            CompressionProfile profile) throws IOException {
        return compressSegyToSdc(segyPath, sdcPath, profile,
                TracePredictor.identity(), UUID.randomUUID());
    }

    /**
     * Overload de retrocompatibilidade: usa {@link CompressionProfile#defaultHighQuality()},
     * {@link TracePredictor#identity()} e um {@link UUID#randomUUID()}.
     */
    public static CompressionResult compressSegyToSdc(Path segyPath, Path sdcPath) throws IOException {
        return compressSegyToSdc(segyPath, sdcPath, CompressionProfile.defaultHighQuality());
    }

    /**
     * Overload de retrocompatibilidade para descompressão que usa template SEG-Y externo.
     *
     * <p><b>Depreciado:</b> na v1, os headers estão embutidos no container .sdc; use
     * {@link #decompressSdcToSegy(Path, Path, TracePredictor)} sem template. Este overload
     * é mantido para compatibilidade com código existente que já usava o template.
     *
     * @deprecated use {@link #decompressSdcToSegy(Path, Path, TracePredictor)}
     */
    @Deprecated
    public static void decompressSdcToSegy(
            Path sdcPath,
            Path templateSegyPath,
            Path outSegyPath) throws IOException {
        // Ignora templateSegyPath: na v1 os headers estão no container .sdc
        decompressSdcToSegy(sdcPath, outSegyPath, TracePredictor.identity());
    }

    // -----------------------------------------------------------------------
    // Métodos 3D (inalterados — fora do escopo de TASK-007)
    // -----------------------------------------------------------------------

    public static CompressionResult compressSegyToSdc3D(Path segyPath,
                                                    Path sdcPath,
                                                    CompressionProfile profile,
                                                    int blockInline,
                                                    int blockXline,
                                                    int blockTime) throws IOException {
        SegyIO.SegyDataset dataset = SegyIO.read(segyPath);

        int traceCount = dataset.traceCount();
        int samplesPerTrace = dataset.samplesPerTrace;

        long segyBytes = Files.size(segyPath);
        long rawDataBytes = (long) traceCount * samplesPerTrace * 4L;

        Sdc3DFileWriter.write3D(sdcPath, dataset, profile, blockInline, blockXline, blockTime);

        long sdcBytes = Files.size(sdcPath);

        double ratioFile = (double) sdcBytes / (double) segyBytes;
        double ratioData = (double) sdcBytes / (double) rawDataBytes;
        double savingsPercent = (1.0 - ratioFile) * 100.0;

        double psnrFirst = Double.NaN;
        double psnrMean  = Double.NaN;
        double psnrMin   = Double.NaN;
        double psnrMax   = Double.NaN;

        if (traceCount > 0) {
            Sdc3DFileReader.Sdc3DVolume volume = Sdc3DFileReader.read3D(sdcPath, profile);
            java.util.List<TraceBlock> reconTraces =
                    reconstructTracesFromVolume(dataset, volume);

            int n = Math.min(traceCount, reconTraces.size());
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                float[] orig = dataset.traces.get(i).samples();
                float[] rec  = reconTraces.get(i).samples();
                double psnr  = LinearQuantizer.psnr(orig, rec);

                if (i == 0) {
                    psnrFirst = psnr;
                    psnrMin   = psnr;
                    psnrMax   = psnr;
                } else {
                    if (psnr < psnrMin) psnrMin = psnr;
                    if (psnr > psnrMax) psnrMax = psnr;
                }
                sum += psnr;
            }
            if (n > 0) {
                psnrMean = sum / n;
            }
        }

        return new CompressionResult(
                segyPath,
                sdcPath,
                segyBytes,
                sdcBytes,
                rawDataBytes,
                traceCount,
                samplesPerTrace,
                ratioFile,
                ratioData,
                savingsPercent,
                psnrFirst,
                psnrMean,
                psnrMin,
                psnrMax
        );
    }

    public static void decompressSdcToSegy3D(Path sdcPath,
                                         Path templateSegyPath,
                                         Path outSegyPath,
                                         CompressionProfile profile) throws IOException {
        SegyIO.SegyDataset template = SegyIO.read(templateSegyPath);
        Sdc3DFileReader.Sdc3DVolume volume = Sdc3DFileReader.read3D(sdcPath, profile);
        java.util.List<TraceBlock> tracesReconstruidos =
                reconstructTracesFromVolume(template, volume);
        SegyIO.write(outSegyPath, template, tracesReconstruidos);
    }

    // -----------------------------------------------------------------------
    // Helpers privados
    // -----------------------------------------------------------------------

    /**
     * Concatena os 240 bytes de header de cada traço em um único blob.
     */
    private static byte[] buildTraceHeadersBlob(List<byte[]> traceHeaders) {
        int traceCount = traceHeaders.size();
        byte[] blob = new byte[SdcContainerV1.TRACE_HEADER_BYTES * traceCount];
        for (int i = 0; i < traceCount; i++) {
            byte[] th = traceHeaders.get(i);
            System.arraycopy(th, 0, blob, i * SdcContainerV1.TRACE_HEADER_BYTES,
                    SdcContainerV1.TRACE_HEADER_BYTES);
        }
        return blob;
    }

    /**
     * Divide o blob de trace headers em uma lista de arrays de 240 bytes.
     */
    private static List<byte[]> splitTraceHeadersBlob(byte[] blob, int traceCount) {
        List<byte[]> headers = new ArrayList<>(traceCount);
        for (int i = 0; i < traceCount; i++) {
            byte[] th = new byte[SdcContainerV1.TRACE_HEADER_BYTES];
            System.arraycopy(blob, i * SdcContainerV1.TRACE_HEADER_BYTES, th, 0,
                    SdcContainerV1.TRACE_HEADER_BYTES);
            headers.add(th);
        }
        return headers;
    }

    /**
     * Constrói uma lista de TraceMeta dummy para uso em SegyDataset sintético
     * (campos inline/xline irrelevantes para o round-trip de headers preservados).
     */
    private static List<SegyIO.TraceMeta> buildDummyTraceMeta(int traceCount) {
        List<SegyIO.TraceMeta> meta = new ArrayList<>(traceCount);
        for (int i = 0; i < traceCount; i++) {
            meta.add(new SegyIO.TraceMeta(i, 0, 0));
        }
        return meta;
    }

    /**
     * Constrói um TraceGrid vazio (1×1) para uso em SegyDataset sintético;
     * não utilizado em round-trip de arquivo SEG-Y.
     */
    private static SegyIO.TraceGrid buildDummyTraceGrid() {
        return new SegyIO.TraceGrid(new int[]{0}, new int[]{0}, new int[][]{{-1}});
    }

    private static java.util.List<TraceBlock> reconstructTracesFromVolume(
        SegyIO.SegyDataset template,
        Sdc3DFileReader.Sdc3DVolume volume) throws IOException {

        int inlineCount = template.traceGrid.inlineCount();
        int xlineCount  = template.traceGrid.xlineCount();
        int timeCount   = template.samplesPerTrace;

        if (volume.inlineCount != inlineCount ||
            volume.xlineCount  != xlineCount ||
            volume.timeCount   != timeCount) {
            throw new IOException("Dimensões do volume 3D não batem com o template SEG-Y: " +
                    "volume=(" + volume.inlineCount + "," + volume.xlineCount + "," + volume.timeCount + "), " +
                    "template=(" + inlineCount + "," + xlineCount + "," + timeCount + ")");
        }

        int traceCount = template.traceCount();
        java.util.List<TraceBlock> recon = new java.util.ArrayList<>(traceCount);
        for (int i = 0; i < traceCount; i++) {
            recon.add(null);
        }

        for (int ii = 0; ii < inlineCount; ii++) {
            for (int jj = 0; jj < xlineCount; jj++) {
                int traceIndex = template.traceGrid.traceIndexAt(ii, jj);
                if (traceIndex < 0) {
                    continue;
                }
                float[] samples = new float[timeCount];
                System.arraycopy(volume.data[ii][jj], 0, samples, 0, timeCount);
                recon.set(traceIndex, new TraceBlock(traceIndex, samples));
            }
        }

        for (int i = 0; i < recon.size(); i++) {
            if (recon.get(i) == null) {
                throw new IOException("Reconstrução 3D: falta traço para índice " + i);
            }
        }

        return recon;
    }
}

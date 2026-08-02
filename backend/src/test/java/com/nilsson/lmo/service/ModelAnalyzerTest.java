package com.nilsson.lmo.service;

import com.nilsson.lmo.domain.ModelMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>The {@code ModelAnalyzerTest} suite validates the accuracy and robustness
 * of the model identification engine. It tests the multi-stage heuristic pipeline,
 * ensuring high classification precision across various model formats.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Heuristic Accuracy:</b> Verifies filename tokenization and mapping for
 *   architectures (e.g., Flux, SDXL, Wan).</li>
 *   <li><b>Sidecar Parsing:</b> Ensures prioritisation of authoritative {@code .civitai.info}
 *   sidecars over speculative analysis.</li>
 *   <li><b>Binary Header Integrity:</b> Validates low-level memory-mapped parsing of
 *   {@code .safetensors} headers.</li>
 *   <li><b>Fallback Logic:</b> Asserts graceful handling of models with missing or
 *   ambiguous metadata.</li>
 * </ul>
 * </p>
 */
class ModelAnalyzerTest {

    private ModelAnalyzer modelAnalyzer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        modelAnalyzer = new ModelAnalyzer();
    }

    @Test
    void analyze_shouldIdentifyFluxViaHeuristics() throws IOException {
        Path modelFile = tempDir.resolve("flux_schnell_v1.safetensors");
        Files.writeString(modelFile, "dummy data");

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("Flux .1 S", metadata.architecture());
    }

    @Test
    void analyze_shouldPrioritizeSidecarMetadata() throws IOException {
        Path modelFile = tempDir.resolve("custom_model.safetensors");
        Path sidecarFile = tempDir.resolve("custom_model.civitai.info");
        Files.writeString(modelFile, "dummy data");
        Files.writeString(sidecarFile, "{\"baseModel\": \"SDXL 1.0\"}");

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("SDXL 1.0", metadata.architecture());
        assertEquals("SDXL 1.0", metadata.baseModel());
    }

    @Test
    void analyze_shouldReadSafetensorsHeader() throws IOException {
        Path modelFile = tempDir.resolve("header_test.safetensors");
        String jsonHeader = "{\"__metadata__\": {\"ss_base_model_version\": \"SDXL 1.0\"}}";
        byte[] headerBytes = jsonHeader.getBytes();

        ByteBuffer buffer = ByteBuffer.allocate(8 + headerBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(headerBytes.length);
        buffer.put(headerBytes);

        Files.write(modelFile, buffer.array());

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("SDXL 1.0", metadata.architecture());
    }

    @Test
    void analyze_shouldReturnUncategorizedOnNoMatch() throws IOException {
        Path modelFile = tempDir.resolve("unknown_random_file.safetensors");
        Files.writeString(modelFile, "random data");

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("Unknown", metadata.architecture());
    }

    @Test
    void analyze_shouldIdentifyKrea2ViaHeuristics() throws IOException {
        Path modelFile = tempDir.resolve("krea2_style.safetensors");
        Files.writeString(modelFile, "dummy data");

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("Krea 2", metadata.architecture());
    }

    @Test
    void analyze_shouldIdentifyKrea2ViaSidecar() throws IOException {
        Path modelFile = tempDir.resolve("krea_model.safetensors");
        Path sidecarFile = tempDir.resolve("krea_model.civitai.info");
        Files.writeString(modelFile, "dummy data");
        Files.writeString(sidecarFile, "{\"baseModel\": \"Krea 2\"}");

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("Krea 2", metadata.architecture());
        assertEquals("Krea 2", metadata.baseModel());
    }

    @Test
    void analyze_shouldIdentifyKrea2ViaSafetensorsHeader() throws IOException {
        Path modelFile = tempDir.resolve("krea_header.safetensors");
        String jsonHeader = "{\"__metadata__\": {\"ss_base_model_version\": \"Krea 2\"}}";
        byte[] headerBytes = jsonHeader.getBytes();

        ByteBuffer buffer = ByteBuffer.allocate(8 + headerBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(headerBytes.length);
        buffer.put(headerBytes);

        Files.write(modelFile, buffer.array());

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("Krea 2", metadata.architecture());
    }

    @Test
    void analyze_shouldPreferFilenameOverGenericSdxlHeaderForIllustrious() throws IOException {
        // Kohya writes ss_base_model_version as the generic SDXL architecture even when the
        // checkpoint is actually an Illustrious fine-tune.
        Path modelFile = tempDir.resolve("illustriousXL_stabilizer_v1.23.safetensors");
        String jsonHeader = "{\"__metadata__\": {\"ss_base_model_version\": \"sdxl_base_v1-0\"}}";
        byte[] headerBytes = jsonHeader.getBytes();

        ByteBuffer buffer = ByteBuffer.allocate(8 + headerBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(headerBytes.length);
        buffer.put(headerBytes);

        Files.write(modelFile, buffer.array());

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("Illustrious", metadata.architecture());
    }

    @Test
    void analyze_shouldPreferFilenameOverGenericSdxlHeaderForNoobAiAndPony() throws IOException {
        String jsonHeader = "{\"__metadata__\": {\"ss_base_model_version\": \"sdxl_base_v1-0\"}}";
        byte[] headerBytes = jsonHeader.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(8 + headerBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(headerBytes.length);
        buffer.put(headerBytes);

        Path noobFile = tempDir.resolve("noobaiXLNAIXL_epsilonPred11Version-lora.safetensors");
        Files.write(noobFile, buffer.array());
        assertEquals("NoobAI", modelAnalyzer.analyze(noobFile).architecture());

        Path ponyFile = tempDir.resolve("queencomplex_pony_v1.safetensors");
        Files.write(ponyFile, buffer.array());
        assertEquals("Pony", modelAnalyzer.analyze(ponyFile).architecture());
    }

    @Test
    void analyze_shouldNotOverrideGenericSdxlHeaderWhenFilenameHasNoSpecificHint() throws IOException {
        Path modelFile = tempDir.resolve("generic_style_lora.safetensors");
        String jsonHeader = "{\"__metadata__\": {\"ss_base_model_version\": \"sdxl_base_v1-0\"}}";
        byte[] headerBytes = jsonHeader.getBytes();

        ByteBuffer buffer = ByteBuffer.allocate(8 + headerBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(headerBytes.length);
        buffer.put(headerBytes);

        Files.write(modelFile, buffer.array());

        ModelMetadata metadata = modelAnalyzer.analyze(modelFile);

        assertEquals("SDXL 1.0", metadata.architecture());
    }

    @Test
    void analyze_shouldIdentifyNewModelsViaHeuristics() throws IOException {
        // Wan 2.7 Video and Image
        Path wanVideoFile = tempDir.resolve("wan2.7_t2v_test.safetensors");
        Files.writeString(wanVideoFile, "dummy");
        assertEquals("Wan Video 2.7", modelAnalyzer.analyze(wanVideoFile).architecture());

        Path wanImageFile = tempDir.resolve("wan_image2.7_test.safetensors");
        Files.writeString(wanImageFile, "dummy");
        assertEquals("Wan Image 2.7", modelAnalyzer.analyze(wanImageFile).architecture());

        // LTXV 2.3
        Path ltxvFile = tempDir.resolve("ltx_2.3_video.safetensors");
        Files.writeString(ltxvFile, "dummy");
        assertEquals("LTXV 2.3", modelAnalyzer.analyze(ltxvFile).architecture());

        // Qwen 2
        Path qwenFile = tempDir.resolve("qwen2_lora.safetensors");
        Files.writeString(qwenFile, "dummy");
        assertEquals("Qwen 2", modelAnalyzer.analyze(qwenFile).architecture());

        // HiDream-O1
        Path hiDreamFile = tempDir.resolve("hidream-o1_style.safetensors");
        Files.writeString(hiDreamFile, "dummy");
        assertEquals("HiDream-O1", modelAnalyzer.analyze(hiDreamFile).architecture());

        // Ideogram 4.0
        Path ideogramFile = tempDir.resolve("ideogram4.0_model.safetensors");
        Files.writeString(ideogramFile, "dummy");
        assertEquals("Ideogram 4.0", modelAnalyzer.analyze(ideogramFile).architecture());

        // Grok
        Path grokFile = tempDir.resolve("grok_model.safetensors");
        Files.writeString(grokFile, "dummy");
        assertEquals("Grok", modelAnalyzer.analyze(grokFile).architecture());
    }

    @Test
    void analyze_shouldIdentifyNewModelsViaSidecar() throws IOException {
        Path modelFile = tempDir.resolve("test_model.safetensors");
        Path sidecarFile = tempDir.resolve("test_model.civitai.info");
        Files.writeString(modelFile, "dummy");

        Files.writeString(sidecarFile, "{\"baseModel\": \"Wan Video 2.7\"}");
        assertEquals("Wan Video 2.7", modelAnalyzer.analyze(modelFile).architecture());

        Files.writeString(sidecarFile, "{\"baseModel\": \"LTXV 2.3\"}");
        assertEquals("LTXV 2.3", modelAnalyzer.analyze(modelFile).architecture());

        Files.writeString(sidecarFile, "{\"baseModel\": \"Qwen 2\"}");
        assertEquals("Qwen 2", modelAnalyzer.analyze(modelFile).architecture());
    }
}
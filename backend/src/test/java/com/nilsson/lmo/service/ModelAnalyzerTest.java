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
}
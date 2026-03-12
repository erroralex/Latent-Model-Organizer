package com.latent.organizer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latent.organizer.domain.ModelMetadata;
import com.latent.organizer.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Service responsible for analyzing model files to determine their neural network architecture.
 * <p>
 * This analyzer supports two detection methods:
 * <ol>
 *     <li>Reading a sidecar `.civitai.info` JSON file.</li>
 *     <li>Parsing the header of a `.safetensors` file directly (memory-efficient).</li>
 * </ol>
 */
public class ModelAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ModelAnalyzer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Analyzes the given model file to extract metadata and determine its architecture.
     *
     * @param modelPath The path to the main model file (e.g., .safetensors).
     * @return A {@link ModelMetadata} object containing the detected architecture and base model.
     * @throws OrganizerException if the file cannot be read or analyzed.
     */
    public ModelMetadata analyze(Path modelPath) {
        String fileName = modelPath.getFileName().toString();
        
        try {
            // Method 1: Check for sidecar .civitai.info file
            Optional<ModelMetadata> sidecarMetadata = analyzeSidecar(modelPath);
            if (sidecarMetadata.isPresent()) {
                return sidecarMetadata.get();
            }

            // Method 2: Analyze .safetensors header
            if (fileName.endsWith(".safetensors")) {
                return analyzeSafetensorsHeader(modelPath);
            }

            // Fallback for unknown types
            logger.warn("Could not determine architecture for file: {}", fileName);
            return new ModelMetadata(fileName, "Unknown", "Unknown");

        } catch (IOException e) {
            throw new OrganizerException("Failed to analyze model: " + fileName, e);
        }
    }

    /**
     * Attempts to find and parse a corresponding .civitai.info file.
     */
    private Optional<ModelMetadata> analyzeSidecar(Path modelPath) throws IOException {
        String fileName = modelPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        Path sidecarPath = modelPath.resolveSibling(baseName + ".civitai.info");

        if (Files.exists(sidecarPath)) {
            logger.debug("Found sidecar file: {}", sidecarPath);
            JsonNode rootNode = objectMapper.readTree(sidecarPath.toFile());
            
            if (rootNode.has("baseModel")) {
                String baseModel = rootNode.get("baseModel").asText();
                String architecture = mapBaseModelToArchitecture(baseModel);
                return Optional.of(new ModelMetadata(fileName, architecture, baseModel));
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the .safetensors header efficiently without loading the full file.
     * <p>
     * .safetensors format:
     * 8 bytes (u64 le) - length of the JSON header
     * N bytes          - JSON header data
     * ...              - Tensor data
     */
    private ModelMetadata analyzeSafetensorsHeader(Path modelPath) throws IOException {
        try (FileChannel channel = FileChannel.open(modelPath, StandardOpenOption.READ)) {
            // 1. Read the 8-byte header length prefix
            ByteBuffer lengthBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            int bytesRead = channel.read(lengthBuffer);
            
            if (bytesRead != 8) {
                throw new OrganizerException("Invalid safetensors file: insufficient header length bytes");
            }
            
            lengthBuffer.flip();
            long headerLength = lengthBuffer.getLong();
            
            if (headerLength <= 0 || headerLength > 100_000_000) { // Safety cap (100MB header is huge)
                throw new OrganizerException("Invalid safetensors header length: " + headerLength);
            }

            // 2. Read the JSON header content
            ByteBuffer headerBuffer = ByteBuffer.allocate((int) headerLength);
            channel.read(headerBuffer);
            headerBuffer.flip();
            
            String jsonHeader = new String(headerBuffer.array(), StandardCharsets.UTF_8);
            JsonNode metadataNode = objectMapper.readTree(jsonHeader);
            
            // 3. Extract metadata
            String baseModel = "Unknown";
            
            // Check for standard metadata keys
            if (metadataNode.has("__metadata__")) {
                JsonNode internalMeta = metadataNode.get("__metadata__");
                if (internalMeta.has("ss_sd_model_name")) {
                    baseModel = internalMeta.get("ss_sd_model_name").asText();
                } else if (internalMeta.has("modelspec.title")) {
                    baseModel = internalMeta.get("modelspec.title").asText();
                } else if (internalMeta.has("ss_base_model_version")) {
                     baseModel = internalMeta.get("ss_base_model_version").asText();
                }
            }

            String architecture = mapBaseModelToArchitecture(baseModel);
            return new ModelMetadata(modelPath.getFileName().toString(), architecture, baseModel);
        }
    }

    /**
     * Maps a raw base model string to a simplified architecture category.
     */
    private String mapBaseModelToArchitecture(String baseModel) {
        if (baseModel == null) return "Unknown";
        
        String lower = baseModel.toLowerCase();
        
        if (lower.contains("sdxl") || lower.contains("stable diffusion xl")) {
            return "SDXL";
        } else if (lower.contains("pony")) {
            return "Pony"; // Pony is often based on SDXL but users prefer separate folders
        } else if (lower.contains("flux")) {
            return "Flux";
        } else if (lower.contains("sd 1.5") || lower.contains("v1-5") || lower.contains("1.5")) {
            return "SD1.5";
        } else if (lower.contains("sd 2.1") || lower.contains("v2-1")) {
            return "SD2.1";
        } else {
            return "Other";
        }
    }
}

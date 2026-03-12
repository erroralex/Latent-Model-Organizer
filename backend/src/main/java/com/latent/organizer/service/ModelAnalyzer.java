package com.latent.organizer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latent.organizer.domain.ModelMetadata;
import com.latent.organizer.exception.OrganizerException;
import com.latent.organizer.util.HashUtil;
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
 * <p>Advanced model analysis engine for identifying neural network architectures and metadata.</p>
 *
 * <p>The {@code ModelAnalyzer} employs a multi-tiered heuristic approach to identify models,
 * prioritizing performance and local privacy before falling back to network-based lookups.
 * The analysis pipeline consists of the following stages:
 * <ol>
 *     <li><b>Sidecar Inspection:</b> Checks for existing {@code .civitai.info} files which provide
 *     authoritative metadata without additional computation.</li>
 *     <li><b>Header Parsing:</b> Directly reads the JSON header of {@code .safetensors} files using
 *     memory-mapped I/O (FileChannel). This is extremely efficient as it only reads the first
 *     few kilobytes of multi-gigabyte files.</li>
 *     <li><b>API Fallback:</b> Computes a SHA-256 hash of the model and queries the Civitai API.
 *     Upon a successful match, it persists the metadata locally for future use and downloads
 *     available preview images.</li>
 * </ol>
 * </p>
 *
 * <p>The analyzer includes a sophisticated mapping logic to categorize raw metadata strings into
 * clean architectural buckets like "SDXL", "Flux", "Pony", and "Illustrious".</p>
 */
public class ModelAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ModelAnalyzer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final CivitaiApiClient civitaiApiClient;

    public ModelAnalyzer() {
        this(new CivitaiApiClient());
    }

    public ModelAnalyzer(CivitaiApiClient civitaiApiClient) {
        this.civitaiApiClient = civitaiApiClient;
    }

    public ModelMetadata analyze(Path modelPath) {
        String fileName = modelPath.getFileName().toString();

        try {
            Optional<ModelMetadata> sidecarMetadata = analyzeSidecar(modelPath);
            if (sidecarMetadata.isPresent()) {
                return sidecarMetadata.get();
            }

            ModelMetadata headerMetadata = null;
            if (fileName.endsWith(".safetensors")) {
                headerMetadata = analyzeSafetensorsHeader(modelPath);
                if (!"Unknown".equals(headerMetadata.architecture())) {
                    return headerMetadata;
                }
            }

            logger.info("Local analysis failed for '{}'. Attempting Civitai API lookup...", fileName);
            return analyzeViaCivitai(modelPath);

        } catch (IOException e) {
            throw new OrganizerException("Failed to analyze model: " + fileName, e);
        }
    }

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

    private ModelMetadata analyzeSafetensorsHeader(Path modelPath) throws IOException {
        try (FileChannel channel = FileChannel.open(modelPath, StandardOpenOption.READ)) {
            ByteBuffer lengthBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            int bytesRead = channel.read(lengthBuffer);

            if (bytesRead != 8) {
                logger.warn("Invalid safetensors file header: insufficient bytes");
                return new ModelMetadata(modelPath.getFileName().toString(), "Unknown", "Unknown");
            }

            lengthBuffer.flip();
            long headerLength = lengthBuffer.getLong();

            if (headerLength <= 0 || headerLength > 100_000_000) {
                throw new OrganizerException("Invalid safetensors header length: " + headerLength);
            }

            ByteBuffer headerBuffer = ByteBuffer.allocate((int) headerLength);
            channel.read(headerBuffer);
            headerBuffer.flip();

            String jsonHeader = new String(headerBuffer.array(), StandardCharsets.UTF_8);
            JsonNode metadataNode = objectMapper.readTree(jsonHeader);

            String baseModel = "Unknown";

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
        } catch (Exception e) {
            logger.warn("Safetensors header parsing failed: {}", e.getMessage());
            return new ModelMetadata(modelPath.getFileName().toString(), "Unknown", "Unknown");
        }
    }

    private ModelMetadata analyzeViaCivitai(Path modelPath) {
        String fileName = modelPath.getFileName().toString();
        try {
            String hash = HashUtil.calculateSHA256(modelPath);

            String jsonResponse = civitaiApiClient.fetchMetadataByHash(hash);

            if (jsonResponse != null) {
                JsonNode rootNode = objectMapper.readTree(jsonResponse);

                if (rootNode.has("baseModel")) {
                    String baseModel = rootNode.get("baseModel").asText();
                    String architecture = mapBaseModelToArchitecture(baseModel);

                    String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                    Path sidecarPath = modelPath.resolveSibling(baseName + ".civitai.info");
                    Files.writeString(sidecarPath, jsonResponse, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    logger.info("Cached metadata for '{}' to '{}'", fileName, sidecarPath);

                    if (rootNode.has("images") && rootNode.get("images").isArray() && !rootNode.get("images").isEmpty()) {
                        String imageUrl = rootNode.get("images").get(0).get("url").asText();

                        String extension = ".preview.png";
                        String lowerUrl = imageUrl.toLowerCase();
                        if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")) extension = ".preview.jpeg";
                        else if (lowerUrl.endsWith(".webp")) extension = ".preview.webp";

                        Path imagePath = modelPath.resolveSibling(baseName + extension);

                        if (!Files.exists(imagePath)) {
                            civitaiApiClient.downloadPreviewImage(imageUrl, imagePath);
                        }
                    }

                    return new ModelMetadata(fileName, architecture, baseModel);
                }
            }
        } catch (Exception e) {
            logger.error("Civitai API fallback failed for '{}': {}", fileName, e.getMessage());
        }

        return new ModelMetadata(fileName, "Unknown", "Unknown");
    }

    private String mapBaseModelToArchitecture(String baseModel) {
        if (baseModel == null) return "Unknown";

        String upper = baseModel.toUpperCase();

        if (upper.contains("PONY")) return "Pony";
        if (upper.contains("ILLUSTRIOUS")) return "Illustrious";
        if (upper.contains("SANA")) return "Sana";
        if (upper.contains("NOOB")) return "Noob V";
        if (upper.contains("FLUX")) return "Flux";

        if (upper.contains("SD3.5") || upper.contains("SD 3.5") || upper.contains("SD3") || upper.contains("SD 3")) {
            return "SD 3.5";
        }

        if (upper.contains("SDXL")) return "SDXL";

        if (upper.contains("V1-5") || upper.contains("SD1.5") || upper.contains("SD 1.5") || upper.contains("SD15")) {
            return "SD 1.5";
        }
        if (upper.contains("V1-4") || upper.contains("SD1.4") || upper.contains("SD 1.4") || upper.contains("SD14")) {
            return "SD 1.4";
        }

        return "Unknown";
    }
}

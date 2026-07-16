package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.domain.ModelMetadata;
import com.nilsson.lmo.exception.OrganizerException;
import com.nilsson.lmo.util.HashUtil;
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
import java.util.List;
import java.util.Optional;

/**
 * <p>The {@code ModelAnalyzer} is a specialized analysis engine designed to identify the neural network
 * architecture and associated metadata of machine learning model files (specifically {@code .safetensors}).</p>
 *
 * <p>It implements a multi-stage heuristic pipeline to accurately categorize models:
 * <ol>
 *   <li><b>Sidecar Analysis:</b> Checks for existing {@code .civitai.info} files containing metadata.</li>
 *   <li><b>Header Inspection:</b> Performs zero-memory byte parsing of the Safetensors JSON header to extract
 *   embedded architectural hints or model versions.</li>
 *   <li><b>Filename Heuristics:</b> Applies complex regex and token matching against the filename if
 *   internal metadata is missing or ambiguous.</li>
 *   <li><b>Remote API Lookup:</b> As a final fallback, calculates the SHA-256 hash of the model and
 *   queries the Civitai API for canonical metadata and preview images.</li>
 * </ol>
 * </p>
 *
 * <p>This engine is designed for high-performance and low memory overhead, utilizing {@code FileChannel}
 * for direct header access without loading multi-gigabyte files into the JVM heap.</p>
 */
public class ModelAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ModelAnalyzer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final CivitaiApiClient civitaiApiClient;

    public static final List<String> SUPPORTED_ARCHITECTURES = List.of(
            "Flux .2 Klein 9B-base", "Flux .2 Klein 9B", "Flux .2 Klein 4B-base", "Flux .2 Klein 4B", "Flux .2 D",
            "Flux .1 Kontext", "Flux .1 Krea", "Flux .1 S", "Flux .1 D",
            "Krea 2",
            "Wan Video 2.7", "Wan Image 2.7",
            "Wan Video 2.5 I2V", "Wan Video 2.5 T2V", "Wan Video 2.2 I2V-A14B", "Wan Video 2.2 TI2V-5B",
            "Wan Video 2.2 T2V-A14B", "Wan Video 14B i2v 720p", "Wan Video 14B i2v 480p", "Wan Video 14B t2v",
            "Wan Video 1.3B t2v",
            "SDXL Lightning", "SDXL Hyper", "SDXL Turbo", "SDXL 1.0",
            "SD 3.5", "SD 2.1", "SD 2.0", "SD 1.5 Hyper", "SD 1.5 LCM", "SD 1.5", "SD 1.4",
            "Pony V7", "Pony", "Illustrious", "NoobAI", "Sana",
            "LTXV 2.3", "LTXV2", "LTXV", "Mochi", "CogVideoX", "Hunyuan Video", "Hunyuan 1",
            "HiDream-O1", "HiDream", "PixArt Σ", "PixArt α", "Aura Flow", "Lumina", "Kolors",
            "Chroma", "Anima", "Qwen 2", "Qwen", "Z Image Base", "Z Image Turbo",
            "Ideogram 4.0", "Grok",
            "Uncategorized", "Unknown"
    );

    public ModelAnalyzer() {
        this(new CivitaiApiClient());
    }

    public ModelAnalyzer(CivitaiApiClient civitaiApiClient) {
        this.civitaiApiClient = civitaiApiClient;
    }

    public List<String> getAllArchitectures() {
        return SUPPORTED_ARCHITECTURES;
    }

    public ModelMetadata analyze(Path modelPath) {
        String fileName = modelPath.getFileName().toString();

        try {
            Optional<ModelMetadata> sidecarMetadata = analyzeSidecar(modelPath);
            if (sidecarMetadata.isPresent()) {
                ModelMetadata sidecar = sidecarMetadata.get();
                if ("Uncategorized".equals(sidecar.architecture())) {
                    String filenameArch = checkFilenameHeuristics(fileName);
                    if (!"Unknown".equals(filenameArch)) {
                        logger.info("Heuristic override for Uncategorized sidecar '{}' → '{}'", fileName, filenameArch);
                        return new ModelMetadata(fileName, filenameArch, sidecar.baseModel());
                    }
                }
                return sidecar;
            }

            if (fileName.toLowerCase().endsWith(".safetensors")) {
                ModelMetadata headerMetadata = analyzeSafetensorsHeader(modelPath);
                if (!"Unknown".equals(headerMetadata.architecture())) {
                    String filenameArch = checkFilenameHeuristics(fileName);
                    if (isZImageFamily(headerMetadata.architecture()) && isZImageFamily(filenameArch)
                            && !filenameArch.equals(headerMetadata.architecture())) {
                        logger.info("Heuristic override for Z Image variant: '{}' → '{}'", fileName, filenameArch);
                        return new ModelMetadata(fileName, filenameArch, headerMetadata.baseModel());
                    }
                    return headerMetadata;
                }
            }

            String heuristicArch = checkFilenameHeuristics(fileName);
            if (!"Unknown".equals(heuristicArch)) {
                logger.info("Identified architecture via heuristics for '{}': {}", fileName, heuristicArch);
                return new ModelMetadata(fileName, heuristicArch, "Heuristic Fallback");
            }

            logger.info("Local analysis failed for '{}'. Attempting Civitai API lookup...", fileName);
            return analyzeViaCivitai(modelPath);

        } catch (IOException e) {
            throw new OrganizerException("Failed to analyze model: " + fileName, e);
        }
    }

    private String checkFilenameHeuristics(String filename) {
        String lower = filename.toLowerCase();

        if (lower.contains("krea") && (lower.contains("2") || lower.contains("two"))) {
            return "Krea 2";
        }

        if (lower.contains("flux.2") || lower.contains("flux_2") || lower.contains("flux-2")) {
            if (lower.contains("klein")) {
                if (lower.contains("9b-base") || lower.contains("9b_base")) return "Flux .2 Klein 9B-base";
                if (lower.contains("9b")) return "Flux .2 Klein 9B";
                if (lower.contains("4b-base") || lower.contains("4b_base")) return "Flux .2 Klein 4B-base";
                if (lower.contains("4b")) return "Flux .2 Klein 4B";
            }
            return "Flux .2 D";
        }

        if (lower.contains("flux")) {
            if (lower.contains("kontext")) return "Flux .1 Kontext";
            if (lower.contains("krea")) return "Flux .1 Krea";
            if (lower.contains("schnell") || lower.contains(".1s") || lower.contains("_1s")) return "Flux .1 S";
            if (lower.contains("klein")) {
                if (lower.contains("9b-base") || lower.contains("9b_base")) return "Flux .2 Klein 9B-base";
                if (lower.contains("9b")) return "Flux .2 Klein 9B";
                if (lower.contains("4b-base") || lower.contains("4b_base")) return "Flux .2 Klein 4B-base";
                if (lower.contains("4b")) return "Flux .2 Klein 4B";
            }
            return "Flux .1 D";
        }

        if (lower.contains("wan")) {
            if (lower.contains("2.7") || lower.contains("2_7")) {
                if (lower.contains("image")) return "Wan Image 2.7";
                return "Wan Video 2.7";
            }
            if (lower.contains("2.5") && lower.contains("i2v")) return "Wan Video 2.5 I2V";
            if (lower.contains("2.5") && lower.contains("t2v")) return "Wan Video 2.5 T2V";
            if (lower.contains("2.2") && lower.contains("i2v") && lower.contains("a14b"))
                return "Wan Video 2.2 I2V-A14B";
            if (lower.contains("2.2") && lower.contains("ti2v")) return "Wan Video 2.2 TI2V-5B";
            if (lower.contains("2.2") && lower.contains("t2v")) return "Wan Video 2.2 T2V-A14B";
            if (lower.contains("14b") && lower.contains("i2v") && lower.contains("720"))
                return "Wan Video 14B i2v 720p";
            if (lower.contains("14b") && lower.contains("i2v") && lower.contains("480"))
                return "Wan Video 14B i2v 480p";
            if (lower.contains("14b") && lower.contains("t2v")) return "Wan Video 14B t2v";
            if (lower.contains("1.3b") || lower.contains("1_3b")) return "Wan Video 1.3B t2v";
            return "Wan Video 14B t2v";
        }

        if (lower.contains("sdxl") || lower.contains("xl-base") || lower.contains("xl_base")) {
            if (lower.contains("lightning")) return "SDXL Lightning";
            if (lower.contains("hyper")) return "SDXL Hyper";
            if (lower.contains("turbo")) return "SDXL Turbo";
            return "SDXL 1.0";
        }

        if (lower.contains("sd3") || lower.contains("sd_3") || lower.contains("sd-3")) return "SD 3.5";

        if (lower.contains("sd21") || lower.contains("sd2.1") || lower.contains("sd_2_1") || lower.contains("v2-1"))
            return "SD 2.1";
        if (lower.contains("sd20") || lower.contains("sd2.0") || lower.contains("sd_2_0") || lower.contains("v2-0"))
            return "SD 2.0";

        if (lower.contains("sd15") || lower.contains("sd1.5") || lower.contains("sd_1_5") || lower.contains("sd-1-5") || lower.contains("v1-5")) {
            if (lower.contains("hyper")) return "SD 1.5 Hyper";
            if (lower.contains("lcm")) return "SD 1.5 LCM";
            return "SD 1.5";
        }

        if (lower.contains("sd14") || lower.contains("sd1.4") || lower.contains("v1-4")) return "SD 1.4";

        if (lower.contains("pony") || lower.contains("pdxl")) {
            if (lower.contains("v7") || lower.contains("_v7") || lower.contains("-v7")) return "Pony V7";
            return "Pony";
        }

        if (lower.contains("illustrious")) return "Illustrious";
        if (lower.contains("noob")) return "NoobAI";

        if (lower.contains("ltx") && (lower.contains("2.3") || lower.contains("2_3"))) return "LTXV 2.3";
        if (lower.contains("ltxv2") || lower.contains("ltx-v2") || lower.contains("ltx_v2")) return "LTXV2";
        if (lower.contains("ltx-2") || lower.contains("ltx_2") || lower.contains("ltx2")) return "LTXV2";
        if (lower.contains("ltxv") || lower.contains("ltx-video") || lower.contains("ltx_video")) return "LTXV";

        if (lower.contains("mochi")) return "Mochi";
        if (lower.contains("cogvideo") || lower.contains("cog-video") || lower.contains("cog_video"))
            return "CogVideoX";
        if (lower.contains("hunyuan") && lower.contains("video")) return "Hunyuan Video";
        if (lower.contains("hunyuan")) return "Hunyuan 1";

        if (lower.contains("hidream") || lower.contains("hi-dream") || lower.contains("hi_dream")) {
            if (lower.contains("o1") || lower.contains("o-1")) return "HiDream-O1";
            return "HiDream";
        }
        if (lower.contains("pixart") && (lower.contains("sigma") || lower.contains("σ"))) return "PixArt Σ";
        if (lower.contains("pixart")) return "PixArt α";
        if (lower.contains("auraflow") || lower.contains("aura-flow") || lower.contains("aura_flow"))
            return "Aura Flow";
        if (lower.contains("lumina")) return "Lumina";
        if (lower.contains("kolors")) return "Kolors";
        if (lower.contains("sana")) return "Sana";
        if (lower.contains("chroma")) return "Chroma";
        if (lower.contains("anima")) return "Anima";
        if (lower.contains("qwen")) {
            if (lower.contains("2")) return "Qwen 2";
            return "Qwen";
        }
        if (lower.contains("ideogram")) return "Ideogram 4.0";
        if (lower.contains("grok")) return "Grok";

        if (isZImageBase(lower)) return "Z Image Base";
        if (isZImageTurbo(lower)) return "Z Image Turbo";

        return "Unknown";
    }

    private static boolean isZImageBase(String lower) {
        if (lower.contains("zbase") || lower.contains("z-base") || lower.contains("z_base")) return true;
        if (lower.contains("z-image-base") || lower.contains("z_image_base")) return true;
        return (lower.contains("zimage") || lower.contains("z_image") || lower.contains("z-image")) && lower.contains("base");
    }

    private static boolean isZImageTurbo(String lower) {
        if (lower.contains("zimageturbo") || lower.contains("zimage_turbo")
                || lower.contains("zimage-turbo") || lower.contains("z_image_turbo")
                || lower.contains("z-image-turbo")) return true;
        if (containsToken(lower, "zit")) return true;
        return lower.contains("zimage") || lower.contains("z_image") || lower.contains("z-image");
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
                if (internalMeta.has("ss_base_model_version")) {
                    baseModel = internalMeta.get("ss_base_model_version").asText();
                } else if (internalMeta.has("ss_sd_model_name")) {
                    baseModel = internalMeta.get("ss_sd_model_name").asText();
                } else if (internalMeta.has("modelspec.architecture")) {
                    baseModel = internalMeta.get("modelspec.architecture").asText();
                } else if (internalMeta.has("modelspec.title")) {
                    baseModel = internalMeta.get("modelspec.title").asText();
                }
            }

            String architecture = mapBaseModelToArchitecture(baseModel);

            if ("Unknown".equals(architecture) && !"Unknown".equals(baseModel)) {
                architecture = checkFilenameHeuristics(baseModel);
            }

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
                    Files.writeString(sidecarPath, jsonResponse,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    logger.info("Cached metadata for '{}' to '{}'", fileName, sidecarPath);

                    JsonNode images = rootNode.path("images");
                    if (images.isArray() && !images.isEmpty()) {
                        String imageUrl = images.get(0).path("url").asText(null);
                        if (imageUrl != null && !imageUrl.isBlank()) {
                            String extension = OrganizationService.resolvePreviewExtension(imageUrl);
                            Path imagePath = modelPath.resolveSibling(baseName + extension);
                            if (!Files.exists(imagePath)) {
                                civitaiApiClient.downloadPreviewImage(imageUrl, imagePath);
                            }
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
        if (baseModel == null || baseModel.isBlank()) return "Unknown";

        String upper = baseModel.toUpperCase();

        if (upper.contains("FLUX.2") || upper.contains("FLUX 2") || upper.contains("FLUX_2")) {
            if (upper.contains("KLEIN")) {
                if (upper.contains("9B-BASE") || upper.contains("9B_BASE")) return "Flux .2 Klein 9B-base";
                if (upper.contains("9B")) return "Flux .2 Klein 9B";
                if (upper.contains("4B-BASE") || upper.contains("4B_BASE")) return "Flux .2 Klein 4B-base";
                if (upper.contains("4B")) return "Flux .2 Klein 4B";
            }
            return "Flux .2 D";
        }

        if (upper.contains("FLUX")) {
            if (upper.contains("KONTEXT")) return "Flux .1 Kontext";
            if (upper.contains("KREA")) return "Flux .1 Krea";
            if (upper.contains("SCHNELL")) return "Flux .1 S";
            if (upper.contains("KLEIN")) {
                if (upper.contains("9B-BASE") || upper.contains("9B_BASE")) return "Flux .2 Klein 9B-base";
                if (upper.contains("9B")) return "Flux .2 Klein 9B";
                if (upper.contains("4B-BASE") || upper.contains("4B_BASE")) return "Flux .2 Klein 4B-base";
                if (upper.contains("4B")) return "Flux .2 Klein 4B";
            }
            return "Flux .1 D";
        }

        if (upper.contains("KREA 2") || (upper.contains("KREA") && upper.contains("2"))) {
            return "Krea 2";
        }

        if (upper.contains("WAN")) {
            if (upper.contains("2.7")) {
                if (upper.contains("IMAGE")) return "Wan Image 2.7";
                return "Wan Video 2.7";
            }
            if (upper.contains("2.5") && upper.contains("I2V")) return "Wan Video 2.5 I2V";
            if (upper.contains("2.5") && upper.contains("T2V")) return "Wan Video 2.5 T2V";
            if (upper.contains("2.2") && upper.contains("I2V") && upper.contains("A14B"))
                return "Wan Video 2.2 I2V-A14B";
            if (upper.contains("2.2") && upper.contains("TI2V")) return "Wan Video 2.2 TI2V-5B";
            if (upper.contains("2.2") && upper.contains("T2V")) return "Wan Video 2.2 T2V-A14B";
            if (upper.contains("14B") && upper.contains("I2V") && upper.contains("720"))
                return "Wan Video 14B i2v 720p";
            if (upper.contains("14B") && upper.contains("I2V") && upper.contains("480"))
                return "Wan Video 14B i2v 480p";
            if (upper.contains("14B") && upper.contains("T2V")) return "Wan Video 14B t2v";
            if (upper.contains("1.3B")) return "Wan Video 1.3B t2v";
        }

        if (upper.contains("PONY") || upper.contains("PDXL")) {
            if (upper.contains("V7")) return "Pony V7";
            return "Pony";
        }

        if (upper.contains("ILLUSTRIOUS")) return "Illustrious";
        if (upper.contains("NOOB")) return "NoobAI";
        if (upper.contains("SANA")) return "Sana";

        if (upper.contains("SD3.5") || upper.contains("SD 3.5") || upper.contains("SD3") || upper.contains("SD 3"))
            return "SD 3.5";

        if (upper.contains("SDXL") || upper.contains("XL-BASE") || upper.contains("XL_BASE")) {
            if (upper.contains("LIGHTNING")) return "SDXL Lightning";
            if (upper.contains("HYPER")) return "SDXL Hyper";
            if (upper.contains("TURBO")) return "SDXL Turbo";
            return "SDXL 1.0";
        }

        if (upper.contains("V2-1") || upper.contains("SD2.1") || upper.contains("SD 2.1")) return "SD 2.1";
        if (upper.contains("V2-0") || upper.contains("SD2.0") || upper.contains("SD 2.0")) return "SD 2.0";

        if (upper.contains("V1-5") || upper.contains("SD1.5") || upper.contains("SD 1.5") || upper.contains("SD15")) {
            if (upper.contains("HYPER")) return "SD 1.5 Hyper";
            if (upper.contains("LCM")) return "SD 1.5 LCM";
            return "SD 1.5";
        }

        if (upper.contains("V1-4") || upper.contains("SD1.4") || upper.contains("SD 1.4") || upper.contains("SD14"))
            return "SD 1.4";

        if (upper.contains("LTX") && (upper.contains("2.3") || upper.contains("2_3"))) return "LTXV 2.3";
        if (upper.contains("LTXV2") || upper.contains("LTX-V2") || upper.contains("LTX_V2")) return "LTXV2";
        if (upper.contains("LTX-2") || upper.contains("LTX_2") || upper.contains("LTX2")) return "LTXV2";
        if (upper.contains("LTXV") || upper.contains("LTX-VIDEO") || upper.contains("LTX_VIDEO")) return "LTXV";

        if (upper.contains("MOCHI")) return "Mochi";
        if (upper.contains("COGVIDEO") || upper.contains("COG-VIDEO")) return "CogVideoX";
        if (upper.contains("HUNYUAN") && upper.contains("VIDEO")) return "Hunyuan Video";
        if (upper.contains("HUNYUAN")) return "Hunyuan 1";

        if (upper.contains("HIDREAM") || upper.contains("HI-DREAM") || upper.contains("HI_DREAM")) {
            if (upper.contains("O1") || upper.contains("O-1")) return "HiDream-O1";
            return "HiDream";
        }
        if (upper.contains("PIXART") && (upper.contains("SIGMA") || upper.contains("Σ"))) return "PixArt Σ";
        if (upper.contains("PIXART")) return "PixArt α";
        if (upper.contains("AURAFLOW") || upper.contains("AURA FLOW") || upper.contains("AURA_FLOW"))
            return "Aura Flow";
        if (upper.contains("LUMINA")) return "Lumina";
        if (upper.contains("KOLORS")) return "Kolors";
        if (upper.contains("CHROMA")) return "Chroma";
        if (upper.contains("ANIMA")) return "Anima";
        if (upper.contains("QWEN")) {
            if (upper.contains("2")) return "Qwen 2";
            return "Qwen";
        }
        if (upper.contains("IDEOGRAM")) return "Ideogram 4.0";
        if (upper.contains("GROK")) return "Grok";

        if (isZImageBase(upper.toLowerCase())) return "Z Image Base";
        if (isZImageTurbo(upper.toLowerCase())) return "Z Image Turbo";

        return "Uncategorized";
    }

    private static boolean isZImageFamily(String architecture) {
        return "Z Image Turbo".equals(architecture) || "Z Image Base".equals(architecture);
    }

    private static boolean containsToken(String text, String token) {
        int idx = text.indexOf(token);
        while (idx != -1) {
            boolean startOk = (idx == 0) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean endOk = (idx + token.length() == text.length()) || !Character.isLetterOrDigit(text.charAt(idx + token.length()));
            if (startOk && endOk) return true;
            idx = text.indexOf(token, idx + 1);
        }
        return false;
    }
}
package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.StringJoiner;

/**
 * <p>The {@code ForgeUserMetadataWriter} translates Civitai {@code trainedWords} into the
 * user-metadata sidecar consumed by AUTOMATIC1111, SD WebUI Forge, and Forge Neo.</p>
 *
 * <p>Those front-ends do not read {@code .civitai.info} files — that format belongs to the
 * Civitai Helper extension. The WebUI reads exactly one file per model,
 * {@code <basename>.json}, and populates its "Activation text" field from the
 * {@code "activation text"} key. This writer produces that file so trigger words appear in
 * the LoRA card's metadata editor without any extension installed.</p>
 *
 * <p>Key Features:
 * <ul>
 *   <li><b>Non-Destructive Merge:</b> Existing keys (notes, preferred weight, description)
 *   are read and rewritten untouched; a user-authored activation text is never overwritten.</li>
 *   <li><b>Section Separator:</b> Trained words are joined with {@code ",, "}, the Civitai
 *   convention that Forge's Card Master extension splits back into selectable sections.</li>
 *   <li><b>Atomic Persistence:</b> Writes via a temporary file and an atomic move, so an
 *   interrupted run cannot truncate a user's existing metadata.</li>
 *   <li><b>Fail-Safe:</b> Malformed or unexpected existing metadata is left untouched rather
 *   than replaced.</li>
 * </ul>
 * </p>
 *
 * @see CivitaiApiClient
 */
public class ForgeUserMetadataWriter {

    private static final Logger logger = LoggerFactory.getLogger(ForgeUserMetadataWriter.class);

    private static final String ACTIVATION_TEXT_KEY = "activation text";
    private static final String SECTION_SEPARATOR = ",, ";
    private static final String METADATA_EXTENSION = ".json";

    private final ObjectMapper objectMapper;

    public ForgeUserMetadataWriter() {
        this(new ObjectMapper());
    }

    public ForgeUserMetadataWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the model's trigger words into its Forge user-metadata sidecar, unless the file
     * already carries an activation text of its own.
     *
     * @param rootNode
     *         the parsed Civitai model-version response
     * @param modelPath
     *         path to the {@code .safetensors} file (used to resolve siblings)
     * @param baseName
     *         the model filename without extension
     *
     * @return {@code true} if an activation text was written, {@code false} if there was
     * nothing to write or existing metadata was preserved
     */
    public boolean writeActivationTextIfAbsent(JsonNode rootNode, Path modelPath, String baseName) {
        return writeActivationTextIfAbsent(rootNode, modelPath, baseName, false);
    }

    /**
     * As {@link #writeActivationTextIfAbsent(JsonNode, Path, String)}, but able to evaluate the
     * outcome without touching the filesystem.
     *
     * @param isDryRun
     *         when {@code true}, every check is performed and the result reported, but nothing
     *         is written
     *
     * @return {@code true} if an activation text was written — or would have been, under a dry run
     */
    public boolean writeActivationTextIfAbsent(JsonNode rootNode, Path modelPath, String baseName, boolean isDryRun) {
        String activationText = joinTrainedWords(rootNode);
        if (activationText.isEmpty()) {
            return false;
        }

        Path metadataPath = modelPath.resolveSibling(baseName.trim() + METADATA_EXTENSION);

        ObjectNode userMetadata = readExistingMetadata(metadataPath);
        if (userMetadata == null) {
            return false;
        }

        if (!userMetadata.path(ACTIVATION_TEXT_KEY).asText("").isBlank()) {
            logger.debug("Keeping existing activation text in '{}'", metadataPath.getFileName());
            return false;
        }

        if (isDryRun) {
            logger.info("[DRY RUN] Would save trigger words for '{}'", metadataPath.getFileName());
            return true;
        }

        userMetadata.put(ACTIVATION_TEXT_KEY, activationText);
        return persist(metadataPath, userMetadata);
    }

    /**
     * @return the existing metadata object, an empty object if no file exists yet, or
     * {@code null} if the file is present but cannot be safely rewritten.
     */
    private ObjectNode readExistingMetadata(Path metadataPath) {
        if (!Files.exists(metadataPath)) {
            return objectMapper.createObjectNode();
        }

        try {
            JsonNode existing = objectMapper.readTree(metadataPath.toFile());
            if (existing instanceof ObjectNode objectNode) {
                return objectNode;
            }
            logger.warn("User metadata '{}' is not a JSON object — leaving it untouched",
                    metadataPath.getFileName());
        } catch (IOException e) {
            logger.warn("Could not parse user metadata '{}' — leaving it untouched: {}",
                    metadataPath.getFileName(), e.getMessage());
        }
        return null;
    }

    private boolean persist(Path metadataPath, ObjectNode userMetadata) {
        Path tempPath = metadataPath.resolveSibling(metadataPath.getFileName() + ".tmp");

        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), userMetadata);
            Files.move(tempPath, metadataPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Saved trigger words for '{}'", metadataPath.getFileName());
            return true;
        } catch (IOException e) {
            logger.warn("Could not write user metadata '{}': {}", metadataPath.getFileName(), e.getMessage());
            deleteQuietly(tempPath);
            return false;
        }
    }

    private static String joinTrainedWords(JsonNode rootNode) {
        JsonNode trainedWords = rootNode.path("trainedWords");
        if (!trainedWords.isArray()) {
            return "";
        }

        StringJoiner joiner = new StringJoiner(SECTION_SEPARATOR);
        for (JsonNode word : trainedWords) {
            if (!word.isTextual()) {
                continue;
            }
            String normalized = stripSeparators(word.asText());
            if (!normalized.isEmpty()) {
                joiner.add(normalized);
            }
        }
        return joiner.toString();
    }

    /**
     * Strips surrounding whitespace and commas from a single trained word. Civitai authors
     * frequently leave a trailing comma on each entry; joining those verbatim would emit
     * {@code ",,,"} runs that corrupt the section boundaries Forge extensions split on.
     */
    private static String stripSeparators(String rawWord) {
        int start = 0;
        int end = rawWord.length();

        while (start < end && isSeparator(rawWord.charAt(start))) {
            start++;
        }
        while (end > start && isSeparator(rawWord.charAt(end - 1))) {
            end--;
        }
        return rawWord.substring(start, end);
    }

    private static boolean isSeparator(char c) {
        return c == ',' || Character.isWhitespace(c);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("Failed to delete partial file '{}': {}", path.getFileName(), e.getMessage());
        }
    }
}

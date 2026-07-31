package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nilsson.lmo.util.HtmlToPlainText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.StringJoiner;

/**
 * <p>The {@code ForgeUserMetadataWriter} translates Civitai metadata — trigger words and the
 * model description — into the user-metadata sidecar consumed by AUTOMATIC1111, SD WebUI Forge,
 * and Forge Neo.</p>
 *
 * <p>Those front-ends do not read {@code .civitai.info} files — that format belongs to the
 * Civitai Helper extension. The WebUI reads exactly one file per model,
 * {@code <basename>.json}, populating its "Activation text" field from the
 * {@code "activation text"} key and its "Description" field from {@code "description"}. This
 * writer produces that file so both appear in the LoRA card's metadata editor without any
 * extension installed.</p>
 *
 * <p>Key Features:
 * <ul>
 *   <li><b>Non-Destructive Merge:</b> Existing keys (notes, preferred weight) are read and
 *   rewritten untouched, and a field the user filled in themselves is never overwritten.</li>
 *   <li><b>Section Separator:</b> Trained words are joined with {@code ",, "}, the Civitai
 *   convention that Forge's Card Master extension splits back into selectable sections.</li>
 *   <li><b>Plain Text Descriptions:</b> Civitai serves HTML, which the WebUI escapes by default;
 *   descriptions are reduced to text via {@link HtmlToPlainText}.</li>
 *   <li><b>Atomic Persistence:</b> Writes via a temporary file and an atomic move, so an
 *   interrupted run cannot truncate a user's existing metadata.</li>
 *   <li><b>Fail-Safe:</b> Malformed or unexpected existing metadata is left untouched rather
 *   than replaced.</li>
 * </ul>
 * </p>
 *
 * @see CivitaiApiClient
 * @see HtmlToPlainText
 */
public class ForgeUserMetadataWriter {

    private static final Logger logger = LoggerFactory.getLogger(ForgeUserMetadataWriter.class);

    private static final String ACTIVATION_TEXT_KEY = "activation text";
    private static final String DESCRIPTION_KEY = "description";
    private static final String SECTION_SEPARATOR = ",, ";
    private static final String METADATA_EXTENSION = ".json";

    /**
     * What a single write actually changed. Reported per field so callers can tally trigger
     * words and descriptions separately rather than collapsing both into one count.
     *
     * @param activationTextWritten
     *         whether an activation text was added
     * @param descriptionWritten
     *         whether a description was added
     */
    public record WriteOutcome(boolean activationTextWritten, boolean descriptionWritten) {

        static final WriteOutcome NOTHING = new WriteOutcome(false, false);

        public boolean wroteAnything() {
            return activationTextWritten || descriptionWritten;
        }
    }

    private final ObjectMapper objectMapper;

    public ForgeUserMetadataWriter() {
        this(new ObjectMapper());
    }

    public ForgeUserMetadataWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the model's trigger words and description into its Forge user-metadata sidecar.
     * Each field is filled only when the sidecar does not already carry one.
     *
     * @param rootNode
     *         the parsed Civitai model-version response
     * @param modelPath
     *         path to the {@code .safetensors} file (used to resolve siblings)
     * @param baseName
     *         the model filename without extension
     *
     * @return which fields were added; nothing is written when both were already present or
     * neither is available
     */
    public WriteOutcome writeUserMetadata(JsonNode rootNode, Path modelPath, String baseName) {
        return writeUserMetadata(rootNode, modelPath, baseName, false);
    }

    /**
     * As {@link #writeUserMetadata(JsonNode, Path, String)}, but able to evaluate the outcome
     * without touching the filesystem.
     *
     * @param isDryRun
     *         when {@code true}, every check is performed and the result reported, but nothing
     *         is written
     *
     * @return which fields were added — or would have been, under a dry run
     */
    public WriteOutcome writeUserMetadata(JsonNode rootNode, Path modelPath, String baseName, boolean isDryRun) {
        String activationText = joinTrainedWords(rootNode);
        String description = extractDescription(rootNode);

        if (activationText.isEmpty() && description.isEmpty()) {
            return WriteOutcome.NOTHING;
        }

        Path metadataPath = modelPath.resolveSibling(baseName.trim() + METADATA_EXTENSION);

        ObjectNode userMetadata = readExistingMetadata(metadataPath);
        if (userMetadata == null) {
            return WriteOutcome.NOTHING;
        }

        boolean addActivationText = !activationText.isEmpty() && isBlank(userMetadata, ACTIVATION_TEXT_KEY);
        boolean addDescription = !description.isEmpty() && isBlank(userMetadata, DESCRIPTION_KEY);

        if (!addActivationText && !addDescription) {
            logger.debug("Keeping existing user metadata in '{}'", metadataPath.getFileName());
            return WriteOutcome.NOTHING;
        }

        if (isDryRun) {
            logger.info("[DRY RUN] Would update user metadata for '{}'", metadataPath.getFileName());
            return new WriteOutcome(addActivationText, addDescription);
        }

        if (addActivationText) {
            userMetadata.put(ACTIVATION_TEXT_KEY, activationText);
        }
        if (addDescription) {
            userMetadata.put(DESCRIPTION_KEY, description);
        }

        return persist(metadataPath, userMetadata)
                ? new WriteOutcome(addActivationText, addDescription)
                : WriteOutcome.NOTHING;
    }

    private static boolean isBlank(ObjectNode userMetadata, String key) {
        return userMetadata.path(key).asText("").isBlank();
    }

    /**
     * Civitai carries two descriptions: one on the model and one on the individual version. The
     * model's is what the site presents as "Description", so it wins; the version's short release
     * note is used only when the model has none.
     */
    private static String extractDescription(JsonNode rootNode) {
        String modelDescription = HtmlToPlainText.convert(textOrNull(rootNode.path("model").path(DESCRIPTION_KEY)));
        if (!modelDescription.isEmpty()) {
            return modelDescription;
        }
        return HtmlToPlainText.convert(textOrNull(rootNode.path(DESCRIPTION_KEY)));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
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

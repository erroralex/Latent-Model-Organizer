package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.domain.OperationReport;
import com.nilsson.lmo.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * <p>The {@code ActivationTextBackfillService} retrofits an existing model library with the
 * trigger words it already owns. Every {@code .civitai.info} sidecar downloaded by the fetcher
 * contains a {@code trainedWords} array, but AUTOMATIC1111, Forge, and Forge Neo never read that
 * file — they read {@code <basename>.json}. This service bridges the two, purely from local
 * data.</p>
 *
 * <p>No hashing and no network calls are involved: the pass reads sidecars straight off disk,
 * which makes it fast and safe to re-run. It is idempotent, since
 * {@link ForgeUserMetadataWriter} refuses to overwrite an activation text that is already set.</p>
 *
 * @see ForgeUserMetadataWriter
 * @see OrganizationService#fetchMissingMetadata
 */
public class ActivationTextBackfillService {

    private static final Logger logger = LoggerFactory.getLogger(ActivationTextBackfillService.class);

    private static final String SIDECAR_SUFFIX = ".civitai.info";
    private static final String MODEL_EXTENSION = ".safetensors";
    private static final int MAX_SCAN_DEPTH = 4;

    private static final String STAT_SAVED = "Trigger Words Saved";
    private static final String STAT_SIMULATED = "Simulated Writes";
    private static final String STAT_ALREADY_SET = "Skipped (already set)";
    private static final String STAT_NO_WORDS = "Skipped (no trigger words)";
    private static final String STAT_MODEL_MISSING = "Skipped (model missing)";
    private static final String STAT_ERRORS = "Errors";

    private final ForgeUserMetadataWriter userMetadataWriter;
    private final ObjectMapper objectMapper;

    public ActivationTextBackfillService() {
        this(new ForgeUserMetadataWriter(), new ObjectMapper());
    }

    public ActivationTextBackfillService(ForgeUserMetadataWriter userMetadataWriter, ObjectMapper objectMapper) {
        this.userMetadataWriter = userMetadataWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * Scans for Civitai sidecars and writes their trigger words into the matching Forge
     * user-metadata files.
     *
     * @param targetDir
     *         root of the library to scan
     * @param isRecursive
     *         whether subdirectories are included
     * @param isDryRun
     *         when {@code true}, reports what would be written without touching disk
     * @param isCancelled
     *         polled between sidecars to support user cancellation
     * @param onTotalKnown
     *         invoked once with the number of sidecars found
     * @param onItemComplete
     *         invoked after each sidecar is handled
     *
     * @return a report tallying writes and each distinct skip reason
     */
    public OperationReport backfillActivationText(Path targetDir, boolean isRecursive, boolean isDryRun,
                                                  Supplier<Boolean> isCancelled, IntConsumer onTotalKnown,
                                                  Runnable onItemComplete) {
        logger.info("Starting trigger word backfill. Recursive: {}, Dry Run: {}", isRecursive, isDryRun);

        Map<String, Integer> stats = new HashMap<>();
        List<String> errors = new ArrayList<>();

        List<Path> sidecars = findSidecars(targetDir, isRecursive);

        if (isCancelled.get()) {
            return cancelledReport(stats, errors);
        }

        logger.info("Found {} Civitai sidecars to inspect.", sidecars.size());
        onTotalKnown.accept(sidecars.size());

        int processed = 0;
        for (Path sidecar : sidecars) {
            if (isCancelled.get()) {
                return cancelledReport(stats, errors);
            }
            processSidecar(sidecar, isDryRun, stats, errors);
            processed++;
            onItemComplete.run();
        }

        logger.info("Trigger word backfill completed. {} sidecar(s) inspected.", processed);

        return new OperationReport(
                isDryRun ? "Backfill simulation completed." : "Trigger word backfill completed successfully.",
                stats, errors, processed, 0);
    }

    private List<Path> findSidecars(Path targetDir, boolean isRecursive) {
        try (Stream<Path> fileStream = isRecursive ? Files.walk(targetDir, MAX_SCAN_DEPTH) : Files.list(targetDir)) {
            return fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(SIDECAR_SUFFIX))
                    .toList();
        } catch (IOException e) {
            throw new OrganizerException("Failed to scan target directory: " + targetDir, e);
        }
    }

    private void processSidecar(Path sidecar, boolean isDryRun, Map<String, Integer> stats, List<String> errors) {
        String fileName = sidecar.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - SIDECAR_SUFFIX.length());

        try {
            Path modelPath = sidecar.resolveSibling(baseName + MODEL_EXTENSION);
            if (!Files.exists(modelPath)) {
                logger.debug("No model file beside sidecar '{}' — skipping", fileName);
                increment(stats, STAT_MODEL_MISSING);
                return;
            }

            JsonNode rootNode = objectMapper.readTree(sidecar.toFile());

            if (!hasTriggerWords(rootNode)) {
                increment(stats, STAT_NO_WORDS);
                return;
            }

            if (userMetadataWriter.writeActivationTextIfAbsent(rootNode, modelPath, baseName, isDryRun)) {
                increment(stats, isDryRun ? STAT_SIMULATED : STAT_SAVED);
            } else {
                increment(stats, STAT_ALREADY_SET);
            }

        } catch (Exception e) {
            String msg = String.format("Failed to backfill trigger words for '%s': %s", fileName, e.getMessage());
            logger.error(msg);
            errors.add(msg);
            increment(stats, STAT_ERRORS);
        }
    }

    private static boolean hasTriggerWords(JsonNode rootNode) {
        JsonNode trainedWords = rootNode.path("trainedWords");
        if (!trainedWords.isArray()) {
            return false;
        }
        for (JsonNode word : trainedWords) {
            if (word.isTextual() && !word.asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private OperationReport cancelledReport(Map<String, Integer> stats, List<String> errors) {
        logger.warn("Trigger word backfill was cancelled by the user.");
        errors.add("Operation was manually cancelled by the user. Partial changes may have been applied.");
        int processed = stats.values().stream().mapToInt(Integer::intValue).sum();
        return new OperationReport("Operation Cancelled", stats, errors, processed, 0);
    }

    private static void increment(Map<String, Integer> stats, String key) {
        stats.merge(key, 1, Integer::sum);
    }
}

package com.latent.organizer.service;

import com.latent.organizer.domain.ModelMetadata;
import com.latent.organizer.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service responsible for orchestrating the file organization process.
 * <p>
 * It groups files by their base name, determines the architecture using {@link ModelAnalyzer},
 * and moves them to the appropriate target directories using Virtual Threads for high concurrency.
 */
public class OrganizationService {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationService.class);
    private final ModelAnalyzer modelAnalyzer;

    /**
     * @param modelAnalyzer The analyzer service used to identify model architectures.
     */
    public OrganizationService(ModelAnalyzer modelAnalyzer) {
        this.modelAnalyzer = modelAnalyzer;
    }

    /**
     * Organizes files from the source directory into the target directory.
     * <p>
     * Blocks until all file operations are complete.
     *
     * @param sourceDir The directory containing unorganized models.
     * @param targetDir The root directory where organized models will be moved.
     * @throws OrganizerException If the source directory cannot be read.
     */
    public void organizeModels(Path sourceDir, Path targetDir) {
        logger.info("Starting organization task. Source: {}, Target: {}", sourceDir, targetDir);

        try (Stream<Path> fileStream = Files.list(sourceDir)) {
            // Group files by base name (e.g., "model1.safetensors", "model1.png" -> "model1")
            Map<String, List<Path>> groupedFiles = fileStream
                    .filter(Files::isRegularFile)
                    .collect(Collectors.groupingBy(this::getBaseName));

            logger.info("Found {} unique model groups to process.", groupedFiles.size());

            // Use Virtual Threads for high-throughput I/O operations.
            // The try-with-resources block implicitly awaits termination of all tasks upon closing.
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                groupedFiles.forEach((baseName, files) ->
                        executor.submit(() -> processGroup(baseName, files, targetDir))
                );
            } // Thread execution blocks here until all submitted Virtual Threads complete.

        } catch (IOException e) {
            throw new OrganizerException("Failed to read source directory: " + sourceDir, e);
        }

        logger.info("Organization task completed.");
    }

    /**
     * Processes a single group of files: analyzes architecture and moves files.
     */
    private void processGroup(String baseName, List<Path> files, Path targetDir) {
        try {
            // 1. Find the .safetensors file to analyze
            Optional<Path> modelFile = files.stream()
                    .filter(p -> p.toString().endsWith(".safetensors"))
                    .findFirst();

            String architecture = "Uncategorized";

            if (modelFile.isPresent()) {
                try {
                    ModelMetadata metadata = modelAnalyzer.analyze(modelFile.get());
                    architecture = metadata.architecture();
                } catch (Exception e) {
                    logger.warn("Failed to analyze model group '{}', moving to Uncategorized. Error: {}", baseName, e.getMessage());
                }
            } else {
                logger.debug("No .safetensors file found for group '{}', checking sidecars...", baseName);
                // Fallback: If we have a .civitai.info but no .safetensors, we might still want to organize it
                // For now, defaulting to Uncategorized if main model file is missing is safer.
            }

            // 2. Prepare target directory
            if (architecture == null || architecture.trim().isEmpty()) {
                architecture = "Uncategorized";
            }
            
            Path architectureDir = targetDir.resolve(architecture);
            if (!Files.exists(architectureDir)) {
                Files.createDirectories(architectureDir);
            }

            // 3. Move all files in the group
            for (Path file : files) {
                Path targetPath = architectureDir.resolve(file.getFileName());
                // logger.debug("Moving {} to {}", file, targetPath); // Commented out to reduce noise
                Files.move(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            logger.info("Moved group '{}' to '{}'", baseName, architecture);

        } catch (IOException e) {
            logger.error("IO Error processing group '{}'", baseName, e);
            // Don't rethrow, so other groups can continue
        } catch (Exception e) {
            logger.error("Unexpected error processing group '{}'", baseName, e);
        }
    }

    /**
     * Extracts the base name of a file (ignoring the last extension).
     */
    private String getBaseName(Path path) {
        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot == -1) ? fileName : fileName.substring(0, lastDot);
    }
}

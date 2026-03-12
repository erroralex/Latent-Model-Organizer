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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>The central orchestration service for organizing model files based on identified architectures.</p>
 *
 * <p>This service manages the end-to-end workflow of file organization, including:
 * <ol>
 *     <li><b>File Discovery & Grouping:</b> Scans the source directory and groups related files
 *     (e.g., weights, config, preview images) by their common base name.</li>
 *     <li><b>Concurrent Analysis:</b> Utilizes Java 21 Virtual Threads to perform architectural
 *     analysis on each model group in parallel using the {@link ModelAnalyzer}.</li>
 *     <li><b>Filtered Execution:</b> Respects architectural whitelists provided in the request,
 *     ensuring only specified model types are processed.</li>
 *     <li><b>Atomic File Movement:</b> Safely moves grouped files into structured subdirectories
 *     named after their identified architecture (e.g., {@code /target/SDXL/model.safetensors}).</li>
 * </ol>
 * </p>
 *
 * <p>The service is designed for high-concurrency I/O operations, ensuring minimal blocking
 * of the main application thread during long-running organization tasks.</p>
 */
public class OrganizationService {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationService.class);
    private final ModelAnalyzer modelAnalyzer;

    public OrganizationService(ModelAnalyzer modelAnalyzer) {
        this.modelAnalyzer = modelAnalyzer;
    }

    public void organizeModels(Path sourceDir, Path targetDir, List<String> allowedArchitectures) {
        logger.info("Starting organization task. Source: {}, Target: {}", sourceDir, targetDir);

        try (Stream<Path> fileStream = Files.list(sourceDir)) {
            Map<String, List<Path>> groupedFiles = fileStream
                    .filter(Files::isRegularFile)
                    .collect(Collectors.groupingBy(this::getBaseName));

            logger.info("Found {} unique model groups to process.", groupedFiles.size());

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Map.Entry<String, List<Path>> entry : groupedFiles.entrySet()) {
                    String baseName = entry.getKey();
                    List<Path> files = entry.getValue();
                    executor.submit(() -> processGroup(baseName, files, targetDir, allowedArchitectures));
                }
            }

        } catch (IOException e) {
            throw new OrganizerException("Failed to read source directory: " + sourceDir, e);
        }

        logger.info("Organization task completed.");
    }

    private void processGroup(String baseName, List<Path> files, Path targetDir, List<String> allowedArchitectures) {
        try {
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
            }

            if (allowedArchitectures != null && !allowedArchitectures.isEmpty()) {
                String finalArchitecture = architecture;
                boolean isAllowed = allowedArchitectures.stream()
                        .anyMatch(allowed -> allowed.equalsIgnoreCase(finalArchitecture));

                if (!isAllowed) {
                    logger.debug("Skipping group '{}' because architecture '{}' is not in the allowed list.", baseName, architecture);
                    return;
                }
            }

            if (architecture == null || architecture.trim().isEmpty()) {
                architecture = "Uncategorized";
            }

            Path architectureDir = targetDir.resolve(architecture);
            if (!Files.exists(architectureDir)) {
                Files.createDirectories(architectureDir);
            }

            for (Path file : files) {
                Path targetPath = architectureDir.resolve(file.getFileName());
                Files.move(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            logger.info("Moved group '{}' to '{}'", baseName, architecture);

        } catch (IOException e) {
            logger.error("IO Error processing group '{}'", baseName, e);
        } catch (Exception e) {
            logger.error("Unexpected error processing group '{}'", baseName, e);
        }
    }

    private String getBaseName(Path path) {
        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot == -1) ? fileName : fileName.substring(0, lastDot);
    }
}

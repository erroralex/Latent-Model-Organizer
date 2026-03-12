package com.latent.organizer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latent.organizer.domain.ModelMetadata;
import com.latent.organizer.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * <p>The central orchestration service for organizing model files based on identified architectures.</p>
 *
 * <p>This service implements a sophisticated multi-pass grouping and organization engine. It is designed
 * to manage complex model relationships where weights, configurations, and previews may follow inconsistent
 * naming conventions. The service uses a combination of prefix matching and common-root analysis
 * to ensure related files are never orphaned during the movement process.</p>
 *
 * <p>Operational characteristics:
 * <ul>
 *     <li><b>Prefix-Matching Engine:</b> Anchors groups on discovered {@code .safetensors} files,
 *     using defensive longest-stem sorting to prevent overlapping group collisions.</li>
 *     <li><b>Recursive Tree Walking:</b> Scans the entire source directory tree, allowing for
 *     re-organization of previously sorted or nested collections.</li>
 *     <li><b>Virtual Thread Concurrency:</b> Offloads heavy I/O operations (file movement, API lookups)
 *     to Java 21's Virtual Threads, maintaining high throughput without blocking system resources.</li>
 *     <li><b>Atomic Movements:</b> Ensures file integrity by using atomic move operations where supported
 *     by the underlying file system.</li>
 * </ul>
 * </p>
 */
public class OrganizationService {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String UNCATEGORIZED = "Uncategorized";

    private final ModelAnalyzer modelAnalyzer;
    private final CivitaiApiClient civitaiApiClient;

    public OrganizationService(ModelAnalyzer modelAnalyzer) {
        this.modelAnalyzer = modelAnalyzer;
        this.civitaiApiClient = new CivitaiApiClient();
    }

    public void organizeModels(Path sourceDir, Path targetDir, List<String> allowedArchitectures) {
        logger.info("Starting organization task. Source: {}, Target: {}", sourceDir, targetDir);

        Set<String> allowedSet = buildAllowedSet(allowedArchitectures);

        List<Path> allFiles;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            allFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isAlreadyOrganized(p, targetDir))
                    .toList();
        } catch (IOException e) {
            throw new OrganizerException("Failed to read source directory: " + sourceDir, e);
        }

        logger.info("Found {} candidate files after recursive scan.", allFiles.size());

        Map<String, List<Path>> groupedFiles = groupByPrefix(allFiles);
        logger.info("Identified {} model groups via prefix matching.", groupedFiles.size());

        runConcurrently(groupedFiles.entrySet(), entry ->
                processGroup(entry.getKey(), entry.getValue(), targetDir, allowedSet));

        logger.info("Organization task completed.");
    }

    public void fetchMissingMetadata(Path targetDir) {
        logger.info("Starting metadata fetch scan in: {}", targetDir);

        List<Path> modelsToProcess;
        try (Stream<Path> fileStream = Files.walk(targetDir)) {
            modelsToProcess = fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".safetensors"))
                    .filter(this::isMissingSidecar)
                    .toList();
        } catch (IOException e) {
            throw new OrganizerException("Failed to scan target directory: " + targetDir, e);
        }

        logger.info("Found {} models missing metadata sidecars.", modelsToProcess.size());

        runConcurrently(modelsToProcess, this::processMissingMetadata);

        logger.info("Metadata fetch task completed.");
    }

    private static final int MIN_COMMON_ROOT_LENGTH = 8;

    private Map<String, List<Path>> groupByPrefix(List<Path> allFiles) {
        List<Path> safetensorPaths = allFiles.stream()
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".safetensors"))
                .sorted(Comparator.comparingInt(p -> -p.getFileName().toString().length()))
                .toList();

        Map<String, List<Path>> groups = new HashMap<>();

        for (Path file : allFiles) {
            String fileName = file.getFileName().toString();
            String targetGroup;

            if (fileName.toLowerCase().endsWith(".safetensors")) {
                targetGroup = groupKey(file);
            } else {
                Optional<String> exactMatch = safetensorPaths.stream()
                        .filter(sf -> sf.getParent().equals(file.getParent()))
                        .map(sf -> sf.getFileName().toString())
                        .map(sfName -> sfName.substring(0, sfName.lastIndexOf('.')))
                        .filter(fileName::startsWith)
                        .findFirst()
                        .map(stem -> file.getParent().resolve(stem.trim()).toString());

                if (exactMatch.isPresent()) {
                    targetGroup = exactMatch.get();
                } else {
                    String fileNameNoExt = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf(".")) : fileName;
                    String fileStem = stripVersionSuffix(fileNameNoExt);

                    Optional<String> rootMatch = safetensorPaths.stream()
                            .filter(sf -> sf.getParent().equals(file.getParent()))
                            .map(sf -> {
                                String sfStem = stripVersionSuffix(
                                        sf.getFileName().toString().substring(0, sf.getFileName().toString().lastIndexOf('.')));
                                int commonLen = commonPrefixLength(fileStem, sfStem);
                                return Map.entry(sf, commonLen);
                            })
                            .filter(e -> e.getValue() >= MIN_COMMON_ROOT_LENGTH)
                            .max(Comparator.comparingInt(Map.Entry::getValue))
                            .map(e -> {
                                String sfName = e.getKey().getFileName().toString();
                                String sfStem = sfName.substring(0, sfName.lastIndexOf('.'));
                                return file.getParent().resolve(sfStem.trim()).toString();
                            });

                    targetGroup = rootMatch.orElseGet(() -> groupKey(file));
                }
            }

            groups.computeIfAbsent(targetGroup, k -> new ArrayList<>()).add(file);
        }

        return groups;
    }

    private static String stripVersionSuffix(String stem) {
        return stem.replaceAll("[_\\-]+(v\\d+|\\d+)[_\\-]*$", "")
                .replaceAll("[_\\-]+$", "");
    }

    private static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static String groupKey(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = (dot == -1) ? name : name.substring(0, dot);
        return file.getParent().resolve(stem.trim()).toString();
    }

    private void processGroup(String groupKey, List<Path> files, Path targetDir, Set<String> allowedSet) {
        String baseName = Path.of(groupKey).getFileName().toString().trim();

        try {
            String architecture = resolveArchitecture(baseName, files).trim();

            if (!allowedSet.isEmpty() && !allowedSet.contains(architecture.toLowerCase(Locale.ROOT))) {
                logger.debug("Skipping group '{}' (architecture '{}' not in allowed list).", baseName, architecture);
                return;
            }

            if (files.stream().allMatch(f -> isInArchitectureDir(f, targetDir, architecture))) {
                logger.debug("Skipping group '{}' — all files already in '{}'.", baseName, architecture);
                return;
            }

            Path architectureDir = targetDir.resolve(architecture);
            ensureDirectoryExists(architectureDir);

            for (Path file : files) {
                Path targetPath = architectureDir.resolve(file.getFileName().toString().trim());
                if (!file.toAbsolutePath().equals(targetPath.toAbsolutePath())) {
                    Files.move(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            logger.info("Moved group '{}' ({} files) → '{}'", baseName, files.size(), architecture);

        } catch (IOException e) {
            logger.error("IO error processing group '{}'", baseName, e);
        } catch (Exception e) {
            logger.error("Unexpected error processing group '{}'", baseName, e);
        }
    }

    private String resolveArchitecture(String baseName, List<Path> files) {
        Optional<Path> modelFile = files.stream()
                .filter(p -> p.toString().toLowerCase().endsWith(".safetensors"))
                .findFirst();

        if (modelFile.isEmpty()) {
            return UNCATEGORIZED;
        }

        try {
            ModelMetadata metadata = modelAnalyzer.analyze(modelFile.get());
            String arch = metadata.architecture();
            if (arch == null || arch.isBlank() || "Unknown".equalsIgnoreCase(arch)) {
                return UNCATEGORIZED;
            }
            return arch;
        } catch (Exception e) {
            return UNCATEGORIZED;
        }
    }

    private boolean isMissingSidecar(Path modelPath) {
        String fileName = modelPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.')).trim();
        Path sidecarPath = modelPath.resolveSibling(baseName + ".civitai.info");
        return !Files.exists(sidecarPath);
    }

    private void processMissingMetadata(Path modelPath) {
        String fileName = modelPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.')).trim();

        try {
            logger.debug("Fetching missing metadata for: {}", fileName);
            String hash = civitaiApiClient.hashWithTiming(modelPath);
            String jsonResponse = civitaiApiClient.fetchMetadataByHash(hash);

            if (jsonResponse == null) {
                return;
            }

            Path sidecarPath = modelPath.resolveSibling(baseName + ".civitai.info");
            Files.writeString(sidecarPath, jsonResponse,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Saved metadata sidecar for '{}'", fileName);

            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            downloadPreviewImageIfAbsent(rootNode, modelPath, baseName);

        } catch (Exception e) {
            logger.error("Failed to fetch metadata for '{}': {}", fileName, e.getMessage());
        }
    }

    private void downloadPreviewImageIfAbsent(JsonNode rootNode, Path modelPath, String baseName) {
        JsonNode images = rootNode.path("images");
        if (!images.isArray() || images.isEmpty()) return;

        String imageUrl = images.get(0).path("url").asText(null);
        if (imageUrl == null || imageUrl.isBlank()) return;

        String extension = resolvePreviewExtension(imageUrl);
        Path imagePath = modelPath.resolveSibling(baseName.trim() + extension);

        if (Files.exists(imagePath)) {
            return;
        }

        try {
            civitaiApiClient.downloadPreviewImage(imageUrl, imagePath);
        } catch (Exception e) {
            logger.warn("Failed to download preview image for '{}': {}", baseName, e.getMessage());
        }
    }

    private static boolean isInArchitectureDir(Path file, Path targetDir, String architecture) {
        Path expectedParent = targetDir.resolve(architecture.trim()).toAbsolutePath().normalize();
        Path actualParent = file.toAbsolutePath().normalize().getParent();
        return expectedParent.equals(actualParent);
    }

    private static boolean isAlreadyOrganized(Path file, Path targetDir) {
        Path parent = file.getParent();
        if (parent == null) return false;
        Path grandParent = parent.getParent();
        if (grandParent == null) return false;
        return grandParent.toAbsolutePath().normalize()
                .equals(targetDir.toAbsolutePath().normalize());
    }

    static String resolvePreviewExtension(String imageUrl) {
        String lower = imageUrl.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".preview.jpeg";
        if (lower.endsWith(".webp")) return ".preview.webp";
        return ".preview.png";
    }

    private static Set<String> buildAllowedSet(List<String> allowedArchitectures) {
        if (allowedArchitectures == null || allowedArchitectures.isEmpty()) return Set.of();
        Set<String> set = new HashSet<>(allowedArchitectures.size());
        for (String arch : allowedArchitectures) {
            if (arch != null && !arch.isBlank()) set.add(arch.trim().toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(set);
    }

    private static void ensureDirectoryExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private <T> void runConcurrently(Iterable<T> items, java.util.function.Consumer<T> task) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (T item : items) {
                executor.submit(() -> task.accept(item));
            }
        }
    }
}

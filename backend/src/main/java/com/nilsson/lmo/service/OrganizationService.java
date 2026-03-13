package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.domain.ModelMetadata;
import com.nilsson.lmo.domain.OperationReport;
import com.nilsson.lmo.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>The {@code OrganizationService} is the central orchestration layer for managing, classifying,
 * and relocating machine learning model files. It implements a sophisticated multi-pass grouping
 * engine designed to handle complex model structures and inconsistent naming conventions.</p>
 *
 * <p>This service coordinates between the {@link ModelAnalyzer} for architectural identification
 * and the {@link CivitaiApiClient} for external metadata enrichment. It leverages Java 21
 * Virtual Threads to parallelize I/O-intensive file operations and network requests, ensuring
 * high throughput during large-scale library reorganizations.</p>
 *
 * <p>Key Capabilities:
 * <ul>
 *   <li><b>Intelligent Grouping:</b> Associates weights, configs, and previews using prefix matching
 *   and stem analysis to ensure atomic file movements.</li>
 *   <li><b>Scalable I/O:</b> Uses Virtual Threads for concurrent processing of model groups,
 *   minimizing execution time for thousands of files.</li>
 *   <li><b>Recursive Discovery:</b> Scans directory trees with configurable depth to find and
 *   re-organize nested collections.</li>
 *   <li><b>Non-Destructive Simulation:</b> Provides a comprehensive "dry run" mode to preview
 *   organizational changes without affecting the filesystem.</li>
 * </ul>
 * </p>
 */
public class OrganizationService {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String UNCATEGORIZED = "Uncategorized";
    private static final int MAX_SCAN_DEPTH = 4;

    private final ModelAnalyzer modelAnalyzer;
    private final CivitaiApiClient civitaiApiClient;

    public OrganizationService(ModelAnalyzer modelAnalyzer) {
        this.modelAnalyzer = modelAnalyzer;
        this.civitaiApiClient = new CivitaiApiClient();
    }

    public OperationReport organizeModels(Path sourceDir, Path targetDir, List<String> allowedArchitectures, boolean isRecursive, boolean isDryRun) {
        logger.info("Starting organization task. Recursive: {}, Dry Run: {}", isRecursive, isDryRun);
        Set<String> allowedSet = buildAllowedSet(allowedArchitectures);

        ConcurrentHashMap<String, AtomicInteger> stats = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();

        try (Stream<Path> fileStream = isRecursive ? Files.walk(sourceDir, MAX_SCAN_DEPTH) : Files.list(sourceDir)) {
            List<Path> allFiles = fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isAlreadyOrganized(p, targetDir))
                    .toList();

            int totalFiles = allFiles.size();
            logger.info("Found {} candidate files.", totalFiles);

            Map<String, List<Path>> groupedFiles = groupByPrefix(allFiles);
            logger.info("Identified {} model groups via prefix matching.", groupedFiles.size());

            List<Future<?>> futures = new ArrayList<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Map.Entry<String, List<Path>> entry : groupedFiles.entrySet()) {
                    futures.add(executor.submit(() -> processGroup(entry.getKey(), entry.getValue(), targetDir, allowedSet, isDryRun, stats, errors)));
                }
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    logger.error("Task failed unexpectedly: {}", e.getCause().getMessage(), e.getCause());
                    errors.add("Task failure: " + e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            logger.info("Organization task completed.");

            Map<String, Integer> finalStats = stats.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

            int totalProcessed = finalStats.values().stream().mapToInt(Integer::intValue).sum();
            int totalUncategorized = finalStats.getOrDefault(UNCATEGORIZED, 0);

            return new OperationReport("Organization completed successfully.", finalStats, new ArrayList<>(errors), totalProcessed, totalUncategorized);

        } catch (IOException e) {
            throw new OrganizerException("Failed to read source directory: " + sourceDir, e);
        }
    }

    public OperationReport fetchMissingMetadata(Path targetDir, boolean isRecursive, boolean isDryRun) {
        logger.info("Starting metadata fetch scan. Recursive: {}, Dry Run: {}", isRecursive, isDryRun);

        ConcurrentHashMap<String, AtomicInteger> stats = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();

        try (Stream<Path> fileStream = isRecursive ? Files.walk(targetDir, MAX_SCAN_DEPTH) : Files.list(targetDir)) {
            List<Path> modelsToProcess = fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".safetensors"))
                    .filter(this::isMissingSidecar)
                    .toList();

            int totalProcessed = modelsToProcess.size();
            logger.info("Found {} models missing metadata sidecars.", totalProcessed);

            List<Future<?>> fetchFutures = new ArrayList<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Path modelPath : modelsToProcess) {
                    fetchFutures.add(executor.submit(() -> processMissingMetadata(modelPath, isDryRun, stats, errors)));
                }
            }
            for (Future<?> f : fetchFutures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    logger.error("Fetch task failed unexpectedly: {}", e.getCause().getMessage(), e.getCause());
                    errors.add("Fetch task failure: " + e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            logger.info("Metadata fetch task completed.");

            Map<String, Integer> finalStats = stats.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

            int totalUncategorized = finalStats.getOrDefault("Not Found on Civitai", 0);

            return new OperationReport("Metadata fetch completed successfully.", finalStats, new ArrayList<>(errors), totalProcessed, totalUncategorized);

        } catch (IOException e) {
            throw new OrganizerException("Failed to scan target directory: " + targetDir, e);
        }
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

    private void processGroup(String groupKey, List<Path> files, Path targetDir, Set<String> allowedSet, boolean isDryRun,
                              ConcurrentHashMap<String, AtomicInteger> stats, CopyOnWriteArrayList<String> errors) {
        String baseName = Path.of(groupKey).getFileName().toString().trim();
        String dryRunPrefix = isDryRun ? "[DRY RUN] " : "";

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
            if (!isDryRun) {
                ensureDirectoryExists(architectureDir);
            }

            for (Path file : files) {
                Path targetPath = architectureDir.resolve(file.getFileName().toString().trim());
                if (!file.toAbsolutePath().equals(targetPath.toAbsolutePath())) {
                    if (!isDryRun) {
                        Files.move(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            logger.info("{}{}", dryRunPrefix, String.format("Moved group '%s' (%d files) → '%s'", baseName, files.size(), architecture));

            stats.computeIfAbsent(architecture, k -> new AtomicInteger(0)).incrementAndGet();

        } catch (IOException e) {
            String msg = String.format("IO error processing group '%s': %s", baseName, e.getMessage());
            logger.error(msg, e);
            errors.add(msg);
        } catch (Exception e) {
            String msg = String.format("Unexpected error processing group '%s': %s", baseName, e.getMessage());
            logger.error(msg, e);
            errors.add(msg);
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

    private void processMissingMetadata(Path modelPath, boolean isDryRun, ConcurrentHashMap<String, AtomicInteger> stats, CopyOnWriteArrayList<String> errors) {
        String fileName = modelPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.')).trim();

        try {
            if (isDryRun) {
                logger.info("[DRY RUN] Would fetch metadata for: {}", fileName);
                stats.computeIfAbsent("Simulated Fetches", k -> new AtomicInteger(0)).incrementAndGet();
                return;
            }

            logger.debug("Fetching missing metadata for: {}", fileName);
            String hash = civitaiApiClient.hashWithTiming(modelPath);
            String jsonResponse = civitaiApiClient.fetchMetadataByHash(hash);

            if (jsonResponse == null) {
                stats.computeIfAbsent("Not Found on Civitai", k -> new AtomicInteger(0)).incrementAndGet();
                return;
            }

            Path sidecarPath = modelPath.resolveSibling(baseName + ".civitai.info");
            Files.writeString(sidecarPath, jsonResponse,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            logger.info("Saved metadata sidecar for '{}'", fileName);
            stats.computeIfAbsent("Metadata Retrieved", k -> new AtomicInteger(0)).incrementAndGet();

            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            downloadPreviewImageIfAbsent(rootNode, modelPath, baseName);

        } catch (Exception e) {
            String msg = String.format("Failed to fetch metadata for '%s': %s", fileName, e.getMessage());
            logger.error(msg);
            errors.add(msg);
            stats.computeIfAbsent("Errors", k -> new AtomicInteger(0)).incrementAndGet();
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
}

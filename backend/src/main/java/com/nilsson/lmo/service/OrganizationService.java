package com.nilsson.lmo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.domain.ModelMetadata;
import com.nilsson.lmo.domain.OperationReport;
import com.nilsson.lmo.domain.UndoManifest;
import com.nilsson.lmo.domain.UndoManifest.MoveRecord;
import com.nilsson.lmo.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>The {@code OrganizationService} is the core engine of the Latent Model Organizer, responsible for the
 * systematic classification, grouping, and relocation of machine learning model files. It is engineered
 * to handle massive libraries by treating related files (weights, configs, previews) as atomic "model units".</p>
 *
 * <h3>Core Capabilities:</h3>
 * <ul>
 *   <li><b>Advanced Prefix Analysis:</b> Implements a sophisticated grouping algorithm that uses stem
 *   analysis and version-suffix stripping to associate disparate files (e.g., .safetensors, .yaml, .png)
 *   belonging to the same model.</li>
 *   <li><b>Virtual Thread Parallelism:</b> Utilizes Java 21's {@code Virtual Threads} to maximize I/O
 *   throughput during filesystem scanning and file relocation, ensuring high performance even on
 *   high-latency storage.</li>
 *   <li><b>Atomic Relocation & Collision Safety:</b> Employs a non-destructive relocation strategy with
 *   automatic OS-style renaming (e.g., "model (1)") to prevent data loss. It ensures all members of a
 *   model group are renamed consistently to maintain sidecar integrity.</li>
 *   <li><b>Transactional Undo System:</b> Generates a detailed {@link UndoManifest} for every operation,
 *   allowing users to revert organizational changes with bit-perfect accuracy using a parallelized
 *   reverse-move execution.</li>
 *   <li><b>Metadata Enrichment:</b> Integrates with external providers (e.g., Civitai) to fetch missing
 *   model hashes, metadata, and preview images, filling gaps in local data to improve classification.</li>
 * </ul>
 *
 * <p>The service is designed as a stateless, thread-safe component, making it suitable for high-concurrency
 * environments where multiple organization tasks might be queued or requested simultaneously.</p>
 *
 * @see ModelAnalyzer
 * @see CivitaiApiClient
 * @see UndoManifest
 */
public class OrganizationService {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String UNCATEGORIZED = "Uncategorized";
    private static final int MAX_SCAN_DEPTH = 4;

    public static final String UNDO_MANIFEST_FILENAME = "undo-manifest.json";

    private final ModelAnalyzer modelAnalyzer;
    private final CivitaiApiClient civitaiApiClient;

    public OrganizationService(ModelAnalyzer modelAnalyzer) {
        this.modelAnalyzer = modelAnalyzer;
        this.civitaiApiClient = new CivitaiApiClient();
    }

    public OperationReport organizeModels(Path sourceDir, Path targetDir, List<String> allowedArchitectures,
                                          boolean isRecursive, boolean isDryRun, Supplier<Boolean> isCancelled,
                                          IntConsumer onTotalKnown, Runnable onGroupComplete) {
        logger.info("Starting organization task. Recursive: {}, Dry Run: {}", isRecursive, isDryRun);
        Set<String> allowedSet = buildAllowedSet(allowedArchitectures);

        ConcurrentHashMap<String, AtomicInteger> stats = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        ConcurrentLinkedQueue<MoveRecord> moveLog = new ConcurrentLinkedQueue<>();
        ConcurrentHashMap<Path, Boolean> createdDirs = new ConcurrentHashMap<>();

        try (Stream<Path> fileStream = isRecursive ? Files.walk(sourceDir, MAX_SCAN_DEPTH) : Files.list(sourceDir)) {
            List<Path> allFiles = fileStream
                    .filter(p -> !isCancelled.get()) // Short-circuit scan
                    .filter(Files::isRegularFile)
                    .filter(p -> !isAlreadyOrganized(p, targetDir))
                    .toList();

            if (isCancelled.get()) {
                return createCancelledReport(stats, errors);
            }

            int totalFiles = allFiles.size();
            logger.info("Found {} candidate files.", totalFiles);

            Map<String, List<Path>> groupedFiles = groupByPrefix(allFiles);
            logger.info("Identified {} model groups via prefix matching.", groupedFiles.size());

            onTotalKnown.accept(groupedFiles.size());

            List<Future<?>> futures = new ArrayList<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Map.Entry<String, List<Path>> entry : groupedFiles.entrySet()) {
                    if (isCancelled.get()) break;
                    futures.add(executor.submit(() -> {
                        if (isCancelled.get()) return;
                        processGroup(entry.getKey(), entry.getValue(), targetDir, allowedSet, isDryRun, stats, errors, moveLog, createdDirs);
                        onGroupComplete.run();
                    }));
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

            if (isCancelled.get()) {
                return createCancelledReport(stats, errors);
            }

            if (!isDryRun && !moveLog.isEmpty()) {
                writeUndoManifest(targetDir, moveLog);
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

    public OperationReport undoLastOrganize(Path targetDir) {
        Path manifestPath = targetDir.resolve(UNDO_MANIFEST_FILENAME);

        if (!Files.exists(manifestPath)) {
            throw new OrganizerException("No undo manifest found in: " + targetDir + ". Nothing to undo.");
        }

        UndoManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestPath.toFile(), UndoManifest.class);
        } catch (IOException e) {
            throw new OrganizerException("Failed to read undo manifest: " + e.getMessage(), e);
        }

        logger.info("Undoing sort from {}. Reversing {} file moves.", manifest.timestamp(), manifest.moveCount());

        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
        AtomicInteger restored = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>(manifest.moves().size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (MoveRecord record : manifest.moves()) {
                futures.add(executor.submit(() -> reverseMove(record, restored, errors)));
            }
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                logger.error("Undo task failed: {}", e.getCause().getMessage(), e.getCause());
                errors.add("Undo task failure: " + e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        pruneEmptyArchitectureDirs(targetDir);

        try {
            Files.deleteIfExists(manifestPath);
        } catch (IOException e) {
            logger.warn("Could not delete undo manifest after successful undo: {}", e.getMessage());
        }

        int restoredCount = restored.get();
        logger.info("Undo complete. Restored {} files, {} errors.", restoredCount, errors.size());

        Map<String, Integer> finalStats = Map.of("Restored", restoredCount, "Errors", errors.size());
        return new OperationReport(
                String.format("Undo complete. %d file(s) restored to their original locations.", restoredCount),
                finalStats, new ArrayList<>(errors), restoredCount, 0);
    }

    public OperationReport fetchMissingMetadata(Path targetDir, boolean isRecursive, boolean isDryRun,
                                                Supplier<Boolean> isCancelled, IntConsumer onTotalKnown, Runnable onItemComplete) {
        logger.info("Starting metadata fetch scan. Recursive: {}, Dry Run: {}", isRecursive, isDryRun);

        ConcurrentHashMap<String, AtomicInteger> stats = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();

        try (Stream<Path> fileStream = isRecursive ? Files.walk(targetDir, MAX_SCAN_DEPTH) : Files.list(targetDir)) {
            List<Path> modelsToProcess = fileStream
                    .filter(p -> !isCancelled.get()) // Short-circuit scan
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".safetensors"))
                    .filter(this::isMissingSidecar)
                    .toList();

            if (isCancelled.get()) {
                return createCancelledReport(stats, errors);
            }

            int totalProcessed = modelsToProcess.size();
            logger.info("Found {} models missing metadata sidecars.", totalProcessed);

            onTotalKnown.accept(totalProcessed);

            List<Future<?>> fetchFutures = new ArrayList<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Path modelPath : modelsToProcess) {
                    if (isCancelled.get()) break;
                    fetchFutures.add(executor.submit(() -> {
                        if (isCancelled.get()) return;
                        processMissingMetadata(modelPath, isDryRun, stats, errors);
                        onItemComplete.run();
                    }));
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

            if (isCancelled.get()) {
                return createCancelledReport(stats, errors);
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

    private OperationReport createCancelledReport(ConcurrentHashMap<String, AtomicInteger> stats, CopyOnWriteArrayList<String> errors) {
        logger.warn("Operation was cancelled by the user.");
        errors.add("Operation was manually cancelled by the user. Partial changes may have been applied.");
        Map<String, Integer> finalStats = stats.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
        int totalProcessed = finalStats.values().stream().mapToInt(Integer::intValue).sum();
        int totalUncategorized = finalStats.getOrDefault(UNCATEGORIZED, 0);
        return new OperationReport("Operation Cancelled", finalStats, new ArrayList<>(errors), totalProcessed, totalUncategorized);
    }

    private void writeUndoManifest(Path targetDir, ConcurrentLinkedQueue<MoveRecord> moveLog) {
        List<MoveRecord> moves = new ArrayList<>(moveLog);
        UndoManifest manifest = new UndoManifest(Instant.now().toString(), moves.size(), moves);

        Path manifestPath = targetDir.resolve(UNDO_MANIFEST_FILENAME);
        Path tempPath = targetDir.resolve(UNDO_MANIFEST_FILENAME + ".tmp");

        try {
            ensureDirectoryExists(targetDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), manifest);
            Files.move(tempPath, manifestPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Undo manifest written: {} moves recorded at '{}'", moves.size(), manifestPath);
        } catch (IOException e) {
            logger.warn("Could not write undo manifest (undo will be unavailable): {}", e.getMessage());
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
        }
    }

    private void reverseMove(MoveRecord record, AtomicInteger restored, CopyOnWriteArrayList<String> errors) {
        Path src = Path.of(record.to());
        Path dst = Path.of(record.from());

        if (!Files.exists(src)) {
            String msg = String.format("Undo skipped — file no longer exists at '%s'", src);
            logger.warn(msg);
            errors.add(msg);
            return;
        }

        try {
            ensureDirectoryExists(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            restored.incrementAndGet();
            logger.debug("Restored '{}' → '{}'", src.getFileName(), dst.getParent());
        } catch (IOException e) {
            String msg = String.format("Failed to restore '%s': %s", src, e.getMessage());
            logger.error(msg, e);
            errors.add(msg);
        }
    }

    private void pruneEmptyArchitectureDirs(Path targetDir) {
        try (Stream<Path> dirs = Files.list(targetDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().equals("."))
                    .forEach(dir -> {
                        try {
                            if (isDirectoryEmpty(dir)) {
                                Files.delete(dir);
                                logger.debug("Pruned empty directory: {}", dir.getFileName());
                            }
                        } catch (IOException e) {
                            logger.info("Could not prune directory '{}': {}", dir.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            logger.warn("Could not list target directory for pruning: {}", e.getMessage());
        }
    }

    private static boolean isDirectoryEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
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
                              ConcurrentHashMap<String, AtomicInteger> stats, CopyOnWriteArrayList<String> errors,
                              ConcurrentLinkedQueue<MoveRecord> moveLog, ConcurrentHashMap<Path, Boolean> createdDirs) {
        String originalBaseName = Path.of(groupKey).getFileName().toString().trim();
        String dryRunPrefix = isDryRun ? "[DRY RUN] " : "";

        try {
            String architecture = resolveArchitecture(files).trim();

            if (!allowedSet.isEmpty() && !allowedSet.contains(architecture.toLowerCase(Locale.ROOT))) {
                logger.debug("Skipping group '{}' (architecture '{}' not in allowed list).", originalBaseName, architecture);
                return;
            }

            if (files.stream().allMatch(f -> isInArchitectureDir(f, targetDir, architecture))) {
                logger.debug("Skipping group '{}' — all files already in '{}'.", originalBaseName, architecture);
                return;
            }

            Path architectureDir = targetDir.resolve(architecture);
            if (!isDryRun) {
                createdDirs.computeIfAbsent(architectureDir, p -> {
                    try {
                        Files.createDirectories(p);
                        return Boolean.TRUE;
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }

            String targetBaseName = resolveCollisionFreeBaseName(architectureDir, originalBaseName, files);

            if (!originalBaseName.equals(targetBaseName)) {
                logger.info("Collision detected for group '{}'. Renaming to '{}' to prevent data loss.", originalBaseName, targetBaseName);
            }

            for (Path file : files) {
                String originalFileName = file.getFileName().toString();
                String extension = getExtension(originalFileName, originalBaseName);

                String newFileName = targetBaseName + extension;
                Path targetPath = architectureDir.resolve(newFileName);

                if (!file.toAbsolutePath().equals(targetPath.toAbsolutePath())) {
                    if (!isDryRun) {
                        Files.move(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        moveLog.offer(new MoveRecord(
                                file.toAbsolutePath().toString(),
                                targetPath.toAbsolutePath().toString()
                        ));
                    }
                }
            }

            logger.info("{}{}", dryRunPrefix, String.format("Moved group '%s' (%d files) → '%s'", targetBaseName, files.size(), architecture));
            stats.computeIfAbsent(architecture, k -> new AtomicInteger(0)).incrementAndGet();

        } catch (IOException e) {
            String msg = String.format("IO error processing group '%s': %s", originalBaseName, e.getMessage());
            logger.error(msg, e);
        } catch (Exception e) {
            String msg = String.format("Unexpected error processing group '%s': %s", originalBaseName, e.getMessage());
            logger.error(msg, e);
            errors.add(msg);
        }
    }

    private String getExtension(String originalFileName, String baseName) {
        int dotIndex = originalFileName.indexOf('.', baseName.length());
        if (dotIndex != -1) {
            return originalFileName.substring(dotIndex);
        } else if (originalFileName.length() > baseName.length()) {
             return originalFileName.substring(baseName.length());
        }
        return "";
    }

    private String resolveCollisionFreeBaseName(Path architectureDir, String originalBaseName, List<Path> files) {
        String currentBaseName = originalBaseName;
        int counter = 1;

        while (true) {
            boolean collision = false;
            for (Path file : files) {
                String originalFileName = file.getFileName().toString();
                String extension = getExtension(originalFileName, originalBaseName);

                String testFileName = currentBaseName + extension;
                Path testPath = architectureDir.resolve(testFileName);

                // If the file already exists at the target, and it's NOT the exact same file
                // we are trying to move (e.g., it's already in the correct architecture dir),
                // we have a collision.
                if (Files.exists(testPath) && !file.toAbsolutePath().normalize().equals(testPath.toAbsolutePath().normalize())) {
                    collision = true;
                    break;
                }
            }

            if (!collision) {
                return currentBaseName;
            }

            currentBaseName = originalBaseName + " (" + counter + ")";
            counter++;
        }
    }

    private String resolveArchitecture(List<Path> files) {
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

        JsonNode urlNode = images.get(0).path("url");
        String imageUrl = urlNode.isMissingNode() ? null : urlNode.asText();
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

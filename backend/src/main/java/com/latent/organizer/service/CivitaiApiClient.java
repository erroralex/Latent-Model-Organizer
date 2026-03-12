package com.latent.organizer.service;

import com.latent.organizer.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>A high-performance HTTP client for interacting with the Civitai REST API.</p>
 *
 * <p>This client is specifically engineered for Java 21+, utilizing {@link HttpClient}
 * with a virtual thread executor to handle concurrent API requests without blocking
 * operating system threads. It provides methods for retrieving model metadata by file
 * hash and for downloading associated preview images.</p>
 *
 * <p>Key features:
 * <ul>
 *     <li>HTTP/2 with multiplexed requests.</li>
 *     <li>Automatic redirection handling.</li>
 *     <li>Retry with exponential backoff for transient failures (429, 503) only —
 *         404 (not found) and other terminal statuses never retry.</li>
 *     <li>Partial-file cleanup on failed image downloads.</li>
 *     <li>Timed hash logging so slow SHA-256 passes on large checkpoints are visible.</li>
 * </ul>
 * </p>
 *
 * <p>Instances should be reused across the application lifetime and closed via
 * {@link #close()} or try-with-resources when no longer needed.</p>
 */
public class CivitaiApiClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CivitaiApiClient.class);

    private static final String BASE_URL          = "https://civitai.com/api/v1/model-versions/by-hash/";
    private static final String USER_AGENT        = "LatentModelOrganizer/1.0 (Java 21)";
    private static final Duration CONNECT_TIMEOUT  = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT  = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Maximum number of attempts for <em>retryable</em> HTTP errors (429, 503) and
     * transient IOExceptions. 404 and other terminal statuses are never retried.
     */
    private static final int  MAX_RETRIES    = 3;

    /**
     * Base delay for exponential backoff: {@code BASE_BACKOFF_MS * 2^attempt}.
     * Attempt 0 → 1 s, attempt 1 → 2 s, attempt 2 → 4 s.
     */
    private static final long BASE_BACKOFF_MS = 1_000L;

    /**
     * Log a warning when SHA-256 hashing takes longer than this threshold.
     * Large checkpoints (fp8, GGUF, nvfp4) can easily take 5-10+ seconds.
     */
    private static final long HASH_WARN_THRESHOLD_MS = 3_000L;

    private final ExecutorService executor;
    private final HttpClient httpClient;

    public CivitaiApiClient() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches model metadata JSON from the Civitai API by SHA-256 hash.
     *
     * <p>Only HTTP 429 (rate limit) and 503 (server overload) are retried with
     * exponential backoff. HTTP 404 (not on Civitai) and all other non-200 statuses
     * are terminal and never retried.</p>
     *
     * @param sha256Hash hex-encoded SHA-256 hash of the model file
     * @return raw JSON response body, or {@code null} if the model is not found (404)
     * @throws IllegalArgumentException if the hash is null or blank
     * @throws OrganizerException       on unrecoverable HTTP or network errors
     */
    public String fetchMetadataByHash(String sha256Hash) {
        if (sha256Hash == null || sha256Hash.isBlank()) {
            throw new IllegalArgumentException("Hash cannot be null or empty");
        }

        String targetUrl = BASE_URL + sha256Hash;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build();

        logger.debug("Querying Civitai API: {}", targetUrl);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                switch (status) {
                    case 200 -> {
                        logger.debug("Successfully fetched metadata for hash: {}", sha256Hash);
                        return response.body();
                    }
                    case 404 -> {
                        // Model is simply not on Civitai — not a transient error, never retry.
                        logger.warn("Model hash not found on Civitai: {}", sha256Hash);
                        return null;
                    }
                    case 429, 503 -> {
                        // Transient — rate limited or server overloaded. Retry with backoff.
                        long backoffMs = BASE_BACKOFF_MS * (1L << attempt);
                        logger.warn("Civitai API returned {} (attempt {}/{}). Retrying in {}ms...",
                                status, attempt + 1, MAX_RETRIES, backoffMs);
                        sleep(backoffMs);
                    }
                    default -> {
                        // Any other status (400, 401, 500, etc.) is terminal — don't retry.
                        throw new OrganizerException(
                                "Civitai API returned unexpected status " + status + " for hash: " + sha256Hash);
                    }
                }

            } catch (IOException e) {
                // Network-level failure — retry up to MAX_RETRIES, then give up.
                if (attempt < MAX_RETRIES - 1) {
                    long backoffMs = BASE_BACKOFF_MS * (1L << attempt);
                    logger.warn("Network error on attempt {}/{} for hash '{}': {}. Retrying in {}ms...",
                            attempt + 1, MAX_RETRIES, sha256Hash, e.getMessage(), backoffMs);
                    sleep(backoffMs);
                } else {
                    throw new OrganizerException(
                            "Failed to fetch metadata from Civitai API after " + MAX_RETRIES + " attempts", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OrganizerException("Metadata fetch interrupted for hash: " + sha256Hash, e);
            }
        }

        throw new OrganizerException("Civitai API fetch exhausted all retries for hash: " + sha256Hash);
    }

    /**
     * Downloads a preview image from the given URL to the specified destination path.
     *
     * <p>If the download fails or the server returns a non-200 status, any partially
     * written destination file is deleted to avoid leaving corrupt files on disk.</p>
     *
     * @param imageUrl    fully-qualified URL of the preview image
     * @param destination local path to write the image to
     */
    public void downloadPreviewImage(String imageUrl, Path destination) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .build();

        logger.debug("Downloading preview image: {} → {}", imageUrl, destination.getFileName());

        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
            int status = response.statusCode();

            if (status == 200) {
                logger.info("Downloaded preview image: {}", destination.getFileName());
            } else {
                logger.warn("Preview image download failed (HTTP {}): {}", status, imageUrl);
                deleteQuietly(destination);
            }

        } catch (IOException e) {
            logger.warn("IO error downloading preview image '{}': {}", imageUrl, e.getMessage());
            deleteQuietly(destination);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Preview image download interrupted: {}", imageUrl);
            deleteQuietly(destination);
        }
    }

    /**
     * Computes the SHA-256 hash of {@code modelPath} and logs a warning if hashing
     * takes longer than {@value #HASH_WARN_THRESHOLD_MS}ms. Large checkpoint files
     * (fp8, GGUF, nvfp4) can take many seconds to hash, which is expected behaviour,
     * but having it visible in logs avoids confusion about why the fetch appears slow.
     *
     * <p>This is a convenience wrapper around {@link com.latent.organizer.util.HashUtil}
     * so callers don't need to implement timing themselves.</p>
     *
     * @param modelPath path to the model file to hash
     * @return hex-encoded SHA-256 hash string
     * @throws IOException if the file cannot be read
     */
    public String hashWithTiming(Path modelPath) throws IOException {
        String fileName = modelPath.getFileName().toString();
        long sizeMb = -1;
        try {
            sizeMb = Files.size(modelPath) / (1024 * 1024);
        } catch (IOException ignored) { }

        logger.debug("Computing SHA-256 for '{}' ({}MB)...", fileName, sizeMb >= 0 ? sizeMb : "?");
        long start = System.currentTimeMillis();

        String hash = com.latent.organizer.util.HashUtil.calculateSHA256(modelPath);

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= HASH_WARN_THRESHOLD_MS) {
            logger.warn("SHA-256 hashing of '{}' ({}MB) took {}ms — large file, this is expected",
                    fileName, sizeMb >= 0 ? sizeMb : "?", elapsed);
        } else {
            logger.debug("SHA-256 for '{}' computed in {}ms", fileName, elapsed);
        }

        return hash;
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    /**
     * Shuts down the underlying virtual-thread executor and releases HTTP client
     * resources. Should be called when the application is done using this client.
     */
    @Override
    public void close() {
        executor.shutdown();
        logger.debug("CivitaiApiClient closed.");
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Deletes a file silently, logging a warning if deletion fails.
     * Used to clean up partial downloads on error.
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            logger.warn("Failed to delete partial file '{}': {}", path.getFileName(), ex.getMessage());
        }
    }

    /**
     * Sleeps for the given duration. Converts {@link InterruptedException} into a
     * thread interrupt + early return so callers don't need to handle it inline.
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
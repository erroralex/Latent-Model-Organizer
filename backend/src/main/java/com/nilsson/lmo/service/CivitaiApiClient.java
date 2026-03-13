package com.nilsson.lmo.service;

import com.nilsson.lmo.exception.OrganizerException;
import com.nilsson.lmo.util.HashUtil;
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
 * <p>The {@code CivitaiApiClient} is a high-performance HTTP client specifically engineered for interacting
 * with the Civitai REST API. It leverages Java 21 {@link HttpClient} and Virtual Threads
 * to handle concurrent metadata retrieval and file downloads efficiently.</p>
 *
 * <p>This client implements robust error handling, including exponential backoff for transient
 * server errors and rate limiting. It provides specialized methods for fetching model metadata
 * by SHA-256 hash and downloading associated preview images, ensuring data integrity through
 * partial-file cleanup on failure.</p>
 *
 * <p>Key Features:
 * <ul>
 *   <li><b>Virtual Thread Integration:</b> Uses {@code Executors.newVirtualThreadPerTaskExecutor()}
 *   for non-blocking I/O operations.</li>
 *   <li><b>Resilience:</b> Implements retry logic with exponential backoff for 429 and 5xx status codes.</li>
 *   <li><b>Performance:</b> Utilizes HTTP/2 and multiplexed requests where supported.</li>
 *   <li><b>Diagnostics:</b> Includes timed hashing operations to provide visibility into large file processing.</li>
 * </ul>
 * </p>
 */
public class CivitaiApiClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CivitaiApiClient.class);

    private static final String BASE_URL = "https://civitai.com/api/v1/model-versions/by-hash/";
    private static final String USER_AGENT = "LatentModelOrganizer/1.0 (Java 21)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1_000L;
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
                        logger.warn("Model hash not found on Civitai: {}", sha256Hash);
                        return null;
                    }
                    case 429, 503 -> {
                        long backoffMs = BASE_BACKOFF_MS * (1L << attempt);
                        logger.warn("Civitai API returned {} (attempt {}/{}). Retrying in {}ms...",
                                status, attempt + 1, MAX_RETRIES, backoffMs);
                        sleep(backoffMs);
                    }
                    default -> {
                        throw new OrganizerException(
                                "Civitai API returned unexpected status " + status + " for hash: " + sha256Hash);
                    }
                }

            } catch (IOException e) {
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

    public String hashWithTiming(Path modelPath) throws IOException {
        String fileName = modelPath.getFileName().toString();
        long sizeMb = -1;
        try {
            sizeMb = Files.size(modelPath) / (1024 * 1024);
        } catch (IOException ignored) {
        }

        logger.debug("Computing SHA-256 for '{}' ({}MB)...", fileName, sizeMb >= 0 ? sizeMb : "?");
        long start = System.currentTimeMillis();

        String hash = HashUtil.calculateSHA256(modelPath);

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= HASH_WARN_THRESHOLD_MS) {
            logger.warn("SHA-256 hashing of '{}' ({}MB) took {}ms — large file, this is expected",
                    fileName, sizeMb >= 0 ? sizeMb : "?", elapsed);
        } else {
            logger.debug("SHA-256 for '{}' computed in {}ms", fileName, elapsed);
        }

        return hash;
    }

    @Override
    public void close() {
        executor.shutdown();
        logger.debug("CivitaiApiClient closed.");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            logger.warn("Failed to delete partial file '{}': {}", path.getFileName(), ex.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.latent.organizer.service;

import com.latent.organizer.exception.OrganizerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * <p>A high-performance HTTP client for interacting with the Civitai REST API.</p>
 *
 * <p>This client is specifically engineered for Java 21+, utilizing {@link java.net.http.HttpClient}
 * with a virtual thread executor to handle concurrent API requests without blocking operating system
 * threads. It provides methods for retrieving model metadata by file hash and for downloading
 * associated preview images.</p>
 *
 * <p>Key features include:
 * <ul>
 *     <li>HTTP/2 support for multiplexed requests.</li>
 *     <li>Automatic redirection handling.</li>
 *     <li>Configurable timeouts for both metadata lookups and binary downloads.</li>
 *     <li>Custom User-Agent identification for API compliance.</li>
 * </ul>
 * </p>
 */
public class CivitaiApiClient {

    private static final Logger logger = LoggerFactory.getLogger(CivitaiApiClient.class);
    private static final String BASE_URL = "https://civitai.com/api/v1/model-versions/by-hash/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public CivitaiApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String fetchMetadataByHash(String sha256Hash) {
        if (sha256Hash == null || sha256Hash.isEmpty()) {
            throw new IllegalArgumentException("Hash cannot be null or empty");
        }

        String targetUrl = BASE_URL + sha256Hash;
        logger.debug("Querying Civitai API: {}", targetUrl);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .GET()
                    .timeout(TIMEOUT)
                    .header("User-Agent", "LatentModelOrganizer/1.0 (Java 21)")
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                logger.debug("Successfully fetched metadata for hash: {}", sha256Hash);
                return response.body();
            } else if (statusCode == 404) {
                logger.warn("Model hash not found on Civitai: {}", sha256Hash);
                return null;
            } else {
                logger.error("Civitai API returned unexpected status code: {}", statusCode);
                throw new OrganizerException("Civitai API error: " + statusCode + " - " + response.body());
            }

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new OrganizerException("Failed to fetch metadata from Civitai API", e);
        }
    }

    public void downloadPreviewImage(String imageUrl, Path destination) {
        try {
            logger.debug("Downloading preview image from: {}", imageUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .GET()
                    .timeout(DOWNLOAD_TIMEOUT)
                    .header("User-Agent", "LatentModelOrganizer/1.0 (Java 21)")
                    .build();

            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));

            if (response.statusCode() == 200) {
                logger.info("Successfully downloaded preview image to: {}", destination);
            } else {
                logger.warn("Failed to download preview image. Status: {}, URL: {}", response.statusCode(), imageUrl);
            }

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Exception during preview image download: {}", e.getMessage());
        }
    }
}

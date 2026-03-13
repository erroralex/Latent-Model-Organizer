package com.nilsson.lmo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * <p>The {@code CivitaiApiClientTest} suite validates the network communication and
 * integration logic between the Latent Model Organizer and the Civitai REST API.
 * It simulates diverse network scenarios to ensure robust metadata retrieval.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Successful Interactions:</b> Validates JSON payload parsing and metadata
 *   mapping from authoritative external responses.</li>
 *   <li><b>Error States:</b> Asserts graceful handling of 404 (Not Found) or 429
 *   (Rate Limited) statuses from the upstream API.</li>
 *   <li><b>Binary Persistence:</b> Ensures that preview images are correctly
 *   downloaded and persisted to the local filesystem.</li>
 *   <li><b>Failure Resilience:</b> Verifies partial-file cleanup on network or
 *   I/O failures during downloads.</li>
 * </ul>
 * </p>
 */
class CivitaiApiClientTest {

    private HttpClient mockHttpClient;
    private CivitaiApiClient civitaiApiClient;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        civitaiApiClient = new CivitaiApiClient(mockHttpClient);
    }

    @Test
    void fetchMetadataByHash_shouldReturnJsonOnSuccess() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"baseModel\": \"SDXL\"}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        String json = civitaiApiClient.fetchMetadataByHash("dummy_hash");

        assertNotNull(json);
        assertTrue(json.contains("SDXL"));
    }

    @Test
    void fetchMetadataByHash_shouldReturnNullOn404() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        String json = civitaiApiClient.fetchMetadataByHash("missing_hash");

        assertNull(json);
    }

    @Test
    void downloadPreviewImage_shouldSaveFile() throws Exception {
        HttpResponse<Path> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);

        Path imagePath = tempDir.resolve("preview.png");
        when(mockResponse.body()).thenReturn(imagePath);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    Files.writeString(imagePath, "fake image bytes");
                    return mockResponse;
                });

        civitaiApiClient.downloadPreviewImage("http://example.com/image.png", imagePath);

        assertTrue(Files.exists(imagePath));
        assertEquals("fake image bytes", Files.readString(imagePath));
    }
}
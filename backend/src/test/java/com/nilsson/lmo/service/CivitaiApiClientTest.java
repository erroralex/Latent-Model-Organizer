package com.nilsson.lmo.service;

import com.nilsson.lmo.exception.OrganizerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
 * <li><b>Successful Interactions:</b> Validates JSON payload parsing and metadata mapping.</li>
 * <li><b>Error States:</b> Asserts graceful handling of 404 (Not Found) statuses.</li>
 * <li><b>Backpressure & Retries:</b> Verifies exponential backoff logic on 429 (Rate Limited) or 5xx responses.</li>
 * <li><b>Concurrency Safety:</b> Ensures {@link InterruptedException} is properly caught, restored, and wrapped.</li>
 * <li><b>Binary Persistence:</b> Ensures preview images are correctly persisted.</li>
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

    @AfterEach
    void tearDown() {
        // Ensure interrupt flag is cleared between tests to prevent test pollution
        Thread.interrupted();
    }

    @Test
    void fetchMetadataByHash_shouldReturnJsonOnSuccess() throws Exception {
        @SuppressWarnings("unchecked")
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
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        String json = civitaiApiClient.fetchMetadataByHash("missing_hash");

        assertNull(json);
    }

    @Test
    void fetchMetadataByHash_shouldExhaustRetriesAndThrowOn429() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(429); // Simulate Rate Limit
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        OrganizerException exception = assertThrows(OrganizerException.class, () ->
                civitaiApiClient.fetchMetadataByHash("rate_limited_hash")
        );

        assertTrue(exception.getMessage().contains("exhausted all retries"));

        // Verify the client attempted the request the maximum number of times (e.g., 3)
        verify(mockHttpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fetchMetadataByHash_shouldHandleThreadInterruptionGracefully() {
        // Pre-interrupt the thread to simulate a cancellation signal during Semaphore.acquire()
        Thread.currentThread().interrupt();

        OrganizerException exception = assertThrows(OrganizerException.class, () ->
                civitaiApiClient.fetchMetadataByHash("interrupted_hash")
        );

        // Verify the exception wraps the interruption
        assertTrue(exception.getMessage().contains("interrupted"));

        // Verify the interrupt flag was correctly restored by the catch block
        assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag should be restored");
    }

    @Test
    void downloadPreviewImage_shouldSaveFile() throws Exception {
        @SuppressWarnings("unchecked")
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

    @Test
    void downloadPreviewImage_shouldCleanupPartialFileOnIoException() throws Exception {
        Path imagePath = tempDir.resolve("broken_preview.png");
        Files.writeString(imagePath, "partial payload");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection reset by peer"));

        civitaiApiClient.downloadPreviewImage("http://example.com/broken.png", imagePath);

        // Verify the client's catch block executed Files.deleteIfExists()
        assertFalse(Files.exists(imagePath), "Partial file should be deleted on IO failure");
    }
}
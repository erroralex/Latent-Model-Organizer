package com.nilsson.lmo.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * <p>The {@code LogStreamHandlerTest} suite validates the Server-Sent Events (SSE)
 * gateway for real-time log propagation. It tests the protocol-level implementation
 * for client-facing log streaming.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Protocol Compliance:</b> Asserts that the handler correctly upgrades the
 *   HTTP connection to the {@code text/event-stream} format.</li>
 *   <li><b>Header Enforcement:</b> Verifies the presence of requisite SSE headers
 *   including {@code Content-Type}, {@code Cache-Control}, and {@code Connection}.</li>
 *   <li><b>Access Control:</b> Validates CORS headers for cross-origin log
 *   monitoring.</li>
 *   <li><b>Request Filtering:</b> Ensures only valid GET requests can establish
 *   the stream while rejecting unsupported methods (e.g., POST).</li>
 * </ul>
 * </p>
 */
class LogStreamHandlerTest {

    private LogStreamHandler handler;

    @Mock
    private HttpExchange mockExchange;

    @Mock
    private Headers mockHeaders;

    private ByteArrayOutputStream outputStream;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new LogStreamHandler();
        outputStream = new ByteArrayOutputStream();

        when(mockExchange.getResponseHeaders()).thenReturn(mockHeaders);
        when(mockExchange.getResponseBody()).thenReturn(outputStream);
    }

    @Test
    void handle_shouldSetSSEHeadersAndReturn200() throws IOException {
        when(mockExchange.getRequestMethod()).thenReturn("GET");

        Thread handlerThread = new Thread(() -> {
            try {
                handler.handle(mockExchange);
            } catch (IOException e) {
            }
        });

        handlerThread.start();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        handlerThread.interrupt();

        verify(mockHeaders).add("Content-Type", "text/event-stream");
        verify(mockHeaders).add("Cache-Control", "no-cache");
        verify(mockHeaders).add("Connection", "keep-alive");
        verify(mockHeaders).add("Access-Control-Allow-Origin", "*");

        verify(mockExchange).sendResponseHeaders(eq(200), eq(0L));
    }

    @Test
    void handle_shouldRejectNonGetRequests() throws IOException {
        when(mockExchange.getRequestMethod()).thenReturn("POST");

        handler.handle(mockExchange);

        verify(mockExchange).sendResponseHeaders(eq(405), eq(-1L));
        verify(mockExchange, never()).getResponseBody();
    }
}
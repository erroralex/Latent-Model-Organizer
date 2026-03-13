package com.nilsson.lmo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.api.LogStreamHandler;
import com.nilsson.lmo.domain.FetchRequest;
import com.nilsson.lmo.domain.OperationReport;
import com.nilsson.lmo.domain.OrganizationRequest;
import com.nilsson.lmo.exception.OrganizerException;
import com.nilsson.lmo.service.ModelAnalyzer;
import com.nilsson.lmo.service.OrganizationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * <h1>LmoApplication</h1>
 * <p>
 * The central entry point for the Latent Model Organizer Backend application.
 * This class orchestrates the initialization of core services and provides a high-performance
 * HTTP server implementation for managing machine learning model file systems.
 * </p>
 *
 * <h2>Architecture Overview</h2>
 * <p>
 * The application leverages a lightweight {@link HttpServer} configured with a
 * <b>Virtual Thread Per Task Executor</b> (Project Loom), ensuring that each incoming request
 * is handled by a lightweight thread. This architecture allows the system to remain highly
 * responsive during I/O-bound operations like directory scanning, file hashing, and
 * external API communication with Civitai.
 * </p>
 *
 * <h2>Core Functionalities</h2>
 * <ul>
 *   <li><b>Model Organization:</b> Categorizes and moves model files based on identified architectures via {@code /api/organize}.</li>
 *   <li><b>Metadata Retrieval:</b> Scans for missing sidecar files and fetches data from external providers via {@code /api/fetch}.</li>
 *   <li><b>Real-time Monitoring:</b> Streams system logs to clients using Server-Sent Events (SSE) via {@code /api/logs}.</li>
 *   <li><b>Process Management:</b> Handles graceful termination of the JVM via {@code /api/shutdown}.</li>
 * </ul>
 *
 * <h2>Implementation Details</h2>
 * <p>
 * The application uses Jackson for JSON serialization/deserialization and SLF4J/Logback for
 * logging. CORS headers are automatically applied to all responses to support browser-based
 * frontend clients.
 * </p>
 *
 * @see OrganizationService
 * @see LogStreamHandler
 */
public class LmoApplication {

    private static final Logger logger = LoggerFactory.getLogger(LmoApplication.class);
    private static final int PORT = 8080;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        try {
            ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
            OrganizationService organizationService = new OrganizationService(modelAnalyzer);

            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/api/organize", new OrganizeHandler(organizationService));
            server.createContext("/api/fetch", new FetchHandler(organizationService));
            server.createContext("/api/logs", new LogStreamHandler());
            server.createContext("/api/shutdown", new ShutdownHandler(server));

            server.start();
            logger.info("Latent Model Organizer Backend started on port {}", PORT);
            logger.info("Ready to accept requests at http://localhost:{}", PORT);

        } catch (IOException e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }

    static class OrganizeHandler implements HttpHandler {
        private final OrganizationService organizationService;

        public OrganizeHandler(OrganizationService organizationService) {
            this.organizationService = organizationService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            try (InputStream requestBody = exchange.getRequestBody()) {
                OrganizationRequest request = objectMapper.readValue(requestBody, OrganizationRequest.class);
                logger.info("Received organization request: {}", request);

                if (request.sourceDirectory() == null || request.targetDirectory() == null) {
                    sendError(exchange, 400, "Source and Target directories are required.");
                    return;
                }

                OperationReport report = organizationService.organizeModels(
                        Paths.get(request.sourceDirectory()),
                        Paths.get(request.targetDirectory()),
                        request.allowedArchitectures() != null ? request.allowedArchitectures() : Collections.emptyList(),
                        request.isRecursive(),
                        request.isDryRun()
                );
                sendJson(exchange, 200, report);
            } catch (OrganizerException e) {
                logger.error("Business logic error", e);
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                logger.error("Internal server error", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    static class FetchHandler implements HttpHandler {
        private final OrganizationService organizationService;

        public FetchHandler(OrganizationService organizationService) {
            this.organizationService = organizationService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            try (InputStream requestBody = exchange.getRequestBody()) {
                FetchRequest request = objectMapper.readValue(requestBody, FetchRequest.class);
                logger.info("Received fetch request: {}", request);

                if (request.targetDirectory() == null) {
                    sendError(exchange, 400, "Target directory is required.");
                    return;
                }

                OperationReport report = organizationService.fetchMissingMetadata(
                        Paths.get(request.targetDirectory()),
                        request.isRecursive(),
                        request.isDryRun()
                );
                sendJson(exchange, 200, report);
            } catch (OrganizerException e) {
                logger.error("Business logic error during fetch", e);
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                logger.error("Internal server error during fetch", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    static class ShutdownHandler implements HttpHandler {
        private final HttpServer server;

        public ShutdownHandler(HttpServer server) {
            this.server = server;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            logger.info("Received shutdown signal. Initiating graceful termination sequence.");

            // Spawn a virtual thread to halt the JVM to bypass deadlocks with active SSE streams
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                logger.info("JVM exiting immediately via Runtime.halt(0).");
                Runtime.getRuntime().halt(0);
            });

            try {
                sendJson(exchange, 200, Map.of("status", "shutting down"));
            } catch (IOException e) {
                logger.warn("Client disconnected before shutdown response could be sent. Proceeding with shutdown.");
            }
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendJson(exchange, statusCode, Map.of("status", "error", "message", message));
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Object response) throws IOException {
        byte[] responseBytes = objectMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}

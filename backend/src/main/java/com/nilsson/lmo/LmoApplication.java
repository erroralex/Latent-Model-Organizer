package com.nilsson.lmo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.api.LogStreamHandler;
import com.nilsson.lmo.domain.FetchRequest;
import com.nilsson.lmo.domain.OperationReport;
import com.nilsson.lmo.domain.OrganizationRequest;
import com.nilsson.lmo.domain.UndoRequest;
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
 * <p>The {@code LmoApplication} serves as the central orchestration entry point for the
 * Latent Model Organizer Backend. It bootstraps a high-performance HTTP server
 * specifically designed to handle the complex, I/O-intensive task of managing
 * massive machine learning model libraries.</p>
 *
 * <p>Architectural Highlights:
 * <ul>
 *   <li><b>Project Loom Integration:</b> Utilizes Java 21 Virtual Threads (via {@code newVirtualThreadPerTaskExecutor()})
 *   to provide exceptional throughput during parallelized file moves and network requests without blocking
 *   precious OS threads.</li>
 *   <li><b>Restful API Interface:</b> Exposes endpoints for organization, undo, metadata retrieval,
 *   streaming logging (SSE), and graceful shutdown.</li>
 *   <li><b>Undo/Redo Support:</b> Coordinates with {@link OrganizationService} to manage persistent
 *   undo manifests, allowing for robust filesystem state management.</li>
 *   <li><b>Cross-Origin Resource Sharing (CORS):</b> Implements a flexible CORS policy to facilitate
 *   seamless integration with modern frontend frameworks.</li>
 * </ul>
 * </p>
 *
 * <p>API Endpoints:
 * <ul>
 *   <li>{@code POST /api/organize} - Categorizes and relocates models based on architectural heuristics.</li>
 *   <li>{@code POST /api/undo}     - Reverses the most recent organizational run using a persistent manifest.</li>
 *   <li>{@code POST /api/fetch}    - Retrieves missing sidecar metadata and preview images from external APIs.</li>
 *   <li>{@code GET  /api/logs}     - Server-Sent Events stream for real-time operation logging.</li>
 *   <li>{@code POST /api/shutdown} - Initiates a graceful termination of the JVM.</li>
 * </ul>
 * </p>
 *
 * @see OrganizationService
 * @see ModelAnalyzer
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
            server.createContext("/api/undo", new UndoHandler(organizationService));
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

    static class UndoHandler implements HttpHandler {
        private final OrganizationService organizationService;

        public UndoHandler(OrganizationService organizationService) {
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
                UndoRequest request = objectMapper.readValue(requestBody, UndoRequest.class);
                logger.info("Received undo request for target: {}", request.targetDirectory());

                if (request.targetDirectory() == null || request.targetDirectory().isBlank()) {
                    sendError(exchange, 400, "targetDirectory is required.");
                    return;
                }

                OperationReport report = organizationService.undoLastOrganize(
                        Paths.get(request.targetDirectory()));
                sendJson(exchange, 200, report);

            } catch (OrganizerException e) {
                logger.error("Undo business logic error", e);
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                logger.error("Internal server error during undo", e);
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
                logger.warn("Client disconnected before shutdown response could be sent.");
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
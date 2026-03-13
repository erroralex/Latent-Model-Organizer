package com.latent.organizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.latent.organizer.api.LogStreamHandler;
import com.latent.organizer.domain.FetchRequest;
import com.latent.organizer.domain.OperationReport;
import com.latent.organizer.domain.OrganizationRequest;
import com.latent.organizer.exception.OrganizerException;
import com.latent.organizer.service.ModelAnalyzer;
import com.latent.organizer.service.OrganizationService;
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
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * <p>The {@code OrganizerApplication} serves as the primary entry point and orchestration layer for the
 * Latent Model Organizer Backend. It initializes the core services and manages a lightweight
 * {@link HttpServer} to handle RESTful interactions.</p>
 *
 * <p>The system is designed for high-performance machine learning model management, providing endpoints
 * for model organization, metadata retrieval, and real-time log streaming via SSE. It leverages
 * Java 21 Virtual Threads to handle concurrent API requests efficiently without taxing system resources.</p>
 *
 * <p>Key Endpoints:
 * <ul>
 *   <li>{@code /api/organize}: Triggers the model classification and movement logic.</li>
 *   <li>{@code /api/fetch}: Initiates metadata discovery from external sources like Civitai.</li>
 *   <li>{@code /api/logs}: Provides a Server-Sent Events stream of system logs.</li>
 *   <li>{@code /api/shutdown}: Gracefully terminates the application service.</li>
 * </ul>
 * </p>
 */
public class OrganizerApplication {

    private static final Logger logger = LoggerFactory.getLogger(OrganizerApplication.class);
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

            try {
                InputStream requestBody = exchange.getRequestBody();
                OrganizationRequest request = objectMapper.readValue(requestBody, OrganizationRequest.class);
                logger.info("Received organization request: {}", request);

                if (request.sourceDirectory() == null || request.targetDirectory() == null) {
                    sendError(exchange, 400, "Source and Target directories are required.");
                    return;
                }

                OperationReport report = organizationService.organizeModels(
                        Paths.get(request.sourceDirectory()),
                        Paths.get(request.targetDirectory()),
                        request.allowedArchitectures(),
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

            try {
                InputStream requestBody = exchange.getRequestBody();
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

            logger.info("Received shutdown signal.");
            sendJson(exchange, 200, Map.of("status", "shutting down"));

            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                logger.info("Stopping HTTP Server...");
                server.stop(0);
                System.exit(0);
            });
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

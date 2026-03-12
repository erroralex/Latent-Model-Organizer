package com.latent.organizer;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * <p>The entry point for the Latent Model Organizer Backend application.</p>
 *
 * <p>This application provides a lightweight REST API for organizing machine learning model files
 * (specifically those used in latent diffusion models) based on their metadata and architecture.
 * It utilizes the built-in Java {@link com.sun.net.httpserver.HttpServer} to minimize external dependencies
 * while leveraging Java 21's Virtual Threads for high-performance, non-blocking I/O operations.</p>
 *
 * <p>The server exposes two primary endpoints:
 * <ul>
 *     <li>{@code /api/organize}: Orchestrates the analysis and movement of model files.</li>
 *     <li>{@code /api/shutdown}: Provides a mechanism for graceful server termination.</li>
 * </ul>
 * </p>
 *
 * <p>The application follows a clean architectural approach, delegating business logic to specialized
 * services while the main class handles server lifecycle and dependency wiring.</p>
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
            server.createContext("/api/shutdown", new ShutdownHandler(server));

            server.start();
            logger.info("Latent Model Organizer Backend started on port {}", PORT);
            logger.info("Ready to accept requests at http://localhost:{}/api/organize", PORT);

        } catch (IOException e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }

    /**
     * <p>Internal handler for processing model organization requests.</p>
     *
     * <p>This handler manages the HTTP lifecycle for the {@code /api/organize} endpoint,
     * including CORS header management, request validation, and JSON deserialization.
     * It delegates the actual file organization logic to the {@link OrganizationService}.</p>
     */
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

                Path sourcePath = Paths.get(request.sourceDirectory());
                Path targetPath = Paths.get(request.targetDirectory());

                organizationService.organizeModels(sourcePath, targetPath, request.allowedArchitectures());

                sendSuccess(exchange, "Organization completed successfully.");

            } catch (OrganizerException e) {
                logger.error("Business logic error", e);
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                logger.error("Internal server error", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    /**
     * <p>Internal handler for triggering a graceful application shutdown.</p>
     *
     * <p>When a authorized POST request is received, it initiates a delayed shutdown
     * sequence in a separate Virtual Thread, allowing the response to be sent back
     * to the client before the server stops.</p>
     */
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
            Map<String, String> response = Map.of("status", "shutting down");
            sendJson(exchange, 200, response);

            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // Ignore
                }
                logger.info("Stopping HTTP Server...");
                server.stop(0);
                logger.info("JVM exiting.");
                System.exit(0);
            });
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendSuccess(HttpExchange exchange, String message) throws IOException {
        Map<String, String> response = Map.of(
                "status", "success",
                "message", message
        );
        sendJson(exchange, 200, response);
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, String> response = Map.of(
                "status", "error",
                "message", message
        );
        sendJson(exchange, statusCode, response);
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

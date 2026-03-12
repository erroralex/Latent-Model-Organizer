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
 * Main entry point for the Latent Model Organizer Backend.
 * <p>
 * This class configures and starts a lightweight HTTP server using Java's built-in
 * {@link com.sun.net.httpserver.HttpServer}. It wires up the services and exposes
 * the REST API endpoints.
 */
public class OrganizerApplication {

    private static final Logger logger = LoggerFactory.getLogger(OrganizerApplication.class);
    private static final int PORT = 8080;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        try {
            // 1. Initialize Services (Dependency Injection)
            ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
            OrganizationService organizationService = new OrganizationService(modelAnalyzer);

            // 2. Configure HTTP Server
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            
            // Critical: Use Virtual Threads for handling HTTP requests high-concurrency
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            // 3. Register Endpoints
            server.createContext("/api/organize", new OrganizeHandler(organizationService));
            server.createContext("/api/shutdown", new ShutdownHandler(server));

            // 4. Start Server
            server.start();
            logger.info("Latent Model Organizer Backend started on port {}", PORT);
            logger.info("Ready to accept requests at http://localhost:{}/api/organize", PORT);

        } catch (IOException e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }

    /**
     * Handler for the /api/organize endpoint.
     */
    static class OrganizeHandler implements HttpHandler {

        private final OrganizationService organizationService;

        public OrganizeHandler(OrganizationService organizationService) {
            this.organizationService = organizationService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handle CORS for all requests
            addCorsHeaders(exchange);

            // Handle Pre-flight requests
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Only allow POST
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            try {
                // Read Request Body
                InputStream requestBody = exchange.getRequestBody();
                OrganizationRequest request = objectMapper.readValue(requestBody, OrganizationRequest.class);

                logger.info("Received organization request: {}", request);

                if (request.sourceDirectory() == null || request.targetDirectory() == null) {
                    sendError(exchange, 400, "Source and Target directories are required.");
                    return;
                }

                Path sourcePath = Paths.get(request.sourceDirectory());
                Path targetPath = Paths.get(request.targetDirectory());

                // Execute Business Logic
                organizationService.organizeModels(sourcePath, targetPath);

                // Send Success Response
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
     * Handler for graceful shutdown of the server.
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
            
            // Initiate shutdown in a separate Virtual Thread to allow response to complete
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

    // Shared helper methods

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

package com.latent.organizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.latent.organizer.api.LogStreamHandler;
import com.latent.organizer.domain.FetchRequest;
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
 * <p>The main entry point for the Latent Model Organizer Backend application.</p>
 *
 * <p>This application serves as a lightweight orchestration engine for managing and organizing large-scale
 * machine learning model collections. It exposes a specialized REST API that facilitates automatic
 * architecture identification, multi-pass file grouping, and real-time activity monitoring.</p>
 *
 * <p>Key architectural components:
 * <ul>
 *     <li><b>Java HttpServer:</b> Utilizes the built-in, low-overhead {@link com.sun.net.httpserver.HttpServer}
 *     to provide reliable API endpoints without the weight of a full application server.</li>
 *     <li><b>Project Loom (Virtual Threads):</b> Leverages {@code Executors.newVirtualThreadPerTaskExecutor()}
 *     to handle I/O-bound requests and file system operations with elite high-concurrency efficiency.</li>
 *     <li><b>SSE Logging:</b> Implements a native streaming bridge to push internal SLF4J logs directly
 *     to the frontend console via Server-Sent Events.</li>
 * </ul>
 * </p>
 *
 * <p>The backend follows a domain-driven structure, delegating complex analysis to the {@link ModelAnalyzer}
 * and file system orchestration to the {@link OrganizationService}.</p>
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

                organizationService.organizeModels(
                        Paths.get(request.sourceDirectory()),
                        Paths.get(request.targetDirectory()),
                        request.allowedArchitectures()
                );
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

                organizationService.fetchMissingMetadata(Paths.get(request.targetDirectory()));
                sendSuccess(exchange, "Metadata fetch completed successfully.");
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

    private static void sendSuccess(HttpExchange exchange, String message) throws IOException {
        sendJson(exchange, 200, Map.of("status", "success", "message", message));
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

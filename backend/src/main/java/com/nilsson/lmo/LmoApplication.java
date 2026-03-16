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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>The {@code LmoApplication} serves as the central orchestration entry point for the
 * Latent Model Organizer Backend. It bootstraps a high-performance HTTP server
 * specifically designed to handle the complex, I/O-intensive task of managing
 * massive machine learning model libraries.</p>
 *
 * <p>Architectural Highlights:
 * <ul>
 *   <li><b>Ephemeral Port Allocation:</b> Binds to port {@code 0} so the OS assigns the
 *   first available port at runtime, eliminating {@code BindException} crash-loops when
 *   port 8080 is already in use. The resolved port is signalled to the Electron main
 *   process via a {@code LMO_PORT=} line on {@code stdout} (primary, lowest latency) AND
 *   written to a {@code .lmo-port} file in the OS temp directory as a reliable fallback
 *   IPC mechanism.</li>
 *   <li><b>Project Loom Integration:</b> Utilises Java 21 Virtual Threads (via
 *   {@code newVirtualThreadPerTaskExecutor()}) to provide exceptional throughput during
 *   parallelised file moves and network requests without blocking OS threads.</li>
 *   <li><b>Restful API Interface:</b> Exposes endpoints for organisation, undo, metadata
 *   retrieval, streaming logging (SSE), and graceful shutdown.</li>
 *   <li><b>Undo/Redo Support:</b> Coordinates with {@link OrganizationService} to manage
 *   persistent undo manifests, allowing robust filesystem state management.</li>
 *   <li><b>Cross-Origin Resource Sharing (CORS):</b> Implements a flexible CORS policy
 *   to facilitate seamless integration with modern frontend frameworks.</li>
 * </ul>
 * </p>
 *
 * <p>API Endpoints:
 * <ul>
 *   <li>{@code POST /api/organize} - Categorises and relocates models based on architectural heuristics.</li>
 *   <li>{@code POST /api/undo}     - Reverses the most recent organisational run using a persistent manifest.</li>
 *   <li>{@code POST /api/fetch}    - Retrieves missing sidecar metadata and preview images from external APIs.</li>
 *   <li>{@code GET  /api/logs}     - Server-Sent Events stream for real-time operation logging.</li>
 *   <li>{@code POST /api/progress} - Provides real-time progress updates for ongoing operations.</li>
 *   <li>{@code POST /api/shutdown} - Initiates a graceful termination of the JVM.</li>
 * </ul>
 * </p>
 *
 * @see OrganizationService
 * @see ModelAnalyzer
 */
public class LmoApplication {

    private static final Logger logger = LoggerFactory.getLogger(LmoApplication.class);

    /**
     * Canonical path of the ephemeral port-hint file.
     * <p>
     * Located in the OS temp directory ({@code java.io.tmpdir}) so both the JVM and
     * the Electron main process can agree on the path without any additional
     * configuration. The file contains a single integer string (the assigned port)
     * and is deleted automatically by a JVM shutdown hook on exit.
     */
    static final String PORT_FILE_PATH =
            System.getProperty("java.io.tmpdir") + File.separator + ".lmo-port";

    /**
     * Total number of groups / items to process in the current operation.
     */
    static final AtomicInteger progressTotal = new AtomicInteger(0);

    /**
     * Number of groups / items completed so far.
     */
    static final AtomicInteger progressProcessed = new AtomicInteger(0);

    /**
     * True while an operation is in flight; false before and after.
     */
    static volatile boolean progressActive = false;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        try {
            ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
            OrganizationService organizationService = new OrganizationService(modelAnalyzer);

            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/api/organize", new OrganizeHandler(organizationService));
            server.createContext("/api/undo", new UndoHandler(organizationService));
            server.createContext("/api/fetch", new FetchHandler(organizationService));
            server.createContext("/api/logs", new LogStreamHandler());
            server.createContext("/api/progress", new ProgressHandler());
            server.createContext("/api/shutdown", new ShutdownHandler(server));

            server.start();

            int assignedPort = server.getAddress().getPort();

            System.out.println("LMO_PORT=" + assignedPort);
            System.out.flush();

            writePortFile(assignedPort);

            Runtime.getRuntime().addShutdownHook(new Thread(LmoApplication::deletePortFile));

            logger.info("Latent Model Organizer Backend started on port {}", assignedPort);
            logger.info("Ready to accept requests at http://localhost:{}", assignedPort);

        } catch (IOException e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }

    private static void writePortFile(int port) {
        File portFile = new File(PORT_FILE_PATH);
        try (FileWriter writer = new FileWriter(portFile)) {
            writer.write(String.valueOf(port));
            logger.debug("Port hint written to {}", PORT_FILE_PATH);
        } catch (IOException e) {
            logger.warn("Could not write port file at {}: {}", PORT_FILE_PATH, e.getMessage());
        }
    }

    private static void deletePortFile() {
        File portFile = new File(PORT_FILE_PATH);
        if (portFile.exists() && !portFile.delete()) {
            logger.warn("Could not delete port file at {}", PORT_FILE_PATH);
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

                progressProcessed.set(0);
                progressTotal.set(0);
                progressActive = true;

                OperationReport report;
                try {
                    report = organizationService.organizeModels(
                            Paths.get(request.sourceDirectory()),
                            Paths.get(request.targetDirectory()),
                            request.allowedArchitectures() != null
                                    ? request.allowedArchitectures()
                                    : Collections.emptyList(),
                            request.isRecursive(),
                            request.isDryRun(),
                            progressTotal::set,
                            progressProcessed::incrementAndGet
                    );
                } finally {
                    progressActive = false;
                }
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

                progressProcessed.set(0);
                progressTotal.set(0);
                progressActive = true;

                OperationReport report;
                try {
                    report = organizationService.fetchMissingMetadata(
                            Paths.get(request.targetDirectory()),
                            request.isRecursive(),
                            request.isDryRun(),
                            progressTotal::set,
                            progressProcessed::incrementAndGet
                    );
                } finally {
                    progressActive = false;
                }
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

    /**
     * GET /api/progress
     * <p>
     * Returns the current state of the in-flight operation as a lightweight JSON object:
     * <pre>
     * {
     *   "active":    true,   // whether an operation is currently running
     *   "processed": 14,     // groups / items completed so far
     *   "total":     42      // total groups / items in this operation
     * }
     * </pre>
     * <p>
     * The frontend polls this endpoint every ~300 ms while isProcessing is true
     * to drive a genuinely accurate progress bar.
     */
    static class ProgressHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            sendJson(exchange, 200, Map.of(
                    "active", progressActive,
                    "processed", progressProcessed.get(),
                    "total", progressTotal.get()
            ));
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
                logger.info("JVM exiting via Runtime.halt(0).");
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
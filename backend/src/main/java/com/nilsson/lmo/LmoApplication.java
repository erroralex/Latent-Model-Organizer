package com.nilsson.lmo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.lmo.api.LogStreamHandler;
import com.nilsson.lmo.api.SecurityFilter;
import com.nilsson.lmo.domain.FetchRequest;
import com.nilsson.lmo.domain.OperationReport;
import com.nilsson.lmo.domain.OrganizationRequest;
import com.nilsson.lmo.domain.UndoRequest;
import com.nilsson.lmo.exception.OrganizerException;
import com.nilsson.lmo.service.ActivationTextBackfillService;
import com.nilsson.lmo.service.ModelAnalyzer;
import com.nilsson.lmo.service.OrganizationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   first available port at runtime, eliminating {@code BindException} crash-loops.
 *   The resolved port is signalled to the Electron process via stdout and a temporary
 *   port-hint file.</li>
 *   <li><b>Project Loom Integration:</b> Utilises Java 21 Virtual Threads (via
 *   {@code newVirtualThreadPerTaskExecutor()}) to provide exceptional throughput during
 *   parallelised file moves and network requests without blocking OS threads.</li>
 *   <li><b>Restful API Interface:</b> Exposes endpoints for organisation, undo, metadata
 *   retrieval, streaming logging (SSE), and graceful shutdown.</li>
 *   <li><b>Security Integration:</b> Employs a {@link SecurityFilter} to protect all
 *   endpoints using a cryptographically secure startup token.</li>
 * </ul>
 * </p>
 *
 * <p>Standard API Routes:
 * <ul>
 *   <li>{@code POST /api/organize} - Categorises and relocates models based on architectural heuristics.</li>
 *   <li>{@code POST /api/undo}     - Reverses the most recent organisational run using a persistent manifest.</li>
 *   <li>{@code POST /api/fetch}    - Retrieves missing sidecar metadata and preview images from external APIs.</li>
 *   <li>{@code POST /api/backfill-triggers} - Writes trigger words from existing sidecars into Forge user metadata.</li>
 *   <li>{@code GET  /api/logs}     - Server-Sent Events stream for real-time operation logging.</li>
 *   <li>{@code GET  /api/progress} - Real-time polling endpoint for operation status.</li>
 *   <li>{@code GET  /api/architectures} - Returns the list of supported architectures.</li>
 *   <li>{@code GET  /api/version} - Returns the application version from the pom.xml.</li>
 *   <li>{@code POST /api/shutdown} - Initiates a graceful termination sequence.</li>
 *   <li>{@code POST /api/cancel} - Sends a cancellation signal to the active long-running operation.</li>
 * </ul>
 * </p>
 *
 * @see OrganizationService
 * @see ModelAnalyzer
 */
public class LmoApplication {

    private static final Logger logger = LoggerFactory.getLogger(LmoApplication.class);
    public static final String HANDSHAKE_TOKEN = UUID.randomUUID().toString();
    static final String PORT_FILE_PATH = System.getProperty("java.io.tmpdir") + File.separator + ".lmo-port";
    static final AtomicInteger progressTotal = new AtomicInteger(0);
    static final AtomicInteger progressProcessed = new AtomicInteger(0);
    static volatile boolean progressActive = false;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static final AtomicBoolean cancelRequested = new AtomicBoolean(false);


    public static void main(String[] args) {
        try {
            ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
            OrganizationService organizationService = new OrganizationService(modelAnalyzer);
            ActivationTextBackfillService backfillService = new ActivationTextBackfillService();

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            var securityFilter = new SecurityFilter(HANDSHAKE_TOKEN);

            createSecureContext(server, "/api/organize", new OrganizeHandler(organizationService), securityFilter);
            createSecureContext(server, "/api/undo", new UndoHandler(organizationService), securityFilter);
            createSecureContext(server, "/api/fetch", new FetchHandler(organizationService), securityFilter);
            createSecureContext(server, "/api/backfill-triggers", new BackfillTriggersHandler(backfillService), securityFilter);
            createSecureContext(server, "/api/cancel", new CancelHandler(), securityFilter);
            createSecureContext(server, "/api/logs", new LogStreamHandler(), securityFilter);
            createSecureContext(server, "/api/progress", new ProgressHandler(), securityFilter);
            createSecureContext(server, "/api/architectures", new ArchitectureHandler(modelAnalyzer), securityFilter);
            createSecureContext(server, "/api/version", new VersionHandler(), securityFilter);
            createSecureContext(server, "/api/shutdown", new ShutdownHandler(server), securityFilter);

            server.start();

            int assignedPort = server.getAddress().getPort();

            System.out.println("LMO_PORT=" + assignedPort + ":" + HANDSHAKE_TOKEN);
            System.out.flush();

            writePortFile(assignedPort, HANDSHAKE_TOKEN);

            Runtime.getRuntime().addShutdownHook(new Thread(LmoApplication::deletePortFile));

            logger.info("Latent Model Organizer Backend started on port {} with token {}", assignedPort, HANDSHAKE_TOKEN);
            logger.info("Ready to accept requests at http://127.0.0.1:{}", assignedPort);

        } catch (IOException e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }

    private static void createSecureContext(HttpServer server, String path, HttpHandler handler, SecurityFilter securityFilter) {
        server.createContext(path, handler).getFilters().add(securityFilter);
    }

    private static void writePortFile(int port, String token) {
        try {
            var tempFile = Paths.get(PORT_FILE_PATH + ".tmp");
            var actualFile = Paths.get(PORT_FILE_PATH);
            Files.writeString(tempFile, port + ":" + token, StandardCharsets.UTF_8);
            Files.move(tempFile, actualFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("Port hint written atomically to {}", PORT_FILE_PATH);
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

                cancelRequested.set(false);
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
                            cancelRequested::get,
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

                cancelRequested.set(false);
                progressProcessed.set(0);
                progressTotal.set(0);
                progressActive = true;

                OperationReport report;
                try {
                    report = organizationService.fetchMissingMetadata(
                            Paths.get(request.targetDirectory()),
                            request.isRecursive(),
                            request.isDryRun(),
                            cancelRequested::get,
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

    static class BackfillTriggersHandler implements HttpHandler {
        private final ActivationTextBackfillService backfillService;

        public BackfillTriggersHandler(ActivationTextBackfillService backfillService) {
            this.backfillService = backfillService;
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
                logger.info("Received trigger word backfill request: {}", request);

                if (request.targetDirectory() == null) {
                    sendError(exchange, 400, "Target directory is required.");
                    return;
                }

                cancelRequested.set(false);
                progressProcessed.set(0);
                progressTotal.set(0);
                progressActive = true;

                OperationReport report;
                try {
                    report = backfillService.backfillActivationText(
                            Paths.get(request.targetDirectory()),
                            request.isRecursive(),
                            request.isDryRun(),
                            cancelRequested::get,
                            progressTotal::set,
                            progressProcessed::incrementAndGet
                    );
                } finally {
                    progressActive = false;
                }
                sendJson(exchange, 200, report);
            } catch (OrganizerException e) {
                logger.error("Business logic error during trigger word backfill", e);
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                logger.error("Internal server error during trigger word backfill", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    static class CancelHandler implements HttpHandler {
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

            logger.info("Received cancellation signal.");
            cancelRequested.set(true);
            sendJson(exchange, 200, Map.of("status", "cancelling", "message", "Cancellation signal sent."));
        }
    }

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

    static class ArchitectureHandler implements HttpHandler {
        private final ModelAnalyzer modelAnalyzer;

        public ArchitectureHandler(ModelAnalyzer modelAnalyzer) {
            this.modelAnalyzer = modelAnalyzer;
        }

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
            sendJson(exchange, 200, modelAnalyzer.getAllArchitectures());
        }
    }

    static class VersionHandler implements HttpHandler {
        private static String version = null;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (version == null) {
                    version = readVersionFromPom();
                }
                sendJson(exchange, 200, Map.of("version", version));
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }

        private String readVersionFromPom() {
            try (InputStream input = LmoApplication.class.getResourceAsStream(
                    "/META-INF/maven/com.nilsson.lmo/backend/pom.properties")) {
                if (input == null) {
                    logger.warn("pom.properties not found, version will be 'dev'");
                    return "dev";
                }
                Properties prop = new Properties();
                prop.load(input);
                return prop.getProperty("version", "dev");
            } catch (IOException ex) {
                logger.error("Failed to read pom.properties", ex);
                return "dev";
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
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
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

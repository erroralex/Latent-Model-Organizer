package com.latent.organizer.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * <p>The {@code LogStreamHandler} implements a Server-Sent Events (SSE) gateway for real-time log propagation
 * from the backend to connected clients. It manages persistent HTTP connections and facilitates
 * asynchronous event streaming.</p>
 *
 * <p>This handler transforms standard HTTP requests into long-lived event streams by setting appropriate
 * headers and leveraging {@link SseLogAppender} for message broadcasting. It is designed to run
 * within a Virtual Thread environment to support a high number of concurrent monitoring sessions
 * with minimal overhead.</p>
 *
 * <p>Implementation Details:
 * <ul>
 *   <li><b>Connection Persistence:</b> Uses {@link CountDownLatch} to maintain the connection lifecycle
 *   until client disconnection.</li>
 *   <li><b>Resource Management:</b> Automatically registers and unregisters clients in the
 *   {@link SseLogAppender} registry to ensure memory safety.</li>
 *   <li><b>Protocol Compliance:</b> Adheres to the {@code text/event-stream} specification, including
 *   CORS and cache-control configurations.</li>
 * </ul>
 * </p>
 */
public class LogStreamHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(LogStreamHandler.class);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");

        exchange.sendResponseHeaders(200, 0);

        try (PrintWriter writer = new PrintWriter(exchange.getResponseBody(), true, StandardCharsets.UTF_8)) {
            SseLogAppender.addClient(writer);

            CountDownLatch latch = new CountDownLatch(1);
            try {
                latch.await();
            } catch (InterruptedException e) {
                logger.info("SSE connection interrupted");
                Thread.currentThread().interrupt();
            } finally {
                SseLogAppender.removeClient(writer);
            }

        } catch (Exception e) {
            logger.debug("SSE client disconnected: {}", e.getMessage());
        }
    }
}

package com.nilsson.lmo.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * <h1>LogStreamHandler</h1>
 * <p>
 * Implements a Server-Sent Events (SSE) gateway for real-time log propagation.
 * This handler manages persistent HTTP connections, facilitating the asynchronous
 * streaming of system events from the backend to connected clients.
 * </p>
 *
 * <h2>Streaming Mechanism</h2>
 * <p>
 * Upon receiving a GET request, the handler upgrades the connection to {@code text/event-stream}.
 * It utilizes a {@link CountDownLatch} to maintain the connection lifecycle, effectively
 * parking the handling thread (ideally a Virtual Thread) until the client disconnects or
 * the server shuts down.
 * </p>
 *
 * <h2>Resource Management</h2>
 * <ul>
 *   <li><b>Registration:</b> Registers the client's {@link PrintWriter} with the {@link SseLogAppender}.</li>
 *   <li><b>Cleanup:</b> Ensures the writer is removed from the broadcast registry upon connection termination.</li>
 *   <li><b>Efficiency:</b> Designed to run within a high-concurrency executor to support many simultaneous monitors.</li>
 * </ul>
 *
 * @see SseLogAppender
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

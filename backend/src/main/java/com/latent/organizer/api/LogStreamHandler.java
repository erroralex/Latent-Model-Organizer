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
 * <p>Server-Sent Events (SSE) gateway for real-time log propagation.</p>
 *
 * <p>This handler manages the persistent lifecycle of log streaming connections. It upgrades standard
 * HTTP requests to the SSE protocol by configuring chunked transfer encoding and mandatory headers.
 * The handler integrates with the {@link SseLogAppender} to bridge internal system events to
 * external frontend subscribers.</p>
 *
 * <p>Engineering considerations:
 * <ul>
 *     <li><b>Indefinite Lifecycle:</b> Utilizes {@link CountDownLatch#await()} to sustain the connection context
 *     indefinitely, ensuring the worker thread (Virtual Thread) remains allocated to this stream until
 *     the client terminates the socket.</li>
 *     <li><b>Resource Sanitization:</b> Guarantees unregistration of clients from the broadcaster list
 *     upon disconnection or interruption, preventing memory leaks and stale references.</li>
 *     <li><b>Encoding Safety:</b> Enforces UTF-8 character encoding for consistent log rendering across
 *     different client platforms.</li>
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

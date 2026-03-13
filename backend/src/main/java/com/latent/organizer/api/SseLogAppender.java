package com.latent.organizer.api;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <p>The {@code SseLogAppender} is a high-concurrency Logback appender designed for Server-Sent Events (SSE)
 * broadcasting. It captures system logging events and propagates them to connected clients in real-time.</p>
 *
 * <p>This component acts as a bridge between the application's logging infrastructure and the web interface,
 * allowing developers to monitor backend activity directly from the frontend. It maintains a
 * thread-safe registry of active client writers and handles broadcasting with defensive error
 * management to prune stale connections.</p>
 *
 * <p>Architectural Features:
 * <ul>
 *   <li><b>Concurrent Registry:</b> Uses {@link CopyOnWriteArrayList} for thread-safe management
 *   of client {@link PrintWriter} instances.</li>
 *   <li><b>Log Formatting:</b> Applies custom formatting to logging events for optimized readability
 *   within a browser-based console.</li>
 *   <li><b>Automatic Cleanup:</b> Detects and removes disconnected clients during the broadcast cycle.</li>
 * </ul>
 * </p>
 */
public class SseLogAppender extends AppenderBase<ILoggingEvent> {

    private static final List<PrintWriter> clients = new CopyOnWriteArrayList<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public static void addClient(PrintWriter writer) {
        clients.add(writer);
    }

    public static void removeClient(PrintWriter writer) {
        clients.remove(writer);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        int lastDot = loggerName.lastIndexOf('.');
        String simpleName = (lastDot != -1) ? loggerName.substring(lastDot + 1) : loggerName;

        String log = String.format("%s %-5s %s - %s",
                formatter.format(Instant.ofEpochMilli(event.getTimeStamp())),
                event.getLevel().toString(),
                simpleName,
                event.getFormattedMessage()
        );

        for (PrintWriter client : clients) {
            try {
                client.print("data: " + log + "\n\n");
                client.flush();
            } catch (Exception e) {
                removeClient(client);
            }
        }
    }
}

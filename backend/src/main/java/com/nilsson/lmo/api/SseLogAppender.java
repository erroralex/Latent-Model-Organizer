package com.nilsson.lmo.api;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <h1>SseLogAppender</h1>
 * <p>
 * A custom Logback appender engineered for broadcasting system logs via Server-Sent Events (SSE).
 * This class captures logging events from across the application and propagates them to
 * all active web clients in real-time.
 * </p>
 *
 * <h2>Technical Design</h2>
 * <p>
 * This appender maintains a thread-safe registry of connected client {@link PrintWriter}s
 * using a {@link CopyOnWriteArrayList}, facilitating high-concurrency event broadcasting.
 * It formats log entries into a standardized console-ready string before dispatch.
 * </p>
 *
 * <h2>Broadcast Lifecycle</h2>
 * <ul>
 *   <li><b>Interception:</b> Captures {@link ILoggingEvent} objects from the Logback context.</li>
 *   <li><b>Formatting:</b> Converts logs into the {@code data: [log content]\n\n} SSE format.</li>
 *   <li><b>Distribution:</b> Asynchronously pushes formatted messages to all registered clients.</li>
 *   <li><b>Resilience:</b> Automatically detects and prunes stale connections if writing fails.</li>
 * </ul>
 *
 * @see LogStreamHandler
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

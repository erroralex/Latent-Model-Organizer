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
 * <p>A high-concurrency Logback appender for Server-Sent Events (SSE) broadcasting.</p>
 *
 * <p>The {@code SseLogAppender} intercepts internal logging events and dispatches them to a collection
 * of registered web clients. It implements a non-blocking broadcasting strategy by utilizing a
 * thread-safe {@link CopyOnWriteArrayList} for client management, ensuring stability even under
 * high connection churn.</p>
 *
 * <p>Key features:
 * <ul>
 *     <li><b>Real-Time Propagation:</b> Formats and flushes log strings immediately upon arrival
 *     to minimize perceived latency in the frontend console.</li>
 *     <li><b>Defensive Client Management:</b> Automatically detects and prunes disconnected or
 *     stale clients by catching I/O exceptions during the write cycle.</li>
 *     <li><b>Concise Formatting:</b> Aggressively simplifies timestamps and logger names to
 *     optimize horizontal space in terminal-like UI components.</li>
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

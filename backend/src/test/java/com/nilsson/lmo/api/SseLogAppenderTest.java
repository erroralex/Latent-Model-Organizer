package com.nilsson.lmo.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * <p>The {@code SseLogAppenderTest} suite validates the real-time log broadcasting
 * layer of the Latent Model Organizer. It ensures that internal logging events are
 * accurately captured, formatted, and propagated to connected web clients.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Broadcast Accuracy:</b> Verifies that formatted log strings are
 *   simultaneously written to multiple client streams.</li>
 *   <li><b>Format Integrity:</b> Asserts that logging payloads conform to the
 *   Server-Sent Events (SSE) standard (e.g., prepended with {@code data: }).</li>
 *   <li><b>Registry Management:</b> Validates the dynamic addition and removal of
 *   client writers from the global broadcast registry.</li>
 * </ul>
 * </p>
 */
class SseLogAppenderTest {

    private SseLogAppender appender;

    @BeforeEach
    void setUp() {
        appender = new SseLogAppender();
        appender.start();
    }

    @Test
    void append_shouldBroadcastToAllClients() {
        StringWriter sw1 = new StringWriter();
        PrintWriter pw1 = new PrintWriter(sw1);
        StringWriter sw2 = new StringWriter();
        PrintWriter pw2 = new PrintWriter(sw2);

        SseLogAppender.addClient(pw1);
        SseLogAppender.addClient(pw2);

        ILoggingEvent mockEvent = mock(ILoggingEvent.class);
        when(mockEvent.getFormattedMessage()).thenReturn("Test log message");
        when(mockEvent.getLevel()).thenReturn(Level.INFO);
        when(mockEvent.getTimeStamp()).thenReturn(1672531200000L);
        when(mockEvent.getLoggerName()).thenReturn("com.test.TestLogger");

        appender.append(mockEvent);

        String expectedPayloadStart = "data: ";
        String expectedPayloadEnd = "INFO  TestLogger - Test log message\n\n";

        assertTrue(sw1.toString().startsWith(expectedPayloadStart));
        assertTrue(sw1.toString().endsWith(expectedPayloadEnd));

        assertTrue(sw2.toString().startsWith(expectedPayloadStart));
        assertTrue(sw2.toString().endsWith(expectedPayloadEnd));

        SseLogAppender.removeClient(pw1);
        SseLogAppender.removeClient(pw2);
    }
}
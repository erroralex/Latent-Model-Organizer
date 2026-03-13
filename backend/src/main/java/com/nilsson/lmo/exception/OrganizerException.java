package com.nilsson.lmo.exception;

/**
 * <p>The {@code OrganizerException} class is the base domain exception for the Latent Model Organizer
 * ecosystem. It is a runtime exception that normalizes application-specific failure modes.</p>
 *
 * <p>This exception is used to propagate domain-specific errors across the service layer and
 * the REST API handlers, ensuring that logical failures are distinguished from general system
 * or I/O failures for clearer error reporting and handling.</p>
 */
public class OrganizerException extends RuntimeException {

    public OrganizerException(String message) {
        super(message);
    }

    public OrganizerException(String message, Throwable cause) {
        super(message, cause);
    }
}

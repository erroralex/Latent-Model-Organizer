package com.latent.organizer.exception;

/**
 * <p>Base domain exception for the Latent Model Organizer ecosystem.</p>
 *
 * <p>This runtime exception acts as the top-level catch-all for application-specific
 * failure modes. It is used to normalize error propagation across the multi-threaded
 * service layer and the REST API handlers, ensuring that domain-specific errors
 * are clearly distinguished from general system or I/O failures.</p>
 */
public class OrganizerException extends RuntimeException {

    public OrganizerException(String message) {
        super(message);
    }

    public OrganizerException(String message, Throwable cause) {
        super(message, cause);
    }
}

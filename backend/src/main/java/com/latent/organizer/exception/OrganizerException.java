package com.latent.organizer.exception;

/**
 * Base exception for the Latent Model Organizer application.
 * All application-specific runtime exceptions should extend this class.
 */
public class OrganizerException extends RuntimeException {

    public OrganizerException(String message) {
        super(message);
    }

    public OrganizerException(String message, Throwable cause) {
        super(message, cause);
    }
}

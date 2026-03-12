package com.latent.organizer.exception;

/**
 * <p>Base domain exception for the Latent Model Organizer application.</p>
 *
 * <p>This runtime exception serves as the root for all application-specific error
 * scenarios. It provides mechanisms to capture descriptive error messages and
 * underlying causes, facilitating robust error handling and reporting within
 * the system's service and API layers.</p>
 */
public class OrganizerException extends RuntimeException {

    public OrganizerException(String message) {
        super(message);
    }

    public OrganizerException(String message, Throwable cause) {
        super(message, cause);
    }
}

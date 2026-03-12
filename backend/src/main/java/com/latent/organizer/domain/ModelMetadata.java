package com.latent.organizer.domain;

/**
 * Immutable representation of a model's extracted metadata.
 *
 * @param fileName     The name of the file associated with this metadata.
 * @param architecture The neural network architecture (e.g., "SDXL", "Flux", "SD1.5").
 * @param baseModel    The base model identifier, often extracted from sidecar files or metadata headers.
 */
public record ModelMetadata(
    String fileName,
    String architecture,
    String baseModel
) {
}

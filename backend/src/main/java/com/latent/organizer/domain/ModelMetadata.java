package com.latent.organizer.domain;

/**
 * <p>An immutable representation of extracted or retrieved model metadata.</p>
 *
 * <p>This record encapsulates key identifying information about a model file,
 * including its architectural type (e.g., "SDXL", "Flux") and its base model.
 * These details are extracted from the file headers, sidecar JSON files,
 * or via external API lookups to determine the correct organizational structure
 * during the model movement phase.</p>
 *
 * @param fileName
 *         The name of the file associated with this metadata.
 * @param architecture
 *         The neural network architecture (e.g., "SDXL", "Flux", "SD1.5").
 * @param baseModel
 *         The base model identifier, often extracted from sidecar files or metadata headers.
 */
public record ModelMetadata(
        String fileName,
        String architecture,
        String baseModel
) {
}

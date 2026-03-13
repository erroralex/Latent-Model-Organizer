package com.nilsson.lmo.domain;

/**
 * <p>The {@code ModelMetadata} record is an immutable representation of extracted or retrieved model metadata.
 * It encapsulates key identifying information about a model file, including its architectural type
 * and base model identifier.</p>
 *
 * <p>This information is gathered through file header analysis, sidecar JSON parsing, or external
 * API lookups. It provides the necessary context for the organization service to classify and
 * relocate model files correctly within the target directory structure.</p>
 *
 * @param fileName
 *         The name of the file associated with this metadata.
 * @param architecture
 *         The neural network architecture (e.g., "SDXL", "Flux", "SD1.5").
 * @param baseModel
 *         The base model identifier extracted from sidecar files or metadata headers.
 */
public record ModelMetadata(
        String fileName,
        String architecture,
        String baseModel
) {
}

package com.latent.organizer.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * <p>The {@code ModelGroup} record is a data carrier representing a logical grouping of related model
 * files. It identifies a primary model file and its associated sidecar files (e.g., config,
 * preview, metadata) that share a common identity.</p>
 *
 * <p>This record acts as the fundamental unit of organization within the system, facilitating
 * atomic operations on complex model structures. It links a specific neural network architecture
 * (e.g., SDXL, SD 1.5) to a set of absolute file paths for synchronized movement or classification.</p>
 *
 * @param baseName
 *         The shared filename prefix common to all files in the group.
 * @param architecture
 *         The identified neural network architecture of the model group.
 * @param associatedFiles
 *         An immutable list of absolute {@link java.nio.file.Path}s belonging to this group.
 */
public record ModelGroup(
        String baseName,
        String architecture,
        List<Path> associatedFiles
) {
}

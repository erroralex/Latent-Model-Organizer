package com.latent.organizer.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * <p>A data carrier representing a logical grouping of related model files.</p>
 *
 * <p>In the context of latent diffusion models, a single model often consists of multiple
 * files (e.g., the primary weights, a configuration file, a preview image, and potentially
 * metadata sidecars). This record groups these associated files under a common base name
 * and identifies their shared neural network architecture (e.g., "SDXL", "SD 1.5").</p>
 *
 * <p>This grouping is used during the organization process to ensure that all related
 * components of a model are moved together to the appropriate destination directory.</p>
 *
 * @param baseName
 *         The shared filename prefix (excluding extensions) common to all files in the group.
 * @param architecture
 *         The specific model architecture identified through analysis or metadata lookup.
 * @param associatedFiles
 *         An immutable list of absolute {@link java.nio.file.Path}s representing the files in this group.
 */
public record ModelGroup(
        String baseName,
        String architecture,
        List<Path> associatedFiles
) {
}

package com.latent.organizer.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * Represents a group of related files for a single model.
 * Files are typically grouped by their base name (ignoring extensions).
 *
 * @param baseName        The common base name of the files.
 * @param architecture    The neural network architecture associated with the model (e.g., "SDXL").
 * @param associatedFiles The list of absolute paths to the files belonging to this group.
 */
public record ModelGroup(
    String baseName,
    String architecture,
    List<Path> associatedFiles
) {
}

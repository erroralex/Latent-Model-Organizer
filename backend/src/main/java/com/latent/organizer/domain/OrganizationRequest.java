package com.latent.organizer.domain;

import java.util.List;

/**
 * <p>Data Transfer Object (DTO) for orchestrating model organization requests.</p>
 *
 * <p>This immutable record defines the contractual parameters required to execute a model
 * organization process. It encapsulates the operational scope (source/target directories),
 * filtering criteria (allowed architectures), and behavioral flags (recursion, dry run).</p>
 *
 * @param sourceDirectory      The absolute path of the directory containing the unorganized model files.
 * @param targetDirectory      The absolute path of the directory where organized subdirectories will be created.
 * @param allowedArchitectures A whitelist of architectural types to process. If empty, all types are included.
 * @param isRecursive          If true, the organization engine will traverse the full directory tree of the source.
 * @param isDryRun             If true, the operation will simulate the movement and categorization without
 *                             modifying the file system.
 */
public record OrganizationRequest(
    String sourceDirectory,
    String targetDirectory,
    List<String> allowedArchitectures,
    boolean isRecursive,
    boolean isDryRun
) {
}

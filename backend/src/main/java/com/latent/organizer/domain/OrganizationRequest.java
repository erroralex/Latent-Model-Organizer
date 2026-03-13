package com.latent.organizer.domain;

import java.util.List;

/**
 * <p>The {@code OrganizationRequest} record is a Data Transfer Object (DTO) for orchestrating
 * model organization requests. It defines the contractual parameters required to execute a model
 * organization process.</p>
 *
 * <p>This record encapsulates the operational scope (source/target directories), filtering
 * criteria (allowed architectures), and behavioral flags (recursion, dry run) for
 * the organization service.</p>
 *
 * @param sourceDirectory
 *         The absolute path of the directory containing unorganized model files.
 * @param targetDirectory
 *         The absolute path of the directory where organized subdirectories will be created.
 * @param allowedArchitectures
 *         A whitelist of architectural types to process. If empty, all types are included.
 * @param isRecursive
 *         Flag determining whether to traverse the directory tree of the source.
 * @param isDryRun
 *         Flag indicating whether to simulate movement and categorization without filesystem changes.
 */
public record OrganizationRequest(
        String sourceDirectory,
        String targetDirectory,
        List<String> allowedArchitectures,
        boolean isRecursive,
        boolean isDryRun
) {
}

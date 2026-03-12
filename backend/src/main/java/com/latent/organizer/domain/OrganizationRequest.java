package com.latent.organizer.domain;

import java.util.List;

/**
 * <p>A Data Transfer Object (DTO) for orchestrating model organization requests.</p>
 *
 * <p>This record encapsulates all the parameters required to execute an
 * organization process, including the source and destination paths, and
 * a whitelist of architectural types to process. If {@code allowedArchitectures}
 * is empty or {@code null}, all identified model architectures will be processed
 * by the system.</p>
 *
 * @param sourceDirectory
 *         The absolute path of the directory containing the model files.
 * @param targetDirectory
 *         The absolute path of the directory where files should be organized.
 * @param allowedArchitectures
 *         The list of architectures to organize (e.g., "SDXL", "Flux").
 *         If null or empty, all architectures are processed.
 */
public record OrganizationRequest(
        String sourceDirectory,
        String targetDirectory,
        List<String> allowedArchitectures
) {
}

package com.latent.organizer.domain;

/**
 * Data Transfer Object (DTO) for the organization request.
 * Contains the source directory to scan and the target directory to move files to.
 *
 * @param sourceDirectory The absolute path of the directory containing the model files.
 * @param targetDirectory The absolute path of the directory where files should be organized.
 */
public record OrganizationRequest(
    String sourceDirectory,
    String targetDirectory
) {
}

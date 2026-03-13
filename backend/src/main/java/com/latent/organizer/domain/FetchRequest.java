package com.latent.organizer.domain;

/**
 * <p>The {@code FetchRequest} record is a Data Transfer Object (DTO) for metadata retrieval orchestration.
 * It encapsulates the necessary parameters required to initiate a scan and subsequent metadata
 * fetch for machine learning models missing sidecar documentation.</p>
 *
 * <p>This record represents the immutable contractual input received by the {@code /api/fetch} endpoint,
 * specifying the scope and operational mode of the metadata fetching service.</p>
 *
 * @param targetDirectory
 *         The absolute path of the directory tree to be scanned for models lacking metadata.
 * @param isRecursive
 *         Determines whether subdirectories of the target folder should be included in the scan.
 * @param isDryRun
 *         A flag indicating whether to simulate the process without performing actual downloads.
 */
public record FetchRequest(
        String targetDirectory,
        boolean isRecursive,
        boolean isDryRun
) {
}

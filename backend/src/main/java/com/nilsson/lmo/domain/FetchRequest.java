package com.nilsson.lmo.domain;

import com.nilsson.lmo.service.OrganizationService;

/**
 * <h1>FetchRequest</h1>
 * <p>
 * A Data Transfer Object (DTO) record for metadata retrieval orchestration.
 * It encapsulates all parameters required to initiate directory-wide scans for
 * models lacking sidecar metadata.
 * </p>
 *
 * <h2>Communication Contract</h2>
 * <p>
 * This record defines the immutable input structure for the {@code /api/fetch} endpoint,
 * specifying the scope and operational mode of the background metadata service.
 * </p>
 *
 * @param targetDirectory The absolute path of the directory tree to be scanned.
 * @param isRecursive Determines whether subdirectories should be included in the scan.
 * @param isDryRun Flag indicating if the process should simulate operations without downloading files.
 *
 * @see OrganizationService#fetchMissingMetadata
 */
public record FetchRequest(
        String targetDirectory,
        boolean isRecursive,
        boolean isDryRun
) {
}

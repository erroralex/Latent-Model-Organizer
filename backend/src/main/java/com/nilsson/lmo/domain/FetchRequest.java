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
 * This record defines the immutable input structure for the {@code /api/fetch} and
 * {@code /api/backfill-metadata} endpoints, specifying the scope and operational mode of the
 * background metadata services. Both take the same parameters: a directory, a traversal depth,
 * and a simulation flag.
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

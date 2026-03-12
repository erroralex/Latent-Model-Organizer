package com.latent.organizer.domain;

/**
 * <p>Data Transfer Object (DTO) for metadata retrieval orchestration.</p>
 *
 * <p>This immutable record defines the contractual parameters required to initiate a recursive
 * scan for models missing metadata sidecars. It is used primarily by the {@code /api/fetch}
 * endpoint to communicate the target directory context to the service layer.</p>
 *
 * @param targetDirectory The absolute path of the directory tree to be scanned for models
 *                        lacking {@code .civitai.info} documentation.
 * @param isRecursive     If true, the service will scan subdirectories of the target folder.
 * @param isDryRun        If true, the service will simulate the fetching process without downloading files.
 */
public record FetchRequest(
    String targetDirectory,
    boolean isRecursive,
    boolean isDryRun
) {
}

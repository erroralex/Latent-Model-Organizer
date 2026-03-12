package com.latent.organizer.domain;

import java.util.List;
import java.util.Map;

/**
 * <p>Data Transfer Object (DTO) for reporting the results of an organization or fetch operation.</p>
 *
 * <p>This record encapsulates the final outcome of a batch process, providing aggregated
 * metrics and detailed error reporting to the frontend. It is designed to be serialized
 * directly into JSON for consumption by the UI's summary modal.</p>
 *
 * @param message The overall status message (e.g., "Organization completed successfully").
 * @param summary A map of category names (e.g., architecture types) to the count of items processed in that category.
 * @param errors  A list of specific error messages encountered during processing (e.g., file lock issues).
 * @param totalProcessed The total number of model groups processed in this operation.
 * @param totalUncategorized The total number of model groups that fell into the "Uncategorized" bucket.
 */
public record OperationReport(
    String message,
    Map<String, Integer> summary,
    List<String> errors,
    int totalProcessed,
    int totalUncategorized
) {
}

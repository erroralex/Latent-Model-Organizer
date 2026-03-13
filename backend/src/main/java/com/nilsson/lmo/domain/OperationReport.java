package com.nilsson.lmo.domain;

import java.util.List;
import java.util.Map;

/**
 * <p>The {@code OperationReport} record is a Data Transfer Object (DTO) for reporting the results
 * of an organization or fetch operation. It provides aggregated metrics and detailed error
 * information for batch processes.</p>
 *
 * <p>This record is designed to be serialized directly into JSON for consumption by the frontend,
 * offering a summary of the number of items processed, categorizations, and any issues
 * encountered during execution.</p>
 *
 * @param message
 *         The overall status message of the operation.
 * @param summary
 *         A map of architecture types or categories to the count of processed items.
 * @param errors
 *         A list of specific error messages encountered during the operation.
 * @param totalProcessed
 *         The total number of model groups processed.
 * @param totalUncategorized
 *         The total number of model groups that could not be categorized.
 */
public record OperationReport(
        String message,
        Map<String, Integer> summary,
        List<String> errors,
        int totalProcessed,
        int totalUncategorized
) {
}

package com.nilsson.lmo.domain;

/**
 * <p>The {@code UndoRequest} serves as a lightweight Data Transfer Object (DTO) for
 * specifying the target directory of an undo operation. It is the primary payload
 * for the {@code POST /api/undo} endpoint.</p>
 *
 * <p>Conceptual Role:
 * <ul>
 *   <li><b>Contextual Root:</b> The {@code targetDirectory} identifies the root folder
 *   where the {@code undo-manifest.json} was persisted during the previous organization run.</li>
 *   <li><b>Identity Verification:</b> Ensures that the undo operation is targeted at
 *   the correct library branch, as the manifest acts as the definitive log for that
 *   specific workspace.</li>
 * </ul>
 * </p>
 *
 * <p>This object is typically mapped from a JSON request body, providing the backend
 * service with the starting point for manifest discovery and subsequent file restoration.</p>
 */
public record UndoRequest(String targetDirectory) {
}
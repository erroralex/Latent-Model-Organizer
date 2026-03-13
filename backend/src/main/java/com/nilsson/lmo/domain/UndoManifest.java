package com.nilsson.lmo.domain;

import java.util.List;

/**
 * <p>The {@code UndoManifest} represents a comprehensive, serialized record of all file
 * transformations performed during a single organization execution. It serves as the
 * authoritative source of truth for reversing organizational changes, ensuring that
 * the filesystem can be restored to its exact previous state.</p>
 *
 * <p>Architectural Details:
 * <ul>
 *   <li><b>Atomic Persistence:</b> Written to disk in a single sequential operation
 *   immediately following a successful sort. This ensures that even for libraries
 *   containing thousands of models, the overhead of maintaining undo capability
 *   remains negligible (&lt; 2 MB for ~8,000 files).</li>
 *   <li><b>Structure:</b> Encapsulates a temporal timestamp, a total count of operations,
 *   and an ordered collection of {@code MoveRecord} objects.</li>
 *   <li><b>MoveRecord:</b> A granular data point capturing the absolute source ('from')
 *   and destination ('to') paths. This bidirectional mapping allows for precise
 *   reversal via standard filesystem move operations.</li>
 * </ul>
 * </p>
 *
 * <p>This manifest is typically stored as {@code undo-manifest.json} within the target
 * directory of an organization run, facilitating portable and persistent undo functionality
 * across application restarts.</p>
 */
public record UndoManifest(
        String timestamp,
        int moveCount,
        List<MoveRecord> moves
) {
    public record MoveRecord(String from, String to) {
    }
}
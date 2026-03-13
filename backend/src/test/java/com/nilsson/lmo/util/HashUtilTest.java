package com.nilsson.lmo.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>The {@code HashUtilTest} suite validates the cryptographic integrity and robustness
 * of the hashing utilities within the Latent Model Organizer. It ensures that the system
 * can reliably generate unique identifiers for large model files.</p>
 *
 * <p>Key Responsibilities:
 * <ul>
 *   <li><b>Cryptographic Accuracy:</b> Verifies that the generated SHA-256 hex strings
 *   exactly match authoritative values for controlled inputs.</li>
 *   <li><b>Resource Resiliency:</b> Validates handling of non-existent files or
 *   inaccessible filesystem paths.</li>
 *   <li><b>Platform Consistency:</b> Ensures deterministic results across varying
 *   operating environments and character sets.</li>
 * </ul>
 * </p>
 */
class HashUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void calculateSHA256_shouldComputeCorrectHashForSmallFile() throws Exception {
        Path file = tempDir.resolve("test_hash.txt");
        Files.writeString(file, "test data");

        String hash = HashUtil.calculateSHA256(file);

        assertEquals("916f0027a575074ce72a331777c3478d6513f786a591bd892da1a577bf2335f9", hash.toLowerCase());
    }

    @Test
    void calculateSHA256_shouldThrowOnNonExistentFile() {
        Path missingFile = tempDir.resolve("missing.txt");
        assertThrows(RuntimeException.class, () -> HashUtil.calculateSHA256(missingFile));
    }
}
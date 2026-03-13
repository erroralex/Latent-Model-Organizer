package com.nilsson.lmo.util;

import com.nilsson.lmo.exception.OrganizerException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * <p>The {@code HashUtil} class provides utility methods for cryptographic hashing operations,
 * specifically optimized for processing large machine learning model files. It ensures
 * high performance and low memory overhead during SHA-256 calculation.</p>
 *
 * <p>This utility uses a buffered streaming approach to process multi-gigabyte files,
 * generating unique identifiers used for matching models against external databases
 * like Civitai. It is designed as a stateless, non-instantiable utility class.</p>
 *
 * <p>Implementation Details:
 * <ul>
 *   <li><b>Memory Efficiency:</b> Processes files in chunks using a fixed-size buffer
 *   to avoid loading entire models into memory.</li>
 *   <li><b>Cryptographic Integrity:</b> Uses the standard SHA-256 algorithm via
 *   {@link MessageDigest}.</li>
 *   <li><b>Hex Encoding:</b> Includes a specialized method for converting byte arrays
 *   into lowercase hexadecimal strings.</li>
 * </ul>
 * </p>
 */
public final class HashUtil {

    private static final int BUFFER_SIZE = 8192;

    private HashUtil() {
    }

    public static String calculateSHA256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream is = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return bytesToHex(digest.digest());

        } catch (NoSuchAlgorithmException | IOException e) {
            throw new OrganizerException("Failed to calculate SHA-256 for file: " + file, e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}

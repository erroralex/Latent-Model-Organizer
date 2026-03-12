package com.latent.organizer.util;

import com.latent.organizer.exception.OrganizerException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * <p>Utility for cryptographic hashing operations, optimized for processing large model files.</p>
 *
 * <p>This utility provides efficient SHA-256 calculation by utilizing buffered file reading
 * and a streaming approach to {@link java.security.MessageDigest}, minimizing memory overhead
 * even when processing multi-gigabyte files. It is primarily used to generate unique
 * identifiers for model files to match them against external databases like Civitai.</p>
 *
 * <p>The class is designed as a stateless utility and is not instantiable.</p>
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

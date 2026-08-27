package dev.aisentinel.autoconfigure.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time API key comparison.
 */
public final class ApiKeyAuthenticator {

    private ApiKeyAuthenticator() {
    }

    public static boolean matches(String expected, String provided) {
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            // Compare against self to keep timing closer for length mismatches.
            MessageDigest.isEqual(a, a);
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }
}

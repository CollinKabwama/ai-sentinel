package dev.aisentinel.autoconfigure.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time API key comparison for the remote evaluation boundary.
 * <p>
 * Blank or missing credentials never succeed. Callers must resolve a single
 * unambiguous header value before invoking {@link #matches(String, String)}.
 */
public final class ApiKeyAuthenticator {

    private ApiKeyAuthenticator() {
    }

    /**
     * @return {@code true} only when both sides are non-blank and equal under
     *         constant-time comparison
     */
    public static boolean matches(String expected, String provided) {
        if (expected == null || expected.isBlank() || provided == null || provided.isBlank()) {
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

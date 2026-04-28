package io.aisentinel.autoconfigure.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Thread-local SHA-256 for identity strings; avoids allocating a new {@link MessageDigest} per request. */
final class IdentityHasher {

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for identity hashing", e);
        }
    });

    private IdentityHasher() {
    }

    static String sha256Hex(String s) {
        byte[] input = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        MessageDigest md = SHA256.get();
        md.reset();
        return HexFormat.of().formatHex(md.digest(input));
    }
}

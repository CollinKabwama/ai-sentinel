package io.aisentinel.distributed.training;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable SHA-256 fingerprints for training export (no raw path / key material in events).
 */
public final class TrainingFingerprintHashes {

    private TrainingFingerprintHashes() {
    }

    /**
     * Lowercase hex SHA-256 of UTF-8 bytes (64 characters).
     */
    public static String sha256HexUtf8(String value) {
        String s = value != null ? value : "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Lowercase hex SHA-256 of raw bytes (64 characters). */
    public static String sha256HexBytes(byte[] data) {
        byte[] in = data != null ? data : new byte[0];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(in));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

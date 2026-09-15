package dev.aisentinel.core.scoring.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic SHA-256 helpers for scorer artifact provenance fingerprints.
 */
final class ScorerArtifactFingerprints {

    private ScorerArtifactFingerprints() {
    }

    static String sha256HexUtf8(String value) {
        String s = value != null ? value : "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

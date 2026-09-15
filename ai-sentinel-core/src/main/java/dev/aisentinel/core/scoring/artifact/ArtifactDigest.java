package dev.aisentinel.core.scoring.artifact;

import java.util.Locale;
import java.util.Objects;

/**
 * Explicit integrity digest metadata for a candidate scorer/model artifact.
 * <p>
 * For {@link ArtifactDigestAlgorithm#SHA_256}, the digest is canonical lowercase
 * hex of length 64. Construction validates format only; it does not hash artifact bytes.
 */
public record ArtifactDigest(
    ArtifactDigestAlgorithm algorithm,
    String digestHex
) {
    public ArtifactDigest {
        Objects.requireNonNull(algorithm, "algorithm");
        if (digestHex == null || digestHex.isBlank()) {
            throw new IllegalArgumentException("digestHex must not be blank");
        }
        if (!digestHex.equals(digestHex.trim())) {
            throw new IllegalArgumentException("digestHex must not contain leading or trailing whitespace");
        }
        String normalized = digestHex.toLowerCase(Locale.ROOT);
        if (normalized.length() != algorithm.hexLength()) {
            throw new IllegalArgumentException(
                "digestHex length " + normalized.length()
                    + " is invalid for " + algorithm.wireName()
                    + " (expected " + algorithm.hexLength() + ")"
            );
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException(
                    "digestHex must be lowercase hexadecimal for " + algorithm.wireName()
                );
            }
        }
        digestHex = normalized;
    }

    public static ArtifactDigest sha256Hex(String digestHex) {
        return new ArtifactDigest(ArtifactDigestAlgorithm.SHA_256, digestHex);
    }
}

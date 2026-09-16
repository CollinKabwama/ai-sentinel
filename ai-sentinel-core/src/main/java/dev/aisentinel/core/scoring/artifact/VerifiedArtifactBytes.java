package dev.aisentinel.core.scoring.artifact;

import java.util.Arrays;
import java.util.Objects;

/**
 * Artifact bytes that have been integrity-verified against a declared
 * {@link ArtifactDigest} and defensively copied for immutable consumption.
 * <p>
 * SHA-256 equality proves content equality to the declared digest. It does
 * <strong>not</strong> prove publisher authenticity, malware safety, model
 * quality, or production approval.
 * <p>
 * {@code DIGEST METADATA VALID != ARTIFACT BYTES VERIFIED}<br>
 * {@code VERIFIED BYTES == CONSUMED BYTES}
 */
public final class VerifiedArtifactBytes {

    private final ArtifactDigest declaredDigest;
    private final String computedDigestHex;
    private final byte[] bytes;

    private VerifiedArtifactBytes(ArtifactDigest declaredDigest, String computedDigestHex, byte[] bytes) {
        this.declaredDigest = declaredDigest;
        this.computedDigestHex = computedDigestHex;
        this.bytes = bytes;
    }

    static VerifiedArtifactBytes ofVerified(
        ArtifactDigest declaredDigest,
        String computedDigestHex,
        byte[] verifiedBytes
    ) {
        Objects.requireNonNull(declaredDigest, "declaredDigest");
        Objects.requireNonNull(computedDigestHex, "computedDigestHex");
        Objects.requireNonNull(verifiedBytes, "verifiedBytes");
        return new VerifiedArtifactBytes(
            declaredDigest,
            computedDigestHex,
            Arrays.copyOf(verifiedBytes, verifiedBytes.length)
        );
    }

    public ArtifactDigest declaredDigest() {
        return declaredDigest;
    }

    /** Lowercase hex SHA-256 computed over the verified bytes. */
    public String computedDigestHex() {
        return computedDigestHex;
    }

    public int size() {
        return bytes.length;
    }

    /**
     * Defensive copy of the verified bytes. Callers cannot mutate the internal
     * representation used for construction.
     */
    public byte[] copyOfBytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}

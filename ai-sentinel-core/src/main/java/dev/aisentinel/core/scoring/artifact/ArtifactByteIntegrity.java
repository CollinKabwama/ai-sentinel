package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.scoring.IsolationForestModelCodec;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Integrity helpers for candidate artifact bytes.
 * <p>
 * Digest verification is content integrity against declared metadata, not
 * publisher authenticity or model quality.
 */
final class ArtifactByteIntegrity {

    /**
     * Maximum candidate artifact size accepted by this loading boundary.
     * Matches the Isolation Forest codec limit used by existing model install paths.
     */
    static final int MAX_CANDIDATE_ARTIFACT_BYTES = IsolationForestModelCodec.MAX_PAYLOAD_BYTES;

    private ArtifactByteIntegrity() {
    }

    static byte[] defensiveCopy(byte[] artifactBytes) {
        Objects.requireNonNull(artifactBytes, "artifactBytes");
        return Arrays.copyOf(artifactBytes, artifactBytes.length);
    }

    static String sha256Hex(byte[] artifactBytes) {
        return TrainingFingerprintHashes.sha256HexBytes(artifactBytes);
    }

    /**
     * Constant-time comparison of declared lowercase hex digest to computed digest.
     */
    static boolean digestsMatch(ArtifactDigest declared, String computedDigestHex) {
        Objects.requireNonNull(declared, "declared");
        Objects.requireNonNull(computedDigestHex, "computedDigestHex");
        if (declared.algorithm() != ArtifactDigestAlgorithm.SHA_256) {
            return false;
        }
        byte[] expected;
        byte[] actual;
        try {
            expected = HexFormat.of().parseHex(declared.digestHex());
            actual = HexFormat.of().parseHex(computedDigestHex.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }
}

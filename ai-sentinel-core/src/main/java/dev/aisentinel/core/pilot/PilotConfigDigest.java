package dev.aisentinel.core.pilot;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.util.Objects;

/**
 * Deterministic SHA-256 digest of non-secret pilot configuration.
 */
public final class PilotConfigDigest {

    private PilotConfigDigest() {
    }

    public static String sha256Hex(PilotConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return TrainingFingerprintHashes.sha256HexUtf8(snapshot.canonicalDigestMaterial());
    }
}

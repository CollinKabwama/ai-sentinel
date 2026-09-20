package dev.aisentinel.core.replay;

/**
 * Deterministic scorer configuration for replay.
 */
public record ReplayScorerConfiguration(
    ReplayScorerKind scorerKind,
    String scorerId,
    String scorerVersion,
    int maxKeys,
    long ttlMs,
    int warmupMinSamples,
    double warmupScore
) {
    public ReplayScorerConfiguration {
        if (scorerKind == null) {
            throw new IllegalArgumentException("scorerKind is required");
        }
        if (scorerId == null || scorerId.isBlank()) {
            throw new IllegalArgumentException("scorerId is required");
        }
        scorerVersion = scorerVersion == null ? "" : scorerVersion;
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys must be > 0");
        }
        if (ttlMs <= 0L) {
            throw new IllegalArgumentException("ttlMs must be > 0");
        }
        if (warmupMinSamples < 0) {
            throw new IllegalArgumentException("warmupMinSamples must be >= 0");
        }
        if (!Double.isFinite(warmupScore) || warmupScore < 0.0 || warmupScore > 1.0) {
            throw new IllegalArgumentException("warmupScore must be finite in [0,1]");
        }
    }

    public static ReplayScorerConfiguration statisticalDefaults() {
        return new ReplayScorerConfiguration(
            ReplayScorerKind.STATISTICAL,
            "statistical",
            "0.3.0",
            100_000,
            300_000L,
            2,
            0.4
        );
    }

    /**
     * Replay identity for an explicitly injected candidate scorer.
     * <p>
     * {@code maxKeys}, {@code ttlMs}, {@code warmupMinSamples}, and {@code warmupScore}
     * are unused placeholders required by this record; they do not train or warm a
     * candidate. The verified artifact digest is appended to {@code scorerVersion} so
     * replay configuration identity cannot collide across distinct artifacts that share
     * a declared scorer id/version. Candidate evaluation evidence still records the
     * unsuffixed declared version and digest separately
     * ({@code CONFIGURATION FINGERPRINT != ARTIFACT DIGEST}).
     */
    public static ReplayScorerConfiguration forCandidate(String scorerId, String scorerVersion, String verifiedDigestHex) {
        if (verifiedDigestHex == null || !verifiedDigestHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("verifiedDigestHex must be 64 lowercase hex characters");
        }
        String declaredVersion = scorerVersion == null ? "" : scorerVersion;
        return new ReplayScorerConfiguration(
            ReplayScorerKind.CANDIDATE,
            scorerId,
            declaredVersion + "+sha256:" + verifiedDigestHex,
            100_000,
            300_000L,
            2,
            0.4
        );
    }
}

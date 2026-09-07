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
}

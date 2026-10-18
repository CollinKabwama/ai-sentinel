package dev.aisentinel.core.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Frozen offline Isolation Forest configuration for Level-3 same-framework detector comparison.
 * <p>
 * Values are fixed before comparative metrics are inspected. Tree/seed defaults match
 * {@code SentinelProperties} Isolation Forest defaults ({@code numTrees=100}, {@code maxDepth=10},
 * {@code randomSeed=42}). {@code minTrainingSamples=4} matches the fixed kit-reference warmup
 * event count for the three Level-3 scenarios without reading ground-truth labels at runtime.
 * <p>
 * Offline evaluation only — not a production Isolation Forest runtime profile.
 */
record ReferenceIsolationForestConfig(
    int numTrees,
    int maxDepth,
    long randomSeed,
    int minTrainingSamples,
    double fallbackScore,
    double anomalyThreshold
) {
    static final String SCORER_ID = "isolation-forest-reference";
    static final String SCORER_VERSION = "1.0.0";
    static final String FEATURE_PROJECTION = "statistical-6";

    /**
     * Single frozen Level-3 reference configuration.
     * <p>
     * {@code fallbackScore=0.3} is below the replay elevated policy threshold ({@code 0.4}) so
     * pre-model observations remain in the MONITOR band and receive baseline updates under
     * {@code ALLOW_OR_MONITOR}. It is not a comparative-metric tuning choice.
     */
    static final ReferenceIsolationForestConfig FROZEN = new ReferenceIsolationForestConfig(
        100,
        10,
        42L,
        4,
        0.3d,
        0.5d
    );

    ReferenceIsolationForestConfig {
        if (numTrees < 1) {
            throw new IllegalArgumentException("numTrees must be >= 1");
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        if (minTrainingSamples < 1) {
            throw new IllegalArgumentException("minTrainingSamples must be >= 1");
        }
        if (!Double.isFinite(fallbackScore) || fallbackScore < 0.0 || fallbackScore > 1.0) {
            throw new IllegalArgumentException("fallbackScore must be finite in [0,1]");
        }
        if (!Double.isFinite(anomalyThreshold) || anomalyThreshold < 0.0 || anomalyThreshold > 1.0) {
            throw new IllegalArgumentException("anomalyThreshold must be finite in [0,1]");
        }
    }

    /**
     * SHA-256 hex binding scorer identity and frozen hyperparameters for replay configuration
     * fingerprinting ({@code ReplayScorerConfiguration.forCandidate} digest suffix).
     */
    String configurationDigestHex() {
        String material = String.join("\n",
            "scorerId=" + SCORER_ID,
            "scorerVersion=" + SCORER_VERSION,
            "featureProjection=" + FEATURE_PROJECTION,
            "numTrees=" + numTrees,
            "maxDepth=" + maxDepth,
            "randomSeed=" + randomSeed,
            "minTrainingSamples=" + minTrainingSamples,
            "fallbackScore=" + Double.toString(fallbackScore),
            "anomalyThreshold=" + Double.toString(anomalyThreshold)
        );
        return sha256Hex(material);
    }

    ReferenceIsolationForestConfig withRandomSeed(long seed) {
        return new ReferenceIsolationForestConfig(
            numTrees, maxDepth, seed, minTrainingSamples, fallbackScore, anomalyThreshold);
    }

    ReferenceIsolationForestConfig withNumTrees(int trees) {
        return new ReferenceIsolationForestConfig(
            trees, maxDepth, randomSeed, minTrainingSamples, fallbackScore, anomalyThreshold);
    }

    ReferenceIsolationForestConfig withMaxDepth(int depth) {
        return new ReferenceIsolationForestConfig(
            numTrees, depth, randomSeed, minTrainingSamples, fallbackScore, anomalyThreshold);
    }

    ReferenceIsolationForestConfig withAnomalyThreshold(double threshold) {
        return new ReferenceIsolationForestConfig(
            numTrees, maxDepth, randomSeed, minTrainingSamples, fallbackScore, threshold);
    }

    private static String sha256Hex(String material) {
        Objects.requireNonNull(material, "material");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

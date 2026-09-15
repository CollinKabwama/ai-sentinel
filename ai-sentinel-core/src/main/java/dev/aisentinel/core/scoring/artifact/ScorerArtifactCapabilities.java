package dev.aisentinel.core.scoring.artifact;

/**
 * Declared capability metadata for a candidate scorer/model artifact.
 * <p>
 * Declared capabilities are metadata, not verified quality.
 * {@code DECLARED CAPABILITY != VERIFIED QUALITY}.
 * Isolation Forest artifacts must not claim per-feature attribution.
 */
public record ScorerArtifactCapabilities(
    boolean supportsExplainability,
    boolean supportsPerFeatureAttribution,
    boolean claimsDeterministicExecution
) {
    public static ScorerArtifactCapabilities none() {
        return new ScorerArtifactCapabilities(false, false, false);
    }
}

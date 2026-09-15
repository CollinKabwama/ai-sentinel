package dev.aisentinel.core.scoring.artifact;

/**
 * Declared numeric output range for a candidate scorer/model.
 * <p>
 * AI-Sentinel {@link dev.aisentinel.core.scoring.AnomalyScorer} scores are expected
 * in {@code [0.0, 1.0]}. Declared bounds must be finite with {@code min <= max}
 * and must lie within that contract. Declaring a compatible range is not proof
 * that emitted scores are valid at runtime.
 */
public record ScorerOutputRange(double minimum, double maximum) {
    public ScorerOutputRange {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) {
            throw new IllegalArgumentException("output range bounds must be finite");
        }
        if (minimum > maximum) {
            throw new IllegalArgumentException("output range minimum must be <= maximum");
        }
    }

    public static ScorerOutputRange unitInterval() {
        return new ScorerOutputRange(0.0, 1.0);
    }

    public boolean isCompatibleWithAnomalyScorerContract() {
        return minimum >= 0.0 && maximum <= 1.0;
    }
}

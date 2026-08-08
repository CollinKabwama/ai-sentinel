package dev.aisentinel.core.scoring;

/**
 * Immutable explanation of the latest {@link StatisticalScorer#score} evaluation for one identity|endpoint key.
 * <p>
 * Values are captured during scoring (not recomputed later). Isolation Forest has no per-feature attribution;
 * this snapshot covers the statistical path only.
 *
 * @param score           score returned to callers (warmup score or live sigmoid of max capped |z|)
 * @param warmup          {@code true} when live z-scoring was not used
 * @param dominantFeature statistical feature name for the max capped |z|; {@code null} during warmup
 * @param observedValue   feature value that produced the dominant |z|; {@code null} during warmup
 * @param referenceMean   Welford mean for that feature; {@code null} during warmup
 * @param effectiveStd    std after resolution / {@code MIN_STD} floors; {@code null} during warmup
 * @param rawAbsZ         absolute z before per-feature cap; {@code null} during warmup
 * @param cappedAbsZ      absolute z after cap (feeds the sigmoid); {@code null} during warmup
 */
public record StatisticalScoreSnapshot(
    double score,
    boolean warmup,
    String dominantFeature,
    Double observedValue,
    Double referenceMean,
    Double effectiveStd,
    Double rawAbsZ,
    Double cappedAbsZ
) {
    public static StatisticalScoreSnapshot warmup(double score) {
        return new StatisticalScoreSnapshot(score, true, null, null, null, null, null, null);
    }
}

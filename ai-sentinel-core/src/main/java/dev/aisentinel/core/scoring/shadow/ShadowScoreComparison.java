package dev.aisentinel.core.scoring.shadow;

import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic observational comparison when both authoritative and candidate
 * scores are contract-valid.
 * <p>
 * {@code delta = candidateScore - authoritativeScore}<br>
 * A positive delta means only that the candidate produced a numerically higher
 * risk score — not that the candidate is “worse,” detected an attack, or holds
 * ground truth.
 * <p>
 * {@code DISAGREEMENT != DEFECT}<br>
 * {@code DISAGREEMENT != GROUND TRUTH}<br>
 * {@code ANOMALOUS != MALICIOUS}
 */
public final class ShadowScoreComparison {

    private final double authoritativeScore;
    private final double candidateScore;
    private final double delta;
    private final double absoluteDelta;
    private final ShadowClassificationAgreement classificationAgreement;

    private ShadowScoreComparison(
        double authoritativeScore,
        double candidateScore,
        ShadowClassificationAgreement classificationAgreement
    ) {
        if (!Double.isFinite(authoritativeScore) || authoritativeScore < 0.0) {
            throw new IllegalArgumentException("authoritativeScore must be finite and >= 0");
        }
        if (!Double.isFinite(candidateScore) || candidateScore < 0.0) {
            throw new IllegalArgumentException("candidateScore must be finite and >= 0");
        }
        this.authoritativeScore = authoritativeScore;
        this.candidateScore = candidateScore;
        this.delta = candidateScore - authoritativeScore;
        this.absoluteDelta = Math.abs(this.delta);
        this.classificationAgreement = classificationAgreement;
    }

    public static ShadowScoreComparison of(
        double authoritativeScore,
        double candidateScore,
        ShadowClassificationAgreement classificationAgreementOrNull
    ) {
        return new ShadowScoreComparison(
            authoritativeScore,
            candidateScore,
            classificationAgreementOrNull
        );
    }

    public double authoritativeScore() {
        return authoritativeScore;
    }

    public double candidateScore() {
        return candidateScore;
    }

    /** {@code candidateScore - authoritativeScore}. */
    public double delta() {
        return delta;
    }

    public double absoluteDelta() {
        return absoluteDelta;
    }

    public Optional<ShadowClassificationAgreement> classificationAgreement() {
        return Optional.ofNullable(classificationAgreement);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShadowScoreComparison that)) {
            return false;
        }
        return Double.compare(that.authoritativeScore, authoritativeScore) == 0
            && Double.compare(that.candidateScore, candidateScore) == 0
            && Double.compare(that.delta, delta) == 0
            && Double.compare(that.absoluteDelta, absoluteDelta) == 0
            && classificationAgreement == that.classificationAgreement;
    }

    @Override
    public int hashCode() {
        return Objects.hash(authoritativeScore, candidateScore, delta, absoluteDelta, classificationAgreement);
    }
}

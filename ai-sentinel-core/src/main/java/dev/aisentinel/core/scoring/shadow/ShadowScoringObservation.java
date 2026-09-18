package dev.aisentinel.core.scoring.shadow;

import dev.aisentinel.core.scoring.artifact.CandidateScorerProvenance;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Immutable observational result of one shadow scoring attempt.
 * <p>
 * Impossible states are rejected by factories (for example {@code DISABLED} with a
 * candidate score, or {@code SCORED} without provenance).
 * <p>
 * {@code SHADOW RESULT != PRODUCTION DECISION}
 */
public final class ShadowScoringObservation {

    private final ShadowScoringStatus status;
    private final Double candidateScore;
    private final ShadowScoreComparison comparison;
    private final CandidateScorerProvenance candidateProvenance;
    private final String correlationId;
    private final ShadowIneligibilityReason ineligibilityReason;
    private final String failureDetail;

    private ShadowScoringObservation(
        ShadowScoringStatus status,
        Double candidateScore,
        ShadowScoreComparison comparison,
        CandidateScorerProvenance candidateProvenance,
        String correlationId,
        ShadowIneligibilityReason ineligibilityReason,
        String failureDetail
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.candidateScore = candidateScore;
        this.comparison = comparison;
        this.candidateProvenance = candidateProvenance;
        this.correlationId = correlationId;
        this.ineligibilityReason = ineligibilityReason;
        this.failureDetail = failureDetail;
    }

    public static ShadowScoringObservation disabled(String correlationId) {
        return new ShadowScoringObservation(
            ShadowScoringStatus.DISABLED,
            null,
            null,
            null,
            correlationId,
            null,
            null
        );
    }

    public static ShadowScoringObservation notEligible(
        ShadowIneligibilityReason reason,
        CandidateScorerProvenance provenanceOrNull,
        String correlationId
    ) {
        Objects.requireNonNull(reason, "reason");
        return new ShadowScoringObservation(
            ShadowScoringStatus.NOT_ELIGIBLE,
            null,
            null,
            provenanceOrNull,
            correlationId,
            reason,
            null
        );
    }

    public static ShadowScoringObservation candidateUnavailable(String correlationId) {
        return new ShadowScoringObservation(
            ShadowScoringStatus.CANDIDATE_UNAVAILABLE,
            null,
            null,
            null,
            correlationId,
            null,
            null
        );
    }

    public static ShadowScoringObservation scored(
        double candidateScore,
        ShadowScoreComparison comparisonOrNull,
        CandidateScorerProvenance provenance,
        String correlationId
    ) {
        Objects.requireNonNull(provenance, "provenance");
        if (!Double.isFinite(candidateScore) || candidateScore < 0.0) {
            throw new IllegalArgumentException("SCORED observation requires a finite candidate score >= 0");
        }
        return new ShadowScoringObservation(
            ShadowScoringStatus.SCORED,
            candidateScore,
            comparisonOrNull,
            provenance,
            correlationId,
            null,
            null
        );
    }

    public static ShadowScoringObservation candidateInvalidScore(
        CandidateScorerProvenance provenance,
        String correlationId
    ) {
        Objects.requireNonNull(provenance, "provenance");
        return new ShadowScoringObservation(
            ShadowScoringStatus.CANDIDATE_INVALID_SCORE,
            null,
            null,
            provenance,
            correlationId,
            null,
            null
        );
    }

    public static ShadowScoringObservation candidateExecutionFailed(
        CandidateScorerProvenance provenance,
        String failureDetail,
        String correlationId
    ) {
        Objects.requireNonNull(provenance, "provenance");
        String detail = failureDetail == null || failureDetail.isBlank()
            ? "candidate execution failed"
            : failureDetail;
        return new ShadowScoringObservation(
            ShadowScoringStatus.CANDIDATE_EXECUTION_FAILED,
            null,
            null,
            provenance,
            correlationId,
            null,
            detail
        );
    }

    public ShadowScoringStatus status() {
        return status;
    }

    public OptionalDouble candidateScore() {
        return candidateScore == null ? OptionalDouble.empty() : OptionalDouble.of(candidateScore);
    }

    public Optional<ShadowScoreComparison> comparison() {
        return Optional.ofNullable(comparison);
    }

    public Optional<CandidateScorerProvenance> candidateProvenance() {
        return Optional.ofNullable(candidateProvenance);
    }

    public Optional<String> correlationId() {
        return Optional.ofNullable(correlationId);
    }

    public Optional<ShadowIneligibilityReason> ineligibilityReason() {
        return Optional.ofNullable(ineligibilityReason);
    }

    public Optional<String> failureDetail() {
        return Optional.ofNullable(failureDetail);
    }
}

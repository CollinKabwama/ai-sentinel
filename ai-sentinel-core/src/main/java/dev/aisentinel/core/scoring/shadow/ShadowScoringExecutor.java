package dev.aisentinel.core.scoring.shadow;

import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Executes observational candidate scoring beside an authoritative score.
 * <p>
 * Never feeds candidate output into policy, enforcement, or baseline update.
 * Never substitutes the candidate for an authoritative scorer failure.
 * Never calls {@link AnomalyScorer#update} on the candidate (shadow observation
 * is not training).
 * <p>
 * Default: {@link #disabled()}. Activation requires explicit configuration and
 * an identity-bound accepted candidate.
 * <p>
 * Synchronous in-process execution adds work to the evaluation path when enabled;
 * it is not process isolation and does not claim zero latency impact.
 * <p>
 * {@code SHADOW RESULT != PRODUCTION DECISION}<br>
 * {@code CANDIDATE FAILURE != AUTHORITATIVE FAILURE}<br>
 * {@code TELEMETRY FAILURE != REQUEST FAILURE}<br>
 * {@code SHADOW OBSERVATION != TRAINING}
 */
@Slf4j
public final class ShadowScoringExecutor {

    private final ShadowScoringConfiguration configuration;
    private final ShadowCandidateBinding candidateOrNull;
    private final ShadowObservationSink sink;

    private ShadowScoringExecutor(
        ShadowScoringConfiguration configuration,
        ShadowCandidateBinding candidateOrNull,
        ShadowObservationSink sink
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.candidateOrNull = candidateOrNull;
        this.sink = sink != null ? sink : ShadowObservationSink.NOOP;
    }

    public static ShadowScoringExecutor disabled() {
        return new ShadowScoringExecutor(
            ShadowScoringConfiguration.disabled(),
            null,
            ShadowObservationSink.NOOP
        );
    }

    public static ShadowScoringExecutor of(
        ShadowScoringConfiguration configuration,
        ShadowCandidateBinding candidateOrNull,
        ShadowObservationSink sink
    ) {
        return new ShadowScoringExecutor(configuration, candidateOrNull, sink);
    }

    public ShadowScoringConfiguration configuration() {
        return configuration;
    }

    /**
     * Observes the candidate against the same request features used for the
     * authoritative score. Never mutates authoritative scorer state.
     *
     * @param authoritativeRawScore raw authoritative score (may be invalid)
     * @param features              shared request features (projection left to each scorer)
     * @param correlationId         privacy-safe correlation id (for example pseudonymized identity hash); may be null
     */
    public ShadowScoringObservation observe(
        double authoritativeRawScore,
        RequestFeatures features,
        String correlationId
    ) {
        Objects.requireNonNull(features, "features");
        ShadowScoringObservation observation = observeInternal(authoritativeRawScore, features, correlationId);
        emitSafely(observation);
        return observation;
    }

    private ShadowScoringObservation observeInternal(
        double authoritativeRawScore,
        RequestFeatures features,
        String correlationId
    ) {
        if (!configuration.enabled()) {
            return ShadowScoringObservation.disabled(correlationId);
        }

        AcceptedCandidateIdentity acceptedIdentity = configuration.acceptedIdentity();
        if (acceptedIdentity == null) {
            return ShadowScoringObservation.notEligible(
                ShadowIneligibilityReason.MISSING_ACCEPTED_IDENTITY,
                null,
                correlationId
            );
        }

        if (candidateOrNull == null) {
            return ShadowScoringObservation.candidateUnavailable(correlationId);
        }

        if (!acceptedIdentity.matches(candidateOrNull.provenance())) {
            return ShadowScoringObservation.notEligible(
                ShadowIneligibilityReason.IDENTITY_MISMATCH,
                candidateOrNull.provenance(),
                correlationId
            );
        }

        double rawCandidateScore;
        try {
            rawCandidateScore = candidateOrNull.scorer().score(features);
        } catch (RuntimeException e) {
            log.debug("Shadow candidate scoring failed (contained): {}: {}",
                e.getClass().getSimpleName(), e.getMessage());
            return ShadowScoringObservation.candidateExecutionFailed(
                candidateOrNull.provenance(),
                safeFailureDetail(e),
                correlationId
            );
        }

        if (isInvalidScore(rawCandidateScore)) {
            return ShadowScoringObservation.candidateInvalidScore(
                candidateOrNull.provenance(),
                correlationId
            );
        }

        double candidateScore = clampFiniteScore(rawCandidateScore);
        ShadowScoreComparison comparison = null;
        if (!isInvalidScore(authoritativeRawScore)) {
            double authoritativeScore = clampFiniteScore(authoritativeRawScore);
            ShadowClassificationAgreement agreement = classifyIfConfigured(
                authoritativeScore, candidateScore);
            comparison = ShadowScoreComparison.of(authoritativeScore, candidateScore, agreement);
        }

        return ShadowScoringObservation.scored(
            candidateScore,
            comparison,
            candidateOrNull.provenance(),
            correlationId
        );
    }

    private ShadowClassificationAgreement classifyIfConfigured(
        double authoritativeScore,
        double candidateScore
    ) {
        OptionalDouble thresholdOpt = configuration.diagnosticAnomalyThreshold();
        if (thresholdOpt.isEmpty()) {
            return null;
        }
        double threshold = thresholdOpt.getAsDouble();
        boolean authoritativeAnomalous = authoritativeScore >= threshold;
        boolean candidateAnomalous = candidateScore >= threshold;
        if (authoritativeAnomalous && candidateAnomalous) {
            return ShadowClassificationAgreement.AGREE_ANOMALOUS;
        }
        if (!authoritativeAnomalous && !candidateAnomalous) {
            return ShadowClassificationAgreement.AGREE_NORMAL;
        }
        if (authoritativeAnomalous) {
            return ShadowClassificationAgreement.AUTHORITATIVE_ONLY_ANOMALOUS;
        }
        return ShadowClassificationAgreement.CANDIDATE_ONLY_ANOMALOUS;
    }

    private void emitSafely(ShadowScoringObservation observation) {
        try {
            sink.accept(observation);
        } catch (RuntimeException e) {
            log.debug("Shadow observation sink failed (contained): {}: {}",
                e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static String safeFailureDetail(RuntimeException e) {
        String type = e.getClass().getSimpleName();
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        // Keep short and free of request payload; class + truncated message only.
        String trimmed = message.length() > 120 ? message.substring(0, 120) : message;
        return type + ": " + trimmed;
    }

    /** Same invalid-score contract as the authoritative decision path. */
    static boolean isInvalidScore(double score) {
        return Double.isNaN(score) || Double.isInfinite(score) || score < 0;
    }

    /** Range-clamps a valid finite {@code >= 0} score into {@code [0, 1]}. */
    static double clampFiniteScore(double score) {
        return Math.min(1.0, score);
    }
}

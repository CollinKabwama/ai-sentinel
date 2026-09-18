package dev.aisentinel.core.scoring.lifecycle;

import dev.aisentinel.core.evaluation.DetectionEvaluationMetrics;
import dev.aisentinel.core.evaluation.DetectionMetricValue;
import dev.aisentinel.core.evaluation.DetectionMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic objective comparison of champion vs challenger offline metrics.
 * <p>
 * Does not select a winner. {@code METRIC DELTA != GOVERNANCE DECISION}
 */
public final class ChampionChallengerComparison {

    private final ChampionChallengerComparisonStatus status;
    private final ModelLifecycleIdentity champion;
    private final ModelLifecycleIdentity challenger;
    private final EvaluationEvidenceBinding championEvidence;
    private final EvaluationEvidenceBinding challengerEvidence;
    private final List<MetricDelta> metricDeltas;
    private final ShadowObservationSummary shadowSummary;
    private final List<String> limitations;
    private final String comparisonSha256Hex;
    private final String incompatibilityDetail;

    private ChampionChallengerComparison(
        ChampionChallengerComparisonStatus status,
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger,
        EvaluationEvidenceBinding championEvidence,
        EvaluationEvidenceBinding challengerEvidence,
        List<MetricDelta> metricDeltas,
        ShadowObservationSummary shadowSummary,
        List<String> limitations,
        String incompatibilityDetail
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.champion = Objects.requireNonNull(champion, "champion");
        this.challenger = Objects.requireNonNull(challenger, "challenger");
        this.championEvidence = Objects.requireNonNull(championEvidence, "championEvidence");
        this.challengerEvidence = Objects.requireNonNull(challengerEvidence, "challengerEvidence");
        this.metricDeltas = List.copyOf(metricDeltas);
        this.shadowSummary = shadowSummary;
        this.limitations = List.copyOf(limitations);
        this.incompatibilityDetail = incompatibilityDetail;
        this.comparisonSha256Hex = ModelLifecycleCanonical.sha256Hex(canonicalMaterial());
    }

    public static ChampionChallengerComparison compare(
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger,
        DetectionEvaluationMetrics championMetrics,
        DetectionEvaluationMetrics challengerMetrics,
        EvaluationEvidenceBinding championEvidence,
        EvaluationEvidenceBinding challengerEvidence,
        ShadowObservationSummary shadowSummaryOrNull
    ) {
        Objects.requireNonNull(champion, "champion");
        Objects.requireNonNull(challenger, "challenger");
        Objects.requireNonNull(championEvidence, "championEvidence");
        Objects.requireNonNull(challengerEvidence, "challengerEvidence");
        if (champion.matches(challenger)) {
            return incompatible(champion, challenger, championEvidence, challengerEvidence, shadowSummaryOrNull,
                "champion and challenger identities must differ");
        }
        if (championMetrics == null || challengerMetrics == null) {
            return insufficient(champion, challenger, championEvidence, challengerEvidence, shadowSummaryOrNull,
                "champion and challenger metrics are required");
        }

        List<String> incompat = new ArrayList<>();
        if (!championEvidence.datasetId().equals(challengerEvidence.datasetId())
            || !championMetrics.datasetId().equals(challengerMetrics.datasetId())
            || !championEvidence.datasetId().equals(championMetrics.datasetId())
            || !challengerEvidence.datasetId().equals(challengerMetrics.datasetId())) {
            incompat.add("dataset identity mismatch");
        }
        if (optionalMismatch(championEvidence.evaluationJsonSha256Hex(), challengerEvidence.evaluationJsonSha256Hex())) {
            incompat.add("evaluation evidence hash mismatch");
        }
        if (Double.compare(championEvidence.classificationThreshold(), challengerEvidence.classificationThreshold()) != 0
            || Double.compare(championMetrics.classification().anomalyThreshold(),
                challengerMetrics.classification().anomalyThreshold()) != 0
            || Double.compare(championEvidence.classificationThreshold(),
                championMetrics.classification().anomalyThreshold()) != 0
            || Double.compare(challengerEvidence.classificationThreshold(),
                challengerMetrics.classification().anomalyThreshold()) != 0) {
            incompat.add("classification threshold mismatch");
        }
        if (championEvidence.featureSchemaVersion().isPresent()
            != challengerEvidence.featureSchemaVersion().isPresent()
            || (championEvidence.featureSchemaVersion().isPresent()
                && !championEvidence.featureSchemaVersion().orElseThrow()
                    .equals(challengerEvidence.featureSchemaVersion().orElseThrow()))) {
            incompat.add("feature schema version mismatch");
        }
        if (championEvidence.evaluationSplitId().isPresent()
            != challengerEvidence.evaluationSplitId().isPresent()
            || (championEvidence.evaluationSplitId().isPresent()
                && !championEvidence.evaluationSplitId().orElseThrow()
                    .equals(challengerEvidence.evaluationSplitId().orElseThrow()))) {
            incompat.add("evaluation split identity mismatch");
        }
        if (!incompat.isEmpty()) {
            return incompatible(champion, challenger, championEvidence, challengerEvidence, shadowSummaryOrNull,
                String.join("; ", incompat));
        }

        DetectionMetrics c = championMetrics.metrics();
        DetectionMetrics x = challengerMetrics.metrics();
        List<MetricDelta> deltas = List.of(
            delta("precision", c.precision(), x.precision()),
            delta("recall", c.recall(), x.recall()),
            delta("f1", c.f1(), x.f1()),
            delta("falsePositiveRate", c.falsePositiveRate(), x.falsePositiveRate()),
            delta("falseNegativeRate", c.falseNegativeRate(), x.falseNegativeRate())
        );

        List<String> limitations = new ArrayList<>();
        limitations.add("METRIC DELTA != GOVERNANCE DECISION");
        limitations.add("COMPARISON != WINNER SELECTION");
        limitations.add("REFERENCE THRESHOLD != PRODUCTION THRESHOLD");
        limitations.add("REFERENCE THRESHOLD != PROMOTION THRESHOLD");
        if (shadowSummaryOrNull != null) {
            limitations.add("SHADOW DISAGREEMENT != GROUND TRUTH");
            limitations.add("shadow summary is caller-supplied; core owns no durable shadow history");
        } else {
            limitations.add("no shadow summary supplied");
        }

        return new ChampionChallengerComparison(
            ChampionChallengerComparisonStatus.COMPARABLE,
            champion,
            challenger,
            championEvidence,
            challengerEvidence,
            deltas,
            shadowSummaryOrNull,
            limitations,
            null
        );
    }

    private static ChampionChallengerComparison incompatible(
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger,
        EvaluationEvidenceBinding championEvidence,
        EvaluationEvidenceBinding challengerEvidence,
        ShadowObservationSummary shadow,
        String detail
    ) {
        return new ChampionChallengerComparison(
            ChampionChallengerComparisonStatus.INCOMPATIBLE_EVIDENCE,
            champion,
            challenger,
            championEvidence,
            challengerEvidence,
            List.of(),
            shadow,
            List.of("DIFFERENT EVALUATION CONTEXT != VALID HEAD-TO-HEAD COMPARISON", detail),
            detail
        );
    }

    private static ChampionChallengerComparison insufficient(
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challenger,
        EvaluationEvidenceBinding championEvidence,
        EvaluationEvidenceBinding challengerEvidence,
        ShadowObservationSummary shadow,
        String detail
    ) {
        return new ChampionChallengerComparison(
            ChampionChallengerComparisonStatus.INSUFFICIENT_EVIDENCE,
            champion,
            challenger,
            championEvidence,
            challengerEvidence,
            List.of(),
            shadow,
            List.of(detail),
            detail
        );
    }

    private static MetricDelta delta(String name, DetectionMetricValue champion, DetectionMetricValue challenger) {
        if (champion.defined() && challenger.defined()) {
            return MetricDelta.ofDefined(name, champion.value(), challenger.value());
        }
        return MetricDelta.undefined(name,
            champion.defined() ? champion.value() : null,
            challenger.defined() ? challenger.value() : null);
    }

    private String canonicalMaterial() {
        StringBuilder sb = new StringBuilder();
        ModelLifecycleCanonical.appendField(sb, "status", status.name());
        ModelLifecycleCanonical.appendField(sb, "champion", champion.bindingKey());
        ModelLifecycleCanonical.appendField(sb, "challenger", challenger.bindingKey());
        appendEvidence(sb, "championEvidence", championEvidence);
        appendEvidence(sb, "challengerEvidence", challengerEvidence);
        for (MetricDelta d : metricDeltas) {
            ModelLifecycleCanonical.appendField(sb, "metricName", d.metricName());
            ModelLifecycleCanonical.appendField(sb, "metricChampionValue",
                d.championValue().isPresent() ? Double.toString(d.championValue().getAsDouble()) : "null");
            ModelLifecycleCanonical.appendField(sb, "metricChallengerValue",
                d.challengerValue().isPresent() ? Double.toString(d.challengerValue().getAsDouble()) : "null");
            ModelLifecycleCanonical.appendField(sb, "metricDelta",
                d.delta().isPresent() ? Double.toString(d.delta().getAsDouble()) : "null");
        }
        if (shadowSummary != null) {
            ModelLifecycleCanonical.appendField(sb, "shadowAttempted",
                Long.toString(shadowSummary.attemptedShadowExecutions()));
            ModelLifecycleCanonical.appendField(sb, "shadowValid",
                Long.toString(shadowSummary.validComparisonCount()));
            ModelLifecycleCanonical.appendField(sb, "shadowInvalid",
                Long.toString(shadowSummary.candidateInvalidScoreCount()));
            ModelLifecycleCanonical.appendField(sb, "shadowFailures",
                Long.toString(shadowSummary.candidateExecutionFailureCount()));
            ModelLifecycleCanonical.appendField(sb, "shadowAgreement",
                Long.toString(shadowSummary.agreementCount()));
            ModelLifecycleCanonical.appendField(sb, "shadowDisagreement",
                Long.toString(shadowSummary.disagreementCount()));
            ModelLifecycleCanonical.appendField(sb, "shadowMeanAbsDelta",
                Double.toString(shadowSummary.meanAbsoluteScoreDelta()));
        }
        if (incompatibilityDetail != null) {
            ModelLifecycleCanonical.appendField(sb, "incompatibilityDetail", incompatibilityDetail);
        }
        return sb.toString();
    }

    private static void appendEvidence(StringBuilder sb, String prefix, EvaluationEvidenceBinding evidence) {
        ModelLifecycleCanonical.appendField(sb, prefix + ".datasetId", evidence.datasetId());
        ModelLifecycleCanonical.appendField(sb, prefix + ".classificationThreshold",
            Double.toString(evidence.classificationThreshold()));
        ModelLifecycleCanonical.appendField(sb, prefix + ".acceptanceStatus", evidence.acceptanceStatus().name());
        ModelLifecycleCanonical.appendField(sb, prefix + ".evaluationJsonSha256Hex",
            evidence.evaluationJsonSha256Hex().orElse(""));
        ModelLifecycleCanonical.appendField(sb, prefix + ".featureSchemaVersion",
            evidence.featureSchemaVersion().orElse(""));
        ModelLifecycleCanonical.appendField(sb, prefix + ".evaluationSplitId",
            evidence.evaluationSplitId().orElse(""));
    }

    private static boolean optionalMismatch(Optional<String> left, Optional<String> right) {
        return left.isPresent() != right.isPresent()
            || (left.isPresent() && !left.orElseThrow().equals(right.orElseThrow()));
    }

    public ChampionChallengerComparisonStatus status() {
        return status;
    }

    public ModelLifecycleIdentity champion() {
        return champion;
    }

    public ModelLifecycleIdentity challenger() {
        return challenger;
    }

    public EvaluationEvidenceBinding championEvidence() {
        return championEvidence;
    }

    public EvaluationEvidenceBinding challengerEvidence() {
        return challengerEvidence;
    }

    public List<MetricDelta> metricDeltas() {
        return metricDeltas;
    }

    public Optional<ShadowObservationSummary> shadowSummary() {
        return Optional.ofNullable(shadowSummary);
    }

    public List<String> limitations() {
        return limitations;
    }

    public String comparisonSha256Hex() {
        return comparisonSha256Hex;
    }

    public Optional<String> incompatibilityDetail() {
        return Optional.ofNullable(incompatibilityDetail);
    }
}

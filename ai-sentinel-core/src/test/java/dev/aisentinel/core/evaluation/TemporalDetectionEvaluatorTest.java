package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotationsLoader;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import dev.aisentinel.core.replay.ReplayEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalDetectionEvaluatorTest {
    private static final DetectionClassificationConfiguration THRESHOLD = new DetectionClassificationConfiguration(0.5);

    @TempDir
    Path tempDir;

    @Test
    void immediateDetectionAtAnomalyOnsetHasZeroDelay() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.MONITOR, List.of()),
            anomalousObservation("evt-2", 2, 0.9, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.detected()).isTrue();
        assertThat(segment.detectionObservationDelay()).isZero();
        assertThat(segment.evaluableObservationDelay()).isZero();
        assertThat(segment.detectionTimeDelay()).isEqualTo(Duration.ZERO);
    }

    @Test
    void detectionOnSecondAnomalousObservationHasDelayOne() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.1, EnforcementAction.MONITOR, List.of()),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.detectionObservationDelay()).isEqualTo(1);
        assertThat(segment.evaluableObservationDelay()).isEqualTo(1);
    }

    @Test
    void detectionAfterMultipleAnomalousObservationsHasDeterministicDelay() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.1, EnforcementAction.MONITOR, List.of()),
            anomalousObservation("evt-2", 2, 0.2, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-3", 3, 0.8, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-4", 4, 0.9, EnforcementAction.BLOCK, List.of())
        );

        assertThat(segment.detectionObservationDelay()).isEqualTo(2);
        assertThat(segment.evaluableObservationDelay()).isEqualTo(2);
    }

    @Test
    void anomalySegmentCanRemainUndetected() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.1, EnforcementAction.MONITOR, List.of()),
            anomalousObservation("evt-2", 2, 0.2, EnforcementAction.BLOCK, List.of())
        );

        assertThat(segment.detected()).isFalse();
        assertThat(segment.firstDetection()).isNull();
        assertThat(segment.detectionObservationDelay()).isNull();
        assertThat(segment.evaluableObservationDelay()).isNull();
        assertThat(segment.detectionTimeDelay()).isNull();
    }

    @Test
    void scoreEqualToThresholdDetectsAtOnset() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.5, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.detected()).isTrue();
        assertThat(segment.detectionObservationDelay()).isZero();
    }

    @Test
    void scoreBelowThresholdThenEqualThresholdDelaysDetection() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.49, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", 2, 0.5, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.detectionObservationDelay()).isEqualTo(1);
    }

    @Test
    void policyMonitorDoesNotCreateDetectionWithoutPositiveScore() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.1, EnforcementAction.MONITOR, List.of(EvaluationStatus.STATISTICAL_WARMUP))
        );

        assertThat(segment.detected()).isFalse();
    }

    @Test
    void policyBlockDoesNotCreateDetectionWhenScoreIsBelowThreshold() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.2, EnforcementAction.BLOCK, List.of())
        );

        assertThat(segment.detected()).isFalse();
    }

    @Test
    void missingDetectorScoreBeforeDetectionIsExcludedWithoutShorteningSourceDelay() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, null, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", 2, 0.1, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-3", 3, 0.8, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.unavailableObservationCount()).isEqualTo(1);
        assertThat(segment.detectionObservationDelay()).isEqualTo(2);
        assertThat(segment.evaluableObservationDelay()).isEqualTo(1);
    }

    @Test
    void invalidScoreBeforeDetectionIsExcluded() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of(EvaluationStatus.INVALID_SCORE)),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.unavailableObservationCount()).isEqualTo(1);
        assertThat(segment.detectionObservationDelay()).isEqualTo(1);
        assertThat(segment.evaluableObservationDelay()).isZero();
    }

    @Test
    void remoteEvaluationFailureBeforeDetectionIsExcluded() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of(EvaluationStatus.REMOTE_EVALUATION_FAILURE)),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.unavailableObservationCount()).isEqualTo(1);
        assertThat(segment.detectionObservationDelay()).isEqualTo(1);
        assertThat(segment.evaluableObservationDelay()).isZero();
    }

    @Test
    void modelUnavailableFallbackOnlyEvidenceBeforeDetectionIsExcluded() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW,
                List.of(EvaluationStatus.MODEL_UNAVAILABLE, EvaluationStatus.MODEL_FALLBACK_USED)),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.unavailableObservationCount()).isEqualTo(1);
        assertThat(segment.detectionObservationDelay()).isEqualTo(1);
        assertThat(segment.evaluableObservationDelay()).isZero();
    }

    @Test
    void validDetectorEvidenceWithFallbackStatusesRemainsClassifiableWhenStatisticalEvidenceExists() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.MONITOR,
                List.of(EvaluationStatus.STATISTICAL_WARMUP, EvaluationStatus.MODEL_UNAVAILABLE, EvaluationStatus.MODEL_FALLBACK_USED))
        );

        assertThat(segment.detected()).isTrue();
        assertThat(segment.unavailableObservationCount()).isZero();
    }

    @Test
    void unavailableEvidenceThroughoutAnomalySegmentRemainsUndetected() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, null, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of(EvaluationStatus.INVALID_SCORE)),
            anomalousObservation("evt-3", 3, 0.8, EnforcementAction.ALLOW, List.of(EvaluationStatus.REMOTE_EVALUATION_FAILURE))
        );

        assertThat(segment.detected()).isFalse();
        assertThat(segment.unavailableObservationCount()).isEqualTo(3);
    }

    @Test
    void unavailableEvidenceAfterFirstDetectionStillCountsForSegment() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", 2, null, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-3", 3, 0.9, EnforcementAction.ALLOW, List.of(EvaluationStatus.INVALID_SCORE))
        );

        assertThat(segment.detected()).isTrue();
        assertThat(segment.detectionObservationDelay()).isZero();
        assertThat(segment.unavailableObservationCount()).isEqualTo(2);
    }

    @Test
    void immediateStableRecoveryHasZeroDelay() {
        TemporalRecoveryEvaluation recovery = evaluateSingleRecoveredSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", 2, 0.9, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-3", 3, 0.1, EnforcementAction.MONITOR, List.of()),
            normalObservation("evt-4", 4, 0.2, EnforcementAction.ALLOW, List.of())
        );

        assertThat(recovery.stabilized()).isTrue();
        assertThat(recovery.recoveryObservationDelay()).isZero();
        assertThat(recovery.evaluableRecoveryObservationDelay()).isZero();
        assertThat(recovery.recoveryTimeDelay()).isEqualTo(Duration.ZERO);
    }

    @Test
    void delayedRecoveryCountsFromRecoveryOnset() {
        TemporalRecoveryEvaluation recovery = evaluateSingleRecoveredSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-3", 3, 0.1, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-4", 4, 0.2, EnforcementAction.ALLOW, List.of())
        );

        assertThat(recovery.recoveryObservationDelay()).isEqualTo(1);
        assertThat(recovery.evaluableRecoveryObservationDelay()).isEqualTo(1);
    }

    @Test
    void transientNormalPredictionFollowedByRelapseDelaysStableRecovery() {
        TemporalRecoveryEvaluation recovery = evaluateSingleRecoveredSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, 0.1, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-3", 3, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-4", 4, 0.1, EnforcementAction.ALLOW, List.of())
        );

        assertThat(recovery.recoveryObservationDelay()).isEqualTo(2);
    }

    @Test
    void noRecoveryWithinObservedWindowIsExplicit() {
        TemporalRecoveryEvaluation recovery = evaluateSingleRecoveredSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-3", 3, 0.9, EnforcementAction.ALLOW, List.of())
        );

        assertThat(recovery.stabilized()).isFalse();
        assertThat(recovery.firstStableNormalPrediction()).isNull();
    }

    @Test
    void recoverySegmentWithUnavailableEvidenceCountsUnavailableWithoutFakingStability() {
        TemporalRecoveryEvaluation recovery = evaluateSingleRecoveredSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, null, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-3", 3, 0.1, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-4", 4, 0.2, EnforcementAction.ALLOW, List.of())
        );

        assertThat(recovery.unavailableObservationCount()).isEqualTo(1);
        assertThat(recovery.recoveryObservationDelay()).isEqualTo(1);
        assertThat(recovery.evaluableRecoveryObservationDelay()).isZero();
    }

    @Test
    void recoveryAtFinalObservationIsAllowed() {
        TemporalRecoveryEvaluation recovery = evaluateSingleRecoveredSegment(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-3", 3, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-4", 4, 0.1, EnforcementAction.ALLOW, List.of())
        );

        assertThat(recovery.recoveryObservationDelay()).isEqualTo(2);
    }

    @Test
    void multipleAnomalousSegmentsAreRepresentedIndependently() {
        ScenarioTemporalEvaluation scenario = evaluateScenario(
            normalObservation("evt-1", 1, 0.1, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-3", 3, 0.1, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-4", 4, 0.1, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-5", 5, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-6", 6, 0.1, EnforcementAction.ALLOW, List.of())
        );

        assertThat(scenario.anomalySegments()).hasSize(2);
        assertThat(scenario.anomalySegments()).extracting(TemporalAnomalySegment::segmentIndex)
            .containsExactly(0, 1);
    }

    @Test
    void scenarioContainingOnlyNormalObservationsHasNoAnomalySegments() {
        ScenarioTemporalEvaluation scenario = evaluateScenario(
            normalObservation("evt-1", 1, 0.1, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, 0.2, EnforcementAction.ALLOW, List.of())
        );

        assertThat(scenario.anomalySegments()).isEmpty();
    }

    @Test
    void scenarioBeginningAnomalousIsSupported() {
        ScenarioTemporalEvaluation scenario = evaluateScenario(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, 0.1, EnforcementAction.ALLOW, List.of())
        );

        assertThat(scenario.anomalySegments()).hasSize(1);
        assertThat(scenario.anomalySegments().getFirst().anomalyOnset().eventId()).isEqualTo("evt-1");
    }

    @Test
    void scenarioEndingAnomalousHasNoRecoverySegment() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            normalObservation("evt-1", 1, 0.1, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-3", 3, 0.9, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.recovery()).isNull();
    }

    @Test
    void legitimateAnomalousSegmentUsesNormalAnomalyTemporalSemantics() {
        TemporalAnomalySegment segment = evaluateSingleAnomalousSegment(
            legitimateAnomalousObservation("evt-1", 1, 0.8, false, EnforcementAction.ALLOW, List.of())
        );

        assertThat(segment.detected()).isTrue();
        assertThat(segment.detectionObservationDelay()).isZero();
    }

    @Test
    void maliciousnessDoesNotAlterTemporalAnomalySemantics() {
        TemporalDetectionEvaluation asserted = evaluate(
            alignment(List.of(anomalousObservation("evt-1", 1, 0.8, true, EnforcementAction.ALLOW, List.of())))
        );
        TemporalDetectionEvaluation notAsserted = evaluate(
            alignment(List.of(anomalousObservation("evt-1", 1, 0.8, false, EnforcementAction.ALLOW, List.of())))
        );

        assertThat(asserted.scenarios()).isEqualTo(notAsserted.scenarios());
    }

    @Test
    void sourceOrderAndScenarioOrderingAreDeterministic() {
        TemporalDetectionEvaluation evaluation = evaluate(alignment(List.of(
            anomalousObservation("evt-1", "scenario-b", 1, 0.8, false, EnforcementAction.ALLOW, List.of()),
            anomalousObservation("evt-2", "scenario-a", 2, 0.8, false, EnforcementAction.ALLOW, List.of())
        )));

        assertThat(evaluation.scenarios()).extracting(ScenarioTemporalEvaluation::scenarioId)
            .containsExactly("scenario-b", "scenario-a");
    }

    @Test
    void malformedDecreasingTimestampIsRejected() {
        assertThatThrownBy(() -> evaluate(alignment(List.of(
            anomalousObservation("evt-1", 2, 0.8, EnforcementAction.ALLOW, List.of()),
            new EvaluationObservation(
                "evt-2",
                "scenario-1",
                ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
                3,
                Instant.parse("2026-01-01T00:00:01Z"),
                "identity-scenario-1",
                new EvaluationTruth(ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false),
                new EvaluationPrediction(EvaluationPredictionSource.REPLAY_SCORE, 0.9, 0.9, EnforcementAction.ALLOW, List.of())
            )
        ))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("observedAt");
    }

    @Test
    void scenarioCategoryConflictsRemainRejected() {
        assertThatThrownBy(() -> evaluate(alignment(List.of(
            anomalousObservation("evt-1", "scenario-1", 1, 0.8, false, EnforcementAction.ALLOW, List.of()),
            new EvaluationObservation(
                "evt-2",
                "scenario-1",
                ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
                2,
                Instant.parse("2026-01-01T00:00:02Z"),
                "identity-scenario-1",
                new EvaluationTruth(ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false),
                new EvaluationPrediction(EvaluationPredictionSource.REPLAY_SCORE, 0.9, 0.9, EnforcementAction.ALLOW, List.of())
            )
        ))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conflicting categories");
    }

    @Test
    void classificationConsistencyMatchesDetectionMetricsCalculator() {
        ReferenceEvaluationAlignment alignment = alignment(List.of(
            anomalousObservation("evt-1", 1, null, EnforcementAction.ALLOW,
                List.of(EvaluationStatus.MODEL_UNAVAILABLE, EvaluationStatus.MODEL_FALLBACK_USED)),
            anomalousObservation("evt-2", 2, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-3", 3, 0.1, EnforcementAction.ALLOW, List.of())
        ));

        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment, THRESHOLD);
        TemporalAnomalySegment segment = evaluate(alignment).scenarios().getFirst().anomalySegments().getFirst();

        assertThat(metrics.evaluablePredictionCount()).isEqualTo(2);
        assertThat(metrics.excludedPredictionCount()).isEqualTo(1);
        assertThat(segment.unavailableObservationCount()).isEqualTo(1);
        assertThat(segment.detected()).isTrue();
    }

    @Test
    void repeatedEvaluationProducesEqualResults() {
        ReferenceEvaluationAlignment alignment = alignment(List.of(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of()),
            normalObservation("evt-2", 2, 0.1, EnforcementAction.ALLOW, List.of())
        ));

        TemporalDetectionEvaluation first = evaluate(alignment);
        TemporalDetectionEvaluation second = evaluate(alignment);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void returnedCollectionsAreImmutable() {
        TemporalDetectionEvaluation evaluation = evaluate(alignment(List.of(
            anomalousObservation("evt-1", 1, 0.8, EnforcementAction.ALLOW, List.of())
        )));

        assertThatThrownBy(() -> evaluation.scenarios().add(evaluation.scenarios().getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evaluation.scenarios().getFirst().anomalySegments().add(
            evaluation.scenarios().getFirst().anomalySegments().getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void temporalSegmentRejectsContradictoryDetectedState() {
        TemporalObservationPoint onset = point("evt-1", 1);
        TemporalObservationPoint end = point("evt-2", 2);

        assertThatThrownBy(() -> new TemporalAnomalySegment(
            0,
            onset,
            end,
            2,
            true,
            null,
            0,
            0,
            Duration.ZERO,
            0,
            null
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalAnomalySegment(
            0,
            onset,
            end,
            2,
            false,
            onset,
            0,
            0,
            Duration.ZERO,
            0,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("undetected");
    }

    @Test
    void temporalSegmentRejectsDetectionOutsideWindowOrMismatchedDelay() {
        TemporalObservationPoint onset = point("evt-1", 1);
        TemporalObservationPoint end = point("evt-2", 2);
        TemporalObservationPoint outside = point("evt-3", 3);

        assertThatThrownBy(() -> new TemporalAnomalySegment(
            0,
            onset,
            end,
            2,
            true,
            outside,
            1,
            1,
            Duration.ofSeconds(2),
            0,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("firstDetection");
        assertThatThrownBy(() -> new TemporalAnomalySegment(
            0,
            onset,
            end,
            2,
            true,
            end,
            1,
            0,
            Duration.ZERO,
            0,
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("detectionTimeDelay");
    }

    @Test
    void recoveryRejectsContradictoryStabilizationState() {
        TemporalObservationPoint onset = point("evt-1", 1);
        TemporalObservationPoint end = point("evt-2", 2);

        assertThatThrownBy(() -> new TemporalRecoveryEvaluation(
            onset,
            end,
            2,
            true,
            null,
            0,
            0,
            Duration.ZERO,
            0
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalRecoveryEvaluation(
            onset,
            end,
            2,
            false,
            onset,
            0,
            0,
            Duration.ZERO,
            0
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unstabilized");
    }

    @Test
    void recoveryRejectsStablePointOutsideWindowOrMismatchedDelay() {
        TemporalObservationPoint onset = point("evt-1", 1);
        TemporalObservationPoint end = point("evt-2", 2);
        TemporalObservationPoint outside = point("evt-3", 3);

        assertThatThrownBy(() -> new TemporalRecoveryEvaluation(
            onset,
            end,
            2,
            true,
            outside,
            1,
            1,
            Duration.ofSeconds(2),
            0
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("firstStableNormalPrediction");
        assertThatThrownBy(() -> new TemporalRecoveryEvaluation(
            onset,
            end,
            2,
            true,
            end,
            1,
            0,
            Duration.ZERO,
            0
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recoveryTimeDelay");
    }

    @Test
    void scenarioTemporalEvaluationRejectsNonContiguousSegmentIndexes() {
        TemporalObservationPoint onset = point("evt-1", 1);
        TemporalAnomalySegment badIndex = new TemporalAnomalySegment(
            1,
            onset,
            onset,
            1,
            true,
            onset,
            0,
            0,
            Duration.ZERO,
            0,
            null
        );

        assertThatThrownBy(() -> new ScenarioTemporalEvaluation(
            "scenario-1",
            ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
            1,
            List.of(badIndex)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("segmentIndex");
    }

    @Test
    void consumesTrackedReferenceCorpusDeterministically() throws Exception {
        TemporalDetectionEvaluation evaluation = evaluate(alignTrackedCorpus());

        assertThat(evaluation.scenarios()).hasSize(11);
        long anomalySegments = evaluation.scenarios().stream().mapToLong(s -> s.anomalySegments().size()).sum();
        long recoverySegments = evaluation.scenarios().stream()
            .flatMap(s -> s.anomalySegments().stream())
            .filter(segment -> segment.recovery() != null)
            .count();
        assertThat(anomalySegments).isEqualTo(8);
        assertThat(recoverySegments).isZero();
    }

    private TemporalDetectionEvaluation evaluate(ReferenceEvaluationAlignment alignment) {
        return new TemporalDetectionEvaluator().evaluate(alignment, THRESHOLD);
    }

    private TemporalAnomalySegment evaluateSingleAnomalousSegment(EvaluationObservation... observations) {
        return evaluate(alignment(List.of(observations))).scenarios().getFirst().anomalySegments().getFirst();
    }

    private TemporalRecoveryEvaluation evaluateSingleRecoveredSegment(EvaluationObservation... observations) {
        return evaluateSingleAnomalousSegment(observations).recovery();
    }

    private ScenarioTemporalEvaluation evaluateScenario(EvaluationObservation... observations) {
        return evaluate(alignment(List.of(observations))).scenarios().getFirst();
    }

    private ReferenceEvaluationAlignment alignTrackedCorpus() throws Exception {
        ReplayDataset dataset = new ReplayDatasetLoader().load(repoRoot().resolve("evaluation/reference"));
        ReferenceDatasetAnnotations annotations = new ReferenceDatasetAnnotationsLoader()
            .load(repoRoot().resolve("evaluation/reference/annotations.json"));
        return new ReferenceEvaluationAligner().align(
            dataset,
            annotations,
            new ReplayEngine().run(dataset, ReplayConfiguration.referenceDefaults(), Files.createTempDirectory(tempDir, "replay-")).results()
        );
    }

    private static ReferenceEvaluationAlignment alignment(List<EvaluationObservation> observations) {
        List<EvaluationObservation> copy = new ArrayList<>(observations);
        return new ReferenceEvaluationAlignment(
            "dataset-id",
            "replay-run-id",
            copy.size(),
            (int) copy.stream().map(EvaluationObservation::scenarioId).distinct().count(),
            copy.size(),
            List.copyOf(copy)
        );
    }

    private static EvaluationObservation normalObservation(String eventId,
                                                           int sequenceNumber,
                                                           Double anomalyScore,
                                                           EnforcementAction action,
                                                           List<EvaluationStatus> statuses) {
        return observation(eventId, "scenario-1", sequenceNumber, ReferenceDatasetExpectedClass.NORMAL, false, false,
            anomalyScore, action, statuses);
    }

    private static EvaluationObservation anomalousObservation(String eventId,
                                                              int sequenceNumber,
                                                              Double anomalyScore,
                                                              EnforcementAction action,
                                                              List<EvaluationStatus> statuses) {
        return anomalousObservation(eventId, "scenario-1", sequenceNumber, anomalyScore, false, action, statuses);
    }

    private static EvaluationObservation anomalousObservation(String eventId,
                                                              int sequenceNumber,
                                                              Double anomalyScore,
                                                              boolean maliciousnessAsserted,
                                                              EnforcementAction action,
                                                              List<EvaluationStatus> statuses) {
        return anomalousObservation(eventId, "scenario-1", sequenceNumber, anomalyScore, maliciousnessAsserted, action, statuses);
    }

    private static EvaluationObservation anomalousObservation(String eventId,
                                                              String scenarioId,
                                                              int sequenceNumber,
                                                              Double anomalyScore,
                                                              boolean maliciousnessAsserted,
                                                              EnforcementAction action,
                                                              List<EvaluationStatus> statuses) {
        return observation(eventId, scenarioId, sequenceNumber, ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true,
            maliciousnessAsserted, anomalyScore, action, statuses);
    }

    private static EvaluationObservation legitimateAnomalousObservation(String eventId,
                                                                        int sequenceNumber,
                                                                        Double anomalyScore,
                                                                        boolean maliciousnessAsserted,
                                                                        EnforcementAction action,
                                                                        List<EvaluationStatus> statuses) {
        return observation(eventId, "scenario-1", sequenceNumber, ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS, true,
            maliciousnessAsserted, anomalyScore, action, statuses);
    }

    private static EvaluationObservation observation(String eventId,
                                                     String scenarioId,
                                                     int sequenceNumber,
                                                     ReferenceDatasetExpectedClass expectedClass,
                                                     boolean anomalyExpected,
                                                     boolean maliciousnessAsserted,
                                                     Double anomalyScore,
                                                     EnforcementAction action,
                                                     List<EvaluationStatus> statuses) {
        Instant observedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequenceNumber);
        return new EvaluationObservation(
            eventId,
            scenarioId,
            ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
            sequenceNumber,
            observedAt,
            "identity-" + scenarioId,
            new EvaluationTruth(expectedClass, anomalyExpected, maliciousnessAsserted),
            new EvaluationPrediction(
                EvaluationPredictionSource.REPLAY_SCORE,
                anomalyScore,
                anomalyScore,
                action,
                statuses
            )
        );
    }

    private static TemporalObservationPoint point(String eventId, int sequenceNumber) {
        return new TemporalObservationPoint(
            eventId,
            sequenceNumber,
            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequenceNumber)
        );
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}

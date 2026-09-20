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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetectionMetricsCalculatorTest {

    private static final DetectionClassificationConfiguration THRESHOLD = new DetectionClassificationConfiguration(0.5);

    @TempDir
    Path tempDir;

    @Test
    void rejectsInvalidThreshold() {
        assertThatThrownBy(() -> new DetectionClassificationConfiguration(Double.NaN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyThreshold");
        assertThatThrownBy(() -> new DetectionClassificationConfiguration(Double.POSITIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyThreshold");
        assertThatThrownBy(() -> new DetectionClassificationConfiguration(-0.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyThreshold");
        assertThatThrownBy(() -> new DetectionClassificationConfiguration(1.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyThreshold");
    }

    @Test
    void classifiesScoreBelowEqualAndAboveThresholdDeterministically() {
        DetectionClassificationConfiguration configuration = new DetectionClassificationConfiguration(0.5);

        assertThat(configuration.isPredictedAnomalous(0.49)).isFalse();
        assertThat(configuration.isPredictedAnomalous(0.5)).isTrue();
        assertThat(configuration.isPredictedAnomalous(0.51)).isTrue();
    }

    @Test
    void classifiesScoreAtZeroAndOneDeterministically() {
        DetectionClassificationConfiguration configuration = new DetectionClassificationConfiguration(0.5);

        assertThat(configuration.isPredictedAnomalous(0.0)).isFalse();
        assertThat(configuration.isPredictedAnomalous(1.0)).isTrue();
    }

    @Test
    void allowsThresholdZeroAndOneWithInclusiveBoundary() {
        DetectionClassificationConfiguration zero = new DetectionClassificationConfiguration(0.0);
        DetectionClassificationConfiguration one = new DetectionClassificationConfiguration(1.0);

        assertThat(zero.isPredictedAnomalous(0.0)).isTrue();
        assertThat(zero.isPredictedAnomalous(1.0)).isTrue();
        assertThat(one.isPredictedAnomalous(0.0)).isFalse();
        assertThat(one.isPredictedAnomalous(1.0)).isTrue();
    }

    @Test
    void rejectsInvalidScoresAtClassificationBoundary() {
        DetectionClassificationConfiguration configuration = new DetectionClassificationConfiguration(0.5);

        assertThatThrownBy(() -> configuration.isPredictedAnomalous(Double.NaN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyScore");
        assertThatThrownBy(() -> configuration.isPredictedAnomalous(Double.NEGATIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyScore");
        assertThatThrownBy(() -> configuration.isPredictedAnomalous(-0.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyScore");
        assertThatThrownBy(() -> configuration.isPredictedAnomalous(1.01))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anomalyScore");
    }

    @Test
    void policyActionsDoNotIndependentlyDeterminePositivePrediction() {
        DetectionMetricsCalculator calculator = new DetectionMetricsCalculator();
        for (EnforcementAction action : EnforcementAction.values()) {
            DetectionEvaluationMetrics lowScore = calculator.compute(alignment(
                observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, action, List.of())
            ), THRESHOLD);
            DetectionEvaluationMetrics highScore = calculator.compute(alignment(
                observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.90, action, List.of())
            ), THRESHOLD);

            assertThat(lowScore.confusionMatrix().trueNegatives()).isEqualTo(1);
            assertThat(highScore.confusionMatrix().falsePositives()).isEqualTo(1);
        }
    }

    @Test
    void aggregatesTruePositiveTrueNegativeFalsePositiveAndFalseNegativeCounts() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-tp", "scenario-a", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.ALLOW, List.of()),
            observation("evt-tn", "scenario-a", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.BLOCK, List.of()),
            observation("evt-fp", "scenario-b", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.95, EnforcementAction.MONITOR, List.of()),
            observation("evt-fn", "scenario-b", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.20, EnforcementAction.QUARANTINE, List.of())
        ), THRESHOLD);

        assertThat(metrics.confusionMatrix()).isEqualTo(new DetectionConfusionMatrix(1, 1, 1, 1));
        assertThat(metrics.evaluablePredictionCount()).isEqualTo(4);
        assertThat(metrics.excludedPredictionCount()).isZero();
        assertThat(metrics.totalObservationCount()).isEqualTo(4);
    }

    @Test
    void legitimateAnomalousDetectedCountsAsTruePositive() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS, true, false, 0.90, EnforcementAction.MONITOR, List.of())
        ), THRESHOLD);

        assertThat(metrics.confusionMatrix().truePositives()).isEqualTo(1);
    }

    @Test
    void legitimateAnomalousMissedCountsAsFalseNegative() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS, true, false, 0.20, EnforcementAction.BLOCK, List.of())
        ), THRESHOLD);

        assertThat(metrics.confusionMatrix().falseNegatives()).isEqualTo(1);
    }

    @Test
    void maliciousnessDoesNotAlterAnomalyConfusionAccounting() {
        DetectionMetricsCalculator calculator = new DetectionMetricsCalculator();
        DetectionEvaluationMetrics asserted = calculator.compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, true, 0.90, EnforcementAction.ALLOW, List.of())
        ), THRESHOLD);
        DetectionEvaluationMetrics notAsserted = calculator.compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.ALLOW, List.of())
        ), THRESHOLD);

        assertThat(asserted.confusionMatrix()).isEqualTo(notAsserted.confusionMatrix());
        assertThat(asserted.metrics()).isEqualTo(notAsserted.metrics());
    }

    @Test
    void missingDetectorScoreIsExcludedFromMetrics() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, null, EnforcementAction.ALLOW, List.of())
        ), THRESHOLD);

        assertThat(metrics.evaluablePredictionCount()).isZero();
        assertThat(metrics.excludedPredictionCount()).isEqualTo(1);
        assertThat(metrics.confusionMatrix().totalCount()).isZero();
    }

    @Test
    void invalidScoreStatusIsExcludedFromMetrics() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.ALLOW,
                List.of(EvaluationStatus.INVALID_SCORE))
        ), THRESHOLD);

        assertThat(metrics.evaluablePredictionCount()).isZero();
        assertThat(metrics.excludedPredictionCount()).isEqualTo(1);
    }

    @Test
    void remoteEvaluationFailureIsExcludedFromMetrics() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.ALLOW,
                List.of(EvaluationStatus.REMOTE_EVALUATION_FAILURE))
        ), THRESHOLD);

        assertThat(metrics.evaluablePredictionCount()).isZero();
        assertThat(metrics.excludedPredictionCount()).isEqualTo(1);
    }

    @Test
    void fallbackStatusesRemainDiagnosticWhenDetectorScoreIsAvailable() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.MONITOR,
                List.of(EvaluationStatus.STATISTICAL_LIVE, EvaluationStatus.MODEL_UNAVAILABLE, EvaluationStatus.MODEL_FALLBACK_USED))
        ), THRESHOLD);

        assertThat(metrics.evaluablePredictionCount()).isEqualTo(1);
        assertThat(metrics.excludedPredictionCount()).isZero();
        assertThat(metrics.confusionMatrix().truePositives()).isEqualTo(1);
    }

    @Test
    void modelUnavailableFallbackOnlyIsExcludedFromMetrics() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.MONITOR,
                List.of(EvaluationStatus.MODEL_UNAVAILABLE, EvaluationStatus.MODEL_FALLBACK_USED))
        ), THRESHOLD);

        assertThat(metrics.evaluablePredictionCount()).isZero();
        assertThat(metrics.excludedPredictionCount()).isEqualTo(1);
        assertThat(metrics.confusionMatrix().totalCount()).isZero();
    }

    @Test
    void allExcludedInputProducesUndefinedMetricsAndReconciledCounts() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, null, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90,
                EnforcementAction.ALLOW, List.of(EvaluationStatus.INVALID_SCORE))
        ), THRESHOLD);

        assertThat(metrics.totalObservationCount()).isEqualTo(2);
        assertThat(metrics.evaluablePredictionCount()).isZero();
        assertThat(metrics.excludedPredictionCount()).isEqualTo(2);
        assertThat(metrics.confusionMatrix().totalCount()).isZero();
        assertThat(metrics.metrics().precision()).isEqualTo(DetectionMetricValue.undefined());
        assertThat(metrics.metrics().recall()).isEqualTo(DetectionMetricValue.undefined());
        assertThat(metrics.metrics().f1()).isEqualTo(DetectionMetricValue.undefined());
        assertThat(metrics.metrics().falsePositiveRate()).isEqualTo(DetectionMetricValue.undefined());
        assertThat(metrics.metrics().falseNegativeRate()).isEqualTo(DetectionMetricValue.undefined());
    }

    @Test
    void computesPrecisionRecallF1FalsePositiveRateAndFalseNegativeRate() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-tp1", "scenario-a", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.ALLOW, List.of()),
            observation("evt-tp2", "scenario-a", ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS, true, false, 0.80, EnforcementAction.MONITOR, List.of()),
            observation("evt-tn1", "scenario-a", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.BLOCK, List.of()),
            observation("evt-tn2", "scenario-a", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.20, EnforcementAction.QUARANTINE, List.of()),
            observation("evt-fp", "scenario-b", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.70, EnforcementAction.ALLOW, List.of()),
            observation("evt-fn", "scenario-b", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.30, EnforcementAction.MONITOR, List.of())
        ), THRESHOLD);

        assertThat(metrics.metrics().precision()).isEqualTo(DetectionMetricValue.defined(2.0 / 3.0));
        assertThat(metrics.metrics().recall()).isEqualTo(DetectionMetricValue.defined(2.0 / 3.0));
        assertThat(metrics.metrics().f1()).isEqualTo(DetectionMetricValue.defined(2.0 / 3.0));
        assertThat(metrics.metrics().falsePositiveRate()).isEqualTo(DetectionMetricValue.defined(1.0 / 3.0));
        assertThat(metrics.metrics().falseNegativeRate()).isEqualTo(DetectionMetricValue.defined(1.0 / 3.0));
    }

    @Test
    void precisionIsUndefinedWhenThereAreNoPredictedPositives() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.20, EnforcementAction.MONITOR, List.of())
        ), THRESHOLD);

        assertThat(metrics.metrics().precision()).isEqualTo(DetectionMetricValue.undefined());
        assertThat(metrics.metrics().f1()).isEqualTo(DetectionMetricValue.defined(0.0));
    }

    @Test
    void recallAndFalseNegativeRateAreUndefinedWhenThereAreNoActualPositives() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.20, EnforcementAction.MONITOR, List.of())
        ), THRESHOLD);

        assertThat(metrics.metrics().recall()).isEqualTo(DetectionMetricValue.undefined());
        assertThat(metrics.metrics().falseNegativeRate()).isEqualTo(DetectionMetricValue.undefined());
    }

    @Test
    void falsePositiveRateIsUndefinedWhenThereAreNoActualNegatives() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.60, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-1", ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS, true, false, 0.80, EnforcementAction.MONITOR, List.of())
        ), THRESHOLD);

        assertThat(metrics.metrics().falsePositiveRate()).isEqualTo(DetectionMetricValue.undefined());
    }

    @Test
    void falseNegativeRateIsDefinedWhenActualPositivesExist() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.20, EnforcementAction.ALLOW, List.of())
        ), THRESHOLD);

        assertThat(metrics.metrics().falseNegativeRate()).isEqualTo(DetectionMetricValue.defined(1.0));
    }

    @Test
    void metricValueRejectsContradictoryDefinedState() {
        assertThatThrownBy(() -> new DetectionMetricValue(true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("defined");
        assertThatThrownBy(() -> new DetectionMetricValue(false, 0.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("undefined");
    }

    @Test
    void definedMetricValuesRemainWithinUnitInterval() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.MONITOR, List.of()),
            observation("evt-3", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.80, EnforcementAction.MONITOR, List.of()),
            observation("evt-4", "scenario-1", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.20, EnforcementAction.MONITOR, List.of())
        ), THRESHOLD);

        assertThat(List.of(
            metrics.metrics().precision(),
            metrics.metrics().recall(),
            metrics.metrics().f1(),
            metrics.metrics().falsePositiveRate(),
            metrics.metrics().falseNegativeRate()
        )).allSatisfy(value -> {
            if (value.defined()) {
                assertThat(value.value()).isBetween(0.0, 1.0);
            }
        });
    }

    @Test
    void repeatedEvaluationProducesEqualResults() {
        ReferenceEvaluationAlignment alignment = alignment(
            observation("evt-1", "scenario-a", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-b", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.MONITOR, List.of())
        );
        DetectionMetricsCalculator calculator = new DetectionMetricsCalculator();

        DetectionEvaluationMetrics first = calculator.compute(alignment, THRESHOLD);
        DetectionEvaluationMetrics second = calculator.compute(alignment, THRESHOLD);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void observationOrderingDoesNotAlterAggregateCounts() {
        DetectionMetricsCalculator calculator = new DetectionMetricsCalculator();
        List<EvaluationObservation> ordered = List.of(
            observation("evt-1", "scenario-a", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-b", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.MONITOR, List.of()),
            observation("evt-3", "scenario-b", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.20, EnforcementAction.BLOCK, List.of()),
            observation("evt-4", "scenario-a", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.80, EnforcementAction.QUARANTINE, List.of())
        );

        DetectionEvaluationMetrics first = calculator.compute(alignment(ordered), THRESHOLD);
        DetectionEvaluationMetrics second = calculator.compute(alignment(List.of(
            ordered.get(2),
            ordered.get(0),
            ordered.get(3),
            ordered.get(1)
        )), THRESHOLD);

        assertThat(first.confusionMatrix()).isEqualTo(second.confusionMatrix());
        assertThat(first.metrics()).isEqualTo(second.metrics());
    }

    @Test
    void scenarioAggregationIsDeterministicAndPreservesFirstAppearanceOrder() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-b", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.ALLOW, List.of()),
            observation("evt-2", "scenario-a", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.90, EnforcementAction.MONITOR, List.of()),
            observation("evt-3", "scenario-b", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.80, EnforcementAction.BLOCK, List.of()),
            observation("evt-4", "scenario-a", ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, true, false, 0.20, EnforcementAction.QUARANTINE, List.of())
        ), THRESHOLD);

        assertThat(metrics.scenarios()).extracting(ScenarioDetectionMetrics::scenarioId)
            .containsExactly("scenario-b", "scenario-a");
        assertThat(metrics.scenarios().get(0).confusionMatrix()).isEqualTo(new DetectionConfusionMatrix(0, 1, 1, 0));
        assertThat(metrics.scenarios().get(1).confusionMatrix()).isEqualTo(new DetectionConfusionMatrix(1, 0, 0, 1));
    }

    @Test
    void rejectsConflictingCategoriesForSameScenarioId() {
        EvaluationObservation first = observation(
            "evt-1",
            "scenario-a",
            ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
            ReferenceDatasetExpectedClass.NORMAL,
            false,
            false,
            0.10,
            EnforcementAction.ALLOW,
            List.of()
        );
        EvaluationObservation conflicting = observation(
            "evt-2",
            "scenario-a",
            ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            0.90,
            EnforcementAction.MONITOR,
            List.of()
        );

        assertThatThrownBy(() -> new DetectionMetricsCalculator().compute(alignment(first, conflicting), THRESHOLD))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conflicting categories");
    }

    @Test
    void publicCountArithmeticFailsOnOverflowInsteadOfWrappingNegative() {
        DetectionConfusionMatrix matrix = new DetectionConfusionMatrix(Long.MAX_VALUE, 0, 1, 0);

        assertThatThrownBy(matrix::predictedPositiveCount)
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(matrix::totalCount)
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void returnedScenarioCollectionIsImmutable() {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment(
            observation("evt-1", "scenario-1", ReferenceDatasetExpectedClass.NORMAL, false, false, 0.10, EnforcementAction.ALLOW, List.of())
        ), THRESHOLD);

        assertThatThrownBy(() -> metrics.scenarios().add(metrics.scenarios().getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resultContractsRejectMismatchedDerivedMetrics() {
        DetectionConfusionMatrix matrix = new DetectionConfusionMatrix(1, 0, 0, 0);

        assertThatThrownBy(() -> new ScenarioDetectionMetrics(
            "scenario-1",
            ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
            1,
            1,
            0,
            matrix,
            new DetectionMetrics(
                DetectionMetricValue.undefined(),
                DetectionMetricValue.undefined(),
                DetectionMetricValue.undefined(),
                DetectionMetricValue.undefined(),
                DetectionMetricValue.undefined()
            )
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metrics");
    }

    @Test
    void datasetResultRejectsScenarioConfusionMatricesThatDoNotReconcile() {
        ScenarioDetectionMetrics scenario = new ScenarioDetectionMetrics(
            "scenario-1",
            ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
            1,
            1,
            0,
            new DetectionConfusionMatrix(1, 0, 0, 0),
            DetectionMetrics.from(new DetectionConfusionMatrix(1, 0, 0, 0))
        );
        DetectionConfusionMatrix contradictoryDataset = new DetectionConfusionMatrix(0, 0, 0, 1);

        assertThatThrownBy(() -> new DetectionEvaluationMetrics(
            "dataset-id",
            "replay-run-id",
            THRESHOLD,
            1,
            1,
            0,
            contradictoryDataset,
            DetectionMetrics.from(contradictoryDataset),
            List.of(scenario)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenario confusion");
    }

    @Test
    void consumesTrackedReferenceAlignmentAndReconcilesTruthCounts() throws Exception {
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignTrackedCorpus(), THRESHOLD);

        assertThat(metrics.totalObservationCount()).isEqualTo(84);
        assertThat(metrics.evaluablePredictionCount()).isEqualTo(84);
        assertThat(metrics.excludedPredictionCount()).isZero();
        assertThat(metrics.scenarios()).hasSize(11);
        assertThat(metrics.confusionMatrix().actualNegativeCount()).isEqualTo(26);
        assertThat(metrics.confusionMatrix().actualPositiveCount()).isEqualTo(58);
        assertThat(metrics.totalObservationCount())
            .isEqualTo(metrics.evaluablePredictionCount() + metrics.excludedPredictionCount());
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

    private static ReferenceEvaluationAlignment alignment(EvaluationObservation... observations) {
        return alignment(List.of(observations));
    }

    private static ReferenceEvaluationAlignment alignment(List<EvaluationObservation> observations) {
        return new ReferenceEvaluationAlignment(
            "dataset-id",
            "replay-run-id",
            observations.size(),
            (int) observations.stream().map(EvaluationObservation::scenarioId).distinct().count(),
            observations.size(),
            observations
        );
    }

    private static EvaluationObservation observation(String eventId,
                                                     String scenarioId,
                                                     ReferenceDatasetExpectedClass expectedClass,
                                                     boolean anomalyExpected,
                                                     boolean maliciousnessAsserted,
                                                     Double anomalyScore,
                                                     EnforcementAction action,
                                                     List<EvaluationStatus> statuses) {
        return observation(
            eventId,
            scenarioId,
            ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
            expectedClass,
            anomalyExpected,
            maliciousnessAsserted,
            anomalyScore,
            action,
            statuses
        );
    }

    private static EvaluationObservation observation(String eventId,
                                                     String scenarioId,
                                                     ReferenceDatasetScenarioCategory scenarioCategory,
                                                     ReferenceDatasetExpectedClass expectedClass,
                                                     boolean anomalyExpected,
                                                     boolean maliciousnessAsserted,
                                                     Double anomalyScore,
                                                     EnforcementAction action,
                                                     List<EvaluationStatus> statuses) {
        int sequence = Math.floorMod(eventId.hashCode(), 10_000) + 1;
        return new EvaluationObservation(
            eventId,
            scenarioId,
            scenarioCategory,
            sequence <= 0 ? 1 : sequence,
            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequence),
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

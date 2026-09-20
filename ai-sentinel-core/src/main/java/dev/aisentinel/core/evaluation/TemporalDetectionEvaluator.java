package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic temporal interpretation of aligned anomaly predictions.
 */
public final class TemporalDetectionEvaluator {
    private static final DetectionEvaluationClassifier CLASSIFIER = new DetectionEvaluationClassifier();

    public TemporalDetectionEvaluation evaluate(ReferenceEvaluationAlignment alignment,
                                                DetectionClassificationConfiguration classification) {
        ReferenceEvaluationAlignment safeAlignment = Objects.requireNonNull(alignment, "alignment");
        DetectionClassificationConfiguration safeClassification = Objects.requireNonNull(classification, "classification");

        Map<String, ScenarioSequence> scenarios = new LinkedHashMap<>();
        for (EvaluationObservation observation : safeAlignment.observations()) {
            scenarios.computeIfAbsent(
                    observation.scenarioId(),
                    ignored -> new ScenarioSequence(observation.scenarioId(), observation.scenarioCategory()))
                .add(observation);
        }

        List<ScenarioTemporalEvaluation> scenarioEvaluations = scenarios.values().stream()
            .map(scenario -> evaluateScenario(scenario, safeClassification))
            .toList();
        return new TemporalDetectionEvaluation(
            safeAlignment.datasetId(),
            safeAlignment.replayRunId(),
            safeClassification,
            scenarioEvaluations
        );
    }

    private static ScenarioTemporalEvaluation evaluateScenario(ScenarioSequence scenario,
                                                               DetectionClassificationConfiguration classification) {
        List<EvaluationObservation> observations = scenario.observations;
        List<TemporalAnomalySegment> segments = new ArrayList<>();
        int segmentIndex = 0;
        int start = 0;
        while (start < observations.size()) {
            boolean anomalous = observations.get(start).truth().anomalousExpected();
            int end = start;
            while (end + 1 < observations.size()
                && observations.get(end + 1).truth().anomalousExpected() == anomalous) {
                end++;
            }
            if (anomalous) {
                segments.add(evaluateAnomalousSegment(segmentIndex++, observations, start, end, classification));
            }
            start = end + 1;
        }

        return new ScenarioTemporalEvaluation(
            scenario.scenarioId,
            scenario.scenarioCategory,
            observations.size(),
            segments
        );
    }

    private static TemporalAnomalySegment evaluateAnomalousSegment(int segmentIndex,
                                                                   List<EvaluationObservation> observations,
                                                                   int anomalyStart,
                                                                   int anomalyEnd,
                                                                   DetectionClassificationConfiguration classification) {
        TemporalObservationPoint onset = TemporalObservationPoint.from(observations.get(anomalyStart));
        TemporalObservationPoint windowEnd = TemporalObservationPoint.from(observations.get(anomalyEnd));

        TemporalObservationPoint firstDetection = null;
        Integer observationDelay = null;
        Integer evaluableObservationDelay = null;
        Duration timeDelay = null;
        long unavailable = 0L;
        int evaluableBeforeDetection = 0;
        boolean foundDetection = false;
        for (int i = anomalyStart; i <= anomalyEnd; i++) {
            DetectionEvaluationClassifier.ClassifiedPrediction classified =
                CLASSIFIER.classify(observations.get(i).prediction(), classification);
            if (!classified.evaluable()) {
                unavailable++;
                continue;
            }
            if (!foundDetection && classified.predictedAnomalous()) {
                firstDetection = TemporalObservationPoint.from(observations.get(i));
                observationDelay = i - anomalyStart;
                evaluableObservationDelay = evaluableBeforeDetection;
                timeDelay = durationBetween(onset.observedAt(), firstDetection.observedAt(), "firstDetection");
                foundDetection = true;
            } else if (!foundDetection) {
                evaluableBeforeDetection++;
            }
        }

        TemporalRecoveryEvaluation recovery = null;
        if (anomalyEnd + 1 < observations.size() && !observations.get(anomalyEnd + 1).truth().anomalousExpected()) {
            int recoveryEnd = anomalyEnd + 1;
            while (recoveryEnd + 1 < observations.size()
                && !observations.get(recoveryEnd + 1).truth().anomalousExpected()) {
                recoveryEnd++;
            }
            recovery = evaluateRecoverySegment(observations, anomalyEnd + 1, recoveryEnd, classification);
        }

        return new TemporalAnomalySegment(
            segmentIndex,
            onset,
            windowEnd,
            anomalyEnd - anomalyStart + 1,
            firstDetection != null,
            firstDetection,
            observationDelay,
            evaluableObservationDelay,
            timeDelay,
            unavailable,
            recovery
        );
    }

    private static TemporalRecoveryEvaluation evaluateRecoverySegment(List<EvaluationObservation> observations,
                                                                      int recoveryStart,
                                                                      int recoveryEnd,
                                                                      DetectionClassificationConfiguration classification) {
        TemporalObservationPoint recoveryOnset = TemporalObservationPoint.from(observations.get(recoveryStart));
        TemporalObservationPoint windowEnd = TemporalObservationPoint.from(observations.get(recoveryEnd));
        long unavailable = 0L;
        List<DetectionEvaluationClassifier.ClassifiedPrediction> classified = new ArrayList<>(recoveryEnd - recoveryStart + 1);
        for (int i = recoveryStart; i <= recoveryEnd; i++) {
            DetectionEvaluationClassifier.ClassifiedPrediction prediction =
                CLASSIFIER.classify(observations.get(i).prediction(), classification);
            classified.add(prediction);
            if (!prediction.evaluable()) {
                unavailable++;
            }
        }

        int stableSuffixOffset = -1;
        for (int offset = classified.size() - 1; offset >= 0; offset--) {
            DetectionEvaluationClassifier.ClassifiedPrediction candidate = classified.get(offset);
            if (!candidate.evaluable() || candidate.predictedAnomalous()) {
                break;
            }
            stableSuffixOffset = offset;
        }
        int candidateIndex = -1;
        if (stableSuffixOffset >= 0) {
            candidateIndex = recoveryStart + stableSuffixOffset;
        }
        if (candidateIndex < 0) {
            return new TemporalRecoveryEvaluation(
                recoveryOnset,
                windowEnd,
                recoveryEnd - recoveryStart + 1,
                false,
                null,
                null,
                null,
                null,
                unavailable
            );
        }

        int evaluableBeforeRecovery = 0;
        for (int offset = 0; offset < candidateIndex - recoveryStart; offset++) {
            if (classified.get(offset).evaluable()) {
                evaluableBeforeRecovery++;
            }
        }
        TemporalObservationPoint stablePoint = TemporalObservationPoint.from(observations.get(candidateIndex));
        return new TemporalRecoveryEvaluation(
            recoveryOnset,
            windowEnd,
            recoveryEnd - recoveryStart + 1,
            true,
            stablePoint,
            candidateIndex - recoveryStart,
            evaluableBeforeRecovery,
            durationBetween(recoveryOnset.observedAt(), stablePoint.observedAt(), "recovery"),
            unavailable
        );
    }

    private static Duration durationBetween(Instant start, Instant end, String label) {
        Duration duration = Duration.between(start, end);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(label + " cannot precede onset");
        }
        return duration;
    }

    private static final class ScenarioSequence {
        private final String scenarioId;
        private final ReferenceDatasetScenarioCategory scenarioCategory;
        private final List<EvaluationObservation> observations = new ArrayList<>();

        private ScenarioSequence(String scenarioId, ReferenceDatasetScenarioCategory scenarioCategory) {
            this.scenarioId = scenarioId;
            this.scenarioCategory = scenarioCategory;
        }

        void add(EvaluationObservation observation) {
            Objects.requireNonNull(observation, "observation");
            if (observation.scenarioCategory() != scenarioCategory) {
                throw new IllegalArgumentException("scenarioId has conflicting categories: " + scenarioId);
            }
            if (!observations.isEmpty()) {
                EvaluationObservation previous = observations.getLast();
                if (observation.sequenceNumber() <= previous.sequenceNumber()) {
                    throw new IllegalArgumentException("scenario observations must preserve increasing sequenceNumber: " + scenarioId);
                }
                if (observation.observedAt().isBefore(previous.observedAt())) {
                    throw new IllegalArgumentException("scenario observations must preserve nondecreasing observedAt: " + scenarioId);
                }
            }
            observations.add(observation);
        }
    }
}

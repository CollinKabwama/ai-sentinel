package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds event-inspection rows by joining authored corpus ground truth with replay outcomes.
 */
final class GeneratedCorpusEventInspections {

    private GeneratedCorpusEventInspections() {
    }

    static List<GeneratedCorpusEventInspection> build(
        GeneratedCorpusGroundTruth groundTruth,
        List<ReplayResult> replayResults,
        DetectionClassificationConfiguration classification
    ) {
        Objects.requireNonNull(groundTruth, "groundTruth");
        Objects.requireNonNull(replayResults, "replayResults");
        Objects.requireNonNull(classification, "classification");

        Map<String, GeneratedCorpusGroundTruth.EventAnnotation> byEventId = new LinkedHashMap<>();
        for (GeneratedCorpusGroundTruth.EventAnnotation annotation : groundTruth.annotations()) {
            if (byEventId.put(annotation.eventId(), annotation) != null) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Duplicate annotation eventId: " + annotation.eventId());
            }
        }
        if (byEventId.size() != replayResults.size()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
                "Replay result count does not match ground-truth annotation count");
        }

        List<GeneratedCorpusEventInspection> rows = new ArrayList<>(replayResults.size());
        for (ReplayResult replay : replayResults) {
            GeneratedCorpusGroundTruth.EventAnnotation annotation = byEventId.get(replay.eventId());
            if (annotation == null) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Missing ground-truth annotation for replayed event: " + replay.eventId());
            }
            rows.add(toInspection(annotation, replay, classification));
        }
        return List.copyOf(rows);
    }

    private static GeneratedCorpusEventInspection toInspection(
        GeneratedCorpusGroundTruth.EventAnnotation annotation,
        ReplayResult replay,
        DetectionClassificationConfiguration classification
    ) {
        String participation;
        boolean binaryParticipant;
        if (annotation.warmup()) {
            participation = GeneratedCorpusEventInspection.PARTICIPATION_WARMUP;
            binaryParticipant = false;
        } else if (annotation.unknownOrUnlabeled()) {
            participation = GeneratedCorpusEventInspection.PARTICIPATION_UNKNOWN_UNLABELED;
            binaryParticipant = false;
        } else if (annotation.binaryLabeled()) {
            participation = GeneratedCorpusEventInspection.PARTICIPATION_BINARY_LABELED;
            binaryParticipant = true;
        } else {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "Unsupported ground-truth combination for event: " + annotation.eventId());
        }

        EvaluationPrediction prediction = new EvaluationPrediction(
            EvaluationPredictionSource.REPLAY_SCORE,
            replay.anomalyScore(),
            replay.policyScore(),
            replay.action(),
            replay.evaluationStatuses()
        );
        Boolean predictedAnomalous = null;
        if (prediction.hasValidDetectorScore()) {
            predictedAnomalous = classification.isPredictedAnomalous(prediction.anomalyScore());
        }

        String outcome = resolveOutcome(annotation, binaryParticipant, predictedAnomalous, prediction);

        List<String> statuses = replay.evaluationStatuses().stream()
            .map(Enum::name)
            .sorted()
            .toList();

        return new GeneratedCorpusEventInspection(
            replay.eventId(),
            replay.sequenceNumber(),
            replay.observedAt(),
            replay.identityKey(),
            annotation.category(),
            annotation.expectedClass(),
            participation,
            binaryParticipant,
            replay.anomalyScore(),
            predictedAnomalous,
            replay.action().name(),
            statuses,
            outcome
        );
    }

    private static String resolveOutcome(
        GeneratedCorpusGroundTruth.EventAnnotation annotation,
        boolean binaryParticipant,
        Boolean predictedAnomalous,
        EvaluationPrediction prediction
    ) {
        if (!binaryParticipant) {
            if (annotation.warmup()) {
                return "excluded-warmup";
            }
            return "excluded-unknown-unlabeled";
        }
        if (!prediction.hasValidDetectorScore() || predictedAnomalous == null) {
            return "excluded-invalid-or-missing-score";
        }
        boolean expectedAnomalous = "anomalous".equals(annotation.expectedClass());
        if (expectedAnomalous && predictedAnomalous) {
            return "true-positive";
        }
        if (!expectedAnomalous && !predictedAnomalous) {
            return "true-negative";
        }
        if (!expectedAnomalous && predictedAnomalous) {
            return "false-positive";
        }
        return "false-negative";
    }
}

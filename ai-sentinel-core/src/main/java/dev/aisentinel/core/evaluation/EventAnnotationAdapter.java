package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioAnnotation;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared mapping from Kit event-level annotations onto historical
 * {@link ReferenceDatasetAnnotations} for detection metrics.
 * <p>
 * Used by generated-corpus and evaluator-provided (BYO) evaluation paths.
 * Warmup and unknown/unlabeled events are excluded from binary detection metrics.
 */
final class EventAnnotationAdapter {

    private EventAnnotationAdapter() {
    }

    static ReferenceDatasetAnnotations toReferenceAnnotations(
        String datasetId,
        String scenarioId,
        List<GeneratedCorpusGroundTruth.EventAnnotation> annotations,
        String sourceTag
    ) {
        String safeDatasetId = require("datasetId", datasetId);
        String safeScenarioId = require("scenarioId", scenarioId);
        String safeTag = require("sourceTag", sourceTag);
        List<GeneratedCorpusGroundTruth.EventAnnotation> safeAnnotations =
            List.copyOf(Objects.requireNonNull(annotations, "annotations"));

        List<String> warmupEventIds = new ArrayList<>();
        Map<String, List<GeneratedCorpusGroundTruth.EventAnnotation>> labeledByGroup = new LinkedHashMap<>();

        for (GeneratedCorpusGroundTruth.EventAnnotation annotation : safeAnnotations) {
            if (annotation.warmup()) {
                warmupEventIds.add(annotation.eventId());
                continue;
            }
            if (annotation.unknownOrUnlabeled()) {
                continue;
            }
            if (!annotation.binaryLabeled()) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Unsupported expectedClass for evaluation metrics: " + annotation.expectedClass());
            }
            String groupKey = annotation.category() + "|" + annotation.expectedClass();
            labeledByGroup.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(annotation);
        }

        List<ReferenceDatasetScenarioAnnotation> scenarios = new ArrayList<>();
        for (Map.Entry<String, List<GeneratedCorpusGroundTruth.EventAnnotation>> entry : labeledByGroup.entrySet()) {
            List<GeneratedCorpusGroundTruth.EventAnnotation> group = entry.getValue();
            GeneratedCorpusGroundTruth.EventAnnotation sample = group.get(0);
            List<String> evaluationEventIds = group.stream()
                .map(GeneratedCorpusGroundTruth.EventAnnotation::eventId)
                .toList();
            Set<String> eventIds = new LinkedHashSet<>(warmupEventIds);
            eventIds.addAll(evaluationEventIds);

            ReferenceDatasetExpectedClass expectedClass = mapExpectedClass(sample.expectedClass());
            boolean anomalous = expectedClass != ReferenceDatasetExpectedClass.NORMAL;
            scenarios.add(new ReferenceDatasetScenarioAnnotation(
                safeScenarioId + "#" + sample.category() + "#" + sample.expectedClass(),
                mapCategory(sample.category()),
                expectedClass,
                anomalous,
                false,
                List.copyOf(eventIds),
                List.copyOf(warmupEventIds),
                evaluationEventIds,
                List.of(safeTag),
                List.of("feature-level"),
                "Adapted from " + safeTag + " event-level ground truth; category=" + sample.category()
            ));
        }

        return new ReferenceDatasetAnnotations(
            ReferenceDatasetAnnotations.SCHEMA_VERSION,
            safeDatasetId,
            "Adapted " + safeTag + " annotations for scenarioId=" + safeScenarioId,
            scenarios
        );
    }

    static ReferenceDatasetExpectedClass mapExpectedClass(String expectedClass) {
        return switch (expectedClass) {
            case "benign" -> ReferenceDatasetExpectedClass.NORMAL;
            case "anomalous" -> ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS;
            default -> throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "Cannot map expectedClass into binary detection truth: " + expectedClass);
        };
    }

    static ReferenceDatasetScenarioCategory mapCategory(String kitCategory) {
        return switch (kitCategory) {
            case "evaluation-normal", "recovery" -> ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE;
            case "cold-start" -> ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY;
            case "legitimate-burst" -> ReferenceDatasetScenarioCategory.LEGITIMATE_BULK_OPERATION;
            case "burst" -> ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST;
            case "endpoint-distribution-change" -> ReferenceDatasetScenarioCategory.ENDPOINT_BEHAVIOR_CHANGE;
            case "low-variance-deviation" -> ReferenceDatasetScenarioCategory.LOW_VARIANCE_BASELINE_DEVIATION;
            case "gradual-drift" -> ReferenceDatasetScenarioCategory.GRADUAL_BEHAVIOR_CHANGE;
            case "multi-feature-anomaly" -> ReferenceDatasetScenarioCategory.PARAMETER_COUNT_DEVIATION;
            case "identity-session-transition" -> ReferenceDatasetScenarioCategory.INTERLEAVED_NORMAL_IDENTITIES;
            default -> throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "Unsupported annotation category for metrics mapping: " + kitCategory);
        };
    }

    private static String require(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                field + " is required");
        }
        return value;
    }
}

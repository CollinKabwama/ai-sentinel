package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Event-level ground-truth sidecar for a generated Evaluation Kit corpus.
 * Labels are evaluation-layer only and are never detector inputs.
 */
record GeneratedCorpusGroundTruth(
    String annotationSchemaVersion,
    String annotationId,
    String corpusId,
    String scenarioId,
    List<EventAnnotation> annotations,
    String notes
) {
    GeneratedCorpusGroundTruth {
        annotationSchemaVersion = require("annotationSchemaVersion", annotationSchemaVersion);
        annotationId = require("annotationId", annotationId);
        corpusId = require("corpusId", corpusId);
        scenarioId = require("scenarioId", scenarioId);
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        if (annotations.isEmpty()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "Ground-truth annotations must not be empty");
        }
        notes = notes == null ? "" : notes;
    }

    record EventAnnotation(
        String eventId,
        String scenarioId,
        String expectedClass,
        String category
    ) {
        EventAnnotation {
            eventId = require("eventId", eventId);
            scenarioId = require("scenarioId", scenarioId);
            expectedClass = normalizeClass(expectedClass);
            category = require("category", category);
        }

        boolean warmup() {
            return "warmup".equals(category);
        }

        boolean binaryLabeled() {
            return "benign".equals(expectedClass) || "anomalous".equals(expectedClass);
        }

        boolean unknownOrUnlabeled() {
            return "unknown".equals(expectedClass) || "unlabeled".equals(expectedClass);
        }

        private static String normalizeClass(String value) {
            String normalized = require("expectedClass", value).toLowerCase(Locale.ROOT);
            if (!normalized.equals("benign")
                && !normalized.equals("anomalous")
                && !normalized.equals("unknown")
                && !normalized.equals("unlabeled")) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Unsupported expectedClass: " + value);
            }
            return normalized;
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

    private static String require(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                field + " is required");
        }
        return value;
    }
}

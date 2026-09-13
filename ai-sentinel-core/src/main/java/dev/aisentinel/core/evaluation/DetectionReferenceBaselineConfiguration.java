package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.replay.ReplayConfiguration;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Canonical configuration for Official Detection Reference Baseline capture.
 * <p>
 * Composes accepted replay and classification contracts. The reference
 * classification threshold {@code 0.5} applies only to this baseline context and
 * is not a general evaluation-framework default.
 */
public record DetectionReferenceBaselineConfiguration(
    DetectionClassificationConfiguration classification,
    ReplayConfiguration replayConfiguration,
    String expectedDatasetId,
    String expectedAnnotationSchemaVersion,
    Path datasetDirectory,
    Path annotationsFile
) {
    public DetectionReferenceBaselineConfiguration {
        classification = Objects.requireNonNull(classification, "classification");
        replayConfiguration = Objects.requireNonNull(replayConfiguration, "replayConfiguration");
        expectedDatasetId = requireNotBlank("expectedDatasetId", expectedDatasetId);
        expectedAnnotationSchemaVersion =
            requireNotBlank("expectedAnnotationSchemaVersion", expectedAnnotationSchemaVersion);
        datasetDirectory = Objects.requireNonNull(datasetDirectory, "datasetDirectory").normalize();
        annotationsFile = Objects.requireNonNull(annotationsFile, "annotationsFile").normalize();
        if (Double.compare(
            classification.anomalyThreshold(),
            DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD) != 0) {
            throw new IllegalArgumentException(
                "Official Detection Reference Baseline classification threshold must be "
                    + DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD);
        }
    }

    /**
     * Canonical Official Detection Reference Baseline configuration for the tracked
     * reference corpus.
     */
    public static DetectionReferenceBaselineConfiguration officialReference() {
        return new DetectionReferenceBaselineConfiguration(
            new DetectionClassificationConfiguration(
                DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD),
            ReplayConfiguration.referenceDefaults(),
            ReferenceDatasetGenerator.DATASET_ID,
            ReferenceDatasetAnnotations.SCHEMA_VERSION,
            ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY,
            ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE
        );
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}

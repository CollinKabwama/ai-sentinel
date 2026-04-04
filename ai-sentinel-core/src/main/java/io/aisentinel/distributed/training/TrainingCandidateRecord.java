package io.aisentinel.distributed.training;

import java.util.Arrays;

/**
 * Serializable training candidate for export to Kafka (or batch files). Keep bounded: IF vector length is fixed at 5.
 */
public record TrainingCandidateRecord(
    int schemaVersion,
    String tenantId,
    String nodeId,
    String identityHash,
    String endpoint,
    double[] isolationForestFeatures,
    double compositeScore,
    long capturedAtEpochMillis
) {
    public TrainingCandidateRecord {
        if (isolationForestFeatures != null && isolationForestFeatures.length != 5) {
            throw new IllegalArgumentException("isolationForestFeatures must have length 5");
        }
    }

    /** Defensive copy for use after async handoff. */
    public double[] isolationForestFeaturesCopy() {
        return isolationForestFeatures == null ? null : Arrays.copyOf(isolationForestFeatures, isolationForestFeatures.length);
    }
}

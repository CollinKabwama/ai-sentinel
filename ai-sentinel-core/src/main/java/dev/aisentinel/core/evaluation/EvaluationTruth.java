package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;

import java.util.Objects;

/**
 * Independent labeled truth for a future detection evaluation observation.
 */
public record EvaluationTruth(
    ReferenceDatasetExpectedClass expectedClass,
    boolean anomalousExpected,
    boolean maliciousnessAsserted
) {
    public EvaluationTruth {
        expectedClass = Objects.requireNonNull(expectedClass, "expectedClass");
        boolean expectedClassImpliesAnomaly = expectedClass != ReferenceDatasetExpectedClass.NORMAL;
        if (anomalousExpected != expectedClassImpliesAnomaly) {
            throw new IllegalArgumentException("anomalousExpected contradicts expectedClass");
        }
        if (maliciousnessAsserted && expectedClass == ReferenceDatasetExpectedClass.NORMAL) {
            throw new IllegalArgumentException("maliciousnessAsserted cannot be true for NORMAL");
        }
    }
}

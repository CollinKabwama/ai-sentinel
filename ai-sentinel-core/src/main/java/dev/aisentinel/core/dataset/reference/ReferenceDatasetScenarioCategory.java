package dev.aisentinel.core.dataset.reference;

/**
 * Durable scenario categories for the reference synthetic evaluation corpus.
 */
public enum ReferenceDatasetScenarioCategory {
    ESTABLISHED_NORMAL_BASELINE,
    WARMUP_NEW_IDENTITY,
    RAPID_REQUEST_BURST,
    ENDPOINT_BEHAVIOR_CHANGE,
    PAYLOAD_SIZE_DEVIATION,
    PARAMETER_COUNT_DEVIATION,
    TOKEN_AGE_CHANGE,
    LOW_VARIANCE_BASELINE_DEVIATION,
    GRADUAL_BEHAVIOR_CHANGE,
    LEGITIMATE_BULK_OPERATION,
    INTERLEAVED_NORMAL_IDENTITIES
}

package dev.aisentinel.core.replay;

/**
 * High-level replay failure categories.
 */
public enum ReplayFailureKind {
    DATASET_VALIDATION_FAILURE,
    UNSUPPORTED_SCHEMA,
    CONFIGURATION_FAILURE,
    SCORER_FAILURE,
    POLICY_FAILURE,
    OUTPUT_WRITE_FAILURE,
    INPUT_PARSE_FAILURE
}

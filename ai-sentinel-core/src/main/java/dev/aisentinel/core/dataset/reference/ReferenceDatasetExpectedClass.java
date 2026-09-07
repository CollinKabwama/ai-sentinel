package dev.aisentinel.core.dataset.reference;

/**
 * Synthetic ground-truth classes for the reference evaluation dataset.
 * They are annotations, not scorer inputs.
 */
public enum ReferenceDatasetExpectedClass {
    NORMAL,
    LEGITIMATE_ANOMALOUS,
    SYNTHETIC_ANOMALOUS
}

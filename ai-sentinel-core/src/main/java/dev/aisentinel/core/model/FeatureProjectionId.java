package dev.aisentinel.core.model;

/**
 * Durable identifiers for ordered feature projections consumed by current online scorers
 * and export/training paths.
 */
public enum FeatureProjectionId {
    STATISTICAL,
    ISOLATION_FOREST,
    EXPORT
}

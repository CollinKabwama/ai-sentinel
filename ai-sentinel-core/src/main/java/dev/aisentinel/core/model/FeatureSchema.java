package dev.aisentinel.core.model;

import java.util.List;

/**
 * Explicit contract for request feature vector layouts used by statistical scoring,
 * Isolation Forest scoring, and training export.
 * <p>
 * {@link #VERSION} identifies this layout. Increment {@link #VERSION} when any of the following
 * changes incompatibly: a feature is added, removed, or reordered; a feature's semantic meaning
 * changes; or normalization / encoding of a dimension changes so existing models or trainers
 * would misinterpret vectors. Compatible documentation-only clarifications do not require a bump.
 * <p>
 * Feature order is part of the model contract. Silent reordering is forbidden.
 */
public final class FeatureSchema {

    /**
     * Layout version for statistical, Isolation Forest, and export vectors defined by this class.
     */
    public static final int VERSION = 1;

    /** Length of {@link RequestFeatures#toStatisticalArray()}. */
    public static final int STATISTICAL_DIMENSION = 6;

    /** Length of {@link RequestFeatures#toIsolationForestArray()}. */
    public static final int ISOLATION_FOREST_DIMENSION = 5;

    /** Length of {@link RequestFeatures#toArray()} (training / diagnostics export). */
    public static final int EXPORT_DIMENSION = 7;

    /**
     * Names for {@link RequestFeatures#toStatisticalArray()} positions (index-aligned).
     */
    public static final List<String> STATISTICAL_FEATURE_NAMES = List.of(
        "requestsPerWindow",
        "endpointEntropy",
        "endpointConcentration",
        "tokenAgeSeconds",
        "parameterCount",
        "payloadSizeBytes"
    );

    /**
     * Names for {@link RequestFeatures#toIsolationForestArray()} positions (index-aligned).
     */
    public static final List<String> ISOLATION_FOREST_FEATURE_NAMES = List.of(
        "requestsPerWindow",
        "endpointEntropy",
        "tokenAgeSeconds",
        "parameterCount",
        "payloadSizeBytes"
    );

    /**
     * Names for {@link RequestFeatures#toArray()} positions (index-aligned).
     */
    public static final List<String> EXPORT_FEATURE_NAMES = List.of(
        "requestsPerWindow",
        "endpointEntropy",
        "tokenAgeSeconds",
        "parameterCount",
        "payloadSizeBytes",
        "headerFingerprintHash",
        "ipBucket"
    );

    private FeatureSchema() {
    }

    public static void requireStatisticalDimension(double[] vector) {
        requireLength(vector, STATISTICAL_DIMENSION, "statistical");
    }

    public static void requireIsolationForestDimension(double[] vector) {
        requireLength(vector, ISOLATION_FOREST_DIMENSION, "isolationForest");
    }

    public static void requireExportDimension(double[] vector) {
        requireLength(vector, EXPORT_DIMENSION, "export");
    }

    private static void requireLength(double[] vector, int expected, String label) {
        if (vector == null) {
            throw new IllegalArgumentException(label + " feature vector is null (schemaVersion="
                + VERSION + ", expectedLength=" + expected + ")");
        }
        if (vector.length != expected) {
            throw new IllegalArgumentException(label + " feature vector length " + vector.length
                + " != " + expected + " (schemaVersion=" + VERSION + ")");
        }
    }
}

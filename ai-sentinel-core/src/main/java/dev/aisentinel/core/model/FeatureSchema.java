package dev.aisentinel.core.model;

import java.util.List;
import java.util.Objects;

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
    public static final String VERSION_ID = Integer.toString(VERSION);

    public static final List<FeatureDefinition> CANONICAL_FEATURES = List.of(
        new FeatureDefinition(
            "requestsPerWindow",
            FeatureValueType.DECIMAL,
            "count",
            "Rolling request count within the BaselineStore TTL window"
        ),
        new FeatureDefinition(
            "endpointEntropy",
            FeatureValueType.DECIMAL,
            "nats",
            "Natural-log entropy over recent endpoints for the identity"
        ),
        new FeatureDefinition(
            "endpointConcentration",
            FeatureValueType.DECIMAL,
            "ratio",
            "Maximum endpoint share in the recent per-identity endpoint histogram"
        ),
        new FeatureDefinition(
            "tokenAgeSeconds",
            FeatureValueType.DECIMAL,
            "seconds",
            "Seconds since X-Token-Issued-At when available; -1 means missing, invalid, overflow, or materially future"
        ),
        new FeatureDefinition(
            "parameterCount",
            FeatureValueType.INTEGER,
            "count",
            "Query/form parameter map size"
        ),
        new FeatureDefinition(
            "payloadSizeBytes",
            FeatureValueType.LONG,
            "bytes",
            "Request payload size in bytes"
        ),
        new FeatureDefinition(
            "headerFingerprintHash",
            FeatureValueType.HASHED_LONG,
            "",
            "Java Map hash of lowercase non-Authorization header names and header-value lengths"
        ),
        new FeatureDefinition(
            "ipBucket",
            FeatureValueType.BUCKETED_INTEGER,
            "bucket",
            "IPv4 /24 numeric bucket or non-IPv4 remote-address hash bucket"
        )
    );

    public static final List<FeatureProjection> PROJECTIONS = List.of(
        new FeatureProjection(
            FeatureProjectionId.STATISTICAL,
            List.of(
                "requestsPerWindow",
                "endpointEntropy",
                "endpointConcentration",
                "tokenAgeSeconds",
                "parameterCount",
                "payloadSizeBytes"
            )
        ),
        new FeatureProjection(
            FeatureProjectionId.ISOLATION_FOREST,
            List.of(
                "requestsPerWindow",
                "endpointEntropy",
                "tokenAgeSeconds",
                "parameterCount",
                "payloadSizeBytes"
            )
        ),
        new FeatureProjection(
            FeatureProjectionId.EXPORT,
            List.of(
                "requestsPerWindow",
                "endpointEntropy",
                "tokenAgeSeconds",
                "parameterCount",
                "payloadSizeBytes",
                "headerFingerprintHash",
                "ipBucket"
            )
        )
    );

    public static final List<String> STATISTICAL_FEATURE_NAMES = projection(FeatureProjectionId.STATISTICAL)
        .orderedFeatureNames();
    public static final List<String> ISOLATION_FOREST_FEATURE_NAMES = projection(FeatureProjectionId.ISOLATION_FOREST)
        .orderedFeatureNames();
    public static final List<String> EXPORT_FEATURE_NAMES = projection(FeatureProjectionId.EXPORT)
        .orderedFeatureNames();

    /** Length of {@link RequestFeatures#toStatisticalArray()}. */
    public static final int STATISTICAL_DIMENSION = STATISTICAL_FEATURE_NAMES.size();

    /** Length of {@link RequestFeatures#toIsolationForestArray()}. */
    public static final int ISOLATION_FOREST_DIMENSION = ISOLATION_FOREST_FEATURE_NAMES.size();

    /** Length of {@link RequestFeatures#toArray()} (training / diagnostics export). */
    public static final int EXPORT_DIMENSION = EXPORT_FEATURE_NAMES.size();

    private FeatureSchema() {
    }

    public static boolean supportsVersion(String version) {
        return VERSION_ID.equals(version);
    }

    public static String requireSupportedVersion(String version) {
        if (!supportsVersion(version)) {
            throw new IllegalArgumentException("Unsupported feature schema version: " + version);
        }
        return VERSION_ID;
    }

    public static FeatureProjection projection(FeatureProjectionId projectionId) {
        Objects.requireNonNull(projectionId, "projectionId");
        return PROJECTIONS.stream()
            .filter(projection -> projection.id() == projectionId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported projection: " + projectionId));
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

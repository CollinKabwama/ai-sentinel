package dev.aisentinel.core.model;

import java.util.Objects;

/**
 * Privacy-safe canonical feature values aligned with {@link FeatureSchema#CANONICAL_FEATURES}.
 */
public record FeatureSnapshot(
    double requestsPerWindow,
    double endpointEntropy,
    double endpointConcentration,
    double tokenAgeSeconds,
    int parameterCount,
    long payloadSizeBytes,
    long headerFingerprintHash,
    int ipBucket
) {
    public FeatureSnapshot {
        requireFinite("requestsPerWindow", requestsPerWindow);
        requireFinite("endpointEntropy", endpointEntropy);
        requireFinite("endpointConcentration", endpointConcentration);
        requireFinite("tokenAgeSeconds", tokenAgeSeconds);
        if (parameterCount < 0) {
            throw new IllegalArgumentException("parameterCount must be >= 0");
        }
        if (payloadSizeBytes < 0L) {
            throw new IllegalArgumentException("payloadSizeBytes must be >= 0");
        }
    }

    public static FeatureSnapshot from(RequestFeatures requestFeatures) {
        Objects.requireNonNull(requestFeatures, "requestFeatures");
        return new FeatureSnapshot(
            requestFeatures.requestsPerWindow(),
            requestFeatures.endpointEntropy(),
            requestFeatures.endpointConcentration(),
            requestFeatures.tokenAgeSeconds(),
            requestFeatures.parameterCount(),
            requestFeatures.payloadSizeBytes(),
            requestFeatures.headerFingerprintHash(),
            requestFeatures.ipBucket()
        );
    }

    public String schemaVersion() {
        return FeatureSchema.VERSION_ID;
    }

    public double[] project(FeatureProjectionId projectionId) {
        return FeatureSchema.projection(projectionId).orderedFeatureNames().stream()
            .mapToDouble(this::numericValue)
            .toArray();
    }

    public double numericValue(String featureName) {
        return switch (Objects.requireNonNull(featureName, "featureName")) {
            case "requestsPerWindow" -> requestsPerWindow;
            case "endpointEntropy" -> endpointEntropy;
            case "endpointConcentration" -> endpointConcentration;
            case "tokenAgeSeconds" -> tokenAgeSeconds;
            case "parameterCount" -> parameterCount;
            case "payloadSizeBytes" -> payloadSizeBytes;
            case "headerFingerprintHash" -> headerFingerprintHash;
            case "ipBucket" -> ipBucket;
            default -> throw new IllegalArgumentException("Unknown feature name: " + featureName);
        };
    }

    private static void requireFinite(String field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}

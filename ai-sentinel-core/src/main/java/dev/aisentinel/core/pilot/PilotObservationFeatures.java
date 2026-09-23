package dev.aisentinel.core.pilot;

import dev.aisentinel.core.model.RequestFeatures;

import java.util.Objects;

/**
 * Canonical derived feature subset persisted in pilot observations.
 * These are numeric feature-level values, not raw request content.
 */
public record PilotObservationFeatures(
    double requestsPerWindow,
    double endpointEntropy,
    double endpointConcentration,
    double tokenAgeSeconds,
    int parameterCount,
    long payloadSizeBytes,
    long headerFingerprintHash,
    int ipBucket
) {
    public static PilotObservationFeatures from(RequestFeatures features) {
        Objects.requireNonNull(features, "features");
        return new PilotObservationFeatures(
            features.requestsPerWindow(),
            features.endpointEntropy(),
            features.endpointConcentration(),
            features.tokenAgeSeconds(),
            features.parameterCount(),
            features.payloadSizeBytes(),
            features.headerFingerprintHash(),
            features.ipBucket()
        );
    }
}

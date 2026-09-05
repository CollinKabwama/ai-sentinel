package dev.aisentinel.benchmark.fixture;

import dev.aisentinel.core.model.RequestFeatures;

/**
 * Deterministic {@link RequestFeatures} builders for in-process benchmarks.
 */
public final class BenchmarkFeatureFactory {

    private BenchmarkFeatureFactory() {
    }

    /** Typical established-baseline observation (moderate volume, stable shape). */
    public static RequestFeatures establishedBaseline(String identityHash, String endpoint) {
        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(12)
            .endpointEntropy(0.15)
            .endpointConcentration(0.85)
            .tokenAgeSeconds(120)
            .parameterCount(2)
            .payloadSizeBytes(256)
            .headerFingerprintHash(42L)
            .ipBucket(7)
            .build();
    }

    /** Cold / sparse observation used for warmup-path checks. */
    public static RequestFeatures warmupSparse(String identityHash, String endpoint) {
        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(1)
            .endpointEntropy(0.0)
            .endpointConcentration(1.0)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(1L)
            .ipBucket(1)
            .build();
    }

    /** Abrupt volume deviation relative to an established baseline. */
    public static RequestFeatures abruptVolumeDeviation(String identityHash, String endpoint) {
        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_100L)
            .requestsPerWindow(240)
            .endpointEntropy(0.05)
            .endpointConcentration(0.95)
            .tokenAgeSeconds(30)
            .parameterCount(4)
            .payloadSizeBytes(512)
            .headerFingerprintHash(99L)
            .ipBucket(7)
            .build();
    }

    /** Gradual elevation of volume vs established baseline. */
    public static RequestFeatures gradualElevation(String identityHash, String endpoint, double requestsPerWindow) {
        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_200L)
            .requestsPerWindow(requestsPerWindow)
            .endpointEntropy(0.12)
            .endpointConcentration(0.88)
            .tokenAgeSeconds(90)
            .parameterCount(2)
            .payloadSizeBytes(300)
            .headerFingerprintHash(42L)
            .ipBucket(7)
            .build();
    }

    /** Small request shape for feature-extraction sizing. */
    public static RequestFeatures smallRequestShape(String identityHash, String endpoint) {
        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(2)
            .endpointEntropy(0.0)
            .endpointConcentration(1.0)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0L)
            .ipBucket(0)
            .build();
    }

    /** Larger-but-valid request shape (not a denial-of-service payload). */
    public static RequestFeatures largerValidRequestShape(String identityHash, String endpoint) {
        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(40)
            .endpointEntropy(1.2)
            .endpointConcentration(0.35)
            .tokenAgeSeconds(600)
            .parameterCount(24)
            .payloadSizeBytes(32_768)
            .headerFingerprintHash(123456789L)
            .ipBucket(42)
            .build();
    }
}

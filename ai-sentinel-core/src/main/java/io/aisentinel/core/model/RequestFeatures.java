package io.aisentinel.core.model;

import java.util.Objects;

/**
 * Privacy-aware feature vector extracted from an incoming request.
 * All values are numeric; no raw PII is stored.
 */
public final class RequestFeatures {

    private final String identityHash;
    private final String endpoint;
    private final long timestampMillis;
    private final double requestsPerWindow;
    private final double endpointEntropy;
    private final double tokenAgeSeconds;
    private final int parameterCount;
    private final long payloadSizeBytes;
    private final long headerFingerprintHash;
    private final int ipBucket;

    private RequestFeatures(Builder b) {
        this.identityHash = Objects.requireNonNull(b.identityHash, "identityHash");
        this.endpoint = Objects.requireNonNull(b.endpoint, "endpoint");
        this.timestampMillis = b.timestampMillis;
        this.requestsPerWindow = b.requestsPerWindow;
        this.endpointEntropy = b.endpointEntropy;
        this.tokenAgeSeconds = b.tokenAgeSeconds;
        this.parameterCount = b.parameterCount;
        this.payloadSizeBytes = b.payloadSizeBytes;
        this.headerFingerprintHash = b.headerFingerprintHash;
        this.ipBucket = b.ipBucket;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String identityHash() { return identityHash; }
    public String endpoint() { return endpoint; }
    public long timestampMillis() { return timestampMillis; }
    public double requestsPerWindow() { return requestsPerWindow; }
    public double endpointEntropy() { return endpointEntropy; }
    public double tokenAgeSeconds() { return tokenAgeSeconds; }
    public int parameterCount() { return parameterCount; }
    public long payloadSizeBytes() { return payloadSizeBytes; }
    public long headerFingerprintHash() { return headerFingerprintHash; }
    public int ipBucket() { return ipBucket; }

    /**
     * Full feature vector for statistical scoring (Welford / z-score path).
     * Order: requestsPerWindow, endpointEntropy, tokenAgeSeconds, parameterCount, payloadSizeBytes, headerFingerprintHash, ipBucket
     */
    public double[] toArray() {
        return new double[] {
            requestsPerWindow,
            endpointEntropy,
            tokenAgeSeconds,
            parameterCount,
            payloadSizeBytes,
            headerFingerprintHash,
            ipBucket
        };
    }

    /**
     * Subset for Isolation Forest only: behavioral / magnitude features (no hash-derived ordinals).
     * Order: requestsPerWindow, endpointEntropy, tokenAgeSeconds, parameterCount, payloadSizeBytes
     */
    public double[] toIsolationForestArray() {
        return new double[] {
            requestsPerWindow,
            endpointEntropy,
            tokenAgeSeconds,
            parameterCount,
            payloadSizeBytes
        };
    }

    public static final class Builder {
        private String identityHash;
        private String endpoint;
        private long timestampMillis;
        private double requestsPerWindow;
        private double endpointEntropy;
        private double tokenAgeSeconds = -1;
        private int parameterCount;
        private long payloadSizeBytes;
        private long headerFingerprintHash;
        private int ipBucket;

        public Builder identityHash(String v) { identityHash = v; return this; }
        public Builder endpoint(String v) { endpoint = v; return this; }
        public Builder timestampMillis(long v) { timestampMillis = v; return this; }
        public Builder requestsPerWindow(double v) { requestsPerWindow = v; return this; }
        public Builder endpointEntropy(double v) { endpointEntropy = v; return this; }
        public Builder tokenAgeSeconds(double v) { tokenAgeSeconds = v; return this; }
        public Builder parameterCount(int v) { parameterCount = v; return this; }
        public Builder payloadSizeBytes(long v) { payloadSizeBytes = v; return this; }
        public Builder headerFingerprintHash(long v) { headerFingerprintHash = v; return this; }
        public Builder ipBucket(int v) { ipBucket = v; return this; }

        public RequestFeatures build() {
            return new RequestFeatures(this);
        }
    }
}

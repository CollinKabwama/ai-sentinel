package dev.aisentinel.core.model;

import java.util.Objects;

/**
 * Privacy-aware feature vector extracted from an incoming request.
 * All values are numeric; no raw PII is stored.
 * <p>
 * Feature roles for statistical scoring (see {@link #toStatisticalArray()}):
 * <ul>
 *   <li>{@code requestsPerWindow} — rolling request count within the BaselineStore TTL window
 *       (not a normalized per-second rate); primary flood / volume signal</li>
 *   <li>{@code endpointEntropy} — continuous diversity (Shannon)</li>
 *   <li>{@code endpointConcentration} — continuous concentration (max share); distribution-shift only,
 *       not a mono-endpoint flood discriminator</li>
 *   <li>{@code tokenAgeSeconds} — seconds since {@code X-Token-Issued-At} when {@code Authorization}
 *       is present; {@code -1} means missing/invalid/overflow or a materially future issued-at;
 *       future issued-at within tolerated clock skew is clamped to {@code 0}</li>
 *   <li>{@code parameterCount} — query/form parameter map size (not JSON body field count)</li>
 *   <li>{@code payloadSizeBytes} — continuous magnitude</li>
 *   <li>{@code headerFingerprintHash}, {@code ipBucket} — identity-like; exported in
 *       {@link #toArray()} and used by behavioral trust, but excluded from statistical z-scoring</li>
 * </ul>
 */
public final class RequestFeatures {

    private final String identityHash;
    private final String endpoint;
    private final long timestampMillis;
    private final double requestsPerWindow;
    private final double endpointEntropy;
    private final double endpointConcentration;
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
        this.endpointConcentration = b.endpointConcentration;
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

    /**
     * Max endpoint-share in the recent per-identity histogram, in {@code [0, 1]}.
     * Complements Shannon {@link #endpointEntropy()} (diversity) without overloading it.
     * Detects diverse→mono distribution shifts. Does <strong>not</strong> distinguish established
     * mono-endpoint use from mono-endpoint flooding (both yield concentration ≈ 1); volume floods
     * are carried by {@link #requestsPerWindow()}.
     */
    public double endpointConcentration() { return endpointConcentration; }

    public double tokenAgeSeconds() { return tokenAgeSeconds; }
    public int parameterCount() { return parameterCount; }
    public long payloadSizeBytes() { return payloadSizeBytes; }
    public long headerFingerprintHash() { return headerFingerprintHash; }
    public int ipBucket() { return ipBucket; }

    /**
     * Full export vector (training snapshots / diagnostics).
     * Order: requestsPerWindow, endpointEntropy, tokenAgeSeconds, parameterCount,
     * payloadSizeBytes, headerFingerprintHash, ipBucket.
     * <p>
     * Identity-like hash/IP dimensions are included for export compatibility; the online
     * statistical scorer uses {@link #toStatisticalArray()} instead.
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
     * Behavioral / magnitude features for statistical z-scoring.
     * Excludes identity-like {@code headerFingerprintHash} and {@code ipBucket}.
     * Includes {@link #endpointConcentration()} as a separate concentration signal alongside
     * Shannon {@link #endpointEntropy()}.
     * Order: requestsPerWindow, endpointEntropy, endpointConcentration, tokenAgeSeconds,
     * parameterCount, payloadSizeBytes
     */
    public double[] toStatisticalArray() {
        return new double[] {
            requestsPerWindow,
            endpointEntropy,
            endpointConcentration,
            tokenAgeSeconds,
            parameterCount,
            payloadSizeBytes
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
        private double endpointConcentration;
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
        public Builder endpointConcentration(double v) { endpointConcentration = v; return this; }
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

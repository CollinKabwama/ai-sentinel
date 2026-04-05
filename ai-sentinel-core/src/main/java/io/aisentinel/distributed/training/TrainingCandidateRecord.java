package io.aisentinel.distributed.training;

import java.util.Arrays;
import java.util.Objects;

/**
 * Versioned, bounded training candidate for export to an external trainer (Kafka, log shipper, etc.).
 * Uses SHA-256 fingerprints for endpoint and enforcement-key material — no raw paths or composite keys.
 */
public final class TrainingCandidateRecord {

    /** Schema 2: endpoint/enforcement key as hashes; eventId for deduplication. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private static final int MAX_TENANT = 128;
    private static final int MAX_NODE = 128;
    private static final int MAX_HASH = 128;
    private static final int SHA256_HEX_LEN = 64;
    private static final int MAX_EVENT_ID = 48;
    private static final int MAX_MODE = 32;
    private static final int MAX_ACTION = 32;

    private final int schemaVersion;
    private final String eventId;
    private final String tenantId;
    private final String nodeId;
    private final String identityHash;
    private final String endpointSha256Hex;
    private final String enforcementKeySha256Hex;
    private final long observedAtEpochMillis;
    private final double[] isolationForestFeatures;
    private final double[] statisticalFeatures;
    private final Double statisticalScore;
    private final Double isolationForestScore;
    private final double compositeScore;
    private final String policyAction;
    private final String sentinelMode;
    private final boolean requestProceeded;
    private final boolean startupGraceActive;

    public TrainingCandidateRecord(
        int schemaVersion,
        String eventId,
        String tenantId,
        String nodeId,
        String identityHash,
        String endpointSha256Hex,
        String enforcementKeySha256Hex,
        long observedAtEpochMillis,
        double[] isolationForestFeatures,
        double[] statisticalFeatures,
        Double statisticalScore,
        Double isolationForestScore,
        double compositeScore,
        String policyAction,
        String sentinelMode,
        boolean requestProceeded,
        boolean startupGraceActive
    ) {
        this.schemaVersion = schemaVersion;
        this.eventId = truncate(eventId, MAX_EVENT_ID);
        this.tenantId = truncate(tenantId, MAX_TENANT);
        this.nodeId = truncate(nodeId, MAX_NODE);
        this.identityHash = truncate(identityHash, MAX_HASH);
        this.endpointSha256Hex = normalizeSha256Hex(endpointSha256Hex);
        this.enforcementKeySha256Hex = normalizeSha256Hex(enforcementKeySha256Hex);
        this.observedAtEpochMillis = observedAtEpochMillis;
        this.isolationForestFeatures = copyFixed(isolationForestFeatures, 5);
        this.statisticalFeatures = copyFixed(statisticalFeatures, 7);
        this.statisticalScore = statisticalScore;
        this.isolationForestScore = isolationForestScore;
        this.compositeScore = clampUnit(compositeScore);
        this.policyAction = truncate(policyAction, MAX_ACTION);
        this.sentinelMode = truncate(sentinelMode, MAX_MODE);
        this.requestProceeded = requestProceeded;
        this.startupGraceActive = startupGraceActive;
    }

    private static String normalizeSha256Hex(String hex) {
        if (hex == null || hex.isBlank() || hex.length() < SHA256_HEX_LEN) {
            return "0".repeat(SHA256_HEX_LEN);
        }
        return hex.substring(0, SHA256_HEX_LEN).toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static double[] copyFixed(double[] src, int len) {
        double[] out = new double[len];
        if (src == null) {
            return out;
        }
        int n = Math.min(len, src.length);
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }

    private static double clampUnit(double v) {
        if (Double.isNaN(v) || v < 0) {
            return 0.0;
        }
        return Math.min(1.0, v);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String eventId() {
        return eventId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String nodeId() {
        return nodeId;
    }

    public String identityHash() {
        return identityHash;
    }

    public String endpointSha256Hex() {
        return endpointSha256Hex;
    }

    public String enforcementKeySha256Hex() {
        return enforcementKeySha256Hex;
    }

    public long observedAtEpochMillis() {
        return observedAtEpochMillis;
    }

    public double[] isolationForestFeatures() {
        return Arrays.copyOf(isolationForestFeatures, isolationForestFeatures.length);
    }

    public double[] statisticalFeatures() {
        return Arrays.copyOf(statisticalFeatures, statisticalFeatures.length);
    }

    public Double statisticalScore() {
        return statisticalScore;
    }

    public Double isolationForestScore() {
        return isolationForestScore;
    }

    public double compositeScore() {
        return compositeScore;
    }

    public String policyAction() {
        return policyAction;
    }

    public String sentinelMode() {
        return sentinelMode;
    }

    public boolean requestProceeded() {
        return requestProceeded;
    }

    public boolean startupGraceActive() {
        return startupGraceActive;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrainingCandidateRecord that)) return false;
        return schemaVersion == that.schemaVersion
            && observedAtEpochMillis == that.observedAtEpochMillis
            && Double.compare(that.compositeScore, compositeScore) == 0
            && requestProceeded == that.requestProceeded
            && startupGraceActive == that.startupGraceActive
            && Objects.equals(eventId, that.eventId)
            && Objects.equals(tenantId, that.tenantId)
            && Objects.equals(nodeId, that.nodeId)
            && Objects.equals(identityHash, that.identityHash)
            && Objects.equals(endpointSha256Hex, that.endpointSha256Hex)
            && Objects.equals(enforcementKeySha256Hex, that.enforcementKeySha256Hex)
            && Arrays.equals(isolationForestFeatures, that.isolationForestFeatures)
            && Arrays.equals(statisticalFeatures, that.statisticalFeatures)
            && Objects.equals(statisticalScore, that.statisticalScore)
            && Objects.equals(isolationForestScore, that.isolationForestScore)
            && Objects.equals(policyAction, that.policyAction)
            && Objects.equals(sentinelMode, that.sentinelMode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, eventId, tenantId, nodeId, identityHash, endpointSha256Hex,
            enforcementKeySha256Hex, observedAtEpochMillis, Arrays.hashCode(isolationForestFeatures),
            Arrays.hashCode(statisticalFeatures), statisticalScore, isolationForestScore, compositeScore,
            policyAction, sentinelMode, requestProceeded, startupGraceActive);
    }
}

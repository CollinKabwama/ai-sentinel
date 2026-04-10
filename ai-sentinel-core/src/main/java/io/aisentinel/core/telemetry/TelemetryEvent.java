package io.aisentinel.core.telemetry;

import java.util.Map;

/**
 * Immutable telemetry payload for {@link TelemetryEmitter}. Factory methods mask identity hashes in log output.
 */
public record TelemetryEvent(
    String type,
    long timestampMillis,
    Map<String, Object> payload
) {
    public static TelemetryEvent threatScored(String identityHash, String endpoint, double score) {
        return new TelemetryEvent("ThreatScored", System.currentTimeMillis(),
            Map.of("identityHash", maskHash(identityHash), "endpoint", endpoint, "score", score));
    }

    public static TelemetryEvent anomalyDetected(String identityHash, String endpoint, double score) {
        return new TelemetryEvent("AnomalyDetected", System.currentTimeMillis(),
            Map.of("identityHash", maskHash(identityHash), "endpoint", endpoint, "score", score));
    }

    public static TelemetryEvent policyActionApplied(String identityHash, String endpoint, String action, String detail) {
        var p = new java.util.HashMap<String, Object>();
        p.put("identityHash", maskHash(identityHash));
        p.put("endpoint", endpoint);
        p.put("action", action);
        if (detail != null) p.put("detail", detail);
        return new TelemetryEvent("PolicyActionApplied", System.currentTimeMillis(), p);
    }

    public static TelemetryEvent quarantineStarted(String identityHash, String endpoint, long durationMs) {
        return new TelemetryEvent("QuarantineStarted", System.currentTimeMillis(),
            Map.of("identityHash", maskHash(identityHash), "endpoint", endpoint, "durationMs", durationMs));
    }

    private static String maskHash(String h) {
        if (h == null || h.length() < 8) return "***";
        return h.substring(0, 4) + "***" + h.substring(h.length() - 4);
    }
}

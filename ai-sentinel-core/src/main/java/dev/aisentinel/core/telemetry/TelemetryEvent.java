package dev.aisentinel.core.telemetry;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.OperatorEvaluationPhase;
import dev.aisentinel.core.metrics.FailOpenReason;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable telemetry payload for {@link TelemetryEmitter}. Factory methods mask identity hashes in log output.
 */
public record TelemetryEvent(
    String type,
    long timestampMillis,
    Map<String, Object> payload
) {
    public static TelemetryEvent threatScored(String identityHash, String endpoint, double score) {
        return threatScored(identityHash, endpoint, score, null, null);
    }

    /**
     * Threat score with optional evaluation lifecycle markers and IF score mode.
     * Additive payload keys only — existing consumers ignore unknown fields.
     */
    public static TelemetryEvent threatScored(String identityHash, String endpoint, double score,
                                             Collection<EvaluationStatus> evaluationStatuses,
                                             String isolationForestScoreMode) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("identityHash", maskHash(identityHash));
        p.put("endpoint", endpoint);
        p.put("score", score);
        if (evaluationStatuses != null && !evaluationStatuses.isEmpty()) {
            List<String> names = new ArrayList<>(evaluationStatuses.size());
            for (EvaluationStatus s : evaluationStatuses) {
                if (s != null) {
                    names.add(s.name());
                }
            }
            names.sort(String::compareTo);
            p.put("evaluationStatuses", List.copyOf(names));
            Set<String> phases = OperatorEvaluationPhase.fromStatuses(evaluationStatuses);
            if (!phases.isEmpty()) {
                p.put("operatorPhases", List.copyOf(phases));
            }
        }
        if (isolationForestScoreMode != null && !isolationForestScoreMode.isBlank()) {
            p.put("isolationForestScoreMode", isolationForestScoreMode);
        }
        return new TelemetryEvent("ThreatScored", System.currentTimeMillis(), Map.copyOf(p));
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

    /**
     * Structured fail-open event. Does not claim enforcement succeeded — the request was allowed
     * after an error or optional-subsystem failure.
     */
    public static TelemetryEvent failOpen(FailOpenReason reason, String endpoint) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("reason", reason != null ? reason.name() : "UNKNOWN");
        p.put("operatorPhase", OperatorEvaluationPhase.FAIL_OPEN);
        if (endpoint != null) {
            p.put("endpoint", endpoint);
        }
        return new TelemetryEvent("FailOpen", System.currentTimeMillis(), Map.copyOf(p));
    }

    private static String maskHash(String h) {
        if (h == null || h.length() < 8) return "***";
        return h.substring(0, 4) + "***" + h.substring(h.length() - 4);
    }
}

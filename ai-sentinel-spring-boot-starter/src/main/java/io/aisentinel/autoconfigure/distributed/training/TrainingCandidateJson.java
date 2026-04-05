package io.aisentinel.autoconfigure.distributed.training;

import io.aisentinel.distributed.training.TrainingCandidateRecord;

import java.util.LinkedHashMap;
import java.util.Map;

final class TrainingCandidateJson {

    private TrainingCandidateJson() {
    }

    static Map<String, Object> toMap(TrainingCandidateRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", r.schemaVersion());
        m.put("eventId", r.eventId());
        m.put("tenantId", r.tenantId());
        m.put("nodeId", r.nodeId());
        m.put("identityHash", r.identityHash());
        m.put("endpointSha256Hex", r.endpointSha256Hex());
        m.put("enforcementKeySha256Hex", r.enforcementKeySha256Hex());
        m.put("observedAtEpochMillis", r.observedAtEpochMillis());
        m.put("isolationForestFeatures", r.isolationForestFeatures());
        m.put("statisticalFeatures", r.statisticalFeatures());
        if (r.statisticalScore() != null) {
            m.put("statisticalScore", r.statisticalScore());
        }
        if (r.isolationForestScore() != null) {
            m.put("isolationForestScore", r.isolationForestScore());
        }
        m.put("compositeScore", r.compositeScore());
        m.put("policyAction", r.policyAction());
        m.put("sentinelMode", r.sentinelMode());
        m.put("requestProceeded", r.requestProceeded());
        m.put("startupGraceActive", r.startupGraceActive());
        return m;
    }
}

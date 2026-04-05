package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingCandidateRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Phase 5.5 JSON training candidates; rejects unknown schema or malformed feature arrays.
 */
public final class TrainingCandidateMessageParser {

    private final ObjectMapper mapper;

    public TrainingCandidateMessageParser(ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public TrainingCandidateRecord parse(String json) throws IOException {
        JsonNode n = mapper.readTree(json);
        int schema = n.path("schemaVersion").asInt(-1);
        if (schema != TrainingCandidateRecord.CURRENT_SCHEMA_VERSION) {
            throw new IOException("unsupported schemaVersion: " + schema);
        }
        String eventId = text(n, "eventId");
        String tenantId = text(n, "tenantId");
        String nodeId = text(n, "nodeId");
        String identityHash = text(n, "identityHash");
        String endpointSha256Hex = text(n, "endpointSha256Hex");
        String enforcementKeySha256Hex = text(n, "enforcementKeySha256Hex");
        long observedAt = n.path("observedAtEpochMillis").asLong(0L);
        double[] ifVec = readDoubles(n, "isolationForestFeatures", 5);
        double[] stVec = readDoubles(n, "statisticalFeatures", 7);
        Double statScore = readNullableDouble(n, "statisticalScore");
        Double ifScore = readNullableDouble(n, "isolationForestScore");
        double composite = n.path("compositeScore").asDouble(Double.NaN);
        if (Double.isNaN(composite)) {
            throw new IOException("missing compositeScore");
        }
        String policyAction = text(n, "policyAction");
        String sentinelMode = text(n, "sentinelMode");
        boolean requestProceeded = n.path("requestProceeded").asBoolean(true);
        boolean startupGrace = n.path("startupGraceActive").asBoolean(false);
        return new TrainingCandidateRecord(
            schema,
            eventId,
            tenantId,
            nodeId,
            identityHash,
            endpointSha256Hex,
            enforcementKeySha256Hex,
            observedAt,
            ifVec,
            stVec,
            statScore,
            ifScore,
            composite,
            policyAction,
            sentinelMode,
            requestProceeded,
            startupGrace
        );
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static Double readNullableDouble(JsonNode n, String field) {
        if (!n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return n.get(field).asDouble();
    }

    private static double[] readDoubles(JsonNode n, String field, int expectedLen) throws IOException {
        JsonNode arr = n.get(field);
        if (arr == null || !arr.isArray()) {
            throw new IOException("missing array: " + field);
        }
        if (arr.size() != expectedLen) {
            throw new IOException("bad length for " + field + ": " + arr.size());
        }
        List<Double> tmp = new ArrayList<>(expectedLen);
        for (JsonNode x : arr) {
            if (!x.isNumber()) {
                throw new IOException("non-numeric in " + field);
            }
            tmp.add(x.asDouble());
        }
        double[] out = new double[expectedLen];
        for (int i = 0; i < expectedLen; i++) {
            out[i] = tmp.get(i);
        }
        return out;
    }
}

package dev.aisentinel.core.pilot;

import java.util.Locale;

/**
 * Deterministic JSON serialization for pilot observations (stable field order).
 */
public final class PilotObservationJson {

    private PilotObservationJson() {
    }

    public static String write(PilotObservation observation) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "schemaVersion", observation.schemaVersion(), true);
        appendString(json, "observationId", observation.observationId(), false);
        appendString(json, "observedAt", observation.observedAt(), false);
        appendString(json, "pilotSessionId", observation.pilotSessionId(), false);
        appendString(json, "evidenceClass", observation.evidenceClass(), false);
        appendString(json, "pseudonymousIdentity", observation.pseudonymousIdentity(), false);
        appendString(json, "endpointKey", observation.endpointKey(), false);
        json.append(",\"features\":{");
        PilotObservationFeatures f = observation.features();
        appendDouble(json, "requestsPerWindow", f.requestsPerWindow(), true);
        appendDouble(json, "endpointEntropy", f.endpointEntropy(), false);
        appendDouble(json, "endpointConcentration", f.endpointConcentration(), false);
        appendDouble(json, "tokenAgeSeconds", f.tokenAgeSeconds(), false);
        appendLong(json, "parameterCount", f.parameterCount(), false);
        appendLong(json, "payloadSizeBytes", f.payloadSizeBytes(), false);
        appendLong(json, "headerFingerprintHash", f.headerFingerprintHash(), false);
        appendLong(json, "ipBucket", f.ipBucket(), false);
        json.append('}');
        appendDouble(json, "anomalyScore", observation.anomalyScore(), false);
        if (observation.policyScore() != null) {
            appendDouble(json, "policyScore", observation.policyScore(), false);
        }
        json.append(",\"evaluationStatuses\":[");
        for (int i = 0; i < observation.evaluationStatuses().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(observation.evaluationStatuses().get(i))).append('"');
        }
        json.append(']');
        appendString(json, "riskDerivedAction", observation.riskDerivedAction(), false);
        json.append(",\"enforcementApplied\":").append(observation.enforcementApplied());
        appendString(json, "requestOutcome", observation.requestOutcome(), false);
        appendString(json, "runtimeMode", observation.runtimeMode(), false);
        appendString(json, "baselineUpdateStatus", observation.baselineUpdateStatus(), false);
        appendString(json, "scorerId", observation.scorerId(), false);
        appendString(json, "scorerVersion", observation.scorerVersion(), false);
        appendString(json, "softwareVersion", observation.softwareVersion(), false);
        appendString(json, "featureSchemaVersion", observation.featureSchemaVersion(), false);
        if (observation.pipelineLatencyNanos() != null) {
            json.append(",\"pipelineLatencyNanos\":").append(observation.pipelineLatencyNanos());
        }
        if (observation.failOpenReason() != null && !observation.failOpenReason().isBlank()) {
            appendString(json, "failOpenReason", observation.failOpenReason(), false);
        }
        json.append('}');
        return json.toString();
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(field).append("\":\"").append(escape(value == null ? "" : value)).append('"');
    }

    private static void appendDouble(StringBuilder json, String field, double value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(field).append("\":");
        if (Double.isFinite(value)) {
            json.append(Double.toString(value));
        } else {
            json.append("null");
        }
    }

    private static void appendLong(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(field).append("\":").append(value);
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

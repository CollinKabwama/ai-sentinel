package dev.aisentinel.core.pilot;

import java.util.Objects;

/**
 * Finalized pilot-manifest.json fields.
 */
public record PilotManifest(
    String schemaVersion,
    String pilotSessionId,
    String evidenceClass,
    String startedAt,
    String endedAt,
    String runtimeMode,
    String softwareVersion,
    String featureSchemaVersion,
    String observationSchemaVersion,
    String configDigest,
    String scorerId,
    String scorerVersion,
    String baselineUpdatePolicy,
    String policyEngineId,
    long observationCount,
    String observationsSha256,
    String summarySha256,
    String claimBoundary
) {
    public static final String SCHEMA_VERSION = "1";
    public static final String CLAIM_BOUNDARY =
        "OPERATIONAL_OBSERVATION_ONLY; not detection efficacy; not production validation; not deployment approval";

    public PilotManifest {
        schemaVersion = require(schemaVersion, "schemaVersion");
        pilotSessionId = require(pilotSessionId, "pilotSessionId");
        evidenceClass = require(evidenceClass, "evidenceClass");
        startedAt = require(startedAt, "startedAt");
        endedAt = require(endedAt, "endedAt");
        runtimeMode = require(runtimeMode, "runtimeMode");
        softwareVersion = require(softwareVersion, "softwareVersion");
        featureSchemaVersion = require(featureSchemaVersion, "featureSchemaVersion");
        observationSchemaVersion = require(observationSchemaVersion, "observationSchemaVersion");
        configDigest = require(configDigest, "configDigest");
        scorerId = scorerId == null ? "" : scorerId;
        scorerVersion = scorerVersion == null ? "" : scorerVersion;
        baselineUpdatePolicy = require(baselineUpdatePolicy, "baselineUpdatePolicy");
        policyEngineId = policyEngineId == null ? "" : policyEngineId;
        observationsSha256 = require(observationsSha256, "observationsSha256");
        summarySha256 = require(summarySha256, "summarySha256");
        claimBoundary = require(claimBoundary, "claimBoundary");
    }

    public String toJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        append(json, "schemaVersion", schemaVersion, true);
        append(json, "pilotSessionId", pilotSessionId, false);
        append(json, "evidenceClass", evidenceClass, false);
        append(json, "startedAt", startedAt, false);
        append(json, "endedAt", endedAt, false);
        append(json, "runtimeMode", runtimeMode, false);
        append(json, "softwareVersion", softwareVersion, false);
        append(json, "featureSchemaVersion", featureSchemaVersion, false);
        append(json, "observationSchemaVersion", observationSchemaVersion, false);
        append(json, "configDigest", configDigest, false);
        append(json, "scorerId", scorerId, false);
        append(json, "scorerVersion", scorerVersion, false);
        append(json, "baselineUpdatePolicy", baselineUpdatePolicy, false);
        append(json, "policyEngineId", policyEngineId, false);
        json.append(",\"observationCount\":").append(observationCount);
        append(json, "observationsSha256", observationsSha256, false);
        append(json, "summarySha256", summarySha256, false);
        append(json, "claimBoundary", claimBoundary, false);
        json.append('}');
        return json.toString();
    }

    private static void append(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(field).append("\":\"")
            .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
            .append('"');
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

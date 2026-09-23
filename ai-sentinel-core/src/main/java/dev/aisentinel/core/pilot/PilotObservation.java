package dev.aisentinel.core.pilot;

import java.util.List;
import java.util.Objects;

/**
 * One MONITOR-mode pilot observation.
 * <p>
 * Does not contain fields for Authorization, Cookie, raw headers/body/query values,
 * passwords, tokens, raw usernames/emails, or raw client IP.
 */
public record PilotObservation(
    String schemaVersion,
    String observationId,
    String observedAt,
    String pilotSessionId,
    String evidenceClass,
    String pseudonymousIdentity,
    String endpointKey,
    PilotObservationFeatures features,
    double anomalyScore,
    Double policyScore,
    List<String> evaluationStatuses,
    String riskDerivedAction,
    boolean enforcementApplied,
    String requestOutcome,
    String runtimeMode,
    String baselineUpdateStatus,
    String scorerId,
    String scorerVersion,
    String softwareVersion,
    String featureSchemaVersion,
    Long pipelineLatencyNanos,
    String failOpenReason
) {
    public static final String SCHEMA_VERSION = "1";
    public static final String EVIDENCE_CLASS = "OPERATIONAL_OBSERVATION";
    public static final String REQUEST_OUTCOME_CONTINUED = "CONTINUED";
    public static final String RUNTIME_MODE_MONITOR = "MONITOR";

    public PilotObservation {
        schemaVersion = require(schemaVersion, "schemaVersion");
        observationId = require(observationId, "observationId");
        observedAt = require(observedAt, "observedAt");
        pilotSessionId = require(pilotSessionId, "pilotSessionId");
        evidenceClass = require(evidenceClass, "evidenceClass");
        pseudonymousIdentity = require(pseudonymousIdentity, "pseudonymousIdentity");
        endpointKey = require(endpointKey, "endpointKey");
        Objects.requireNonNull(features, "features");
        evaluationStatuses = evaluationStatuses == null ? List.of() : List.copyOf(evaluationStatuses);
        riskDerivedAction = require(riskDerivedAction, "riskDerivedAction");
        requestOutcome = require(requestOutcome, "requestOutcome");
        runtimeMode = require(runtimeMode, "runtimeMode");
        baselineUpdateStatus = require(baselineUpdateStatus, "baselineUpdateStatus");
        scorerId = scorerId == null ? "" : scorerId;
        scorerVersion = scorerVersion == null ? "" : scorerVersion;
        softwareVersion = require(softwareVersion, "softwareVersion");
        featureSchemaVersion = require(featureSchemaVersion, "featureSchemaVersion");
        failOpenReason = failOpenReason == null ? "" : failOpenReason;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}

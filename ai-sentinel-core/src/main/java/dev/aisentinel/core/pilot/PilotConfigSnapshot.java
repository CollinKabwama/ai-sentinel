package dev.aisentinel.core.pilot;

import java.util.Objects;

/**
 * Non-secret configuration frozen for one pilot session.
 * Used for provenance and deterministic config digests.
 */
public record PilotConfigSnapshot(
    String runtimeMode,
    String baselineUpdatePolicy,
    String scorerId,
    String scorerVersion,
    String softwareVersion,
    String featureSchemaVersion,
    String observationSchemaVersion,
    String evidenceClass,
    String policyEngineId,
    double thresholdModerate,
    double thresholdElevated,
    double thresholdHigh,
    double thresholdCritical
) {
    public PilotConfigSnapshot {
        runtimeMode = require(runtimeMode, "runtimeMode");
        baselineUpdatePolicy = require(baselineUpdatePolicy, "baselineUpdatePolicy");
        scorerId = scorerId == null ? "" : scorerId;
        scorerVersion = scorerVersion == null ? "" : scorerVersion;
        softwareVersion = require(softwareVersion, "softwareVersion");
        featureSchemaVersion = require(featureSchemaVersion, "featureSchemaVersion");
        observationSchemaVersion = require(observationSchemaVersion, "observationSchemaVersion");
        evidenceClass = require(evidenceClass, "evidenceClass");
        policyEngineId = policyEngineId == null ? "" : policyEngineId;
        requireFiniteThreshold(thresholdModerate, "thresholdModerate");
        requireFiniteThreshold(thresholdElevated, "thresholdElevated");
        requireFiniteThreshold(thresholdHigh, "thresholdHigh");
        requireFiniteThreshold(thresholdCritical, "thresholdCritical");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /**
     * Canonical digest material (stable field order, no secrets/paths/timestamps).
     */
    public String canonicalDigestMaterial() {
        return "baselineUpdatePolicy=" + baselineUpdatePolicy
            + "\nevidenceClass=" + evidenceClass
            + "\nfeatureSchemaVersion=" + featureSchemaVersion
            + "\nobservationSchemaVersion=" + observationSchemaVersion
            + "\npolicyEngineId=" + policyEngineId
            + "\nruntimeMode=" + runtimeMode
            + "\nscorerId=" + scorerId
            + "\nscorerVersion=" + scorerVersion
            + "\nsoftwareVersion=" + softwareVersion
            + "\nthresholdCritical=" + Double.toString(thresholdCritical)
            + "\nthresholdElevated=" + Double.toString(thresholdElevated)
            + "\nthresholdHigh=" + Double.toString(thresholdHigh)
            + "\nthresholdModerate=" + Double.toString(thresholdModerate)
            + "\n";
    }

    private static void requireFiniteThreshold(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}

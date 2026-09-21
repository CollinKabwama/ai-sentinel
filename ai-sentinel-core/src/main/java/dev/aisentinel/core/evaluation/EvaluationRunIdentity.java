package dev.aisentinel.core.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic Evaluation Kit concrete-run identity.
 * <p>
 * {@code resultId} remains an input-bound result-family identity. {@code evaluationRunId}
 * identifies one configured evaluation of that input (threshold and replay configuration).
 */
final class EvaluationRunIdentity {

    private EvaluationRunIdentity() {
    }

    static String resultFamilyId(String inputId) {
        return "result." + Objects.requireNonNull(inputId, "inputId");
    }

    static String evaluationRunId(
        String datasetSource,
        String inputId,
        String eventsSha256,
        String annotationsSha256OrEmpty,
        String featureSchemaVersion,
        String evaluationEventSchemaVersion,
        String replayConfigurationFingerprint,
        String scorerId,
        String scorerVersion,
        String policyId,
        String policyVersion,
        double anomalyThreshold,
        String resultSchemaVersion
    ) {
        String material = String.join("\n",
            "datasetSource=" + require(datasetSource, "datasetSource"),
            "inputId=" + require(inputId, "inputId"),
            "eventsSha256=" + require(eventsSha256, "eventsSha256"),
            "annotationsSha256=" + nullToEmpty(annotationsSha256OrEmpty),
            "featureSchemaVersion=" + require(featureSchemaVersion, "featureSchemaVersion"),
            "evaluationEventSchemaVersion="
                + require(evaluationEventSchemaVersion, "evaluationEventSchemaVersion"),
            "replayConfigurationFingerprint="
                + require(replayConfigurationFingerprint, "replayConfigurationFingerprint"),
            "scorerId=" + require(scorerId, "scorerId"),
            "scorerVersion=" + nullToEmpty(scorerVersion),
            "policyId=" + require(policyId, "policyId"),
            "policyVersion=" + nullToEmpty(policyVersion),
            "anomalyThreshold=" + Double.toString(anomalyThreshold),
            "resultSchemaVersion=" + require(resultSchemaVersion, "resultSchemaVersion")
        );
        return "evalrun." + shortSha256(material);
    }

    /**
     * Comparison identity for two concrete runs.
     * <p>
     * Prefer {@code evaluationRunId}. When absent (legacy evidence), use a deterministic
     * surrogate from persisted family identity plus available result-affecting fields.
     * That surrogate is <strong>not</strong> equivalent to a full concrete run identity.
     */
    static String comparisonId(RunEvidence baseline, RunEvidence candidate) {
        String left = concreteOrLegacySurrogate(baseline);
        String right = concreteOrLegacySurrogate(candidate);
        return "comparison." + shortSha256(left + "\n" + right);
    }

    static String concreteOrLegacySurrogate(RunEvidence run) {
        if (run.evaluationRunId() != null && !run.evaluationRunId().isBlank()) {
            return "evaluationRunId=" + run.evaluationRunId();
        }
        // Legacy compatibility surrogate — weaker than evaluationRunId.
        String eventsDigest = "generated-corpus".equals(run.datasetSource())
            ? nullToEmpty(run.corpusEventsSha256())
            : nullToEmpty(run.eventsSha256());
        return String.join("\n",
            "legacySurrogate=true",
            "datasetSource=" + run.datasetSource(),
            "resultId=" + run.resultId(),
            "eventsDigest=" + eventsDigest,
            "annotationsSha256=" + nullToEmpty(run.annotationsSha256()),
            "anomalyThreshold=" + Double.toString(run.anomalyThreshold()),
            "featureSchemaVersion=" + nullToEmpty(run.featureSchemaVersion()),
            "evaluationEventSchemaVersion=" + nullToEmpty(run.evaluationEventSchemaVersion())
        );
    }

    static String shortSha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                value.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for evaluationRunId");
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

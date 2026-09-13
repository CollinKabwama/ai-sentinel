package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic JSON serialization for {@link DetectionReferenceBaselineManifest}.
 */
final class DetectionReferenceBaselineJson {

    private DetectionReferenceBaselineJson() {
    }

    static String write(DetectionReferenceBaselineManifest manifest) {
        DetectionReferenceBaselineManifest safe = Objects.requireNonNull(manifest, "manifest");
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        appendString(json, "baselineSchemaVersion", safe.baselineSchemaVersion(), true);
        appendString(json, "baselineId", safe.baselineId(), false);
        appendString(json, "baselineKind", safe.baselineKind(), false);
        appendString(json, "purpose", safe.purpose(), false);

        json.append(",\"reference\":{");
        DetectionEvaluationEvidence.ReferenceProvenance reference = safe.reference();
        appendString(json, "datasetId", reference.datasetId(), true);
        appendString(json, "datasetSchemaVersion", reference.datasetSchemaVersion(), false);
        appendString(json, "evaluationEventSchemaVersion", reference.evaluationEventSchemaVersion(), false);
        appendString(json, "featureSchemaVersion", reference.featureSchemaVersion(), false);
        appendString(json, "annotationSchemaVersion", reference.annotationSchemaVersion(), false);
        appendString(json, "sourceClassification", reference.sourceClassification(), false);
        appendString(json, "transformationVersion", reference.transformationVersion(), false);
        appendString(json, "ordering", reference.ordering(), false);
        appendString(json, "eventsSha256", reference.eventsSha256(), false);
        appendString(json, "annotationsSha256", safe.annotationsSha256(), false);
        json.append('}');

        json.append(",\"replay\":{");
        DetectionEvaluationEvidence.ReplayProvenance replay = safe.replay();
        appendString(json, "replaySchemaVersion", replay.replaySchemaVersion(), true);
        appendString(json, "replayRunId", replay.replayRunId(), false);
        appendString(json, "datasetId", replay.datasetId(), false);
        appendString(json, "replayMode", replay.replayMode(), false);
        appendString(json, "scorerId", replay.scorerId(), false);
        appendString(json, "scorerVersion", replay.scorerVersion(), false);
        appendString(json, "policyId", replay.policyId(), false);
        appendString(json, "policyVersion", replay.policyVersion(), false);
        appendString(json, "configurationFingerprint", replay.configurationFingerprint(), false);
        appendString(json, "aiSentinelVersion", replay.aiSentinelVersion(), false);
        appendString(json, "resultsSha256", replay.resultsSha256(), false);
        json.append('}');

        json.append(",\"classification\":{");
        DetectionEvaluationEvidence.ClassificationProvenance classification = safe.classification();
        appendNumber(json, "anomalyThreshold", classification.anomalyThreshold(), true);
        appendString(json, "thresholdBoundary", classification.thresholdBoundary(), false);
        appendString(json, "role", "reference-classification-threshold", false);
        json.append('}');

        json.append(",\"structure\":{");
        DetectionEvaluationEvidence.StructuralCounts structure = safe.structure();
        appendNumber(json, "referenceEventCount", structure.referenceEventCount(), true);
        appendNumber(json, "scenarioCount", structure.scenarioCount(), false);
        appendNumber(json, "alignedObservationCount", structure.alignedObservationCount(), false);
        appendNumber(json, "expectedNormalObservationCount", structure.expectedNormalObservationCount(), false);
        appendNumber(json, "expectedAnomalousObservationCount", structure.expectedAnomalousObservationCount(), false);
        appendNumber(json, "evaluablePredictionCount", structure.evaluablePredictionCount(), false);
        appendNumber(json, "excludedPredictionCount", structure.excludedPredictionCount(), false);
        appendNumber(json, "anomalySegmentCount", structure.anomalySegmentCount(), false);
        appendNumber(json, "detectedSegmentCount", structure.detectedSegmentCount(), false);
        appendNumber(json, "undetectedSegmentCount", structure.undetectedSegmentCount(), false);
        appendNumber(json, "recoveryWindowCount", structure.recoveryWindowCount(), false);
        appendNumber(json, "stabilizedRecoveryCount", structure.stabilizedRecoveryCount(), false);
        appendNumber(json, "unstabilizedRecoveryCount", structure.unstabilizedRecoveryCount(), false);
        json.append('}');

        json.append(",\"artifacts\":{");
        DetectionReferenceBaselineManifest.ArtifactDigests artifacts = safe.artifacts();
        appendString(json, "evaluationJsonFile", artifacts.evaluationJsonFile(), true);
        appendString(json, "evaluationMarkdownFile", artifacts.evaluationMarkdownFile(), false);
        appendString(json, "evaluationJsonSha256", artifacts.evaluationJsonSha256(), false);
        appendString(json, "evaluationMarkdownSha256", artifacts.evaluationMarkdownSha256(), false);
        appendNumber(json, "evaluationJsonBytes", artifacts.evaluationJsonBytes(), false);
        appendNumber(json, "evaluationMarkdownBytes", artifacts.evaluationMarkdownBytes(), false);
        json.append('}');

        json.append(",\"limitations\":[");
        List<String> limitations = safe.limitations();
        for (int i = 0; i < limitations.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendRawString(json, limitations.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendRawString(json, field);
        json.append(':');
        appendRawString(json, value);
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendRawString(json, field);
        json.append(':').append(value);
    }

    private static void appendNumber(StringBuilder json, String field, double value, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendRawString(json, field);
        json.append(':').append(Double.toString(value));
    }

    private static void appendRawString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> json.append("\\\\");
                case '"' -> json.append("\\\"");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}

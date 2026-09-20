package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic JSON projection for generated-corpus Kit evaluation results and event inspection.
 * <p>
 * Kit result JSON conforms to the Evaluation Kit evaluation-result schema. Event inspection is a
 * companion artifact referenced by path from the Kit result.
 */
final class GeneratedCorpusEvaluationReportJson {

    private GeneratedCorpusEvaluationReportJson() {
    }

    static String kitResult(GeneratedCorpusEvaluationResult result) {
        GeneratedCorpusEvaluationResult safe = Objects.requireNonNull(result, "result");
        GeneratedCorpusProvenance provenance = safe.provenance();
        GeneratedCorpusPhaseCounts phases = safe.phaseCounts();
        DetectionEvaluationMetrics metrics = safe.detectionRun().metrics();
        DetectionConfusionMatrix confusion = metrics.confusionMatrix();
        DetectionMetrics ratios = metrics.metrics();
        DetectionEvaluationEvidence.ReplayProvenance replay = safe.detectionRun().evidence().replay();

        StringBuilder json = new StringBuilder();
        json.append('{');
        appendString(json, "resultSchemaVersion", "1", true);
        appendString(json, "resultId", "result." + provenance.corpusId(), false);
        appendString(
            json,
            "status",
            safe.limitations().isEmpty() ? "completed" : "completed_with_limitations",
            false
        );

        json.append(",\"limitations\":[");
        for (int i = 0; i < safe.limitations().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(safe.limitations().get(i))).append('"');
        }
        json.append(']');

        json.append(",\"provenance\":{");
        appendString(json, "scenarioId", provenance.scenarioId(), true);
        appendString(json, "scenarioVersion", provenance.scenarioVersion(), false);
        appendString(json, "scenarioSha256", provenance.scenarioSha256(), false);
        appendString(json, "corpusId", provenance.corpusId(), false);
        appendString(json, "corpusSchemaVersion", "1", false);
        appendString(json, "corpusEventsSha256", provenance.eventsSha256(), false);
        appendString(json, "annotationsSha256", provenance.annotationsSha256(), false);
        appendString(json, "seed", provenance.seed(), false);
        appendString(json, "generatorContractVersion", provenance.generatorContractVersion(), false);
        appendString(json, "generatorBuildId", provenance.generatorBuildId(), false);
        appendString(json, "featureSchemaVersion", provenance.featureSchemaVersion(), false);
        appendString(json, "evaluationEventSchemaVersion", provenance.evaluationEventSchemaVersion(), false);
        appendString(json, "aiSentinelVersion", replay.aiSentinelVersion(), false);
        appendString(json, "scorerId", replay.scorerId(), false);
        appendString(json, "scorerVersion", replay.scorerVersion(), false);
        json.append('}');

        json.append(",\"metricFamilies\":{");
        json.append("\"structural\":{");
        appendString(json, "availability", "available", true);
        json.append(",\"values\":{");
        appendNumber(json, "eventCount", phases.totalEvents(), true);
        appendNumber(json, "warmupEvents", phases.warmupEvents(), false);
        appendNumber(json, "labeledBenignEvents", phases.labeledBenignEvents(), false);
        appendNumber(json, "labeledAnomalousEvents", phases.labeledAnomalousEvents(), false);
        appendNumber(json, "unknownOrUnlabeledEvents", phases.unknownOrUnlabeledEvents(), false);
        appendNumber(json, "labeledEvaluationEvents", phases.labeledEvaluationEvents(), false);
        appendNumber(json, "anomalyThreshold", metrics.classification().anomalyThreshold(), false);
        json.append("}}");

        json.append(",\"detectionLabeled\":");
        appendDetectionFamily(json, phases, confusion, ratios);

        json.append(",\"temporal\":");
        appendTemporalFamily(json, safe.detectionRun().temporal());
        json.append('}');

        json.append(",\"detectionEvidenceRef\":\"")
            .append(escape(DetectionEvaluationEvidenceWriter.JSON_FILE_NAME))
            .append('"');
        json.append(",\"eventInspectionRef\":\"")
            .append(escape(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME))
            .append('"');
        json.append(",\"htmlReportRef\":\"")
            .append(escape(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME))
            .append('"');
        json.append(",\"notes\":\"Controlled synthetic reference-evaluation observations only; not production validation.\"");
        json.append('}');
        return json.toString();
    }

    static String eventInspection(GeneratedCorpusEvaluationResult result) {
        GeneratedCorpusEvaluationResult safe = Objects.requireNonNull(result, "result");
        StringBuilder json = new StringBuilder();
        json.append('{');
        appendString(json, "inspectionSchemaVersion", "1", true);
        appendString(json, "corpusId", safe.provenance().corpusId(), false);
        appendString(json, "scenarioId", safe.provenance().scenarioId(), false);
        appendNumber(json, "eventCount", safe.eventInspections().size(), false);
        appendNumber(
            json,
            "anomalyThreshold",
            safe.detectionRun().metrics().classification().anomalyThreshold(),
            false
        );
        json.append(",\"events\":[");
        List<GeneratedCorpusEventInspection> events = safe.eventInspections();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendEvent(json, events.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendDetectionFamily(
        StringBuilder json,
        GeneratedCorpusPhaseCounts phases,
        DetectionConfusionMatrix confusion,
        DetectionMetrics ratios
    ) {
        json.append('{');
        if (phases.labeledEvaluationEvents() == 0) {
            appendString(json, "availability", "unavailable", true);
            appendString(json, "reason", "No binary-labeled evaluation events were present", false);
        } else {
            appendString(json, "availability", "available", true);
            json.append(",\"values\":{");
            appendNumber(json, "truePositives", confusion.truePositives(), true);
            appendNumber(json, "trueNegatives", confusion.trueNegatives(), false);
            appendNumber(json, "falsePositives", confusion.falsePositives(), false);
            appendNumber(json, "falseNegatives", confusion.falseNegatives(), false);
            appendMetric(json, "precision", ratios.precision());
            appendMetric(json, "recall", ratios.recall());
            appendMetric(json, "f1", ratios.f1());
            appendMetric(json, "falsePositiveRate", ratios.falsePositiveRate());
            appendMetric(json, "falseNegativeRate", ratios.falseNegativeRate());
            json.append('}');
        }
        json.append('}');
    }

    private static void appendTemporalFamily(StringBuilder json, TemporalDetectionEvaluation temporal) {
        json.append('{');
        if (temporal.scenarios().isEmpty()) {
            appendString(json, "availability", "unavailable", true);
            appendString(json, "reason", "No temporal scenario slices were present", false);
        } else {
            appendString(json, "availability", "available", true);
            json.append(",\"values\":{");
            appendNumber(json, "scenarioCount", temporal.scenarios().size(), true);
            long observationCount = 0L;
            long segmentCount = 0L;
            for (ScenarioTemporalEvaluation scenario : temporal.scenarios()) {
                observationCount = Math.addExact(observationCount, scenario.observationCount());
                segmentCount = Math.addExact(segmentCount, scenario.anomalySegments().size());
            }
            appendNumber(json, "observationCount", observationCount, false);
            appendNumber(json, "anomalySegmentCount", segmentCount, false);
            json.append('}');
        }
        json.append('}');
    }

    private static void appendEvent(StringBuilder json, GeneratedCorpusEventInspection event) {
        json.append('{');
        appendString(json, "eventId", event.eventId(), true);
        appendNumber(json, "sequenceNumber", event.sequenceNumber(), false);
        appendString(json, "observedAt", event.observedAt().toString(), false);
        appendString(json, "identityKey", event.identityKey(), false);
        appendString(json, "category", event.category(), false);
        appendString(json, "expectedClass", event.expectedClass(), false);
        appendString(json, "participation", event.participation(), false);
        appendBoolean(json, "binaryMetricParticipant", event.binaryMetricParticipant(), false);
        if (event.anomalyScore() == null) {
            json.append(",\"anomalyScore\":null");
        } else {
            appendNumber(json, "anomalyScore", event.anomalyScore(), false);
        }
        if (event.predictedAnomalous() == null) {
            json.append(",\"predictedAnomalous\":null");
        } else {
            appendBoolean(json, "predictedAnomalous", event.predictedAnomalous(), false);
        }
        appendString(json, "action", event.action(), false);
        json.append(",\"evaluationStatuses\":[");
        for (int i = 0; i < event.evaluationStatuses().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(event.evaluationStatuses().get(i))).append('"');
        }
        json.append(']');
        appendString(json, "outcome", event.outcome(), false);
        json.append('}');
    }

    private static void appendMetric(StringBuilder json, String field, DetectionMetricValue value) {
        json.append(",\"").append(escape(field)).append("\":");
        if (value == null || !value.defined()) {
            json.append("null");
        } else {
            json.append(String.format(Locale.ROOT, "%.6f", value.value()));
        }
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendBoolean(StringBuilder json, String field, boolean value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static void appendNumber(StringBuilder json, String field, double value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":")
            .append(String.format(Locale.ROOT, "%.6f", value));
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Locale;

final class EvaluationComparisonJson {
    private EvaluationComparisonJson() {
    }

    static String render(EvaluationComparisonModel comparison) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        string(json, "comparisonSchemaVersion", "1", true);
        string(json, "comparisonId", comparison.comparisonId(), false);
        string(json, "status", comparison.status(), false);
        strings(json, "limitations", comparison.limitations());
        json.append(",\"baseline\":");
        run(json, comparison.baseline());
        json.append(",\"candidate\":");
        run(json, comparison.candidate());
        json.append(",\"compatibility\":{");
        bool(json, "comparable", true, true);
        bool(json, "sameDataset", true, false);
        bool(json, "thresholdEqual", comparison.thresholdEqual(), false);
        json.append(",\"notes\":[]}");
        json.append(",\"metricChanges\":[");
        for (int i = 0; i < comparison.metricChanges().size(); i++) {
            if (i > 0) json.append(',');
            metric(json, comparison.metricChanges().get(i));
        }
        json.append(']');
        json.append(",\"countChanges\":[");
        for (int i = 0; i < comparison.countChanges().size(); i++) {
            if (i > 0) json.append(',');
            CountChange count = comparison.countChanges().get(i);
            json.append('{');
            string(json, "name", count.name(), true);
            integer(json, "baseline", count.baseline(), false);
            integer(json, "candidate", count.candidate(), false);
            integer(json, "delta", count.delta(), false);
            json.append('}');
        }
        json.append(']');
        json.append(",\"correctnessTransitions\":{");
        List<String> transitionNames = List.of("TP_TO_FN", "FN_TO_TP", "TN_TO_FP", "FP_TO_TN",
            "TP_TO_TP", "TN_TO_TN", "FP_TO_FP", "FN_TO_FN");
        for (int i = 0; i < transitionNames.size(); i++) {
            String name = transitionNames.get(i);
            integer(json, name, comparison.correctnessTransitions().get(name), i == 0);
        }
        json.append('}');
        json.append(",\"eventChanges\":[");
        for (int i = 0; i < comparison.eventChanges().size(); i++) {
            if (i > 0) json.append(',');
            eventChange(json, comparison.eventChanges().get(i));
        }
        json.append(']');
        json.append(",\"summary\":{");
        integer(json, "eventsCompared", comparison.eventsCompared(), true);
        integer(json, "eventsWithChanges", comparison.eventChanges().size(), false);
        integer(json, "newFalsePositives", comparison.newFalsePositives(), false);
        integer(json, "newFalseNegatives", comparison.newFalseNegatives(), false);
        json.append('}');
        string(json, "htmlReportRef", "comparison.html", false);
        string(json, "notes",
            "Factual deltas only; not a winner or deployment decision.", false);
        json.append('}');
        return json.toString();
    }

    private static void run(StringBuilder json, RunEvidence run) {
        json.append('{');
        string(json, "datasetSource", run.datasetSource(), true);
        if ("generated-corpus".equals(run.datasetSource())) {
            string(json, "corpusId", run.corpusId(), false);
            string(json, "corpusEventsSha256", run.corpusEventsSha256(), false);
            if (run.annotationsSha256() != null) {
                string(json, "annotationsSha256", run.annotationsSha256(), false);
            }
        } else {
            string(json, "datasetId", run.datasetId(), false);
            string(json, "eventsSha256", run.eventsSha256(), false);
            if (run.annotationsSha256() != null) {
                string(json, "annotationsSha256", run.annotationsSha256(), false);
            }
            string(json, "representationMode", run.representationMode(), false);
        }
        string(json, "resultId", run.resultId(), false);
        if (run.evaluationRunId() != null && !run.evaluationRunId().isBlank()) {
            string(json, "evaluationRunId", run.evaluationRunId(), false);
        }
        integer(json, "eventCount", run.events().size(), false);
        number(json, "anomalyThreshold", run.anomalyThreshold(), false);
        json.append('}');
    }

    private static void metric(StringBuilder json, MetricChange metric) {
        json.append('{');
        string(json, "family", metric.family(), true);
        string(json, "metric", metric.metric(), false);
        string(json, "baselineAvailability", metric.baselineAvailability(), false);
        string(json, "candidateAvailability", metric.candidateAvailability(), false);
        string(json, "availabilityChange", metric.availabilityChange(), false);
        nullableNumber(json, "baselineValue", metric.baselineValue());
        nullableNumber(json, "candidateValue", metric.candidateValue());
        nullableNumber(json, "delta", metric.delta());
        json.append('}');
    }

    private static void eventChange(StringBuilder json, EventChange change) {
        json.append('{');
        string(json, "eventId", change.eventId(), true);
        integer(json, "sequenceNumber", change.sequenceNumber(), false);
        strings(json, "changes", change.changes());
        json.append(",\"baseline\":");
        event(json, change.baseline());
        json.append(",\"candidate\":");
        event(json, change.candidate());
        nullableNumber(json, "scoreDelta", change.scoreDelta());
        json.append(",\"correctnessTransition\":");
        if (change.correctnessTransition() == null) {
            json.append("null");
        } else {
            quoted(json, change.correctnessTransition());
        }
        json.append('}');
    }

    private static void event(StringBuilder json, EventEvidence event) {
        json.append('{');
        nullableNumberFirst(json, "anomalyScore", event.anomalyScore());
        json.append(",\"predictedAnomalous\":")
            .append(event.predictedAnomalous() == null ? "null" : event.predictedAnomalous());
        string(json, "action", event.action(), false);
        strings(json, "evaluationStatuses", event.evaluationStatuses());
        string(json, "outcome", event.outcome(), false);
        string(json, "expectedClass", event.expectedClass(), false);
        string(json, "participation", event.participation(), false);
        bool(json, "binaryMetricParticipant", event.binaryMetricParticipant(), false);
        json.append('}');
    }

    private static void strings(StringBuilder json, String field, List<String> values) {
        json.append(",\"").append(escape(field)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            quoted(json, values.get(i));
        }
        json.append(']');
    }

    private static void nullableNumber(StringBuilder json, String field, Double value) {
        json.append(",\"").append(escape(field)).append("\":");
        if (value == null) json.append("null");
        else appendNumber(json, value);
    }

    private static void nullableNumberFirst(StringBuilder json, String field, Double value) {
        json.append("\"").append(escape(field)).append("\":");
        if (value == null) json.append("null");
        else appendNumber(json, value);
    }

    private static void string(
        StringBuilder json, String field, String value, boolean first
    ) {
        json.append(first ? "" : ",").append('"').append(escape(field)).append("\":");
        quoted(json, value);
    }

    private static void bool(StringBuilder json, String field, boolean value, boolean first) {
        json.append(first ? "" : ",").append('"').append(escape(field)).append("\":").append(value);
    }

    private static void integer(StringBuilder json, String field, long value, boolean first) {
        json.append(first ? "" : ",").append('"').append(escape(field)).append("\":").append(value);
    }

    private static void number(StringBuilder json, String field, double value, boolean first) {
        json.append(first ? "" : ",").append('"').append(escape(field)).append("\":");
        appendNumber(json, value);
    }

    private static void appendNumber(StringBuilder json, double value) {
        json.append(String.format(Locale.ROOT, "%.6f", value));
    }

    private static void quoted(StringBuilder json, String value) {
        json.append('"').append(escape(value)).append('"');
    }

    static String escape(String value) {
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
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}

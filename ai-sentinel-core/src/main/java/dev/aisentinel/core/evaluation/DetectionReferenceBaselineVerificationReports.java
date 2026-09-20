package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic verification report serializers.
 */
final class DetectionReferenceBaselineVerificationReports {

    private DetectionReferenceBaselineVerificationReports() {
    }

    static String writeJson(DetectionReferenceBaselineVerificationResult result) {
        DetectionReferenceBaselineVerificationResult safe = Objects.requireNonNull(result, "result");
        StringBuilder json = new StringBuilder(4096);
        json.append('{');
        appendString(json, "verificationSchemaVersion", safe.verificationSchemaVersion(), true);
        appendString(json, "reportKind", safe.reportKind(), false);
        appendString(json, "status", safe.status().name(), false);
        appendString(json, "baselineId", safe.baselineId(), false);
        appendString(json, "baselineSchemaVersion", safe.baselineSchemaVersion(), false);
        appendString(json, "baselineManifestSha256", safe.baselineManifestSha256(), false);
        appendString(json, "baselineEvaluationJsonSha256", safe.baselineEvaluationJsonSha256(), false);
        appendString(json, "baselineEvaluationMarkdownSha256", safe.baselineEvaluationMarkdownSha256(), false);
        appendString(json, "currentEvaluationJsonSha256", safe.currentEvaluationJsonSha256(), false);
        appendString(json, "currentEvaluationMarkdownSha256", safe.currentEvaluationMarkdownSha256(), false);
        appendBoolean(json, "evaluationJsonBytesEqual", safe.evaluationJsonBytesEqual(), false);
        appendBoolean(json, "evaluationMarkdownBytesEqual", safe.evaluationMarkdownBytesEqual(), false);
        appendNumber(json, "totalDriftEntries", safe.totalDriftEntries(), false);
        json.append(",\"driftCountsByCategory\":{");
        Map<DetectionReferenceBaselineDriftCategory, Integer> counts = safe.driftCountsByCategory();
        boolean firstCount = true;
        for (DetectionReferenceBaselineDriftCategory category : DetectionReferenceBaselineDriftCategory.values()) {
            if (!firstCount) {
                json.append(',');
            }
            firstCount = false;
            appendRawString(json, category.name());
            json.append(':').append(counts.get(category));
        }
        json.append('}');
        json.append(",\"driftEntries\":[");
        List<DetectionReferenceBaselineDriftEntry> entries = safe.driftEntries();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            DetectionReferenceBaselineDriftEntry entry = entries.get(i);
            json.append('{');
            appendString(json, "category", entry.category().name(), true);
            appendString(json, "field", entry.field(), false);
            appendString(json, "baselineValue", entry.baselineValue(), false);
            appendString(json, "currentValue", entry.currentValue(), false);
            json.append('}');
        }
        json.append(']');
        appendString(json, "detail", safe.detail(), false);
        json.append(",\"limitations\":[");
        List<String> limitations = limitations();
        for (int i = 0; i < limitations.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendRawString(json, limitations.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    static String writeMarkdown(DetectionReferenceBaselineVerificationResult result) {
        DetectionReferenceBaselineVerificationResult safe = Objects.requireNonNull(result, "result");
        StringBuilder markdown = new StringBuilder(4096);
        markdown.append("# Official Detection Reference Baseline Verification\n\n");
        markdown.append("This report records deterministic verification against the Official Detection ")
            .append("Reference Baseline. Drift means difference, not defect.\n\n");
        markdown.append("## Status\n\n");
        bullet(markdown, "Overall status", safe.status().name());
        bullet(markdown, "Detail", safe.detail().isBlank() ? "none" : safe.detail());
        bullet(markdown, "Total drift entries", Integer.toString(safe.totalDriftEntries()));
        markdown.append('\n');

        markdown.append("## Baseline\n\n");
        bullet(markdown, "Baseline ID", safe.baselineId());
        bullet(markdown, "Baseline schema", safe.baselineSchemaVersion());
        bullet(markdown, "Manifest SHA-256", safe.baselineManifestSha256());
        bullet(markdown, "evaluation.json SHA-256", safe.baselineEvaluationJsonSha256());
        bullet(markdown, "evaluation.md SHA-256", safe.baselineEvaluationMarkdownSha256());
        markdown.append('\n');

        markdown.append("## Current Evaluation Artifacts\n\n");
        bullet(markdown, "evaluation.json SHA-256", safe.currentEvaluationJsonSha256());
        bullet(markdown, "evaluation.md SHA-256", safe.currentEvaluationMarkdownSha256());
        bullet(markdown, "evaluation.json bytes equal", Boolean.toString(safe.evaluationJsonBytesEqual()));
        bullet(markdown, "evaluation.md bytes equal", Boolean.toString(safe.evaluationMarkdownBytesEqual()));
        markdown.append('\n');

        markdown.append("## Drift Summary By Category\n\n");
        Map<DetectionReferenceBaselineDriftCategory, Integer> counts = safe.driftCountsByCategory();
        for (DetectionReferenceBaselineDriftCategory category : DetectionReferenceBaselineDriftCategory.values()) {
            bullet(markdown, category.name(), Integer.toString(counts.get(category)));
        }
        markdown.append('\n');

        markdown.append("## Drift Details\n\n");
        if (safe.driftEntries().isEmpty()) {
            markdown.append("- none\n\n");
        } else {
            for (DetectionReferenceBaselineDriftEntry entry : safe.driftEntries()) {
                markdown.append("### `").append(escape(entry.category().name())).append(" / ")
                    .append(escape(entry.field())).append("`\n\n");
                bullet(markdown, "Baseline value", entry.baselineValue());
                bullet(markdown, "Current value", entry.currentValue());
                markdown.append('\n');
            }
        }

        markdown.append("## Limitations\n\n");
        for (String limitation : limitations()) {
            markdown.append("- ").append(escape(limitation)).append('\n');
        }
        return markdown.toString();
    }

    static List<String> limitations() {
        return List.of(
            "DRIFT != REGRESSION. Detected difference is not automatically a product defect.",
            "DIFFERENCE != FAILURE. Verification reports technical mismatch, not release rejection.",
            "VERIFICATION RESULT != PRODUCTION ACCEPTANCE.",
            "BASELINE != QUALITY GATE.",
            "DETECTION BASELINE != PRODUCTION EFFICACY.",
            "REFERENCE THRESHOLD != PRODUCTION THRESHOLD.",
            "POLICY ACTION != DETECTOR PREDICTION.",
            "REFERENCE DATASET != DETECTION BASELINE.",
            "No baseline replacement, promotion, or lifecycle approval is performed by verification."
        );
    }

    private static void bullet(StringBuilder markdown, String label, String value) {
        markdown.append("- ").append(label).append(": `").append(escape(value)).append("`\n");
    }

    private static String escape(String value) {
        return Objects.requireNonNull(value, "value")
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendRawString(json, field);
        json.append(':');
        appendRawString(json, value);
    }

    private static void appendBoolean(StringBuilder json, String field, boolean value, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendRawString(json, field);
        json.append(':').append(value);
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendRawString(json, field);
        json.append(':').append(value);
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

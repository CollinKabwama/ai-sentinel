package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic JSON serializers for baseline lifecycle governance records.
 * No wall-clock timestamps, paths, hostnames, or random values.
 */
final class DetectionReferenceBaselineLifecycleReports {

    private DetectionReferenceBaselineLifecycleReports() {
    }

    static String writeCandidateRecord(
        String candidateId,
        String sourceOfficialBaselineId,
        String sourceOfficialManifestSha256,
        String sourceOfficialEvaluationJsonSha256,
        String sourceOfficialEvaluationMarkdownSha256,
        String candidateBaselineId,
        String candidateManifestSha256,
        String candidateEvaluationJsonSha256,
        String candidateEvaluationMarkdownSha256,
        String comparisonStatus,
        String comparisonJsonSha256,
        String comparisonMarkdownSha256,
        int totalDriftEntries
    ) {
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        appendString(json, "lifecycleSchemaVersion", DetectionReferenceBaselineLifecycleSchemas.LIFECYCLE_SCHEMA_VERSION, true);
        appendString(json, "recordKind", DetectionReferenceBaselineLifecycleSchemas.CANDIDATE_RECORD_KIND, false);
        appendString(json, "candidateId", candidateId, false);
        appendString(json, "sourceOfficialBaselineId", sourceOfficialBaselineId, false);
        appendString(json, "sourceOfficialManifestSha256", sourceOfficialManifestSha256, false);
        appendString(json, "sourceOfficialEvaluationJsonSha256", sourceOfficialEvaluationJsonSha256, false);
        appendString(json, "sourceOfficialEvaluationMarkdownSha256", sourceOfficialEvaluationMarkdownSha256, false);
        appendString(json, "candidateBaselineId", candidateBaselineId, false);
        appendString(json, "candidateManifestSha256", candidateManifestSha256, false);
        appendString(json, "candidateEvaluationJsonSha256", candidateEvaluationJsonSha256, false);
        appendString(json, "candidateEvaluationMarkdownSha256", candidateEvaluationMarkdownSha256, false);
        appendString(json, "comparisonStatus", comparisonStatus, false);
        appendString(json, "comparisonJsonSha256", comparisonJsonSha256, false);
        appendString(json, "comparisonMarkdownSha256", comparisonMarkdownSha256, false);
        appendNumber(json, "totalDriftEntries", totalDriftEntries, false);
        json.append(",\"limitations\":[");
        List<String> limitations = List.of(
            "CANDIDATE != OFFICIAL BASELINE.",
            "DRIFT != APPROVAL.",
            "DRIFT != REGRESSION.",
            "METRIC DELTA != GOVERNANCE DECISION.",
            "SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY."
        );
        for (int i = 0; i < limitations.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendRawString(json, limitations.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    static String writeDecisionRecord(
        DetectionReferenceBaselineDecisionState decision,
        String candidateId,
        String candidateManifestSha256,
        String comparisonJsonSha256,
        String sourceOfficialManifestSha256,
        String rationale,
        String approver
    ) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        appendString(json, "governanceSchemaVersion", DetectionReferenceBaselineLifecycleSchemas.GOVERNANCE_SCHEMA_VERSION, true);
        appendString(json, "recordKind", DetectionReferenceBaselineLifecycleSchemas.DECISION_RECORD_KIND, false);
        appendString(json, "decision", decision.name(), false);
        appendString(json, "candidateId", candidateId, false);
        appendString(json, "candidateManifestSha256", candidateManifestSha256, false);
        appendString(json, "comparisonJsonSha256", comparisonJsonSha256, false);
        appendString(json, "sourceOfficialManifestSha256", sourceOfficialManifestSha256, false);
        appendString(json, "rationale", rationale, false);
        appendString(json, "approver", approver, false);
        appendString(
            json,
            "approverTrustNote",
            "SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY",
            false
        );
        json.append('}');
        return json.toString();
    }

    static String writePromotionRecord(
        String candidateId,
        String approvalRecordSha256,
        String comparisonJsonSha256,
        String oldOfficialBaselineId,
        String oldOfficialManifestSha256,
        String oldOfficialEvaluationJsonSha256,
        String oldOfficialEvaluationMarkdownSha256,
        String newOfficialBaselineId,
        String newOfficialManifestSha256,
        String newOfficialEvaluationJsonSha256,
        String newOfficialEvaluationMarkdownSha256,
        String historyId,
        String historyRetentionSha256,
        String rationale,
        String approver
    ) {
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        appendString(json, "governanceSchemaVersion", DetectionReferenceBaselineLifecycleSchemas.GOVERNANCE_SCHEMA_VERSION, true);
        appendString(json, "recordKind", DetectionReferenceBaselineLifecycleSchemas.PROMOTION_RECORD_KIND, false);
        appendString(json, "candidateId", candidateId, false);
        appendString(json, "approvalRecordSha256", approvalRecordSha256, false);
        appendString(json, "comparisonJsonSha256", comparisonJsonSha256, false);
        appendString(json, "oldOfficialBaselineId", oldOfficialBaselineId, false);
        appendString(json, "oldOfficialManifestSha256", oldOfficialManifestSha256, false);
        appendString(json, "oldOfficialEvaluationJsonSha256", oldOfficialEvaluationJsonSha256, false);
        appendString(json, "oldOfficialEvaluationMarkdownSha256", oldOfficialEvaluationMarkdownSha256, false);
        appendString(json, "newOfficialBaselineId", newOfficialBaselineId, false);
        appendString(json, "newOfficialManifestSha256", newOfficialManifestSha256, false);
        appendString(json, "newOfficialEvaluationJsonSha256", newOfficialEvaluationJsonSha256, false);
        appendString(json, "newOfficialEvaluationMarkdownSha256", newOfficialEvaluationMarkdownSha256, false);
        appendString(json, "historyId", historyId, false);
        appendString(json, "historyRetentionSha256", historyRetentionSha256, false);
        appendString(json, "rationale", rationale, false);
        appendString(json, "approver", approver, false);
        appendString(
            json,
            "approverTrustNote",
            "SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY",
            false
        );
        json.append('}');
        return json.toString();
    }

    static String writeRetentionRecord(
        String historyId,
        String retainedBaselineId,
        String retainedManifestSha256,
        String retainedEvaluationJsonSha256,
        String retainedEvaluationMarkdownSha256,
        String supersededByCandidateId,
        String promotionRecordSha256,
        String eventType
    ) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        appendString(json, "governanceSchemaVersion", DetectionReferenceBaselineLifecycleSchemas.GOVERNANCE_SCHEMA_VERSION, true);
        appendString(json, "recordKind", DetectionReferenceBaselineLifecycleSchemas.RETENTION_RECORD_KIND, false);
        appendString(json, "eventType", eventType, false);
        appendString(json, "historyId", historyId, false);
        appendString(json, "retainedBaselineId", retainedBaselineId, false);
        appendString(json, "retainedManifestSha256", retainedManifestSha256, false);
        appendString(json, "retainedEvaluationJsonSha256", retainedEvaluationJsonSha256, false);
        appendString(json, "retainedEvaluationMarkdownSha256", retainedEvaluationMarkdownSha256, false);
        appendString(json, "supersededByCandidateId", supersededByCandidateId, false);
        appendString(json, "promotionRecordSha256", promotionRecordSha256, false);
        json.append('}');
        return json.toString();
    }

    static String writeRollbackRecord(
        String historyTargetId,
        String targetManifestSha256,
        String priorOfficialManifestSha256,
        String priorOfficialEvaluationJsonSha256,
        String priorOfficialEvaluationMarkdownSha256,
        String resultingOfficialManifestSha256,
        String resultingOfficialEvaluationJsonSha256,
        String resultingOfficialEvaluationMarkdownSha256,
        String retainedCurrentHistoryId,
        String retainedCurrentRetentionSha256,
        String rationale,
        String approver
    ) {
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        appendString(json, "governanceSchemaVersion", DetectionReferenceBaselineLifecycleSchemas.GOVERNANCE_SCHEMA_VERSION, true);
        appendString(json, "recordKind", DetectionReferenceBaselineLifecycleSchemas.ROLLBACK_RECORD_KIND, false);
        appendString(json, "historyTargetId", historyTargetId, false);
        appendString(json, "targetManifestSha256", targetManifestSha256, false);
        appendString(json, "priorOfficialManifestSha256", priorOfficialManifestSha256, false);
        appendString(json, "priorOfficialEvaluationJsonSha256", priorOfficialEvaluationJsonSha256, false);
        appendString(json, "priorOfficialEvaluationMarkdownSha256", priorOfficialEvaluationMarkdownSha256, false);
        appendString(json, "resultingOfficialManifestSha256", resultingOfficialManifestSha256, false);
        appendString(json, "resultingOfficialEvaluationJsonSha256", resultingOfficialEvaluationJsonSha256, false);
        appendString(json, "resultingOfficialEvaluationMarkdownSha256", resultingOfficialEvaluationMarkdownSha256, false);
        appendString(json, "retainedCurrentHistoryId", retainedCurrentHistoryId, false);
        appendString(json, "retainedCurrentRetentionSha256", retainedCurrentRetentionSha256, false);
        appendString(json, "rationale", rationale, false);
        appendString(json, "approver", approver, false);
        appendString(
            json,
            "approverTrustNote",
            "SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY",
            false
        );
        json.append('}');
        return json.toString();
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendRawString(json, field);
        json.append(':');
        appendRawString(json, Objects.requireNonNullElse(value, ""));
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
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }
}

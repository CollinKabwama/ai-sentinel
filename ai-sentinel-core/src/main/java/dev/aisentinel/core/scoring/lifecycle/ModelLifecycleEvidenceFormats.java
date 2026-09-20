package dev.aisentinel.core.scoring.lifecycle;

import java.util.Locale;

/**
 * Deterministic JSON fragments for model-lifecycle governance evidence.
 * Paths are never embedded; only technical identity fields.
 */
final class ModelLifecycleEvidenceFormats {

    private ModelLifecycleEvidenceFormats() {
    }

    static String identityJson(ModelLifecycleIdentity identity) {
        return "{"
            + "\"kind\":\"" + identity.kind().name() + "\","
            + "\"scorerId\":\"" + escape(identity.scorerId()) + "\","
            + "\"scorerVersion\":\"" + escape(identity.scorerVersion()) + "\","
            + "\"artifactId\":\"" + escape(identity.artifactId()) + "\","
            + "\"verifiedDigestHex\":\"" + identity.verifiedDigestHex() + "\","
            + "\"configurationFingerprintSha256Hex\":\"" + identity.configurationFingerprintSha256Hex() + "\""
            + "}";
    }

    static String decisionJson(ModelPromotionDecision decision) {
        return "{"
            + "\"schemaVersion\":\"" + ModelLifecycleSchemas.SCHEMA_VERSION + "\","
            + "\"status\":\"" + decision.status().name() + "\","
            + "\"championAtDecision\":" + identityJson(decision.championAtDecision()) + ","
            + "\"challenger\":" + identityJson(decision.challenger()) + ","
            + "\"comparisonSha256Hex\":\"" + decision.comparisonSha256Hex() + "\","
            + "\"rationale\":\"" + escape(decision.rationale()) + "\","
            + "\"approver\":\"" + escape(decision.approver()) + "\","
            + "\"decisionSha256Hex\":\"" + decision.decisionSha256Hex() + "\""
            + "}\n";
    }

    static String promotionJson(ModelPromotionRecord record) {
        return "{"
            + "\"schemaVersion\":\"" + ModelLifecycleSchemas.SCHEMA_VERSION + "\","
            + "\"promotionId\":\"" + record.promotionId() + "\","
            + "\"previousChampion\":" + identityJson(record.previousChampion()) + ","
            + "\"newChampion\":" + identityJson(record.newChampion()) + ","
            + "\"decisionSha256Hex\":\"" + record.decisionSha256Hex() + "\","
            + "\"comparisonSha256Hex\":\"" + record.comparisonSha256Hex() + "\","
            + "\"rationale\":\"" + escape(record.rationale()) + "\","
            + "\"approver\":\"" + escape(record.approver()) + "\","
            + "\"promotionSha256Hex\":\"" + record.promotionSha256Hex() + "\","
            + "\"limitations\":["
            + "\"PROMOTED != PRODUCTION DEPLOYED\","
            + "\"CHAMPION DESIGNATION != PRODUCTION SCORER WIRING\""
            + "]"
            + "}\n";
    }

    static String rollbackJson(ModelRollbackRecord record) {
        return "{"
            + "\"schemaVersion\":\"" + ModelLifecycleSchemas.SCHEMA_VERSION + "\","
            + "\"rollbackId\":\"" + record.rollbackId() + "\","
            + "\"rolledBackPromotionId\":\"" + record.rolledBackPromotionId() + "\","
            + "\"championBeforeRollback\":" + identityJson(record.championBeforeRollback()) + ","
            + "\"restoredChampion\":" + identityJson(record.restoredChampion()) + ","
            + "\"rationale\":\"" + escape(record.rationale()) + "\","
            + "\"approver\":\"" + escape(record.approver()) + "\","
            + "\"rollbackSha256Hex\":\"" + record.rollbackSha256Hex() + "\","
            + "\"limitations\":["
            + "\"ROLLBACK != PRODUCTION DEPLOYMENT ROLLBACK\""
            + "]"
            + "}\n";
    }

    static String championJson(ModelLifecycleIdentity champion) {
        return "{"
            + "\"schemaVersion\":\"" + ModelLifecycleSchemas.SCHEMA_VERSION + "\","
            + "\"role\":\"CHAMPION\","
            + "\"identity\":" + identityJson(champion)
            + "}\n";
    }

    static String challengerJson(ChallengerDesignation designation) {
        return "{"
            + "\"schemaVersion\":\"" + ModelLifecycleSchemas.SCHEMA_VERSION + "\","
            + "\"role\":\"CHALLENGER\","
            + "\"challenger\":" + identityJson(designation.challenger()) + ","
            + "\"expectedChampion\":" + identityJson(designation.expectedChampion()) + ","
            + "\"rationale\":\"" + escape(designation.rationale()) + "\","
            + "\"designator\":\"" + escape(designation.designator()) + "\""
            + "}\n";
    }

    static String comparisonBindingJson(ChampionChallengerComparison comparison) {
        return "{"
            + "\"schemaVersion\":\"" + ModelLifecycleSchemas.SCHEMA_VERSION + "\","
            + "\"status\":\"" + comparison.status().name() + "\","
            + "\"comparisonSha256Hex\":\"" + comparison.comparisonSha256Hex() + "\","
            + "\"champion\":" + identityJson(comparison.champion()) + ","
            + "\"challenger\":" + identityJson(comparison.challenger())
            + "}\n";
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    static String historyFileName(ModelLifecycleHistoryEntry entry) {
        String prefix = entry.kind() == ModelLifecycleHistoryEntry.Kind.PROMOTION
            ? "promotion-"
            : "rollback-";
        String seq = String.format(Locale.ROOT, "%06d", entry.sequence());
        return prefix + seq + "-" + entry.entryId().substring(0, 16) + ".json";
    }
}

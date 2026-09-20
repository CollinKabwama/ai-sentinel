package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;

/**
 * Deterministic rollback evidence for lifecycle champion designation.
 * <p>
 * {@code ROLLBACK != PRODUCTION DEPLOYMENT ROLLBACK}
 */
public final class ModelRollbackRecord {

    private final String rollbackId;
    private final String rolledBackPromotionId;
    private final ModelLifecycleIdentity championBeforeRollback;
    private final ModelLifecycleIdentity restoredChampion;
    private final String rationale;
    private final String approver;
    private final String rollbackSha256Hex;

    public ModelRollbackRecord(
        String rolledBackPromotionId,
        ModelLifecycleIdentity championBeforeRollback,
        ModelLifecycleIdentity restoredChampion,
        String rationale,
        String approver
    ) {
        this.rolledBackPromotionId = ModelLifecycleCanonical.requireSha256Hex(
            rolledBackPromotionId, "rolledBackPromotionId");
        this.championBeforeRollback = Objects.requireNonNull(championBeforeRollback, "championBeforeRollback");
        this.restoredChampion = Objects.requireNonNull(restoredChampion, "restoredChampion");
        if (championBeforeRollback.matches(restoredChampion)) {
            throw new IllegalArgumentException("rollback must change champion designation");
        }
        this.rationale = ModelPromotionDecision.requireGovernanceText(rationale, "rationale");
        this.approver = ModelPromotionDecision.requireGovernanceText(approver, "approver");
        this.rollbackId = ModelLifecycleCanonical.sha256Hex(canonicalIdMaterial());
        this.rollbackSha256Hex = ModelLifecycleCanonical.sha256Hex(canonicalMaterial());
    }

    private String canonicalIdMaterial() {
        StringBuilder sb = new StringBuilder();
        ModelLifecycleCanonical.appendField(sb, "type", "rollback");
        ModelLifecycleCanonical.appendField(sb, "rolledBackPromotionId", rolledBackPromotionId);
        ModelLifecycleCanonical.appendField(sb, "championBeforeRollback", championBeforeRollback.bindingKey());
        ModelLifecycleCanonical.appendField(sb, "restoredChampion", restoredChampion.bindingKey());
        return sb.toString();
    }

    private String canonicalMaterial() {
        StringBuilder sb = new StringBuilder(canonicalIdMaterial());
        ModelLifecycleCanonical.appendField(sb, "rollbackId", rollbackId);
        ModelLifecycleCanonical.appendField(sb, "rationale", rationale);
        ModelLifecycleCanonical.appendField(sb, "approver", approver);
        return sb.toString();
    }

    public String rollbackId() {
        return rollbackId;
    }

    public String rolledBackPromotionId() {
        return rolledBackPromotionId;
    }

    public ModelLifecycleIdentity championBeforeRollback() {
        return championBeforeRollback;
    }

    public ModelLifecycleIdentity restoredChampion() {
        return restoredChampion;
    }

    public String rationale() {
        return rationale;
    }

    public String approver() {
        return approver;
    }

    public String rollbackSha256Hex() {
        return rollbackSha256Hex;
    }
}

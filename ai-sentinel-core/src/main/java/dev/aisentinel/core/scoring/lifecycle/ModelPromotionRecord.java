package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;

/**
 * Deterministic promotion evidence. Records lifecycle champion change only.
 * <p>
 * {@code PROMOTED != PRODUCTION DEPLOYED}<br>
 * {@code CHAMPION DESIGNATION != PRODUCTION SCORER WIRING}
 */
public final class ModelPromotionRecord {

    private final String promotionId;
    private final ModelLifecycleIdentity previousChampion;
    private final ModelLifecycleIdentity newChampion;
    private final String decisionSha256Hex;
    private final String comparisonSha256Hex;
    private final String rationale;
    private final String approver;
    private final String promotionSha256Hex;

    public ModelPromotionRecord(
        ModelLifecycleIdentity previousChampion,
        ModelLifecycleIdentity newChampion,
        String decisionSha256Hex,
        String comparisonSha256Hex,
        String rationale,
        String approver
    ) {
        this.previousChampion = Objects.requireNonNull(previousChampion, "previousChampion");
        this.newChampion = Objects.requireNonNull(newChampion, "newChampion");
        if (previousChampion.matches(newChampion)) {
            throw new IllegalArgumentException("previous and new champion must differ");
        }
        this.decisionSha256Hex = ModelLifecycleCanonical.requireSha256Hex(
            decisionSha256Hex, "decisionSha256Hex");
        this.comparisonSha256Hex = ModelLifecycleCanonical.requireSha256Hex(
            comparisonSha256Hex, "comparisonSha256Hex");
        this.rationale = ModelPromotionDecision.requireGovernanceText(rationale, "rationale");
        this.approver = ModelPromotionDecision.requireGovernanceText(approver, "approver");
        this.promotionId = ModelLifecycleCanonical.sha256Hex(canonicalIdMaterial());
        this.promotionSha256Hex = ModelLifecycleCanonical.sha256Hex(canonicalMaterial());
    }

    private String canonicalIdMaterial() {
        StringBuilder sb = new StringBuilder();
        ModelLifecycleCanonical.appendField(sb, "type", "promotion");
        ModelLifecycleCanonical.appendField(sb, "previousChampion", previousChampion.bindingKey());
        ModelLifecycleCanonical.appendField(sb, "newChampion", newChampion.bindingKey());
        ModelLifecycleCanonical.appendField(sb, "decisionSha256Hex", decisionSha256Hex);
        ModelLifecycleCanonical.appendField(sb, "comparisonSha256Hex", comparisonSha256Hex);
        return sb.toString();
    }

    private String canonicalMaterial() {
        StringBuilder sb = new StringBuilder(canonicalIdMaterial());
        ModelLifecycleCanonical.appendField(sb, "promotionId", promotionId);
        ModelLifecycleCanonical.appendField(sb, "rationale", rationale);
        ModelLifecycleCanonical.appendField(sb, "approver", approver);
        return sb.toString();
    }

    public String promotionId() {
        return promotionId;
    }

    public ModelLifecycleIdentity previousChampion() {
        return previousChampion;
    }

    public ModelLifecycleIdentity newChampion() {
        return newChampion;
    }

    public String decisionSha256Hex() {
        return decisionSha256Hex;
    }

    public String comparisonSha256Hex() {
        return comparisonSha256Hex;
    }

    public String rationale() {
        return rationale;
    }

    public String approver() {
        return approver;
    }

    public String promotionSha256Hex() {
        return promotionSha256Hex;
    }
}

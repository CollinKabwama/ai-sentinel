package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;

/**
 * Explicit approve/reject decision for a designated challenger.
 * <p>
 * {@code APPROVED != PROMOTED}<br>
 * {@code SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY}
 */
public final class ModelPromotionDecision {

    private final ModelPromotionDecisionStatus status;
    private final ModelLifecycleIdentity championAtDecision;
    private final ModelLifecycleIdentity challenger;
    private final String comparisonSha256Hex;
    private final String rationale;
    private final String approver;
    private final String decisionSha256Hex;

    private ModelPromotionDecision(
        ModelPromotionDecisionStatus status,
        ModelLifecycleIdentity championAtDecision,
        ModelLifecycleIdentity challenger,
        String comparisonSha256Hex,
        String rationale,
        String approver
    ) {
        this.status = Objects.requireNonNull(status, "status");
        if (status == ModelPromotionDecisionStatus.NOT_DECIDED) {
            throw new IllegalArgumentException("decision status must be APPROVED or REJECTED");
        }
        this.championAtDecision = Objects.requireNonNull(championAtDecision, "championAtDecision");
        this.challenger = Objects.requireNonNull(challenger, "challenger");
        this.comparisonSha256Hex = ModelLifecycleCanonical.requireSha256Hex(
            comparisonSha256Hex, "comparisonSha256Hex");
        this.rationale = requireGovernanceText(rationale, "rationale");
        this.approver = requireGovernanceText(approver, "approver");
        StringBuilder material = new StringBuilder();
        ModelLifecycleCanonical.appendField(material, "status", status.name());
        ModelLifecycleCanonical.appendField(material, "championAtDecision", championAtDecision.bindingKey());
        ModelLifecycleCanonical.appendField(material, "challenger", challenger.bindingKey());
        ModelLifecycleCanonical.appendField(material, "comparisonSha256Hex", this.comparisonSha256Hex);
        ModelLifecycleCanonical.appendField(material, "rationale", this.rationale);
        ModelLifecycleCanonical.appendField(material, "approver", this.approver);
        this.decisionSha256Hex = ModelLifecycleCanonical.sha256Hex(material.toString());
    }

    public static ModelPromotionDecision approved(
        ModelLifecycleIdentity championAtDecision,
        ModelLifecycleIdentity challenger,
        String comparisonSha256Hex,
        String rationale,
        String approver
    ) {
        return new ModelPromotionDecision(
            ModelPromotionDecisionStatus.APPROVED,
            championAtDecision,
            challenger,
            comparisonSha256Hex,
            rationale,
            approver
        );
    }

    public static ModelPromotionDecision rejected(
        ModelLifecycleIdentity championAtDecision,
        ModelLifecycleIdentity challenger,
        String comparisonSha256Hex,
        String rationale,
        String approver
    ) {
        return new ModelPromotionDecision(
            ModelPromotionDecisionStatus.REJECTED,
            championAtDecision,
            challenger,
            comparisonSha256Hex,
            rationale,
            approver
        );
    }

    public ModelPromotionDecisionStatus status() {
        return status;
    }

    public ModelLifecycleIdentity championAtDecision() {
        return championAtDecision;
    }

    public ModelLifecycleIdentity challenger() {
        return challenger;
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

    public String decisionSha256Hex() {
        return decisionSha256Hex;
    }

    static String requireGovernanceText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        if (trimmed.length() > 2000) {
            throw new IllegalArgumentException(name + " must be <= 2000 characters");
        }
        return trimmed;
    }
}

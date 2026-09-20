package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of an explicit governance operation. Does not rewire production scorers.
 */
public final class ModelLifecycleOperationResult {

    private final String operation;
    private final ModelLifecycleIdentity champion;
    private final ModelLifecycleIdentity challengerOrNull;
    private final ModelPromotionDecisionStatus decisionStatus;
    private final String evidenceSha256Hex;
    private final String detail;

    public ModelLifecycleOperationResult(
        String operation,
        ModelLifecycleIdentity champion,
        ModelLifecycleIdentity challengerOrNull,
        ModelPromotionDecisionStatus decisionStatus,
        String evidenceSha256Hex,
        String detail
    ) {
        this.operation = ModelLifecycleCanonical.requireLifecycleToken(operation, "operation");
        this.champion = Objects.requireNonNull(champion, "champion");
        this.challengerOrNull = challengerOrNull;
        this.decisionStatus = decisionStatus;
        this.evidenceSha256Hex = ModelLifecycleCanonical.requireSha256Hex(
            evidenceSha256Hex, "evidenceSha256Hex");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must be non-blank");
        }
    }

    public String operation() {
        return operation;
    }

    public ModelLifecycleIdentity champion() {
        return champion;
    }

    public Optional<ModelLifecycleIdentity> challenger() {
        return Optional.ofNullable(challengerOrNull);
    }

    public Optional<ModelPromotionDecisionStatus> decisionStatus() {
        return Optional.ofNullable(decisionStatus);
    }

    public String evidenceSha256Hex() {
        return evidenceSha256Hex;
    }

    public String detail() {
        return detail;
    }
}

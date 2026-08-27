package dev.aisentinel.core.contract;

import java.util.List;
import java.util.Objects;

/**
 * Transport-safe advisory. Explicitly non-authoritative for enforcement.
 */
public record ContractSecurityAdvice(
    String code,
    String priority,
    String reason,
    List<String> linkedFactorCodes,
    boolean humanReviewRecommended
) {
    public ContractSecurityAdvice {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(priority, "priority");
        reason = reason == null ? "" : reason;
        linkedFactorCodes = linkedFactorCodes == null ? List.of() : List.copyOf(linkedFactorCodes);
    }
}

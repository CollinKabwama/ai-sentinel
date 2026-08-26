package dev.aisentinel.core.decision;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Compact advisory payload distinct from {@link EnforcementAction}.
 *
 * @param code                     advisory vocabulary code
 * @param priority                 operator priority (does not change enforcement)
 * @param reason                   short deterministic reason
 * @param linkedFactorCodes        factor codes this advice is tied to (immutable)
 * @param humanReviewRecommended   whether a human should review
 */
public record SecurityAdvice(
    AdvisoryCode code,
    AdvisoryPriority priority,
    String reason,
    List<RiskFactorCode> linkedFactorCodes,
    boolean humanReviewRecommended
) {
    public SecurityAdvice {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(priority, "priority");
        reason = reason == null ? "" : reason;
        linkedFactorCodes = normalizeLinkedFactorCodes(linkedFactorCodes);
    }

    private static List<RiskFactorCode> normalizeLinkedFactorCodes(List<RiskFactorCode> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        EnumSet<RiskFactorCode> seen = EnumSet.noneOf(RiskFactorCode.class);
        List<RiskFactorCode> copy = new ArrayList<>(input.size());
        for (RiskFactorCode code : input) {
            Objects.requireNonNull(code, "linkedFactorCode");
            if (!seen.add(code)) {
                throw new IllegalArgumentException("duplicate linked factor code: " + code);
            }
            copy.add(code);
        }
        return List.copyOf(copy);
    }
}

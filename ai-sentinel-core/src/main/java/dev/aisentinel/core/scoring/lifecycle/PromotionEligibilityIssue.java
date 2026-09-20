package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;

public final class PromotionEligibilityIssue {

    private final PromotionEligibilityIssueCode code;
    private final String detail;

    public PromotionEligibilityIssue(PromotionEligibilityIssueCode code, String detail) {
        this.code = Objects.requireNonNull(code, "code");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must be non-blank");
        }
    }

    public PromotionEligibilityIssueCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}

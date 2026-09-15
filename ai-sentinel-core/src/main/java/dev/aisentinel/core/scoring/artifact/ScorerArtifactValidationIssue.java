package dev.aisentinel.core.scoring.artifact;

import java.util.Objects;

/**
 * One deterministic, actionable validation issue for a candidate scorer/model descriptor.
 */
public record ScorerArtifactValidationIssue(
    ScorerArtifactValidationIssueCode code,
    String message
) {
    public ScorerArtifactValidationIssue {
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        message = message.trim();
    }
}

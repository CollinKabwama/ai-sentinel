package dev.aisentinel.core.scoring.artifact;

import java.util.Objects;

/**
 * One structured loading/readiness issue.
 * <p>
 * Messages must remain free of secrets, raw artifact bytes, and unnecessary paths.
 */
public record CandidateScorerLoadIssue(
    CandidateScorerLoadFailureCode code,
    String message
) {
    public CandidateScorerLoadIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}

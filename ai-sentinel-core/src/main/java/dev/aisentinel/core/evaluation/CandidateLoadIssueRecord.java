package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.scoring.artifact.CandidateScorerLoadFailureCode;
import dev.aisentinel.core.scoring.artifact.CandidateScorerLoadIssue;

import java.util.Objects;

/**
 * Immutable evidence copy of a candidate load issue.
 * <p>
 * {@code CANDIDATE LOAD FAILURE != ATTACK}
 */
public record CandidateLoadIssueRecord(
    CandidateScorerLoadFailureCode code,
    String message
) {
    public CandidateLoadIssueRecord {
        code = Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }

    static CandidateLoadIssueRecord from(CandidateScorerLoadIssue issue) {
        CandidateScorerLoadIssue safe = Objects.requireNonNull(issue, "issue");
        return new CandidateLoadIssueRecord(safe.code(), safe.message());
    }
}

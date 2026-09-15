package dev.aisentinel.core.scoring.artifact;

import java.util.List;
import java.util.Objects;

/**
 * Result of validating a candidate scorer/model artifact descriptor against the
 * current AI-Sentinel feature schema and anomaly-scorer output contract.
 * <p>
 * Acceptance means the declared metadata is compatible. It does not mean the
 * model is high quality, runtime-available, shadow-eligible, or approved.
 */
public record ScorerArtifactValidationResult(
    boolean accepted,
    List<ScorerArtifactValidationIssue> issues
) {
    public ScorerArtifactValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        if (accepted && !issues.isEmpty()) {
            throw new IllegalArgumentException("accepted results must not contain issues");
        }
        if (!accepted && issues.isEmpty()) {
            throw new IllegalArgumentException("rejected results must contain at least one issue");
        }
    }

    public static ScorerArtifactValidationResult accept() {
        return new ScorerArtifactValidationResult(true, List.of());
    }

    public static ScorerArtifactValidationResult reject(List<ScorerArtifactValidationIssue> issues) {
        Objects.requireNonNull(issues, "issues");
        return new ScorerArtifactValidationResult(false, issues);
    }

    public boolean rejected() {
        return !accepted;
    }
}

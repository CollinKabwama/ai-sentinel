package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic evidence for one candidate scorer replay/evaluation attempt.
 * <p>
 * Nested {@link DetectionEvaluationEvidence} is produced by the existing evaluation
 * framework when status is {@link CandidateDetectionEvaluationStatus#COMPLETED}.
 * Candidate evaluation does not mutate or replace the Official Detection Reference
 * Baseline.
 * <p>
 * {@code EVALUATED CANDIDATE != APPROVED CANDIDATE}<br>
 * {@code EVALUATED CANDIDATE != ACCEPTED CANDIDATE}<br>
 * {@code CANDIDATE EVALUATION != BASELINE PROMOTION}<br>
 * {@code FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE}<br>
 * {@code EVALUATION COMPLETED != ACCEPTED}
 */
public record CandidateDetectionEvaluationEvidence(
    String evidenceSchemaVersion,
    String reportKind,
    CandidateDetectionEvaluationStatus status,
    CandidateEvaluationProvenance candidate,
    DetectionEvaluationEvidence.ClassificationProvenance classification,
    List<CandidateLoadIssueRecord> loadIssues,
    DetectionEvaluationEvidence nestedEvaluation,
    CandidateEvaluationAcceptanceAssessment acceptance,
    List<String> limitations
) {
    public CandidateDetectionEvaluationEvidence(
        String evidenceSchemaVersion,
        String reportKind,
        CandidateDetectionEvaluationStatus status,
        CandidateEvaluationProvenance candidate,
        DetectionEvaluationEvidence.ClassificationProvenance classification,
        List<CandidateLoadIssueRecord> loadIssues,
        DetectionEvaluationEvidence nestedEvaluation,
        List<String> limitations
    ) {
        this(
            evidenceSchemaVersion,
            reportKind,
            status,
            candidate,
            classification,
            loadIssues,
            nestedEvaluation,
            CandidateEvaluationAcceptanceAssessment.notAssessed(null),
            limitations
        );
    }

    public CandidateDetectionEvaluationEvidence {
        evidenceSchemaVersion = requireNotBlank("evidenceSchemaVersion", evidenceSchemaVersion);
        reportKind = requireNotBlank("reportKind", reportKind);
        status = Objects.requireNonNull(status, "status");
        candidate = Objects.requireNonNull(candidate, "candidate");
        classification = Objects.requireNonNull(classification, "classification");
        loadIssues = loadIssues == null ? List.of() : List.copyOf(loadIssues);
        acceptance = Objects.requireNonNull(acceptance, "acceptance");
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        for (CandidateLoadIssueRecord issue : loadIssues) {
            Objects.requireNonNull(issue, "loadIssue");
        }
        for (String limitation : limitations) {
            requireNotBlank("limitation", limitation);
        }
        if (status == CandidateDetectionEvaluationStatus.COMPLETED) {
            Objects.requireNonNull(nestedEvaluation, "nestedEvaluation");
            if (!loadIssues.isEmpty()) {
                throw new IllegalArgumentException("COMPLETED candidate evaluation must not carry load issues");
            }
            requireCompletedCandidateProvenance(candidate);
            requireNestedEvaluationMatchesCandidate(candidate, nestedEvaluation);
            if (!candidate.artifactBytesVerified()) {
                throw new IllegalArgumentException("COMPLETED candidate evaluation requires verified artifact provenance");
            }
            if (candidate.verifiedDigestHex().isBlank() || candidate.configurationFingerprintSha256Hex().isBlank()) {
                throw new IllegalArgumentException("COMPLETED candidate evaluation requires digest and configuration fingerprint");
            }
            if (candidate.verifiedDigestHex().equals(candidate.configurationFingerprintSha256Hex())) {
                throw new IllegalArgumentException("CONFIGURATION FINGERPRINT != ARTIFACT DIGEST");
            }
            if (Double.compare(classification.anomalyThreshold(), nestedEvaluation.classification().anomalyThreshold()) != 0) {
                throw new IllegalArgumentException("classification threshold must match nested evaluation evidence");
            }
            requireCompletedAcceptance(acceptance);
        } else if (nestedEvaluation != null) {
            throw new IllegalArgumentException("non-COMPLETED candidate evaluation must not carry nested evaluation evidence");
        } else if (loadIssues.isEmpty()) {
            throw new IllegalArgumentException("non-COMPLETED candidate evaluation must carry at least one load issue");
        } else if (acceptance.status() != CandidateEvaluationAcceptanceStatus.NOT_ASSESSED) {
            throw new IllegalArgumentException("non-COMPLETED candidate evaluation cannot be accepted or rejected");
        }
    }

    public Optional<DetectionEvaluationEvidence> evaluation() {
        return Optional.ofNullable(nestedEvaluation);
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void requireCompletedCandidateProvenance(CandidateEvaluationProvenance candidate) {
        requireNotBlank("candidate.scorerId", candidate.scorerId());
        requireNotBlank("candidate.scorerVersion", candidate.scorerVersion());
        requireNotBlank("candidate.artifactId", candidate.artifactId());
        requireNotBlank("candidate.artifactFormat", candidate.artifactFormat());
        requireNotBlank("candidate.scorerType", candidate.scorerType());
        requireNotBlank("candidate.artifactDigestAlgorithm", candidate.artifactDigestAlgorithm());
        requireSha256("candidate.artifactDigestHex", candidate.artifactDigestHex());
        requireSha256("candidate.verifiedDigestHex", candidate.verifiedDigestHex());
        requireNotBlank("candidate.featureSchemaVersion", candidate.featureSchemaVersion());
        if (candidate.requiredFeatureNames().isEmpty()) {
            throw new IllegalArgumentException("COMPLETED candidate evaluation requires required feature names");
        }
        if (candidate.declaredFeatureDimension() <= 0) {
            throw new IllegalArgumentException("COMPLETED candidate evaluation requires declared feature dimension");
        }
        requireSha256("candidate.configurationFingerprintSha256Hex", candidate.configurationFingerprintSha256Hex());
        requireNotBlank("candidate.runtimeImplementationId", candidate.runtimeImplementationId());
        if (!candidate.artifactDigestHex().equals(candidate.verifiedDigestHex())) {
            throw new IllegalArgumentException("candidate verified digest must match declared artifact digest");
        }
    }

    private static void requireCompletedAcceptance(CandidateEvaluationAcceptanceAssessment acceptance) {
        if (acceptance.configuredPolicy().isEmpty()) {
            if (acceptance.status() != CandidateEvaluationAcceptanceStatus.NOT_ASSESSED) {
                throw new IllegalArgumentException("accepted with no acceptance policy");
            }
            return;
        }
        if (acceptance.status() == CandidateEvaluationAcceptanceStatus.NOT_ASSESSED) {
            throw new IllegalArgumentException("COMPLETED evaluation with an acceptance policy must be assessed");
        }
        if (acceptance.status() == CandidateEvaluationAcceptanceStatus.ACCEPTED && !acceptance.issues().isEmpty()) {
            throw new IllegalArgumentException("acceptance reasons inconsistent with acceptance status");
        }
        if (acceptance.status() == CandidateEvaluationAcceptanceStatus.REJECTED && acceptance.issues().isEmpty()) {
            throw new IllegalArgumentException("acceptance reasons inconsistent with acceptance status");
        }
    }

    private static void requireNestedEvaluationMatchesCandidate(
        CandidateEvaluationProvenance candidate,
        DetectionEvaluationEvidence nestedEvaluation
    ) {
        DetectionEvaluationEvidence.ReplayProvenance replay = nestedEvaluation.replay();
        if (!candidate.scorerId().equals(replay.scorerId())) {
            throw new IllegalArgumentException("candidate scorerId must match nested replay evidence");
        }
        String expectedVersion = candidate.scorerVersion() + "+sha256:" + candidate.verifiedDigestHex();
        if (!expectedVersion.equals(replay.scorerVersion())) {
            throw new IllegalArgumentException("candidate scorerVersion/digest must match nested replay evidence");
        }
    }

    private static void requireSha256(String field, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
    }
}
